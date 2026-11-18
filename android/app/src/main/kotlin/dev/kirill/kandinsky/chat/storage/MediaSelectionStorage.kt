package dev.kirill.kandinsky.chat.storage

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import java.util.Locale
import java.util.UUID

internal object MediaSelectionStorage {
    private const val MAXIMUM_PAYLOAD_BYTES = 1_048_576

    // Связь с чатом удаляет такое намерение вместе с удалением его местной записи
    fun install(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS media_selection_intents(chatID INTEGER PRIMARY KEY " +
                "REFERENCES chats(id) ON DELETE CASCADE,payload TEXT NOT NULL)"
        )
    }

    private fun valid(commands: List<SendCommand>, chatID: Long): Boolean =
        chatID > 0 &&
            commands.size in 1..32 &&
            commands.sumOf { it.attachments.size } <= 32 &&
            commands.map { it.operationID.lowercase(Locale.ROOT) }.toSet().size == commands.size &&
            commands.all { command ->
                command.chatID == chatID &&
                    runCatching {
                            UUID.fromString(command.operationID)
                                .toString()
                                .equals(command.operationID, ignoreCase = true)
                        }
                        .getOrDefault(false) &&
                    command.attachments.size in 1..10 &&
                    command.attachments.all { asset ->
                        !asset.relativePath.startsWith("/") &&
                            asset.relativePath.split('/').none {
                                it.isEmpty() || it == "." || it == ".."
                            }
                    }
            }

    private fun decode(payload: String, chatID: Long): List<SendCommand> {
        if (payload.toByteArray(Charsets.UTF_8).size > MAXIMUM_PAYLOAD_BYTES)
            throw RepoException(RepoError.CORRUPT)
        val commands =
            try {
                ChatStorageCodec.readMediaBatch(payload)
            } catch (_: org.json.JSONException) {
                throw RepoException(RepoError.CORRUPT)
            } catch (_: IllegalArgumentException) {
                throw RepoException(RepoError.CORRUPT)
            }
        if (!valid(commands, chatID)) throw RepoException(RepoError.CORRUPT)
        return commands
    }

    fun load(db: SQLiteDatabase, chatID: Long): List<SendCommand>? =
        db.rawQuery(
                "SELECT payload FROM media_selection_intents WHERE chatID=?",
                arrayOf(chatID.toString()),
            )
            .use { if (it.moveToFirst()) decode(it.getString(0), chatID) else null }

    fun save(db: SQLiteDatabase, chatID: Long, commands: List<SendCommand>?) {
        if (commands == null) {
            db.execSQL("DELETE FROM media_selection_intents WHERE chatID=?", arrayOf(chatID))
            return
        }
        if (!valid(commands, chatID)) throw RepoException(RepoError.INVALID_INPUT)
        val payload = ChatStorageCodec.mediaBatch(commands)
        if (payload.toByteArray(Charsets.UTF_8).size > MAXIMUM_PAYLOAD_BYTES)
            throw RepoException(RepoError.INVALID_INPUT)
        db.execSQL(
            "INSERT OR REPLACE INTO media_selection_intents(chatID,payload) VALUES(?,?)",
            arrayOf<Any?>(chatID, payload),
        )
    }

    // Обходит всю таблицу, поскольку очистка не должна принять неполную страницу за полный список
    // ещё
    // используемых оригиналов.
    fun references(db: SQLiteDatabase): Set<String> {
        val paths = mutableSetOf<String>()
        db.rawQuery("SELECT chatID,payload FROM media_selection_intents", null).use { c ->
            while (c.moveToNext()) for (command in
                decode(c.getString(1), c.getLong(0))) for (asset in command.attachments) paths +=
                asset.relativePath
        }
        return paths
    }
}
