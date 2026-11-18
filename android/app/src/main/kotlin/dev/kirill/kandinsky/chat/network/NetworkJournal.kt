// Сохраняет команды и номера для повторной отправки и отслеживает прочитанные границы чатов.
// Размер журнала ограничен, иначе долгий обрыв связи позволил бы копить неограниченную очередь.

package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.ChatStorageCodec
import dev.kirill.kandinsky.chat.storage.LocalChangeJournal
import java.util.UUID
import org.json.JSONObject

class NetworkJournal(private val userID: Long) : LocalChangeJournal {
    companion object {
        // Выбирает исправные отправки и удаления в порядке записи. Ограничивает порцию по весу и
        // не смешивает удаление с другими командами, чтобы диспетчер получил подходящий набор.
        // Команды прежнего приложения с другим назначением не задерживают эту очередь.
        fun readyOperations(database: SQLiteDatabase): List<Pair<String, String>> {
            val commands = ArrayList<Pair<String, String>>()
            database
                .rawQuery(
                    "SELECT action,payload " +
                        "FROM net_outbox " +
                        "WHERE errorCode IS NULL AND action IN ('send','delete') " +
                        "ORDER BY rowid LIMIT 10",
                    null,
                )
                .use { rows ->
                    var bytes = 0
                    while (rows.moveToNext()) {
                        val payload = rows.getString(1)
                        if (
                            commands.isNotEmpty() &&
                                (bytes + payload.toByteArray().size > 40000 ||
                                    rows.getString(0) != "send")
                        )
                            break
                        commands.add(rows.getString(0) to payload)
                        bytes += payload.toByteArray().size
                        if (rows.getString(0) != "send") break
                    }
                }
            return commands
        }
    }

    // Проверяет номер, длину текста и размеры вложений, затем сохраняет команду вместе со связью
    // местного сообщения. После разрыва можно повторить содержимое под прежним ключом.
    override fun appended(database: SQLiteDatabase, command: SendCommand, localID: Long) {
        val id =
            try {
                UUID.fromString(command.operationID).toString()
            } catch (_: IllegalArgumentException) {
                throw RepoException(RepoError.INVALID_INPUT)
            }
        if (
            command.text.toByteArray().size > 16384 ||
                command.caption.toByteArray().size > 4096 ||
                command.attachments.size > 10 ||
                command.attachments.any { it.byteSize !in 1..67108864L }
        ) {
            throw RepoException(RepoError.INVALID_INPUT)
        }
        val payload = ChatStorageCodec.commandString(command)
        val bytes = payload.toByteArray().size
        if (bytes > 40960) throw RepoException(RepoError.INVALID_INPUT)
        val mediaBytes = command.attachments.sumOf { it.byteSize }
        reserve(database, bytes, mediaBytes)
        database.execSQL(
            "INSERT INTO net_messages(localID,clientID,senderID) VALUES(?,?,?)",
            arrayOf<Any?>(localID, id, userID),
        )
        database.execSQL(
            "INSERT INTO net_outbox(id,action,localID,payload,payloadBytes,mediaBytes) VALUES(?,'send',?,?,?,?)",
            arrayOf<Any?>(id, localID, payload, bytes, mediaBytes),
        )
        database.execSQL("UPDATE messages SET deliveryState='pending' WHERE id=?", arrayOf(localID))
    }

    // Отмечает удаление местной строки и сверяет автора. Если отправка окончательно отклонена и
    // серверного номера нет, очищает местную очередь. В остальных случаях сохраняет команду по
    // известным номерам.
    override fun deleting(database: SQLiteDatabase, message: Message) {
        val mapping =
            database
                .rawQuery(
                    "SELECT serverID,clientID,senderID FROM net_messages WHERE localID=?",
                    arrayOf(message.id.toString()),
                )
                .use {
                    if (!it.moveToFirst()) return
                    Triple(
                        if (it.isNull(0)) null else it.getLong(0),
                        it.getString(1),
                        it.getLong(2),
                    )
                }
        database.execSQL("UPDATE net_messages SET tombstone=1 WHERE localID=?", arrayOf(message.id))
        if (mapping.third != userID) return
        val rejected =
            database
                .rawQuery(
                    "SELECT 1 FROM net_outbox WHERE action='send' AND localID=? AND errorCode IS NOT NULL",
                    arrayOf(message.id.toString()),
                )
                .use { it.moveToFirst() }
        if (mapping.first == null && rejected) {
            database.delete("net_outbox", "localID=?", arrayOf(message.id.toString()))
            return
        }
        val id = UUID.randomUUID().toString()
        val payload =
            JSONObject()
                .put("chatId", message.chatID)
                .put("messageId", mapping.first ?: JSONObject.NULL)
                .put("targetClientId", mapping.second)
                .toString()
        reserve(database, payload.toByteArray().size, 0)
        database.execSQL(
            "INSERT INTO net_outbox(id,action,localID,payload,payloadBytes) VALUES(?,'delete',?,?,?)",
            arrayOf<Any?>(id, message.id, payload, payload.toByteArray().size),
        )
    }

    // Читает последнюю известную границу и сохраняет её, только если она двигается вперёд.
    // Повторная доставка старого события не вернёт уже прочитанные строки.
    override fun read(database: SQLiteDatabase, chatID: Long) {
        val through =
            database
                .rawQuery(
                    "SELECT lastSeq FROM net_chats WHERE chatID=?",
                    arrayOf(chatID.toString()),
                )
                .use { if (it.moveToFirst()) it.getLong(0) else return }
        database.execSQL(
            "INSERT OR IGNORE INTO net_reads(chatID,through) VALUES(?,?)",
            arrayOf(chatID, through),
        )
        database.execSQL(
            "UPDATE net_reads SET through=MAX(through,?) WHERE chatID=?",
            arrayOf(through, chatID),
        )
    }

    // Проверяет число команд и запас байтов для их описаний и вложений. Ошибка до добавления
    // оставляет базу без половины записанной команды.
    private fun reserve(database: SQLiteDatabase, bytes: Int, mediaBytes: Long) {
        database
            .rawQuery(
                "SELECT count(*),COALESCE(sum(payloadBytes),0),COALESCE(sum(mediaBytes),0) " +
                    "FROM net_outbox " +
                    "WHERE action IN ('send','delete')",
                null,
            )
            .use {
                check(it.moveToFirst())
                if (
                    it.getLong(0) >= 256 ||
                        it.getLong(1) + bytes > 2 * 1024 * 1024 ||
                        it.getLong(2) + mediaBytes > 512 * 1024 * 1024
                )
                    throw RepoException(RepoError.STORAGE_FULL)
            }
    }
}
