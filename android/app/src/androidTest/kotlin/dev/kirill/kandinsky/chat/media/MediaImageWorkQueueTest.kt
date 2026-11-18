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
