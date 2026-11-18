package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.storage.AppDataRoot
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class SourceUploadEvidenceTest {
    // Читает только явно указанный аккаунт. Другие проверки используют временные папки.
    @Test
    fun acceptedSourceIsRememberedInTheOwnersDatabase() {
        val arguments = InstrumentationRegistry.getArguments()
        val owner = arguments.getString("expected-user-id")
        val attachment = arguments.getString("expected-attachment-id")
        val blob = arguments.getString("expected-source-blob")
        assumeNotNull(owner, attachment, blob)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = checkNotNull(NetworkCredentials(context).load())
        assertEquals(owner!!.toLong(), profile.userID)
        val root = AppDataRoot.resolve(context.filesDir)
        val database = File(root, "net-v1-u${profile.userID}/chat.sqlite3")
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT blobID FROM net_uploads WHERE attachmentID=?", arrayOf(attachment))
                .use { rows ->
                    assertTrue("Source confirmation was not saved on the phone", rows.moveToFirst())
                    assertEquals(blob, rows.getString(0))
                    assertFalse(rows.moveToNext())
                }
        }
    }
}
