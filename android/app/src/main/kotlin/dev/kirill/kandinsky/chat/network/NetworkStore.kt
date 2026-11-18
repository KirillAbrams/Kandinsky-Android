// Собирает сетевые изменения в местной базе и возвращает экрану изменённые сообщения, удаления и
// сводки чатов. Хранит границу сохранения отдельно от подтверждения сервером.

package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONObject

class NetworkStore(val local: SQLiteChatRepository, val userID: Long) {
    // Читает номер последнего записанного события или начинает с нуля. После переподключения
    // приложение может продолжить с места, которое уже есть в базе.
    fun cursor(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT value FROM net_state WHERE key='cursor'", null).use {
            if (it.moveToFirst()) it.getString(0).toLong() else 0L
        }

    // Сравнивает последний сохранённый номер с последним подтверждённым. Если нового номера нет,
    // лишнюю команду серверу отправлять не нужно.
    fun cursorAwaitingConfirmation(db: SQLiteDatabase): Long? {
        val saved = cursor(db)
        val confirmed =
            db.rawQuery("SELECT value FROM net_state WHERE key='ackedCursor'", null).use {
                if (it.moveToFirst()) it.getString(0).toLong() else -1L
            }
        return saved.takeIf { it > confirmed }
    }

    // Проверяет, что начальная загрузка оставила отметку. По наличию записи можно отличить
    // сохранённый ноль от ещё пустой базы.
    fun initialized(db: SQLiteDatabase) =
        db.rawQuery("SELECT 1 FROM net_state WHERE key='cursor'", null).use { it.moveToFirst() }

    // Заменяет значение служебного ключа в одной строке. Читающий код не видит несколько версий
    // одного параметра.
    internal fun state(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO net_state(key,value) VALUES(?,?)", arrayOf(key, value))
    }

    // Передаёт первичный снимок общей процедуре записи. Экран может открыть историю после того,
    // как в базе появились и сообщения, и отметка для последующих событий.
    fun bootstrap(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        applyInitialSnapshot(db, json)

    // Передаёт удаление чата процедуре местной очистки и возвращает её результат. Вызывающий код
    // может отдельно увидеть, что менять уже нечего.
    fun retireStudioThread(db: SQLiteDatabase, chat: Long): List<Long>? = retireChat(db, chat)

    // Объединяет полученные карточки с местными и передаёт признак недавней страницы и защиты
    // прежних отметок. Частичная сверка не должна перезаписывать свежий список целиком.
    fun mergeChats(
        db: SQLiteDatabase,
        json: JSONObject,
        studioRecent: Boolean = false,
        preserveDirectoryMetadata: Boolean = false,
    ): List<MessageDelta> = mergeChatList(db, json, studioRecent, preserveDirectoryMetadata)

    // Сверяет отметку с началом сетевого чтения и бережёт более новые признаки списка, если
    // успели прийти события. Затем соединяет недавние и отдельно проверенные карточки.
    fun mergeStudioDirectorySnapshot(
        db: SQLiteDatabase, recent: JSONObject, states: List<JSONObject> = emptyList(),
        startedAtCursor: Long,
    ): List<MessageDelta> {
        val preserve = cursor(db) != startedAtCursor
        val changes = mergeChats(db, recent, studioRecent = true, preserveDirectoryMetadata = preserve)
        return changes + states.flatMap { mergeChats(db, it, preserveDirectoryMetadata = preserve) }
    }

    // Загружает сохранённую страницу недавних чатов для быстрого показа. Экран не ждёт обновления
    // по сети, чтобы показать знакомые карточки.
    fun studioRecent(db: SQLiteDatabase): StudioRecentPage = loadRecentChats(db)

    // Читает известные номера по порядку без истории. Запрос состояния может проверить и старые
    // чаты, которые не видны на первой странице.
    fun studioChatIDs(db: SQLiteDatabase): List<Long> =
        db.rawQuery("SELECT chatID FROM net_chats ORDER BY chatID", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getLong(0)) }
        }

    // Проверяет, упоминает ли полученная порция неизвестные чаты. Если карточки нет, клиент
    // узнает её до применения событий.
    fun hasUnknownChats(db: SQLiteDatabase, json: JSONObject): Boolean =
        containsUnknownChat(db, json)

    // Передаёт сетевую порцию процедуре записи сообщений. Возвращаемые изменения нужны для показа
    // только после сохранения.
    fun events(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> = applyEventBatch(db, json)

    // Сверяет состояние упомянутых карточек до и после записи порции. Лента получает сообщения, а
    // список обновляется только если его данные действительно изменились.
    fun directoryEvents(db: SQLiteDatabase, json: JSONObject): Pair<List<MessageDelta>, Boolean> {
        val catalog = json.optJSONArray("chats")
        require(catalog == null || catalog.length() <= 128)
        val keys =
            if (catalog == null) emptyList()
            else List(catalog.length()) {
                "studioDirectory:${catalog.getJSONObject(it).getLong("id")}"
            }
        val before = directoryState(db, keys)
        val changes = events(db, json)
        return changes to (before != directoryState(db, keys))
    }

    // Читает выбранные служебные ключи с их значениями в устойчивом порядке. Сравнение до и после
    // не зависит от случайного порядка, в котором база вернула строки.
    private fun directoryState(db: SQLiteDatabase, keys: List<String>): List<String> {
        if (keys.isEmpty()) return emptyList()
        val placeholders = keys.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT key,value FROM net_state WHERE key IN ($placeholders) ORDER BY key",
            keys.toTypedArray(),
        ).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0) + ":" + rows.getString(1)) }
        }
    }

    // Записывает страницу для нужного чата и возвращает затронутые строки. Так подгрузка старых
    // сообщений использует тот же местный порядок, что и события.
    fun history(db: SQLiteDatabase, chat: Long, json: JSONObject): List<MessageDelta> =
        applyHistoryPage(db, chat, json)

    // Передаёт чтение или создание постоянного номера повтора общей записи. Если ответ потерян,
    // повтор получает прежний номер вместо нового задания.
    fun pendingRetry(db: SQLiteDatabase, key: String, proposed: java.util.UUID?): String =
        loadOrCreateRetryID(db, key, proposed)

    // Передаёт подтверждённые строки генерации на запись вместе с номером ожидаемой попытки.
    // Старый ответ не должен снять отметку более свежего повтора.
    fun generationRows(
        db: SQLiteDatabase,
        chat: Long,
        json: JSONObject,
        retryKey: String? = null,
        operation: String? = null,
    ): List<MessageDelta> = saveGenerationResponse(db, chat, json, retryKey, operation)

    // Передаёт подтверждение отправки на запись, чтобы связать местные и серверные строки.
    // Пользователь видит переход от ожидания к принятию без новой копии сообщения.
    fun accepted(db: SQLiteDatabase, json: JSONObject): List<MessageDelta> =
        confirmSentMessages(db, json)

    // Запоминает отказ для переданных отправок и возвращает изменения. Остальные команды не
    // должны выглядеть неотправленными из-за одной ошибки.
    fun failed(db: SQLiteDatabase, ids: List<String>, code: String): List<MessageDelta> =
        recordSendFailure(db, ids, code)

    // Пересчитывает хвост чата и собирает изменившиеся строки, удаления и сводку. Экран может
    // обновить малую часть истории, не разыскивая новые значения самостоятельно.
    fun deltas(
        db: SQLiteDatabase,
        touched: Map<Long, Set<Long>>,
        removed: Map<Long, List<Long>>,
        reason: MessageChangeReason,
    ): List<MessageDelta> {
        return touched.map { (chat, ids) ->
            db.execSQL(
                "UPDATE chats " +
                    "SET lastMessageId=(SELECT id " +
                    "FROM messages " +
                    "WHERE chatId=? " +
                    "ORDER BY sortKey DESC,id DESC LIMIT 1)," +
                    "updatedSortKey=COALESCE((SELECT savedAt " +
                    "FROM messages " +
                    "WHERE chatId=? " +
                    "ORDER BY sortKey DESC,id DESC LIMIT 1),0) " +
                    "WHERE id=?",
                arrayOf(chat, chat, chat),
            )
            val rows =
                if (ids.isEmpty()) emptyList()
                else
                    local.messages(
                        db,
                        "SELECT * FROM messages WHERE id IN (${ids.joinToString(",") { "?" }})",
                        ids.map(Long::toString).toTypedArray(),
                    )
            MessageDelta(
                updated = rows,
                removedIDs = removed[chat] ?: emptyList(),
                chat = ChatServerState.decorate(db, local.summary(db, chat)),
                reason = reason,
            )
        }
    }

    companion object {
        // Начинает запись, подтверждает её только после успешного действия и всегда закрывает.
        // Ошибка не оставляет половину обновления и занятую базу.
        fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
            db.beginTransaction()
            try {
                val value = action()
                db.setTransactionSuccessful()
                return value
            } finally {
                db.endTransaction()
            }
        }
    }
}

// Читает необязательный номер, сохраняя отсутствие и явное пустое поле как пустое значение. Не
// подменяет их нулём, который был бы отдельным номером.
internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else getLong(key)
