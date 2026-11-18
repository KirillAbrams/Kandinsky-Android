package dev.kirill.kandinsky.chat.network

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.support.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NetworkCachedMediaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

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

        fun photo(blob: String) = Attachment(
            blob, "remote/$blob.jpg", "image/jpeg", bytes.size.toLong(),
            3000, 1732, 1, null, blob,
        )

        fun cached(path: String, contents: ByteArray = bytes): File = File(h.root.canonicalFile, "media/$path").apply {
            parentFile!!.mkdirs()
            writeBytes(contents)
        }

        fun map(attachment: Attachment, preview: String? = null) {
            awaitResult<Unit> { callback ->
                h.store.submit({ db ->
                    NetworkSchema.install(db)
                    db.execSQL(
                        "INSERT INTO net_media VALUES(?,?,?,?,?)",
                        arrayOf(attachment.id, attachment.thumbnailKey, preview,
                            attachment.mime, attachment.byteSize),
                    )
                    Unit
                }, callback)
            }
        }

        override fun close() { api.close(); h.close(); h.root.deleteRecursively() }
    }

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
            assertEquals(0, f.requests.get())
        }
    }

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
}
