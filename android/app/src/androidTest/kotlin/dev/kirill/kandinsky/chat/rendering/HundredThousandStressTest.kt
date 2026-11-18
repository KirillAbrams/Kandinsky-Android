package dev.kirill.kandinsky.chat.rendering

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.SeedInstaller
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class HundredThousandStressTest {
    @Test
    fun nativeSQLiteKeysetBoundedWindowAndSameMediaPool() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val h = RepositoryHarness.open(File(context.cacheDir, "stress100k-" + UUID.randomUUID()))
        try {
            awaitResult<Unit> {
                SeedInstaller(h.store, TestFixtures.assets).installIfNeeded(callback = it)
            }
            val before = h.rawScalar("SELECT COUNT(DISTINCT relativePath) FROM attachments")
            awaitResult<Unit> { cb ->
                h.store.submit(
                    { db ->
                        db.beginTransaction()
                        try {
                            for (n in 1..9) {
                                val offset = n * 1000000
                                val sort = n * 100000000
                                db.execSQL(
                                    "INSERT INTO messages(id,chatId,sortKey,kind,text,caption,replyToId," +
                                        "revision,savedAt,direction,searchText) SELECT id+$offset,chatId," +
                                        "sortKey+$sort,kind,text,caption,NULL,revision,savedAt,direction,searchText " +
                                        "FROM messages " +
                                        "WHERE chatId=4 AND id BETWEEN 100001 AND 110000"
                                )
                                db.execSQL(
                                    "INSERT INTO attachments(id,messageId,relativePath,mime,byteSize,width," +
                                        "height,durationMs,thumbnailKey,orientation) SELECT 'stress-$n-'||id," +
                                        "messageId+$offset,relativePath,mime,byteSize,width,height,durationMs," +
                                        "thumbnailKey,orientation " +
                                        "FROM attachments " +
                                        "WHERE messageId BETWEEN 100001 AND 110000"
                                )
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    },
                    cb,
                )
            }
            assertEquals(100000, h.messageCount(4))
            assertEquals(
                before,
                h.rawScalar("SELECT COUNT(DISTINCT relativePath) FROM attachments"),
            )
            val start = System.nanoTime()
            val rows = awaitResult<List<IndexedRow>> { h.repo.index(4, it) }
            val ms = (System.nanoTime() - start) / 1e6
            assertEquals(100000, rows.size)
            val window = PageWindow(4, h.repo)
            var maximum = 0
            val times = JSONArray()
            for (position in 0 until 100000 step 8333) {
                val t = System.nanoTime()
                val page =
                    awaitResult<HistoryPage> {
                        window.request(
                            PageRequest(4, rows[position].order, PageDirection.AROUND),
                            it,
                        )
                    }
                times.put((System.nanoTime() - t) / 1e6)
                assertFalse(page.rows.isEmpty())
                assertTrue(page.rows.any { it.id == rows[position].messageID })
                maximum = maxOf(maximum, window.loadedCount)
                assertTrue(window.loadedCount <= 384)
                for (a in page.rows.flatMap { it.attachments }) assertTrue(
                    File(File(h.root, "media"), a.relativePath).isFile
                )
            }
            window.close()
            assertEquals(0, window.loadedCount)
            assertEquals("ok", h.integrity())
            val out = File(context.filesDir, "task19").apply { mkdirs() }
            File(out, "100k-native-sqlite.json")
                .writeText(
                    JSONObject()
                        .put("metadataRows", rows.size)
                        .put("metadataOpenMs", ms)
                        .put("pageMs", times)
                        .put("maxFullMessages", maximum)
                        .put("distinctMediaPaths", before)
                        .toString()
                )
        } finally {
            h.close()
        }
    }
}
