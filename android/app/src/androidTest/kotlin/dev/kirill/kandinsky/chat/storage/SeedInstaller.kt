// Устанавливает длинную историю и её файлы в отдельную проверочную базу телефона. Сверяет состав
// и отмечает успешную запись, чтобы повторный запуск был безопасным.

package dev.kirill.kandinsky.chat.storage

import android.content.res.AssetManager
import android.database.sqlite.SQLiteFullException
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import dev.kirill.kandinsky.chat.domain.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

class SeedInstaller(private val store: SQLiteStore, private val assets: AssetManager) {
    // Записывает байты отпечатка в едином виде. Иначе настройки языка могли бы изменить строку и
    // помешать сверке с описанием файла.
    private fun hex(bytes: ByteArray) =
        bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 255) }

    // Читает файл небольшими порциями, вычисляет отпечаток и возвращает строку для сравнения.
    // Проверка крупного вложения не загружает весь файл в память.
    private fun sha(file: File, buffer: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hex(hash.digest())
    }

    // Сверяет описание, записывает или проверяет файлы, затем добавляет чаты, сообщения и отметку
    // готовности. Общая запись базы сохраняет историю целиком, а отметка не даёт создать её
    // дважды.
    fun installIfNeeded(
        version: String = "v1",
        callback: (Result<Unit>) -> Unit,
        onProgress: (Double) -> Unit = {},
    ): Cancellation =
        store.submit(
            { db ->
                if (version != "v1") throw RepoException(RepoError.INVALID_INPUT)
                SeedMediaInstaller.install(store.root, assets)
                SeedMediaPreviews.install(db, assets)
                val installed =
                    db.rawQuery("SELECT value FROM metadata WHERE key='seed'", null).use {
                        it.moveToFirst() && it.getString(0) == version
                    }
                if (installed) return@submit Unit
                val manifest =
                    JSONObject(assets.open("manifest.json").bufferedReader().use { it.readText() })
                val seedBytes = assets.open("seed-v1.json").use { it.readBytes() }
                if (
                    manifest.getString("version") != version ||
                        manifest.getString("seedSha256") !=
                            hex(MessageDigest.getInstance("SHA-256").digest(seedBytes))
                )
                    throw RepoException(RepoError.CORRUPT)
                val document = JSONObject(String(seedBytes, Charsets.UTF_8))
                val rows = document.getJSONArray("messages")
                val files = manifest.getJSONArray("files")
                val chats = document.getJSONArray("chats")
                if (
                    files.length() != 329 ||
                        (0 until rows.length()).count {
                            rows.getJSONObject(it).getLong("chatID") == 4L
                        } != 10000
                )
                    throw RepoException(RepoError.CORRUPT)
                val directory = File(store.root, "media/seed")
                if (!directory.exists() && !directory.mkdirs()) throw RepoException(RepoError.IO)
                val fileMap = HashMap<String, JSONObject>()
                val buffer = ByteArray(65536)
                val main = Handler(Looper.getMainLooper())
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    val name = file.getString("fileName")
                    val id = file.getString("id")
                    if (
                        name.contains("/") ||
                            name == "." ||
                            name == ".." ||
                            file.getString("relativePath") != "seed/" + name ||
                            fileMap.put(id, file) != null
                    )
                        throw RepoException(RepoError.CORRUPT)
                    val destination = File(directory, name)
                    if (destination.exists()) {
                        if (sha(destination, buffer) != file.getString("sha256"))
                            throw RepoException(RepoError.CORRUPT)
                    } else {
                        val temporary =
                            File(directory, "." + name + "-" + UUID.randomUUID() + ".tmp")
                        try {
                            val hash = MessageDigest.getInstance("SHA-256")
                            var size = 0L
                            assets.open("media/" + name).use { input ->
                                FileOutputStream(temporary).use { output ->
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                        hash.update(buffer, 0, n)
                                        size += n
                                    }
                                    output.fd.sync()
                                }
                            }
                            if (
                                hex(hash.digest()) != file.getString("sha256") ||
                                    size != file.getLong("byteSize")
                            )
                                throw RepoException(RepoError.CORRUPT)
                            if (!temporary.renameTo(destination)) throw RepoException(RepoError.IO)
                        } finally {
                            if (temporary.exists()) temporary.delete()
                        }
                    }
                    if (i % 64 == 0) {
                        val value = i.toDouble() / files.length() * 0.6
                        main.post { onProgress(value) }
                    }
                }
                val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    Os.close(fd)
                }
                if (store.faults.consume(RepoFault.STORAGE_FULL))
                    throw SQLiteFullException("Injected seed full")
                db.beginTransaction()
                try {
                    db.rawQuery(
                            "SELECT COUNT(*) FROM messages WHERE id BETWEEN 100001 AND 110000 OR id IN(21,22,23)",
                            null,
                        )
                        .use { c ->
                            c.moveToFirst()
                            if (c.getInt(0) != 0) throw RepoException(RepoError.CORRUPT)
                        }
                    for (i in 0 until chats.length()) {
                        val chat = chats.getJSONObject(i)
                        db.execSQL(
                            "INSERT OR IGNORE INTO chats(id,title,avatarKind,updatedSortKey," +
                                "unreadCount,pinned) " +
                                "VALUES(?,?,'initial',0,?,?)",
                            arrayOf(
                                chat.getLong("id"),
                                chat.getString("title"),
                                chat.getInt("unreadCount"),
                                if (chat.getBoolean("pinned")) 1 else 0,
                            ),
                        )
                    }
                    db.compileStatement(
                            "INSERT INTO messages(id,chatId,sortKey,kind,text,caption," +
                                "replyToId,revision,savedAt,direction,searchText) " +
                                "VALUES(?,?,?,?,?,?,NULL,1,?,?,?)"
                        )
                        .use { messageStatement ->
                            db.compileStatement(
                                    "INSERT INTO attachments(id,messageId,relativePath,mime," +
                                        "byteSize,width,height,durationMs,thumbnailKey," +
                                        "orientation) " +
                                        "VALUES(?,?,?,?,?,?,?,?,?,1)"
                                )
                                .use { attachmentStatement ->
                                    for (i in 0 until rows.length()) {
                                        val row = rows.getJSONObject(i)
                                        val id = row.getLong("id")
                                        val text = row.getString("text")
                                        val caption = row.getString("caption")
                                        MessageKind.valueOf(
                                            row.getString("kind").uppercase(Locale.ROOT)
                                        )
                                        MessageDirection.valueOf(
                                            row.getString("direction").uppercase(Locale.ROOT)
                                        )
                                        messageStatement.clearBindings()
                                        messageStatement.bindLong(1, id)
                                        messageStatement.bindLong(2, row.getLong("chatID"))
                                        messageStatement.bindLong(3, row.getLong("sortKey"))
                                        messageStatement.bindString(4, row.getString("kind"))
                                        messageStatement.bindString(5, text)
                                        messageStatement.bindString(6, caption)
                                        messageStatement.bindLong(7, row.getLong("savedAtMs"))
                                        messageStatement.bindString(8, row.getString("direction"))
                                        messageStatement.bindString(
                                            9,
                                            (text + " " + caption).lowercase(Locale.ROOT),
                                        )
                                        messageStatement.executeInsert()
                                        val ids = row.getJSONArray("attachmentFileIDs")
                                        for (j in 0 until ids.length()) {
                                            val fileID = ids.getString(j)
                                            val f =
                                                fileMap[fileID]
                                                    ?: throw RepoException(RepoError.CORRUPT)
                                            attachmentStatement.clearBindings()
                                            attachmentStatement.bindString(1, "seed-$id-$j")
                                            attachmentStatement.bindLong(2, id)
                                            attachmentStatement.bindString(
                                                3,
                                                f.getString("relativePath"),
                                            )
                                            attachmentStatement.bindString(4, f.getString("mime"))
                                            attachmentStatement.bindLong(5, f.getLong("byteSize"))
                                            attachmentStatement.bindLong(6, f.getLong("width"))
                                            attachmentStatement.bindLong(7, f.getLong("height"))
                                            val duration = f.getLong("durationMs")
                                            if (duration > 0)
                                                attachmentStatement.bindLong(8, duration)
                                            else attachmentStatement.bindNull(8)
                                            attachmentStatement.bindString(
                                                9,
                                                "seed-thumb-" + fileID,
                                            )
                                            attachmentStatement.executeInsert()
                                        }
                                        if (i % 1000 == 0) {
                                            val value = 0.6 + i.toDouble() / rows.length() * 0.4
                                            main.post { onProgress(value) }
                                        }
                                    }
                                }
                        }
                    for (i in 0 until chats.length()) {
                        val id = chats.getJSONObject(i).getLong("id")
                        val last =
                            db.rawQuery(
                                    "SELECT id,sortKey " +
                                        "FROM messages " +
                                        "WHERE chatId=? " +
                                        "ORDER BY sortKey DESC,id DESC LIMIT 1",
                                    arrayOf(id.toString()),
                                )
                                .use { c ->
                                    if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
                                }
                        db.execSQL(
                            "UPDATE chats SET lastMessageId=?,updatedSortKey=? WHERE id=?",
                            arrayOf(last?.first, last?.second ?: 0, id),
                        )
                    }
                    db.execSQL(
                        "INSERT OR REPLACE INTO metadata(key,value) VALUES('seed',?)",
                        arrayOf(version),
                    )
                    if (store.faults.consume(RepoFault.BEFORE_COMMIT))
                        throw RepoException(RepoError.IO)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                if (store.faults.consume(RepoFault.AFTER_COMMIT_BEFORE_REPLY))
                    throw RepoException(RepoError.IO)
                main.post { onProgress(1.0) }
                Unit
            },
            callback,
        )
}
