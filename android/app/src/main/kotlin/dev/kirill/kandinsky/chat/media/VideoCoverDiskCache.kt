package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import androidx.core.graphics.createBitmap

// Сохраняет готовые пиксели последнего кадра, чтобы возврат не начинался с маленького превью.
internal class VideoCoverDiskCache(private val root: File) {
    private var closed = false
    @Volatile private var deletionEpoch = 0L
    private val revokedPaths = linkedSetOf<String>()
    init {
        synchronized(gate) {
            active.removeAll { it.get() == null }
            active += java.lang.ref.WeakReference(this)
        }
    }
    fun epoch(): Long = deletionEpoch

    fun store(bitmap: Bitmap, key: VideoPlaybackKey, epoch: Long? = null) = synchronized(gate) {
        if (closed || epoch != null && epoch != deletionEpoch || hash(key.relativePath) in revokedPaths || bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.width !in 1..1024 ||
            bitmap.height !in 1..1024 || bitmap.byteCount > maximumBytes) return@synchronized
        val directory = directory(root)
        check(directory.mkdirs() || directory.isDirectory)
        // Удаляем остатки прерванной записи и отметки прежней версии.
        for (file in directory.listFiles().orEmpty()) {
            if (file.extension !in listOf("frame", "jpg") && file.isFile) check(file.delete())
        }
        val pixels = ByteArray(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
        val header = ByteBuffer.allocate(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            .put(magic).putInt(bitmap.width).putInt(bitmap.height).putInt(bitmap.rowBytes)
            .putInt(1).put(digest(key.cacheKey.toByteArray(Charsets.UTF_8))).put(digest(pixels)).array()
        val temporary = File.createTempFile("cover-", ".pending", directory)
        try {
            FileOutputStream(temporary).use { out -> out.write(header); out.write(pixels) }
            check(temporary.renameTo(File(directory, name(key))))
        } finally { temporary.delete() }
        val ordered = directory.listFiles().orEmpty().filter { it.extension == "frame" }
            .sortedBy { it.lastModified() }
        var count = ordered.size
        var bytes = ordered.sumOf { it.length() }
        for (file in ordered) {
            if (count <= 64 && bytes <= 128L * 1024 * 1024) break
            val amount = file.length()
            check(file.delete())
            count--
            bytes -= amount
        }
    }

    // Чёткая копия помещается в отдельный предел и переживает очистку исходников.
    fun galleryFile(relative: String): File? = synchronized(gate) {
        val prefix = hash(relative)
        if (closed || prefix in revokedPaths) return@synchronized null
        val file = File(directory(root), "$prefix-gallery.jpg")
        if (file.canonicalFile != file.absoluteFile || !file.isFile || file.length() !in 1L..262_144L)
            return@synchronized null
        file.setLastModified(System.currentTimeMillis())
        file
    }

    fun storeGalleryFile(data: ByteArray, relative: String, epoch: Long) = synchronized(gate) {
        val prefix = hash(relative)
        if (closed || epoch != deletionEpoch || prefix in revokedPaths || data.size !in 1..262_144)
            return@synchronized
        val directory = directory(root)
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "$prefix-gallery.jpg")
        if (file.exists()) return@synchronized
        val temporary = File.createTempFile("gallery-", ".pending", directory)
        try {
            temporary.writeBytes(data)
            check(temporary.renameTo(file))
        } finally {
            temporary.delete()
        }
        val copies = directory.listFiles().orEmpty().filter {
            it != file && it.name.endsWith("-gallery.jpg")
        }
        if (copies.size >= 256) {
            for (old in copies.sortedBy { it.lastModified() }.take(copies.size - 255))
                check(old.delete())
        }
    }

    fun readPixels(key: VideoPlaybackKey, budget: ImageByteBudget): ImagePixels? = synchronized(gate) {
        if (closed || hash(key.relativePath) in revokedPaths) return@synchronized null
        var charge: ImageByteCharge? = null
        var bitmap: Bitmap? = null
        try {
            val directory = directory(root)
            val descriptor = Os.open(File(directory, name(key)).path,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            FileInputStream(descriptor).use { input ->
                val info = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(info.st_mode) || info.st_size !in headerBytes.toLong()..(headerBytes + maximumBytes).toLong())
                    return@synchronized null
                val amount = info.st_size.toInt() - headerBytes
                charge = budget.reserve(amount * 2) ?: return@synchronized null
                val header = ByteArray(headerBytes)
                input.readFully(header)
                val words = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val signature = ByteArray(8).also { words.get(it) }
                val width = words.int; val height = words.int; val stride = words.int; val format = words.int
                val identity = ByteArray(32).also { words.get(it) }
                val checksum = ByteArray(32).also { words.get(it) }
                if (!signature.contentEquals(magic) || width !in 1..1024 || height !in 1..1024 ||
                    stride != width * 4 || stride * height != amount || format != 1 ||
                    !identity.contentEquals(digest(key.cacheKey.toByteArray(Charsets.UTF_8)))) {
                    charge?.close(); return@synchronized null
                }
                val pixels = ByteArray(amount)
                input.readFully(pixels)
                if (!checksum.contentEquals(digest(pixels))) {
                    charge?.close(); return@synchronized null
                }
                bitmap = createBitmap(width, height)
                bitmap!!.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                charge!!.shrink(bitmap!!.allocationByteCount)
                val owner = ImagePixels(bitmap!!, charge!!)
                bitmap = null; charge = null
                owner
            }
        } catch (_: Exception) {
            bitmap?.recycle(); charge?.close(); null
        }
    }

    fun close() = synchronized(gate) {
        closed = true
        revokedPaths.clear()
        active.removeAll { it.get() == null || it.get() === this }
    }

    companion object {
        private val gate = Any()
        private val active = mutableListOf<java.lang.ref.WeakReference<VideoCoverDiskCache>>()
        private const val headerBytes = 88
        private const val maximumBytes = 4 * 1024 * 1024
        private val magic = "KVIDEO01".toByteArray(Charsets.US_ASCII)
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun hash(text: String) = digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun name(key: VideoPlaybackKey) = hash(key.relativePath) + "-" + hash(key.cacheKey) + ".frame"
        private fun directory(root: File): File = File(root.canonicalFile, "media/video-covers").also {
            check(it.canonicalFile == it.absoluteFile)
        }
        private fun FileInputStream.readFully(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                val count = read(bytes, offset, bytes.size - offset)
                check(count > 0)
                offset += count
            }
        }
        fun revoke(root: File, relativePath: String) = synchronized(gate) {
            val directory = directory(root)
            val prefix = hash(relativePath)
            val identity = root.canonicalFile
            active.removeAll { it.get() == null }
            for (item in active) {
                val cache = item.get() ?: continue
                if (cache.root.canonicalFile != identity) continue
                cache.deletionEpoch++
                cache.revokedPaths.remove(prefix)
                cache.revokedPaths.add(prefix)
                if (cache.revokedPaths.size > 512) cache.revokedPaths.remove(cache.revokedPaths.first())
            }
            if (!directory.exists()) return@synchronized
            for (file in directory.listFiles().orEmpty()) {
                if (file.name.startsWith(prefix + "-")) check(file.delete())
            }
        }
    }
}
