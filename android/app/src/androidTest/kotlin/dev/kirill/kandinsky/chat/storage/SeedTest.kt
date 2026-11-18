package dev.kirill.kandinsky.chat.storage

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeedTest {
    @Test
    fun corpusFilesAndComposition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = TestFixtures.assets
        assertTrue("Native offline corpus bundled", assets.list("")!!.contains("manifest.json"))
        val manifest =
            JSONObject(assets.open("manifest.json").bufferedReader().use { it.readText() })
        val files = manifest.getJSONArray("files")
        assertEquals(329, files.length())
        val seed =
            JSONObject(assets.open("seed-v1.json").bufferedReader().use { it.readText() })
                .getJSONArray("messages")
        val counts = HashMap<String, Int>()
        for (i in 0 until seed.length()) {
            val row = seed.getJSONObject(i)
            if (row.getInt("chatID") == 4) {
                val kind = row.getString("kind")
                counts[kind] = (counts[kind] ?: 0) + 1
            }
        }
        assertEquals(
            listOf(6500, 2000, 700, 400, 200, 200),
            listOf("text", "photo", "voice", "video", "round", "gif").map { counts[it] },
        )
        var photoBytes = 0L
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val path = "media/" + f.getString("fileName")
            val mime = f.getString("mime")
            if (mime == "image/jpeg" || mime == "image/png") {
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open(path).use { BitmapFactory.decodeStream(it, null, info) }
                assertTrue(info.outWidth > 0 && info.outHeight > 0)
                if (mime == "image/jpeg") photoBytes += info.outWidth.toLong() * info.outHeight * 4
            } else if (mime == "image/gif") {
                val bytes = assets.open(path).use { it.readBytes() }
                assertTrue(
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                        is AnimatedImageDrawable
                )
            } else {
                assets.openFd(path).use { fd ->
                    MediaMetadataRetriever().use { r ->
                        r.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                        assertTrue(
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                                .toLong() > 0
                        )
                        if (mime == "video/mp4") {
                            val frame = r.getFrameAtTime(0)
                            assertNotNull(frame)
                            frame!!.recycle()
                        }
                    }
                }
            }
        }
        assertTrue(photoBytes > 48 * 1024 * 1024)
    }

    // Повторная установка не должна удалять добавленную строку или повторно создавать исходные
    // сообщения.
    @Test
    fun seedCompositionAndIdempotence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var h =
            dev.kirill.kandinsky.chat.support.RepositoryHarness.open(
                java.io.File(context.filesDir, "test-seed-" + java.util.UUID.randomUUID())
            )
        dev.kirill.kandinsky.chat.support.awaitResult<Unit> {
            SeedInstaller(h.store, TestFixtures.assets).installIfNeeded(callback = it)
        }
        val counts =
            listOf("text", "photo", "voice", "video", "round", "gif").map {
                h.rawScalar("SELECT COUNT(*) FROM messages WHERE chatId=4 AND kind='$it'")
            }
        assertEquals(listOf(6500, 2000, 700, 400, 200, 200), counts)
        assertEquals(10000, h.messageCount(4L))
        dev.kirill.kandinsky.chat.support.awaitResult<Unit> { cb ->
            h.store.submit(
                { db ->
                    db.rawQuery("SELECT DISTINCT relativePath FROM attachments", null).use { c ->
                        while (c.moveToNext()) assertTrue(
                            java.io.File(java.io.File(h.root, "media"), c.getString(0)).isFile
                        )
                    }
                },
                cb,
            )
        }
        val id =
            dev.kirill.kandinsky.chat.support
                .awaitResult<dev.kirill.kandinsky.chat.domain.MessageDelta> {
                    h.repo.send(
                        dev.kirill.kandinsky.chat.domain.SendCommand(
                            "user-after-seed",
                            4L,
                            text = "Пользовательское сообщение",
                        ),
                        it,
                    )
                }
                .inserted
                .first()
                .id
        h = h.reopen()
        dev.kirill.kandinsky.chat.support.awaitResult<Unit> {
            SeedInstaller(h.store, TestFixtures.assets).installIfNeeded(callback = it)
        }
        assertEquals(10001, h.messageCount(4L))
        assertEquals(1, h.rawScalar("SELECT COUNT(*) FROM messages WHERE id=$id"))
        assertEquals("ok", h.integrity())
        h.close()
    }
}
