// Проверяем чтение, обновление времени и очистку сохранённых файлов. Отдельные сценарии читают
// настоящий диапазон видео через существующий аккаунт.

package dev.kirill.kandinsky.chat.network

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.support.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class NetworkDiskCacheTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext

    // Подготавливаем подключение, находим нужный чат и читаем до 128 его последних строк. Ответ
    // нужен, чтобы выбрать реальный файл для сетевого сценария.
    private fun history(api: NetworkAPI): org.json.JSONArray {
        api.initialize()
        val catalog = api.json("/v1/chats").getJSONArray("chats")
        val chats = (0 until catalog.length()).map { catalog.getJSONObject(it) }
        val dense = chats.firstOrNull { it.getString("title") == "Плотная медиалента" }
            ?: chats.firstOrNull { it.getString("title") == "Медиа с сервера" }
        val chat = checkNotNull(dense) { "В проверочном аккаунте нужна история с фото и видео" }
        return api.json("/v1/chats/${chat.getLong("id")}/history?limit=128").getJSONArray("messages")
    }

    // Ставим файлу старое время и читаем его из местного хранилища. Байты должны совпасть, время
    // обновиться, а число сетевых запросов остаться нулевым.
    @Test
    fun diskHitRefreshesLastUseWithoutOpeningNetwork() {
        val root = File(context.cacheDir, "disk-hit-" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(root, "cached.jpg")
        val bytes = "authored persistent cache bytes".toByteArray()
        file.writeBytes(bytes)
        assertTrue(file.setLastModified(100000))
        val counter = AtomicInteger()
        val api = NetworkAPI(null) { counter.incrementAndGet() }
        try {
            api.download("a".repeat(32), file, 1024, MediaCancellation())
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(0, counter.get())
            assertTrue("Disk hit must refresh eviction recency", file.lastModified() > 100000)
        } finally {
            api.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun aPreviouslyDownloadedVideoGetsItsExtensionAndPlaysWithoutAnotherRequest() {
        val root = File(context.cacheDir, "legacy-video-cache-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val calls = AtomicInteger()
        val api = NetworkAPI(null) { calls.incrementAndGet() }
        try {
            val blob = "b".repeat(32)
            val legacy = File(root, "media/remote/$blob")
            assertTrue(legacy.parentFile!!.mkdirs())
            val bytes = TestFixtures.assets.open("media/video-01.mp4").use { it.readBytes() }
            legacy.writeBytes(bytes)
            val attachment = Attachment(
                id = "saved-video", relativePath = "remote/$blob", mime = "video/mp4",
                byteSize = bytes.size.toLong(), rawWidth = 256, rawHeight = 256,
                orientation = 1, durationMs = 3000, thumbnailKey = blob,
            )
            val media = NetworkMedia(harness.store, api)
            val typed = File(legacy.path + ".mp4").canonicalFile
            media.playback(attachment, MediaCancellation()).use { source ->
                assertEquals(typed, source.file)
                assertFalse(legacy.exists())
                assertArrayEquals(bytes, typed.readBytes())
                val video = android.media.MediaMetadataRetriever()
                try {
                    video.setDataSource(typed.path)
                    assertEquals("256", video.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                } finally {
                    video.release()
                }
            }
            media.playback(attachment, MediaCancellation()).use {
                assertEquals(typed, it.file)
            }
            assertEquals(0, calls.get())
        } finally {
            api.close()
            harness.close()
            root.deleteRecursively()
        }
    }

    // Большое фото без миниатюры ждёт открытия. Два запроса оригинала делят одну загрузку, а после
    // освобождения памяти тот же файл читается с диска.
    @Test
    fun concurrentRemoteOpensReuseFileAfterRamClear() {
        val profile = NetworkCredentials(context).load()
        Assume.assumeNotNull(profile)
        val p = checkNotNull(profile)
        val lookup = NetworkAPI(p)
        val wire =
            try {
                val rows = history(lookup)
                (0 until rows.length())
                    .map { rows.getJSONObject(it) }
                    .filter { it.getString("kind") == "photo" }
                    .map { it.getJSONArray("attachments").getJSONObject(0) }
                    .first { it.getLong("byteSize") > 256 * 1024 }
            } finally {
                lookup.close()
            }
        val blob = wire.getString("blobId")
        val mime = wire.getString("mime")
        val size = wire.getLong("byteSize")
        val h = RepositoryHarness.open(File(context.cacheDir, "disk-live-" + UUID.randomUUID()))
        val count = AtomicInteger()
        val api = NetworkAPI(p) { count.incrementAndGet() }
        val remote = NetworkMedia(h.store, api)
        awaitResult<Unit> { cb ->
            h.store.submit(
                { db ->
                    NetworkSchema.install(db)
                    db.execSQL(
                        "INSERT INTO net_media(attachmentID,blobID,previewBlobID,mime,byteSize) VALUES(?,?,NULL,?,?)",
                        arrayOf("cache-probe", blob, mime, size),
                    )
                    Unit
                },
                cb,
            )
        }
        val a =
            Attachment(
                "cache-probe",
                NetworkMedia.relativePath(blob, mime),
                mime,
                size,
                wire.optInt("rawWidth", 512),
                wire.optInt("rawHeight", 512),
                1,
                null,
                "",
            )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val missingPreview = assertThrows(MediaException::class.java) {
                remote.resolve(a, true, MediaCancellation())
            }
            assertEquals(MediaError.UNAVAILABLE, missingPreview.reason)
            assertEquals(0, count.get())
            val first =
                pool.submit<java.io.File> { remote.resolve(a, false, MediaCancellation()).first }
            val second =
                pool.submit<java.io.File> { remote.resolve(a, false, MediaCancellation()).first }
            val one = first.get(20, TimeUnit.SECONDS)
            assertEquals(one, second.get(20, TimeUnit.SECONDS))
            assertEquals(size, one.length())
            assertEquals(1, count.get())
            lateinit var media: MediaStore
            i.runOnMainSync {
                media = MediaStore(h.store)
                media.remoteFile = { attachment, preview, token ->
                    remote.resolve(attachment, preview, token)
                }
            }
            val f =
                awaitResult<PreparedImage> { cb ->
                    i.runOnMainSync {
                        media.thumbnail(a, ThumbnailKey(512), 0) { _, result -> cb(result) }
                    }
                    Cancellation {}
                }
            i.runOnMainSync {
                assertNotNull(f.bitmap)
                f.close()
                media.releaseIdleImages()
                media.close()
                assertEquals(0, media.bytesUsed)
            }
            assertTrue(one.isFile)
            val before = count.get()
            assertEquals(one, remote.resolve(a, false, MediaCancellation()).first)
            assertEquals(before, count.get())
        } finally {
            pool.shutdownNow()
            api.close()
            h.close()
        }
    }

    // Удаляем оригинал, кадр и части одного вложения из местного хранилища. Соседний файл
    // остаётся, а начатое чтение удалённого вложения больше не разрешено.
    @Test
    fun studioRemovalEvictsOriginalPosterAndRangesWithoutTouchingAnotherBlob() {
        val h =
            RepositoryHarness.open(File(context.cacheDir, "studio-removal-" + UUID.randomUUID()))
        val api = NetworkAPI(null)
        val remote = NetworkMedia(h.store, api)
        val blob = "a".repeat(32)
        val preview = "b".repeat(32)
        val other = "c".repeat(32)
        val removal = checkNotNull(StudioMediaRemoval.parse(blob, preview))
        val relative = "remote/$blob.mp4"
        // Создаём файл по заданному пути в местной папке проверки. Так можно отдельно разместить
        // оригинал, уменьшенную копию и соседнее вложение.
        fun file(path: String) =
            File(h.root, "media/$path").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(16))
            }
        val copies =
            listOf(
                file(relative),
                file("previews/$preview"),
                file("previews/" + MediaPreviewGenerator.derivedName(relative)),
                file("ranges/$blob/0.chunk"),
            )
        val retained = file("remote/$other.mp4")
        try {
            remote.removeCached(removal)
            remote.removeCached(removal)
            assertTrue(copies.none { it.exists() })
            assertTrue(retained.isFile)
            val attachment = Attachment("retired", relative, "video/mp4", 16, 32, 20, 1, 1000, blob)
            val error =
                runCatching { remote.resolve(attachment, false, MediaCancellation()) }
                    .exceptionOrNull()
            assertEquals(MediaError.CANCELLED, (error as? MediaException)?.reason)
        } finally {
            api.close()
            h.close()
        }
    }

    // С существующим входом читаем первые 4096 байт настоящего видео и сверяем его заголовок. Тот
    // же запрос без входа должен завершиться ошибкой.
    @Test
    fun liveRangeUsesAuthenticated206AndRejectsMissingSession() {
        val profile =
            checkNotNull(NetworkCredentials(context).load()) {
                "Live acceptance requires the existing provisioned session"
            }
        val api = NetworkAPI(profile)
        val anonymous = NetworkAPI(null)
        try {
            val rows = history(api)
            val wire =
                (0 until rows.length())
                    .map { rows.getJSONObject(it) }
                    .first { it.getString("kind") in listOf("video", "round") }
                    .getJSONArray("attachments")
                    .getJSONObject(0)
            val blob = wire.getString("blobId")
            val length = wire.getLong("byteSize")
            val data = api.range(blob, 0, 4095, length, MediaCancellation())
            assertEquals(4096, data.size)
            assertEquals("ftyp", String(data, 4, 4, Charsets.US_ASCII))
            assertTrue(
                runCatching { anonymous.range(blob, 0, 4095, length, MediaCancellation()) }
                    .isFailure
            )
        } finally {
            api.close()
            anonymous.close()
        }
    }
}
