package dev.kirill.kandinsky.chat.network

import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Test

class CurrentProfileInspectionTest {
    @Test
    fun exportProfileAndCachedCorpusCounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = NetworkCredentials(context).load()
        val out =
            JSONObject()
                .put("hasSavedProfile", profile != null)
                .put(
                    "debuggable",
                    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                )
        if (profile != null) {
            out.put("userID", profile.userID)
            val file = File(context.filesDir, "Kandinsky/net-v1-u${profile.userID}/chat.sqlite3")
            out.put("cacheExists", file.isFile)
            if (file.isFile)
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db
                    ->
                    db.rawQuery(
                            "SELECT count(*),count(DISTINCT kind) FROM messages WHERE chatId=1352",
                            null,
                        )
                        .use { c ->
                            check(c.moveToFirst())
                            out.put("corpusRows", c.getLong(0))
                            out.put("corpusKinds", c.getInt(1))
                        }
                    db.rawQuery("SELECT count(*) FROM chats WHERE id=1352", null).use { c ->
                        check(c.moveToFirst())
                        out.put("corpusChatPresent", c.getInt(0) == 1)
                    }
                }
        }
        File(
                context.getExternalFilesDir("kandinsky-test-metrics"),
                "current-profile-inventory.json",
            )
            .writeText(out.toString(2))
    }
}
