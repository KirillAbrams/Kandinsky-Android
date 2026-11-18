package dev.kirill.kandinsky.chat.media

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

// Поиск на диске и ожидание сети не занимают исполнителя, который готовит пиксели.
internal class MediaImageWorkQueue {
    private val main = Handler(Looper.getMainLooper())
    private val lookup = MediaDecodeQueue()
    private val downloads = MediaDecodeQueue()
    private val decoding = MediaDecodeQueue(1)
    private val requests = mutableMapOf<UUID, MediaCancellation>()
    private var closed = false

    fun submit(
        priority: Int,
        fetch: MediaCancellation,
        local: (MediaCancellation) -> Pair<File, String>?,
        remote: (MediaCancellation) -> Pair<File, String>,
        admit: () -> ImageByteCharge,
        decode: (Pair<File, String>, ImageByteCharge) -> ImagePixels,
        completion: (Result<ImagePixels>) -> Unit,
    ): Cancellation {
        check(Looper.myLooper() == Looper.getMainLooper())
        val identity = UUID.randomUUID()
        val request = MediaCancellation()
        requests[identity] = request
        fun finish(result: Result<ImagePixels>) {
            requests.remove(identity)
            fetch.cancel()
            if (!closed && request.finish()) completion(result)
            else {
                result.getOrNull()?.closeOwner()
                completion(Result.failure(MediaException(MediaError.CANCELLED)))
            }
        }
        fun prepare(source: Pair<File, String>) {
            if (closed || request.isCancelled) {
                finish(Result.failure(MediaException(MediaError.CANCELLED)))
                return
            }
            val work = decoding.submit(
                priority = priority,
                work = {
                    if (fetch.isCancelled) throw MediaException(MediaError.CANCELLED)
                    // Кэш мог заполниться за время загрузки. Проверяем место перед чтением пикселей.
                    val admitted = AtomicReference<Result<ImageByteCharge>>()
                    val ready = CountDownLatch(1)
                    main.post {
                        admitted.set(runCatching(admit))
                        ready.countDown()
                    }
                    ready.await()
                    val charge = admitted.get().getOrThrow()
                    try {
                        if (fetch.isCancelled) throw MediaException(MediaError.CANCELLED)
                        decode(source, charge)
                    } catch (error: Throwable) {
                        charge.close()
                        throw error
                    }
                },
                dispose = { it.closeOwner() },
                completion = { finish(it) },
            )
            request.onCancel { fetch.cancel(); work.cancel() }
        }
        val work = lookup.submit(
            priority = priority,
            work = { local(fetch) },
            dispose = {},
        ) { result ->
            if (closed || request.isCancelled) {
                finish(Result.failure(MediaException(MediaError.CANCELLED)))
                return@submit
            }
            result.fold(
                { source ->
                    if (source != null) prepare(source)
                    else {
                        val download = downloads.submit(
                            priority = priority,
                            work = { remote(fetch) },
                            dispose = {},
                        ) { downloaded ->
                            downloaded.fold({ prepare(it) }, { finish(Result.failure(it)) })
                        }
                        request.onCancel { fetch.cancel(); download.cancel() }
                    }
                },
                { finish(Result.failure(it)) },
            )
        }
        request.onCancel { fetch.cancel(); work.cancel() }
        return request
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        val active = requests.values.toList()
        requests.clear()
        active.forEach { it.cancel() }
        lookup.close()
        downloads.close()
        decoding.close()
    }
}
