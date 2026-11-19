// Проверяем точное чтение сохранённых фото и миниатюр, работу при занятой сети и защиту файла от
// удаления во время чтения.

package dev.kirill.kandinsky.chat.network

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.support.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NetworkCachedMediaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Выполняем действие с состоянием экрана на основном потоке. Так проверка использует те же
    // правила доступа к объектам, что приложение.
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test
    fun onlyACompleteDownloadWithMatchingDigestCanReplaceTheTemporaryFile() {
        val directory = File(instrumentation.targetContext.cacheDir, "verified-download-" + UUID.randomUUID())
        val data = ByteArray(140_000) { 0x42 }
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/v1/media/$digest", request.url.encodedPath)
            val body = when (val number = calls.incrementAndGet()) {
                1 -> ByteArray(data.size) { 0x24 }.toResponseBody()
                2, 4 -> data.toResponseBody()
                3 -> object : ResponseBody() {
                    private val bytes = Buffer().write(data, 0, data.size - 1)
                    override fun contentLength() = data.size.toLong()
                    override fun contentType() = null
                    override fun source() = bytes
                }
                else -> throw AssertionError("Лишняя загрузка $number")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Проверочный ответ").body(body).build()
        }.build()
        val api = NetworkAPI(null, client).also { it.initialize() }
        val original = File(directory, "original")
        val incomplete = File(directory, "incomplete")
        val oversized = File(directory, "oversized")
        val limit = data.size.toLong()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                api.download(digest, original, limit, MediaCancellation())
            }
            assertFalse(original.exists())
            assertFalse(File(directory, "original.part").exists())
            api.download(digest, original, limit, MediaCancellation())
            assertArrayEquals(data, original.readBytes())
            api.download(digest, original, limit, MediaCancellation())
            assertEquals(2, calls.get())

            val partial = assertThrows(IOException::class.java) {
                api.download(digest, incomplete, limit, MediaCancellation())
            }
            assertEquals("Incomplete media", partial.message)
            assertFalse(incomplete.exists())
            assertFalse(File(directory, "incomplete.part").exists())

            val large = assertThrows(IOException::class.java) {
                api.download(digest, oversized, limit - 1, MediaCancellation())
            }
            assertEquals("Media exceeds budget", large.message)
            assertFalse(oversized.exists())
            assertFalse(File(directory, "oversized.part").exists())
            assertArrayEquals(data, original.readBytes())
            assertEquals(4, calls.get())
        } finally {
            api.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun structuredAndPartialResponsesEnforceTheirByteLimitsAndAllowTheNextRead() {
        val blob = "3".repeat(32)
        val path = "/v1/media/$blob"
        val catalog = "/v1/kandinsky/models"
        val prefix = "{\"value\":\""
        val suffix = "\"}"
        val maximum = 2 * 1024 * 1024
        val samples = byteArrayOf(10, 20, 30, 40)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .message("Проверочный ответ")
            when (val number = calls.incrementAndGet()) {
                1, 2 -> {
                    assertEquals(catalog, request.url.encodedPath)
                    val length = maximum + if (number == 1) 1 else 0
                    val json = prefix + "x".repeat(length - prefix.length - suffix.length) + suffix
                    response.code(200).body(json.toResponseBody("application/json".toMediaType()))
                }
                3, 4 -> {
                    assertEquals(path, request.url.encodedPath)
                    assertEquals("bytes=0-3", request.header("Range"))
                    val bytes = if (number == 3) samples.copyOf(3) else samples
                    response.code(206).header("Content-Range", "bytes 0-3/8").body(
                        object : ResponseBody() {
                            private val content = Buffer().write(bytes)
                            override fun contentLength() = -1L
                            override fun contentType() = null
                            override fun source() = content
                        },
                    )
                }
                else -> error("Лишний запрос $number")
            }
            response.build()
        }.build()
        val api = NetworkAPI(null, client).also { it.initialize() }
        try {
            val large = assertThrows(IOException::class.java) { api.json(catalog) }
            assertEquals("Response exceeds budget", large.message)
            assertEquals(maximum - prefix.length - suffix.length, api.json(catalog).getString("value").length)

            val incomplete = assertThrows(IOException::class.java) {
                api.range(blob, 0, 3, 8, MediaCancellation())
            }
            assertEquals("Incomplete range", incomplete.message)
            assertArrayEquals(samples, api.range(blob, 0, 3, 8, MediaCancellation()))
            assertEquals(4, calls.get())
        } finally {
            api.close()
        }
    }

    private class Fixture(allowDownloads: Boolean = false, onRequest: () -> Unit = {}) : AutoCloseable {
        val h = RepositoryHarness.open(File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "cached-network-${UUID.randomUUID()}",
        ))
        val bytes = TestFixtures.assets.open(
            "real-media/backend-824d66cce0d64409ad4888a13792c7d4.jpg",
        ).use { it.readBytes() }
        val requests = AtomicInteger()
        val api = NetworkAPI(null, OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            check(allowDownloads) { "Cached file attempted an HTTP request" }
            onRequest()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture")
                .body(bytes.toResponseBody("image/jpeg".toMediaType())).build()
        }.build()).also { it.initialize() }
        val remote = NetworkMedia(h.store, api, cacheLimitBytes = 0)

        // Создаём описание фото с заданным номером файла и известными размерами. Можно
        // подставлять разные номера, используя одни и те же проверочные байты.
        fun photo(blob: String) = Attachment(
            blob, "remote/$blob.jpg", "image/jpeg", bytes.size.toLong(),
            3000, 1732, 1, null, blob,
        )

        // Создаём папку при необходимости и записываем проверочные байты по местному пути.
        // Возвращаем файл, с которым сравним результат поиска.
        fun cached(path: String, contents: ByteArray = bytes): File = File(h.root.canonicalFile, "media/$path").apply {
            parentFile!!.mkdirs()
            writeBytes(contents)
        }

        // Записываем связь номера вложения с оригиналом и миниатюрой через очередь базы. Чтение
        // сохранённого файла затем использует обычное соответствие.
        fun map(attachment: Attachment, preview: String? = null) {
            awaitResult<Unit> { callback ->
                h.store.submit({ db ->
                    NetworkSchema.install(db)
                    db.execSQL(
                        "INSERT INTO net_media VALUES(?,?,?,?,?)",
                        arrayOf<Any?>(attachment.id, attachment.thumbnailKey, preview,
                            attachment.mime, attachment.byteSize),
                    )
                    Unit
                }, callback)
            }
        }

        // Закрываем связь и хранилище, затем удаляем свою временную папку. Следующий сценарий
        // начинает со своего набора файлов.
        override fun close() { api.close(); h.close(); h.root.deleteRecursively() }
    }

    // Резервируем оригинал с двумя вариантами имени и отдельную миниатюру. Сверяем файл, вид и
    // байты, а счётчик запросов должен остаться нулевым.
    @Test
    fun originalLegacyNameAndMappedPreviewUseExactCachedBytesWithoutHttp() {
        Fixture().use { f ->
            val original = f.photo("a".repeat(32))
            val originalFile = f.cached(original.relativePath)
            println("KANDINSKY_CACHE_ROOT root=${f.h.root.absolutePath} canonical=${f.h.root.canonicalPath}")
            println("KANDINSKY_CACHE_FILE absolute=${originalFile.absolutePath} canonical=${originalFile.canonicalPath}")
            val token = MediaCancellation()
            assertEquals(originalFile, f.remote.cached(original, false, token)?.first)
            token.cancel()
            val legacy = original.copy(relativePath = "remote/" + original.thumbnailKey)
            val legacyToken = MediaCancellation()
            assertEquals(originalFile, f.remote.cached(legacy, false, legacyToken)?.first)
            legacyToken.cancel()
            val other = f.photo("b".repeat(32))
            val preview = "c".repeat(32)
            f.map(other, preview)
            val previewBytes = TestFixtures.assets.open(
                "real-media/cached-preview-b2cf77e3cafb4ba5ad916d15533f3298.jpg",
            ).use { it.readBytes() }
            val previewFile = f.cached("previews/$preview", previewBytes)
            val previewToken = MediaCancellation()
            val resolved = checkNotNull(f.remote.cached(other, true, previewToken))
            assertEquals(previewFile, resolved.first)
            assertEquals("image/jpeg", resolved.second)
            assertArrayEquals(previewBytes, resolved.first.readBytes())
            previewToken.cancel()

            val broken = f.photo("d".repeat(32))
            f.map(broken, "old-preview-id")
            val refused = assertThrows(MediaException::class.java) {
                f.remote.cached(broken, true, MediaCancellation())
            }
            assertEquals(MediaError.CORRUPT, refused.reason)
            awaitResult<Unit> { done ->
                f.h.store.submit({ db ->
                    db.execSQL(
                        "UPDATE net_media SET previewBlobID=? WHERE attachmentID=?",
                        arrayOf(preview, broken.id),
                    )
                }, done)
            }
            val repaired = MediaCancellation()
            try {
                assertEquals(previewFile, f.remote.cached(broken, true, repaired)?.first)
            } finally {
                repaired.cancel()
            }
            assertEquals(0, f.requests.get())
        }
    }

    // Удерживаем отдельный вызов передачи и в это время читаем сохранённое изображение. Чтение с
    // диска должно завершиться быстро до освобождения чужого запроса.
    @Test
    fun cachedOriginalDecodesWhileAnUnrelatedRealTransportCallIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(allowDownloads = true, onRequest = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }).use { f ->
            val remotePhoto = f.photo("d".repeat(32))
            val localPhoto = f.photo("e".repeat(32))
            f.map(remotePhoto)
            f.cached(localPhoto.relativePath)
            val media = MediaStore(f.h.store)
            val finished = CountDownLatch(1)
            val error = AtomicReference<Throwable>()
            try {
                main {
                    media.remoteFile = f.remote::resolve
                    media.remoteCached = f.remote::cached
                    media.thumbnail(remotePhoto, ThumbnailKey(256, studioOriginal = true), 0) {
                        _, result ->
                        error.set(result.exceptionOrNull())
                        result.getOrNull()?.close()
                        finished.countDown()
                    }
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val started = System.nanoTime()
                val image = awaitResult<PreparedImage> { callback ->
                    main {
                        media.thumbnail(localPhoto, ThumbnailKey(256, studioOriginal = true), 1) {
                            _, result -> callback(result)
                        }
                    }
                    Cancellation {}
                }
                image.close()
                val milliseconds = (System.nanoTime() - started) / 1_000_000.0
                assertTrue("Disk decode waited $milliseconds ms for unrelated HTTP", milliseconds < 150)
                assertEquals(1L, release.count)
                release.countDown()
                assertTrue(finished.await(3, TimeUnit.SECONDS))
                assertNull(error.get())
                assertEquals(1, f.requests.get())
            } finally {
                release.countDown()
                main { media.close() }
            }
        }
    }

    @Test
    fun cancelledLookupsDoNotReadOrDownloadMediaAfterSQLiteResponds() {
        Fixture(allowDownloads = true).use { fixture ->
            val attachment = fixture.photo("7".repeat(32))
            val previewKey = "8".repeat(32)
            fixture.map(attachment, previewKey)
            val preview = fixture.cached("previews/$previewKey")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val originalToken = MediaCancellation()
            val previewToken = MediaCancellation()
            val originalRead = FutureTask {
                runCatching { fixture.remote.resolve(attachment, false, originalToken) }
            }
            val previewRead = FutureTask {
                runCatching { fixture.remote.cached(attachment, true, previewToken) }
            }
            val originalWorker = Thread(originalRead, "KandinskyTestOriginalLookup")
            val previewWorker = Thread(previewRead, "KandinskyTestPreviewLookup")
            try {
                fixture.h.store.submit({
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }) {}
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                originalWorker.start()
                previewWorker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (System.nanoTime() < deadline &&
                    (originalWorker.state != Thread.State.TIMED_WAITING ||
                        previewWorker.state != Thread.State.TIMED_WAITING)
                ) {
                    Thread.sleep(5)
                }
                assertEquals(Thread.State.TIMED_WAITING, originalWorker.state)
                assertEquals(Thread.State.TIMED_WAITING, previewWorker.state)
                originalToken.cancel()
                previewToken.cancel()
                release.countDown()
                val originalError = originalRead.get(3, TimeUnit.SECONDS).exceptionOrNull()
                val previewError = previewRead.get(3, TimeUnit.SECONDS).exceptionOrNull()
                assertEquals(MediaError.CANCELLED, (originalError as? MediaException)?.reason)
                assertEquals(MediaError.CANCELLED, (previewError as? MediaException)?.reason)
                assertEquals(0, fixture.requests.get())
                assertArrayEquals(fixture.bytes, preview.readBytes())
                val retry = MediaCancellation()
                try {
                    val downloaded = fixture.remote.resolve(attachment, false, retry).first
                    assertArrayEquals(fixture.bytes, downloaded.readBytes())
                    assertEquals(1, fixture.requests.get())
                } finally {
                    retry.cancel()
                }
            } finally {
                release.countDown()
                originalWorker.join(3000)
                previewWorker.join(3000)
            }
        }
    }

    // Удерживаем первый файл чтением и заставляем хранилище освободить место. Файл остаётся до
    // отмены чтения и становится доступен для удаления после неё.
    @Test
    fun cachedReadPinSurvivesPruningAndCancellationMakesItEvictable() {
        Fixture(allowDownloads = true).use { f ->
            val first = f.photo("1".repeat(32))
            val second = f.photo("2".repeat(32))
            val third = f.photo("3".repeat(32))
            val original = f.cached(first.relativePath)
            f.map(second); f.map(third)
            val firstToken = MediaCancellation()
            assertNotNull(f.remote.cached(first, false, firstToken))
            val secondToken = MediaCancellation()
            f.remote.resolve(second, false, secondToken)
            assertTrue("Active cached reader must survive another download's prune", original.isFile)
            firstToken.cancel(); secondToken.cancel()
            val thirdToken = MediaCancellation()
            f.remote.resolve(third, false, thirdToken)
            assertFalse("Released reader can now be evicted", original.exists())
            thirdToken.cancel()
            assertEquals(2, f.requests.get())
        }
    }

    // Проверяем отказ для отменённого чтения и файла вне разрешённой папки. Оба случая должны
    // завершиться без обращения к сети.
    @Test
    fun revokedAndEscapedCachedFilesAreRejectedBeforeReadingOrHttp() {
        Fixture().use { f ->
            val attachment = f.photo("4".repeat(32))
            f.cached(attachment.relativePath)
            f.remote.removeCached(checkNotNull(StudioMediaRemoval.parse(attachment.thumbnailKey, "5".repeat(32))))
            f.cached(attachment.relativePath)
            val revoked = runCatching { f.remote.cached(attachment, false, MediaCancellation()) }.exceptionOrNull()
            assertEquals(MediaError.CANCELLED, (revoked as? MediaException)?.reason)
            val escaped = attachment.copy(relativePath = "remote/../../outside.jpg")
            File(f.h.root, "outside.jpg").writeBytes(f.bytes)
            val invalid = runCatching { f.remote.cached(escaped, false, MediaCancellation()) }.exceptionOrNull()
            assertEquals(MediaError.CORRUPT, (invalid as? MediaException)?.reason)
            assertEquals(0, f.requests.get())
        }
    }

    @Test
    fun removalRefusesABlockedOriginalAndCanCleanItsPreviewAfterTheOriginalDirectoryIsGone() {
        Fixture().use { fixture ->
            val photo = fixture.photo("6".repeat(32))
            val preview = "7".repeat(32)
            val removal = checkNotNull(StudioMediaRemoval.parse(photo.thumbnailKey, preview))
            val originalFolder = File(fixture.h.root.canonicalFile, "media/remote")
            val blocked = File(originalFolder, photo.thumbnailKey + ".jpg")
            assertTrue(blocked.mkdirs())
            val obstruction = File(blocked, "pending").apply { writeText("Занято") }
            val target = fixture.cached("previews/$preview.jpg")
            val unrelated = fixture.cached("previews/another-preview.jpg")

            assertThrows(IllegalStateException::class.java) { fixture.remote.removeCached(removal) }
            assertTrue(obstruction.exists())
            assertTrue(target.isFile)
            val expected = assertThrows(MediaException::class.java) {
                fixture.remote.resolve(photo, false, MediaCancellation())
            }
            assertEquals(MediaError.CANCELLED, expected.reason)
            assertTrue(obstruction.delete())
            assertTrue(blocked.delete())
            assertTrue(originalFolder.delete())

            fixture.remote.removeCached(removal)
            assertFalse(target.exists())
            assertArrayEquals(fixture.bytes, unrelated.readBytes())
            assertEquals(0, fixture.requests.get())
        }
    }
}
