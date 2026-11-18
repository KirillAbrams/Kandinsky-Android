package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import androidx.core.graphics.createBitmap
import dev.kirill.kandinsky.chat.domain.Cancellation

internal class VideoPlaybackCache(private val budget: ImageByteBudget, root: java.io.File? = null) {
    private val disk = root?.let { VideoCoverDiskCache(it) }
    private val writer = MediaDecodeQueue(maximumWorkers = 1)
    private val reader = MediaDecodeQueue(maximumWorkers = 1)
    private val restores = MediaThumbnailRequests()
    private val main = Handler(Looper.getMainLooper())
    private val preparations = linkedMapOf<VideoPlaybackKey, Cancellation?>()
    private val writes = mutableListOf<CoverWrite>()
    private val pendingCopies = mutableMapOf<VideoPlaybackKey, Any>()
    private var activeCopies = 0

    private data class FrameReservation(
        val width: Int,
        val height: Int,
        val epoch: Long,
        val charge: ImageByteCharge,
    )

    private class CoverWrite(private var frame: PreparedImage?, private var charge: ImageByteCharge?) {
        @Synchronized fun perform(disk: VideoCoverDiskCache, key: VideoPlaybackKey, epoch: Long) {
            try { frame?.bitmap?.let { disk.store(it, key, epoch) } } finally { close() }
        }
        @Synchronized fun close() {
            frame?.close(); frame = null
            charge?.close(); charge = null
        }
    }
    private val frames = ThumbnailCache(budget, entryLimit = 64)
    private val positions = LinkedHashMap<VideoPlaybackKey, Long>()
    var generation = 0
        private set
    private var closed = false

    fun position(key: VideoPlaybackKey): Long = positions[key] ?: 0L

    fun rememberPosition(value: Long, key: VideoPlaybackKey, generation: Int) {
        if (closed || this.generation != generation || value < 0) return
        positions.remove(key)
        positions[key] = value
        while (positions.size > 256) positions.remove(positions.keys.first())
    }

    fun frame(key: VideoPlaybackKey): PreparedImage? = frames.frame(key.cacheKey)
    fun cover(key: VideoPlaybackKey): PreparedImage? = if (closed) null else frame(key)

    // В кадре экрана читаем только память. Владельцы одного дискового чтения разделяют результат.
    fun restoreCover(
        key: VideoPlaybackKey,
        priority: Int = 1,
        completion: (Result<PreparedImage>) -> Unit,
    ): Cancellation {
        val ticket = generation
        val epoch = disk?.epoch()
        return restores.submit(key.cacheKey, priority, {}, start = { priority, _, _, done ->
            val cached = cover(key)
            if (cached != null) {
                val token = MediaCancellation()
                token.onCancel { cached.close() }
                main.post {
                    if (!closed && generation == ticket && disk?.epoch() == epoch && token.finish()) done(Result.success(cached))
                    else {
                        cached.close()
                        done(Result.failure(MediaException(MediaError.CANCELLED)))
                    }
                }
                token
            } else {
                reader.submit(priority, work = { disk?.readPixels(key, budget) },
                    dispose = { it?.closeOwner() }) { result ->
                    val pixels = result.getOrNull()
                    if (closed || generation != ticket || disk?.epoch() != epoch) {
                        pixels?.closeOwner()
                        done(Result.failure(MediaException(MediaError.CANCELLED)))
                    } else if (pixels != null) {
                        val fresh = frames.frame(key.cacheKey)
                        if (fresh != null) {
                            pixels.closeOwner()
                            done(Result.success(fresh))
                        } else {
                            retainFrame(pixels, key)
                            val frame = frames.frame(key.cacheKey)
                            if (frame != null) done(Result.success(frame))
                            else done(Result.failure(MediaException(MediaError.UNAVAILABLE)))
                        }
                    } else {
                        done(Result.failure(result.exceptionOrNull() ?: MediaException(MediaError.UNAVAILABLE)))
                    }
                }
            }
        }, completion = { result ->
            if (closed || generation != ticket || disk?.epoch() != epoch) {
                result.getOrNull()?.close()
                completion(Result.failure(MediaException(MediaError.CANCELLED)))
            } else completion(result)
        })
    }

    fun prepareCovers(keys: List<VideoPlaybackKey>) {
        if (closed) return
        val wanted = keys.distinct().take(4).toSet()
        for (key in preparations.keys.toList()) {
            if (key !in wanted) preparations.remove(key)?.cancel()
        }
        for (key in wanted) {
            if (preparations.containsKey(key)) continue
            preparations[key] = null
            val cached = cover(key)
            if (cached != null) cached.close()
            else preparations[key] = restoreCover(key, priority = 0) { result ->
                result.getOrNull()?.close()
                if (preparations.containsKey(key)) preparations[key] = null
            }
        }
    }

    fun makeRoom(amount: Int) = frames.makeRoom(amount)

    // Место резервируется до копирования кадра. Снимки делят общий предел с фотографиями.
    fun rememberFrame(
        key: VideoPlaybackKey,
        generation: Int,
        width: Int,
        height: Int,
        render: (Int, Int) -> Bitmap?,
    ) {
        val reservation = reserveFrame(generation, width, height) ?: return
        var bitmap: Bitmap? = null
        try {
            bitmap = render(reservation.width, reservation.height)
            if (bitmap == null) {
                reservation.charge.close()
                return
            }
            pendingCopies.remove(key)
            publishFrame(key, reservation, bitmap)
        } catch (_: Exception) {
            bitmap?.recycle()
            reservation.charge.close()
        }
    }

    fun refreshCoverFromSurface(
        key: VideoPlaybackKey,
        generation: Int,
        width: Int,
        height: Int,
        surface: Surface,
        onFinished: (Boolean) -> Unit,
    ): Boolean {
        if (activeCopies >= 4 || !surface.isValid || !hasCover(key)) return false
        val reservation = reserveFrame(generation, width, height) ?: return false
        // Пока копирование идёт, последняя чёткая обложка должна быть доступна.
        if (!hasCover(key)) {
            reservation.charge.close()
            return false
        }
        val bitmap = createBitmap(reservation.width, reservation.height)
        val marker = Any()
        pendingCopies[key] = marker
        activeCopies++
        try {
            PixelCopy.request(surface, bitmap, { status ->
                activeCopies--
                val latest = pendingCopies[key] === marker
                if (latest) pendingCopies.remove(key)
                val accepted = latest && !closed && this.generation == generation &&
                    (disk?.epoch() ?: 0L) == reservation.epoch && status == PixelCopy.SUCCESS
                if (accepted) {
                    publishFrame(key, reservation, bitmap)
                } else {
                    bitmap.recycle()
                    reservation.charge.close()
                }
                onFinished(accepted)
            }, main)
            return true
        } catch (_: IllegalArgumentException) {
            activeCopies--
            pendingCopies.remove(key)
            bitmap.recycle()
            reservation.charge.close()
            return false
        }
    }

    private fun hasCover(key: VideoPlaybackKey): Boolean = frame(key)?.use { true } ?: false

    private fun reserveFrame(generation: Int, width: Int, height: Int): FrameReservation? {
        if (closed || this.generation != generation || width <= 0 || height <= 0) return null
        val scale = minOf(1.0, 1024.0 / maxOf(width, height))
        val frameWidth = maxOf(1, (width * scale).toInt())
        val frameHeight = maxOf(1, (height * scale).toInt())
        val amount = frameWidth * frameHeight * 4
        if (!frames.makeRoom(amount)) return null
        val charge = budget.reserve(amount) ?: return null
        return FrameReservation(frameWidth, frameHeight, disk?.epoch() ?: 0L, charge)
    }

    private fun publishFrame(key: VideoPlaybackKey, reservation: FrameReservation, bitmap: Bitmap) {
        val bytes = bitmap.allocationByteCount
        reservation.charge.shrink(bytes)
        retainFrame(ImagePixels(bitmap, reservation.charge), key)
        persist(key, bytes, reservation.epoch)
    }

    private fun retainFrame(pixels: ImagePixels, key: VideoPlaybackKey) {
        val maximumBytes = minOf(16 * 1024 * 1024, budget.limit / 3)
        frames.insert(pixels, key.cacheKey, maximumBytes)
    }

    private fun persist(key: VideoPlaybackKey, bytes: Int, epoch: Long) {
        val disk = disk ?: return
        if (writes.size >= 2) return
        val frame = frames.frame(key.cacheKey) ?: return
        val charge = budget.reserve(bytes * 2)
        if (charge == null) { frame.close(); return }
        val write = CoverWrite(frame, charge)
        writes += write
        writer.submit(
            work = { write.perform(disk, key, epoch) },
            dispose = {},
            completion = {
                write.close()
                writes.remove(write)
            },
        )
    }

    fun clearFrames() {
        generation++
        pendingCopies.clear()
        val pending = preparations.values.toList()
        preparations.clear()
        pending.forEach { it?.cancel() }
        restores.clear()
        frames.clear()
    }

    fun close() {
        closed = true
        reader.close()
        writer.close()
        writes.forEach { it.close() }
        writes.clear()
        disk?.close()
        clearFrames()
        positions.clear()
    }
}
