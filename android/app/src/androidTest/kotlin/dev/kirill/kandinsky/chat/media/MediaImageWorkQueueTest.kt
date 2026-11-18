package dev.kirill.kandinsky.chat.media

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class MediaImageWorkQueueTest {
    @Test
    fun lastSubscriberCancellationStopsSharedWorkBeforeMainQueueRuns() {
        val retired = CountDownLatch(3)
        main {
            val requests = MediaThumbnailRequests()
            val inner = MediaCancellation()
            var starts = 0
            val start: ThumbnailStart = { _, _, _, _ ->
                starts++
                if (starts == 1) inner else MediaCancellation()
            }
            val complete: (Result<PreparedImage>) -> Unit = { result ->
                result.getOrNull()?.close()
                retired.countDown()
            }
            val a = requests.submit("shared", 0, {}, start, complete)
            val b = requests.submit("shared", 1, {}, start, complete)
            val finished = CountDownLatch(1)
            var afterFirst = true
            var afterLast = false
            Thread {
                a.cancel(); afterFirst = inner.isCancelled
                b.cancel(); afterLast = inner.isCancelled
                finished.countDown()
            }.start()
            try {
                assertTrue(finished.await(1, TimeUnit.SECONDS))
                assertFalse("Other owner still needs the transfer", afterFirst)
                assertTrue("Cancellation must not wait behind queued pixels", afterLast)
                val replacement = requests.submit("shared", 1, {}, start, complete)
                assertEquals("A new subscriber must not join cancelled work", 2, starts)
                replacement.cancel()
            } finally { requests.clear() }
        }
        assertTrue(retired.await(1, TimeUnit.SECONDS))
    }

    private enum class Phase { LOOKUP, DOWNLOAD, DECODE }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun cancellationDuringLookupDoesNotAdmitPixels() = checkRetirement(Phase.LOOKUP, false)
    @Test fun closingDuringDownloadCancelsFetchAndNeverDecodes() = checkRetirement(Phase.DOWNLOAD, true)
    @Test fun lateDecodeAfterCancellationReleasesCharge() = checkRetirement(Phase.DECODE, false)

    private fun checkRetirement(phase: Phase, closeQueue: Boolean) {
        val file = File(instrumentation.targetContext.cacheDir, "image-work-${UUID.randomUUID()}.jpg")
        TestFixtures.assets.open("real-media/backend-824d66cce0d64409ad4888a13792c7d4.jpg").use {
            source -> file.outputStream().use { source.copyTo(it) }
        }
        val queue = MediaImageWorkQueue()
        val fetch = MediaCancellation()
        val budget = ImageByteBudget()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val release = CountDownLatch(1)
        val admissions = AtomicInteger()
        val completions = AtomicInteger()
        val error = AtomicReference<Throwable>()
        var request: Cancellation? = null
        fun hold() {
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        fetch.onCancel { release.countDown() }
        try {
            main {
                request = queue.submit(
                    priority = 1,
                    fetch = fetch,
                    local = {
                        if (phase == Phase.LOOKUP) hold()
                        if (phase == Phase.DOWNLOAD) null else file to "image/jpeg"
                    },
                    remote = {
                        check(phase == Phase.DOWNLOAD)
                        hold()
                        file to "image/jpeg"
                    },
                    admit = {
                        admissions.incrementAndGet()
                        checkNotNull(budget.reserve(1024 * 1024))
                    },
                    decode = { source, charge ->
                        check(phase == Phase.DECODE)
                        hold()
                        val options = BitmapFactory.Options().apply { inSampleSize = 16 }
                        val image = checkNotNull(BitmapFactory.decodeFile(source.first.path, options))
                        charge.shrink(image.allocationByteCount)
                        ImagePixels(image, charge)
                    },
                ) { result ->
                    result.getOrNull()?.closeOwner()
                    error.set(result.exceptionOrNull())
                    completions.incrementAndGet()
                    finished.countDown()
                }
            }
            assertTrue("Selected stage reached", started.await(2, TimeUnit.SECONDS))
            main { if (closeQueue) queue.close() else request?.cancel() }
            assertTrue("Cancelled request completes", finished.await(3, TimeUnit.SECONDS))
            assertTrue(fetch.isCancelled)
            assertEquals(MediaError.CANCELLED, (error.get() as? MediaException)?.reason)
            assertEquals(1, completions.get())
            assertEquals(if (phase == Phase.DECODE) 1 else 0, admissions.get())
            assertEquals(0, budget.bytes)
        } finally {
            release.countDown()
            main { request?.cancel(); queue.close() }
            file.delete()
        }
    }
}
