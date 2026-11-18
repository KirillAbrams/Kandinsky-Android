package dev.kirill.kandinsky.chat.support

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.io.File

class RepositoryHarness
private constructor(val root: File, val store: SQLiteStore, val faults: RepositoryFaults) {
    val repo = SQLiteChatRepository(store)

    companion object {
        fun createLegacy(root: File) {
            root.mkdirs()
            val sql =
                "CREATE TABLE chats(id INTEGER PRIMARY KEY,title TEXT NOT NULL," +
                    "avatarKind TEXT NOT NULL,lastMessageId INTEGER," +
                    "updatedSortKey INTEGER NOT NULL,unreadCount INTEGER NOT NULL," +
                    "pinned INTEGER NOT NULL);\n" +
                    "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "chatId INTEGER NOT NULL,sortKey INTEGER NOT NULL,kind TEXT NOT NULL," +
                    "text TEXT NOT NULL,caption TEXT NOT NULL,replyToId INTEGER," +
                    "revision INTEGER NOT NULL,savedAt INTEGER NOT NULL);\n" +
                    "CREATE TABLE attachments(id TEXT PRIMARY KEY,messageId INTEGER NOT NULL," +
                    "relativePath TEXT NOT NULL,mime TEXT NOT NULL,byteSize INTEGER NOT NULL," +
                    "width INTEGER NOT NULL,height INTEGER NOT NULL,durationMs INTEGER," +
                    "thumbnailKey TEXT NOT NULL);\n" +
                    "CREATE TABLE drafts(chatId INTEGER PRIMARY KEY,text TEXT NOT NULL," +
                    "attachmentDraft TEXT);\n" +
                    "CREATE TABLE reading_positions(chatId INTEGER PRIMARY KEY,messageId INTEGER," +
                    "offsetWithin TEXT NOT NULL,followsBottom INTEGER NOT NULL," +
                    "layoutVersion INTEGER NOT NULL);\n" +
                    "CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);\n" +
                    "CREATE INDEX messages_chat_sort ON messages(chatId,sortKey,id);\n" +
                    "CREATE INDEX attachments_message ON attachments(messageId);\n" +
                    "INSERT INTO chats " +
                    "VALUES(2,'Alica','initial',7,10,3,0);\n" +
                    "INSERT INTO messages " +
                    "VALUES(7,2,10,'text','Ранее 👋','',NULL,1,10);\n" +
                    "INSERT INTO drafts " +
                    "VALUES(2,'Старый черновик',NULL);\n" +
                    "INSERT INTO metadata " +
                    "VALUES('schema','1');\n" +
                    "PRAGMA user_version=1;"
            SQLiteDatabase.openOrCreateDatabase(File(root, "chat.sqlite3"), null).use { db ->
                sql.split(";").filter { it.isNotBlank() }.forEach { db.execSQL(it) }
            }
        }

        fun open(root: File, faults: RepositoryFaults = RepositoryFaults()): RepositoryHarness {
            val store = awaitResult<SQLiteStore> { SQLiteStore.open(root, faults, it) }
            awaitResult<Unit> { cb ->
                store.submit(
                    { db ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO chats(id,title,avatarKind,updatedSortKey," +
                                "unreadCount,pinned) " +
                                "VALUES(2,'Alica','initial',0,3,0)"
                        )
                    },
                    cb,
                )
            }
            return RepositoryHarness(root, store, faults)
        }
    }

    fun close() {
        awaitResult<Unit> { store.close(it) }
    }

    fun reopen(): RepositoryHarness {
        close()
        return open(root, faults)
    }

    fun messageCount(chatID: Long): Int =
        rawScalar("SELECT COUNT(*) FROM messages WHERE chatId=$chatID")

    fun rawScalar(sql: String): Int =
        SQLiteDatabase.openDatabase(
                File(root, "chat.sqlite3").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            .use { db ->
                db.rawQuery(sql, null).use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            }

    fun integrity(): String =
        SQLiteDatabase.openDatabase(
                File(root, "chat.sqlite3").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            .use { db ->
                db.rawQuery("PRAGMA integrity_check", null).use {
                    it.moveToFirst()
                    it.getString(0)
                }
            }
}
