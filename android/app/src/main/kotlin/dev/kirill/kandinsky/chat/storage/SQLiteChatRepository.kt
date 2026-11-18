package dev.kirill.kandinsky.chat.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import dev.kirill.kandinsky.chat.domain.*
import java.io.File
import java.util.Locale
import org.json.JSONObject

class SQLiteChatRepository(
    val store: SQLiteStore,
    private val journal: LocalChangeJournal? = null,
) : ChatRepository {
    private val columns =
        "id,chatId,sortKey,kind,text,caption,replyToId,revision,savedAt,direction,deliveryState"

    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))

    // getLong возвращает 0 для SQL NULL. Проверяем поле до чтения числа.
    private fun Cursor.optionalLong(name: String): Long? {
        val i = getColumnIndexOrThrow(name)
        return if (isNull(i)) null else getLong(i)
    }

    private fun preview(c: Cursor): String {
        if (c.optionalLong("lastId") == null) return "Сообщений пока нет"
        val text = c.string("lastText")
        val caption = c.string("lastCaption")
        if (c.string("lastKind") == "text") return text.take(120)
        if (caption.isNotEmpty()) return caption.take(120)
        return when (c.string("lastKind")) {
            "photo" -> "Фотография"
            "video" -> "Видео"
            "voice" -> "Голосовое сообщение"
            "round" -> "Видеокружочек"
            "gif" -> "GIF"
            "sticker" -> "Стикер"
            else -> ""
        }
    }

    private fun summaries(db: SQLiteDatabase): List<ChatSummary> {
        val result = ArrayList<ChatSummary>()
        db.rawQuery(
                "SELECT c.*,m.id AS lastId,m.kind AS lastKind,m.text AS lastText," +
                    "m.caption AS lastCaption,m.savedAt AS lastSavedAt " +
                    "FROM chats c " +
                    "LEFT " +
                    "JOIN messages m ON m.id=c.lastMessageId " +
                    "ORDER BY c.pinned DESC,c.updatedSortKey DESC,c.id",
                null,
            )
            .use { c ->
                while (c.moveToNext()) result.add(
                    ChatSummary(
                        c.long("id"),
                        c.string("title"),
                        preview(c),
                        c.long("updatedSortKey"),
                        c.optionalLong("lastSavedAt") ?: 0,
                        c.long("unreadCount").toInt(),
                        c.long("pinned") != 0L,
                    )
                )
            }
        return result
    }

    internal fun summary(db: SQLiteDatabase, id: Long) =
        summaries(db).firstOrNull { it.id == id } ?: throw RepoException(RepoError.NOT_FOUND)

    override fun markRead(chatID: ChatID, callback: (Result<Unit>) -> Unit): Cancellation =
        store.submit(
            { db ->
                transaction(db) {
                    db.rawQuery("SELECT id FROM chats WHERE id=?", arrayOf(chatID.toString())).use {
                        if (!it.moveToFirst()) throw RepoException(RepoError.NOT_FOUND)
                    }
                    db.execSQL("UPDATE chats SET unreadCount=0 WHERE id=?", arrayOf(chatID))
                    journal?.read(db, chatID)
                }
            },
            callback,
        )

    override fun chats(matching: String, callback: (Result<List<ChatSummary>>) -> Unit) =
        store.submit(
            { db ->
                summaries(db).filter {
                    matching.isEmpty() ||
                        it.title.lowercase(Locale.ROOT).contains(matching.lowercase(Locale.ROOT))
                }
            },
            callback,
        )

    // Сохраняет порядок вложений, чтобы результаты альбома не менялись местами после повторного
    // открытия.
    internal fun messages(db: SQLiteDatabase, sql: String, args: Array<String>): List<Message> {
        val output = ArrayList<Message>()
        var bytes = 0
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val text = c.string("text")
                val caption = c.string("caption")
                val size = (text.length + caption.length) * 2
                if (output.isNotEmpty() && bytes + size > 8 * 1024 * 1024) break
                bytes += size
                val id = c.long("id")
                val kind =
                    try {
                        MessageKind.valueOf(c.string("kind").uppercase(Locale.ROOT))
                    } catch (t: IllegalArgumentException) {
                        throw RepoException(RepoError.CORRUPT)
                    }
                val direction =
                    try {
                        MessageDirection.valueOf(c.string("direction").uppercase(Locale.ROOT))
                    } catch (t: IllegalArgumentException) {
                        throw RepoException(RepoError.CORRUPT)
                    }
                output.add(
                    Message(
                        id,
                        c.long("chatId"),
                        OrderKey(c.long("sortKey"), id),
                        direction,
                        c.long("savedAt"),
                        kind,
                        text,
                        caption,
                        c.optionalLong("replyToId"),
                        c.long("revision").toInt(),
                        emptyList(),
                        MessageDelivery.valueOf(c.string("deliveryState").uppercase(Locale.ROOT)),
                    )
                )
            }
        }
        if (output.isEmpty()) return output
        val grouped = HashMap<Long, MutableList<Attachment>>()
        val placeholders = output.joinToString(",") { "?" }
        db.rawQuery(
                "SELECT a.*,COALESCE(p.tinyPreview,c.tinyPreview) AS tinyPreview," +
                    "COALESCE(p.waveform,c.waveform) AS waveform " +
                    "FROM attachments a " +
                    "LEFT " +
                    "JOIN media_previews p ON p.attachmentID=a.id " +
                    "LEFT " +
                    "JOIN media_preview_catalog c ON c.key=CASE WHEN a.relativePath LIKE " +
                    "'seed/%' THEN a.relativePath ELSE a.thumbnailKey END " +
                    "WHERE messageId IN ($placeholders) " +
                    "ORDER BY a.rowid",
                output.map { it.id.toString() }.toTypedArray(),
            )
            .use { c ->
                while (c.moveToNext()) grouped
                    .getOrPut(c.long("messageId")) { ArrayList() }
                    .add(
                        Attachment(
                            c.string("id"),
                            c.string("relativePath"),
                            c.string("mime"),
                            c.long("byteSize"),
                            c.long("width").toInt(),
                            c.long("height").toInt(),
                            c.long("orientation").toInt(),
                            c.optionalLong("durationMs"),
                            c.string("thumbnailKey"),
                            MediaPreviewStorage.tiny(
                                c.getString(c.getColumnIndexOrThrow("tinyPreview"))
                            ),
                            MediaPreviewStorage.readWave(
                                c.getString(c.getColumnIndexOrThrow("waveform"))
                            ),
                        )
                    )
            }
        val generationIDs =
            if (journal == null) emptyList()
            else
                output
                    .filter {
                        it.direction == MessageDirection.INCOMING &&
                            if (it.kind == MessageKind.TEXT) it.text in StudioGeneration.statusTexts
                            else grouped[it.id]?.firstOrNull()?.id?.contains("-result-") == true
                    }
                    .map { it.id }
        val generations = HashMap<Long, StudioGeneration>()
        if (generationIDs.isNotEmpty()) {
            val marks = generationIDs.joinToString(",") { "?" }
            db.rawQuery(
                    "SELECT localID,payload FROM net_message_metadata WHERE localID IN ($marks)",
                    generationIDs.map(Long::toString).toTypedArray(),
                )
                .use { c ->
                    while (c.moveToNext()) {
                        val value =
                            runCatching {
                                    val payload = JSONObject(c.getString(1))
                                    val stage =
                                        StudioGeneration.Stage.valueOf(
                                            payload
                                                .getString("kandinskyStatus")
                                                .uppercase(Locale.ROOT)
                                        )
                                    fun string(key: String) =
                                        if (payload.isNull(key)) null else payload.getString(key)
                                    val rawSlots = payload.optJSONArray("kandinskySlots")
                                    val slots =
                                        List(rawSlots?.length() ?: 0) { index ->
                                            val item = rawSlots!!.getJSONObject(index)
                                            StudioGeneration.Slot(
                                                item.getString("id"),
                                                StudioGeneration.Stage.valueOf(
                                                    item.getString("status").uppercase(Locale.ROOT)
                                                ),
                                                if (item.isNull("blobId")) null
                                                else item.getString("blobId"),
                                                item.optInt("progress", -1).takeIf { it in 0..99 },
                                                if (item.isNull("runId")) null
                                                else item.getString("runId"),
                                                item.optBoolean("moderated"),
                                            )
                                        }
                                    StudioGeneration(
                                        stage,
                                        string("kandinskyModel"),
                                        string("kandinskyAspect"),
                                        if (payload.isNull("kandinskyStartedAtMs")) null
                                        else payload.getLong("kandinskyStartedAtMs"),
                                        string("kandinskyError"),
                                        payload.optBoolean("kandinskyModerated"),
                                        slots,
                                        payload.optInt("kandinskyProgress", -1).takeIf {
                                            it in 0..99
                                        },
                                    )
                                }
                                .getOrNull()
                        val id = c.getLong(0)
                        if (
                            value != null &&
                                (grouped[id].isNullOrEmpty() || value.slots.isNotEmpty()) &&
                                (value.stage != StudioGeneration.Stage.SUCCEEDED ||
                                    value.slots.isNotEmpty())
                        )
                            generations[id] = value
                    }
                }
        }
        return output.map {
            it.copy(
                attachments = grouped[it.id]?.toList() ?: emptyList(),
                studioGeneration = generations[it.id],
            )
        }
    }

    private fun message(db: SQLiteDatabase, id: Long) =
        messages(db, "SELECT $columns FROM messages WHERE id=?", arrayOf(id.toString()))
            .firstOrNull() ?: throw RepoException(RepoError.NOT_FOUND)

    // У сообщений может совпасть время. По номеру отличаем соседние строки на границе страницы.
    private fun range(anchor: OrderKey, older: Boolean): Pair<String, Array<String>> {
        val op = if (older) "<" else ">"
        return "(sortKey$op? OR (sortKey=? AND id$op?))" to
            arrayOf(
                anchor.sortKey.toString(),
                anchor.sortKey.toString(),
                anchor.messageID.toString(),
            )
    }

    override fun history(request: PageRequest, callback: (Result<HistoryPage>) -> Unit) =
        store.submit(
            { db ->
                if (request.limit !in 1..128) throw RepoException(RepoError.INVALID_INPUT)
                summary(db, request.chatID)
                val visual = if (request.visualOnly) " AND kind IN ('photo','video','gif')" else ""
                fun query(anchor: OrderKey?, older: Boolean, limit: Int): List<Message> {
                    var sql = "SELECT $columns FROM messages WHERE chatId=?$visual"
                    val args = mutableListOf(request.chatID.toString())
                    if (anchor != null) {
                        val r = range(anchor, older)
                        sql += " AND " + r.first
                        args.addAll(r.second)
                    }
                    val order = if (older) "DESC" else "ASC"
                    sql += " ORDER BY sortKey $order,id $order LIMIT ?"
                    args.add(limit.toString())
                    val rows = messages(db, sql, args.toTypedArray())
                    return if (older) rows.reversed() else rows
                }
                val anchor = request.anchor
                val rows =
                    if (request.direction == PageDirection.AROUND && anchor != null) {
                        if (anchor.messageID == Long.MAX_VALUE)
                            throw RepoException(RepoError.INVALID_INPUT)
                        val older =
                            query(
                                OrderKey(anchor.sortKey, anchor.messageID + 1),
                                true,
                                request.limit,
                            )
                        val newer = query(anchor, false, request.limit)
                        val count =
                            minOf(older.size, maxOf(request.limit / 2, request.limit - newer.size))
                        older.takeLast(count) + newer.take(request.limit - count)
                    } else query(anchor, request.direction != PageDirection.NEWER, request.limit)
                fun more(key: OrderKey?, older: Boolean): OrderKey? {
                    if (key == null) return null
                    val r = range(key, older)
                    return db.rawQuery(
                            "SELECT 1 FROM messages WHERE chatId=?" +
                                visual +
                                " AND " +
                                r.first +
                                " LIMIT 1",
                            arrayOf(request.chatID.toString(), *r.second),
                        )
                        .use { if (it.moveToFirst()) key else null }
                }
                HistoryPage(
                    rows,
                    more(rows.firstOrNull()?.order, true),
                    more(rows.lastOrNull()?.order, false),
                )
            },
            callback,
        )

    override fun index(chatID: Long, callback: (Result<List<IndexedRow>>) -> Unit) =
        store.submit(
            { db ->
                summary(db, chatID)
                val output = ArrayList<IndexedRow>()
                db.rawQuery(
                        "SELECT id,sortKey,savedAt AS savedAtMs,kind,revision," +
                            "length(text) AS textLength " +
                            "FROM messages " +
                            "WHERE chatId=? " +
                            "ORDER BY sortKey,id",
                        arrayOf(chatID.toString()),
                    )
                    .use { c ->
                        while (c.moveToNext()) {
                            val id = c.long("id")
                            val height =
                                if (c.string("kind") == "text")
                                    minOf(
                                        360.0,
                                        maxOf(
                                            44.0,
                                            28 + kotlin.math.ceil(c.long("textLength") / 32.0) * 21,
                                        ),
                                    )
                                else 220.0
                            output.add(
                                IndexedRow(
                                    id,
                                    OrderKey(c.long("sortKey"), id),
                                    height,
                                    c.long("revision").toInt(),
                                    c.long("savedAtMs"),
                                )
                            )
                        }
                    }
                output
            },
            callback,
        )

    private fun validate(c: SendCommand) {
        if (
            c.operationID.isEmpty() ||
                c.operationID.toByteArray().size > 128 ||
                c.text.toByteArray().size > 262144 ||
                c.caption.toByteArray().size > 262144 ||
                (c.text.isBlank() && c.attachments.isEmpty()) ||
                (c.kind != MessageKind.TEXT && c.attachments.isEmpty())
        )
            throw RepoException(RepoError.INVALID_INPUT)
        val media = File(store.root, "media").canonicalPath + File.separator
        for (a in c.attachments) {
            val parts = a.relativePath.split("/")
            if (
                a.id.isEmpty() ||
                    a.relativePath.startsWith("/") ||
                    parts.any { it == ".." || it == "." || it.isEmpty() } ||
                    a.byteSize < 0
            )
                throw RepoException(RepoError.INVALID_INPUT)
            val file = File(File(store.root, "media"), a.relativePath).canonicalFile
            if (!file.path.startsWith(media) || !file.isFile)
                throw RepoException(RepoError.INVALID_INPUT)
        }
    }

    private inline fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        var failure: Throwable? = null
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            try {
                db.endTransaction()
            } catch (rollback: Throwable) {
                if (failure != null) failure.addSuppressed(rollback) else throw rollback
            }
        }
    }

    // Ключ операции и сообщение записываются вместе. При повторе вернём уже созданное сообщение.
    override fun send(command: SendCommand, callback: (Result<MessageDelta>) -> Unit) =
        store.submit(
            { db ->
                validate(command)
                val hash = ChatStorageCodec.payloadHash(command)
                val prior =
                    db.rawQuery(
                            "SELECT payloadHash,messageId FROM operations WHERE token=?",
                            arrayOf(command.operationID),
                        )
                        .use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null }
                if (prior != null) {
                    if (prior.first != hash) throw RepoException(RepoError.INVALID_INPUT)
                    return@submit MessageDelta(
                        inserted = listOf(message(db, prior.second)),
                        chat = summary(db, command.chatID),
                    )
                }
                if (store.faults.consume(RepoFault.STORAGE_FULL))
                    throw SQLiteFullException("Injected storage full")
                val delta =
                    transaction(db) {
                        val previous = summary(db, command.chatID)
                        if (
                            command.replyToID != null &&
                                message(db, command.replyToID).chatID != command.chatID
                        )
                            throw RepoException(RepoError.INVALID_INPUT)
                        val now = System.currentTimeMillis()
                        val sort =
                            if (journal != null) {
                                // Неподтверждённые строки идут после серверной последовательности и
                                // упорядочены по местному номеру
                                Long.MAX_VALUE
                            } else {
                                if (previous.lastSortKey == Long.MAX_VALUE)
                                    throw RepoException(RepoError.INVALID_INPUT)
                                maxOf(now, previous.lastSortKey + 1)
                            }
                        val id =
                            db.insertOrThrow(
                                "messages",
                                null,
                                ContentValues().apply {
                                    put("chatId", command.chatID)
                                    put("sortKey", sort)
                                    put("kind", command.kind.name.lowercase(Locale.ROOT))
                                    put("text", command.text)
                                    put("caption", command.caption)
                                    if (command.replyToID == null) putNull("replyToId")
                                    else put("replyToId", command.replyToID)
                                    put("revision", 1)
                                    put("savedAt", now)
                                    put("direction", "outgoing")
                                    put(
                                        "searchText",
                                        (command.text + " " + command.caption).lowercase(
                                            Locale.ROOT
                                        ),
                                    )
                                },
                            )
                        for (a in command.attachments) {
                            db.insertOrThrow(
                                "attachments",
                                null,
                                ContentValues().apply {
                                    put("id", a.id)
                                    put("messageId", id)
                                    put("relativePath", a.relativePath)
                                    put("mime", a.mime)
                                    put("byteSize", a.byteSize)
                                    put("width", a.rawWidth)
                                    put("height", a.rawHeight)
                                    if (a.durationMs == null) putNull("durationMs")
                                    else put("durationMs", a.durationMs)
                                    put("thumbnailKey", a.thumbnailKey)
                                    put("orientation", a.orientation)
                                },
                            )
                            MediaPreviewStorage.save(db, a.id, a.tinyPreview, a.waveform)
                        }
                        db.execSQL(
                            "INSERT INTO operations(token,payloadHash,messageId) VALUES(?,?,?)",
                            arrayOf<Any?>(command.operationID, hash, id),
                        )
                        db.execSQL(
                            "UPDATE chats SET lastMessageId=?,updatedSortKey=? WHERE id=?",
                            arrayOf(id, if (journal == null) sort else now, command.chatID),
                        )
                        journal?.appended(db, command, id)
                        if (store.faults.consume(RepoFault.BEFORE_COMMIT))
                            throw RepoException(RepoError.IO)
                        MessageDelta(
                            inserted = listOf(message(db, id)),
                            chat = summary(db, command.chatID),
                        )
                    }
                if (store.faults.consume(RepoFault.AFTER_COMMIT_BEFORE_REPLY))
                    throw RepoException(RepoError.IO)
                delta
            },
            callback,
        )

    override fun delete(messageID: Long, callback: (Result<MessageDelta>) -> Unit) =
        store.submit(
            { db ->
                transaction(db) {
                    val old = message(db, messageID)
                    journal?.deleting(db, old)
                    db.delete("messages", "id=?", arrayOf(messageID.toString()))
                    val last =
                        db.rawQuery(
                                "SELECT id,sortKey,savedAt " +
                                    "FROM messages " +
                                    "WHERE chatId=? " +
                                    "ORDER BY sortKey DESC,id DESC LIMIT 1",
                                arrayOf(old.chatID.toString()),
                            )
                            .use { c ->
                                if (c.moveToFirst())
                                    c.getLong(0) to c.getLong(if (journal == null) 1 else 2)
                                else null
                            }
                    db.update(
                        "chats",
                        ContentValues().apply {
                            if (last == null) putNull("lastMessageId")
                            else put("lastMessageId", last.first)
                            put("updatedSortKey", last?.second ?: 0)
                        },
                        "id=?",
                        arrayOf(old.chatID.toString()),
                    )
                    MessageDelta(removedIDs = listOf(messageID), chat = summary(db, old.chatID))
                }
            },
            callback,
        )

    override fun search(
        chatID: Long,
        text: String,
        before: OrderKey?,
        callback: (Result<List<Long>>) -> Unit,
    ) =
        store.submit(
            { db ->
                summary(db, chatID)
                if (text.isEmpty()) return@submit emptyList()
                var sql = "SELECT id FROM messages WHERE chatId=? AND instr(searchText,?)>0"
                val args = mutableListOf(chatID.toString(), text.lowercase(Locale.ROOT))
                if (before != null) {
                    val r = range(before, true)
                    sql += " AND " + r.first
                    args.addAll(r.second)
                }
                sql += " ORDER BY sortKey DESC,id DESC LIMIT 128"
                db.rawQuery(sql, args.toTypedArray()).use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) }
                }
            },
            callback,
        )

    // Сохраняем вложения вместе с текстом, чтобы после перезапуска восстановить весь черновик.
    override fun saveDraft(chatID: Long, draft: Draft, callback: (Result<Unit>) -> Unit) =
        store.submit(
            { db ->
                if (draft.text.toByteArray().size > 262144 || draft.revision < 0)
                    throw RepoException(RepoError.INVALID_INPUT)
                summary(db, chatID)
                val json = ChatStorageCodec.draft(draft)
                transaction(db) {
                    val newer =
                        db.rawQuery(
                                "SELECT revision FROM drafts WHERE chatId=?",
                                arrayOf(chatID.toString()),
                            )
                            .use { it.moveToFirst() && it.getLong(0) > draft.revision }
                    if (newer) return@transaction Unit
                    db.execSQL(
                        "INSERT OR IGNORE INTO drafts(chatId,text,attachmentDraft,revision) VALUES(?,?,?,?)",
                        arrayOf<Any?>(chatID, draft.text, json, draft.revision),
                    )
                    db.execSQL(
                        "UPDATE drafts SET text=?,attachmentDraft=?,revision=? WHERE chatId=? AND revision<=?",
                        arrayOf<Any?>(draft.text, json, draft.revision, chatID, draft.revision),
                    )
                    MediaSelectionStorage.save(db, chatID, draft.pendingMediaBatch)
                }
                Unit
            },
            callback,
        )

    override fun draft(chatID: Long, callback: (Result<Draft>) -> Unit) =
        store.submit(
            { db ->
                val draft =
                    db.rawQuery(
                            "SELECT text,attachmentDraft FROM drafts WHERE chatId=?",
                            arrayOf(chatID.toString()),
                        )
                        .use { c ->
                            if (c.moveToFirst())
                                ChatStorageCodec.decodeDraft(
                                    if (c.isNull(1)) null else c.getString(1),
                                    c.getString(0),
                                )
                            else Draft()
                        }
                val intent = MediaSelectionStorage.load(db, chatID)
                if (intent == null) draft else draft.copy(pendingMediaBatch = intent)
            },
            callback,
        )

    override fun savePosition(
        chatID: Long,
        anchor: ReadingAnchor,
        callback: (Result<Unit>) -> Unit,
    ) =
        store.submit(
            { db ->
                if (
                    !anchor.fraction.isFinite() ||
                        anchor.fraction !in 0.0..1.0 ||
                        !anchor.screenDelta.isFinite() ||
                        kotlin.math.abs(anchor.screenDelta) > 8192 ||
                        (anchor.textUTF16Offset ?: 0) < 0 ||
                        anchor.layoutVersion < 1
                )
                    throw RepoException(RepoError.INVALID_INPUT)
                summary(db, chatID)
                db.execSQL(
                    "INSERT OR REPLACE INTO reading_positions(chatId,messageId,offsetWithin," +
                        "followsBottom,layoutVersion) " +
                        "VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(
                        chatID,
                        anchor.messageID,
                        ChatStorageCodec.anchor(anchor),
                        if (anchor.followsBottom) 1 else 0,
                        anchor.layoutVersion,
                    ),
                )
                Unit
            },
            callback,
        )

    override fun position(chatID: Long, callback: (Result<ReadingAnchor?>) -> Unit) =
        store.submit(
            { db ->
                db.rawQuery(
                        "SELECT offsetWithin FROM reading_positions WHERE chatId=?",
                        arrayOf(chatID.toString()),
                    )
                    .use { c ->
                        if (c.moveToFirst())
                            try {
                                ChatStorageCodec.decodeAnchor(c.getString(0))
                            } catch (t: org.json.JSONException) {
                                throw RepoException(RepoError.CORRUPT)
                            }
                        else null
                    }
            },
            callback,
        )
}
