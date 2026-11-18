package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BenchmarkAnchorLookupTest {
    @Test
    fun resolveExistingNativeMessageID() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = checkNotNull(NetworkCredentials(context).load())
        val root = File(context.filesDir, "Kandinsky/net-v1-u" + profile.userID)
        SQLiteDatabase.openDatabase(
                File(root, "chat.sqlite3").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            .use { db ->
                val count =
                    db.rawQuery("SELECT count(*) FROM messages WHERE chatId=4", null).use {
                        it.moveToFirst()
                        it.getLong(0)
                    }
                assertTrue(count >= 10000)
                val id =
                    db.rawQuery(
                            "SELECT id FROM messages WHERE chatId=4 AND text LIKE ? ORDER BY sortKey DESC LIMIT 1",
                            arrayOf("Сообщение 9501.%"),
                        )
                        .use {
                            assertTrue(it.moveToFirst())
                            it.getLong(0)
                        }
                val output =
                    File(context.getExternalFilesDir(null), "kandinsky-network-probe").apply {
                        mkdirs()
                    }
                File(output, "benchmark-anchor.json")
                    .writeText(
                        JSONObject()
                            .put("messageID", id)
                            .put("rows", count)
                            .put("profileRoot", root.name)
                            .put("lookup", "read-only SQLite; exact corpus message prefix")
                            .toString()
                    )
            }
    }
}
