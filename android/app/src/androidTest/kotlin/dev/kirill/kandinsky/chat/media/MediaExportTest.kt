// Проверяем, что система получает оригинал и право читать только переданный файл.

package dev.kirill.kandinsky.chat.media

import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore.Downloads
import androidx.core.content.IntentCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun sharingStagesTheOriginalAndGrantsReadAccessOnce() {
        val outcomes = LinkedBlockingQueue<Result<Intent>>()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun startActivity(intent: Intent) {
                outcomes.add(Result.success(intent))
            }
        }
        withPhoto(context) { media, photo, original, export ->
            val shareFolder = File(context.cacheDir, "kandinsky-share/${media.store.root.name}")
            try {
                assertTrue(shareFolder.mkdirs())
                val old = File(shareFolder, "old.jpg").apply { writeBytes(byteArrayOf(1)) }
                assertTrue(old.setLastModified(1))
                fun start() = export.start(media, photo, share = true) {
                    outcomes.add(Result.failure(it))
                }
                instrumentation.runOnMainSync {
                    start()
                    start()
                }
                val chooser = checkNotNull(outcomes.poll(5, TimeUnit.SECONDS)).getOrThrow()
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = checkNotNull(
                    IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java),
                )
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("image/jpeg", send.type)
                val stream = checkNotNull(
                    IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java),
                )
                assertEquals("content", stream.scheme)
                assertEquals(context.packageName + ".kandinsky-results", stream.authority)
                assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertFalse(old.exists())
                val bytes = checkNotNull(context.contentResolver.openInputStream(stream)).use { it.readBytes() }
                assertArrayEquals(original.readBytes(), bytes)
                assertTrue(outcomes.isEmpty())
            } finally {
                shareFolder.deleteRecursively()
            }
        }
    }

    @Test
    fun savingPublishesTheSameBytesInDownloads() {
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val failures = LinkedBlockingQueue<Throwable>()
        withPhoto(context) { media, photo, original, export ->
            fun entries(): List<Pair<Uri, Int>> {
                val cursor = checkNotNull(resolver.query(
                    Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(Downloads._ID, Downloads.IS_PENDING),
                    "${Downloads.DISPLAY_NAME}=?",
                    arrayOf(original.name),
                    null,
                ))
                return cursor.use {
                    buildList {
                        while (it.moveToNext()) {
                            add(ContentUris.withAppendedId(Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)) to it.getInt(1))
                        }
                    }
                }
            }

            try {
                val held = File(original.parentFile, original.name + ".held")
                assertTrue(original.renameTo(held))
                instrumentation.runOnMainSync {
                    export.start(media, photo, share = false) { failures.add(it) }
                }
                val failure = failures.poll(5, TimeUnit.SECONDS)
                assertTrue("Оригинал недоступен", failure is FileNotFoundException)
                assertTrue("У незавершённой загрузки не остаётся записи", entries().isEmpty())
                assertTrue(held.renameTo(original))

                instrumentation.runOnMainSync {
                    export.start(media, photo, share = false) { failures.add(it) }
                    export.start(media, photo, share = false) { failures.add(it) }
                }
                val deadline = SystemClock.uptimeMillis() + 5_000
                var published = entries().filter { it.second == 0 }
                while (published.isEmpty() && failures.isEmpty() && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(20)
                    published = entries().filter { it.second == 0 }
                }
                failures.peek()?.let { throw AssertionError("Не удалось сохранить оригинал", it) }
                val uri = published.single().first
                val bytes = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
                assertArrayEquals(original.readBytes(), bytes)
                assertEquals("image/jpeg", resolver.getType(uri))
            } finally {
                entries().forEach { (uri, _) -> resolver.delete(uri, null, null) }
            }
        }
    }

    private fun withPhoto(
        context: Context,
        verify: (MediaStore, Attachment, File, KandinskyMediaExport) -> Unit,
    ) {
        check(context.packageName.endsWith(".cleanup"))
        val scratch = File(context.cacheDir, "export-${UUID.randomUUID()}")
        val account = File(scratch, "net-v1-u${System.nanoTime()}")
        val harness = RepositoryHarness.open(account)
        lateinit var media: MediaStore
        lateinit var export: KandinskyMediaExport
        instrumentation.runOnMainSync {
            media = MediaStore(harness.store)
            export = KandinskyMediaExport(context)
        }
        try {
            val name = "checked-${UUID.randomUUID()}.jpg"
            val original = File(account, "media/$name")
            checkNotNull(original.parentFile).mkdirs()
            TestFixtures.assets.open("media/photo-000.jpg").use { input ->
                original.outputStream().use { input.copyTo(it) }
            }
            val photo = Attachment(
                UUID.randomUUID().toString(), name, "image/jpeg", original.length(),
                0, 0, 1, null, "",
            )
            verify(media, photo, original, export)
        } finally {
            instrumentation.runOnMainSync {
                export.close()
                media.close()
            }
            harness.close()
            scratch.deleteRecursively()
        }
    }
}
