package dev.kirill.kandinsky.chat.media

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

class MediaRangeCache(
    private val root: File,
    private val blob: String,
    val length: Long,
    private val original: File,
    private val fetch: (Long, Long, MediaCancellation) -> ByteArray,
) : AutoCloseable {
    companion object {
        const val CHUNK_SIZE = 256 * 1024
    }

    private val directory = File(root.canonicalFile, "media/ranges/$blob")
    private val io = ReentrantLock()
    private val state = Any()
    private var closed = false
    private var active: MediaCancellation? = null
    private val memory = LinkedHashMap<Int, ByteArray>(2, 0.75f, true)

    init {
        require(blob.matches(Regex("[a-f0-9]{32}|[a-f0-9]{64}")) && length in 1..67108864L)
        require(directory.canonicalFile == directory.absoluteFile)
        check(directory.isDirectory || directory.mkdirs())
    }

    // Скачанные части остаются на диске. Если снова открыть видео, плеер сможет использовать их без
    // повторной
    // загрузки.
    fun read(offset: Long, count: Int, cancellation: MediaCancellation): ByteArray {
        require(offset >= 0 && count >= 0)
        if (offset >= length || count == 0) return ByteArray(0)
        io.lock()
        try {
            if (synchronized(state) { closed } || cancellation.isCancelled)
                throw MediaException(MediaError.CANCELLED)
            if (original.isFile)
                return RandomAccessFile(original, "r").use {
                    it.seek(offset)
                    val data = ByteArray(minOf(count, 65536, (length - offset).toInt()))
                    it.readFully(data)
                    data
                }
            val index = (offset / CHUNK_SIZE).toInt()
            val start = index.toLong() * CHUNK_SIZE
            val size = minOf(CHUNK_SIZE.toLong(), length - start).toInt()
            val bytes =
                memory[index]
                    ?: run {
                        val path = File(directory, "$index.chunk")
                        val data =
                            if (path.isFile && path.length() == size.toLong()) path.readBytes()
                            else {
                                val token = MediaCancellation()
                                val stopped =
                                    synchronized(state) {
                                        active = token
                                        closed
                                    }
                                cancellation.onCancel { token.cancel() }
                                if (stopped || cancellation.isCancelled) token.cancel()
                                try {
                                    val value = fetch(start, start + size - 1, token)
                                    if (token.isCancelled)
                                        throw MediaException(MediaError.CANCELLED)
                                    require(value.size == size)
                                    val temp = File(directory, "${UUID.randomUUID()}.part")
                                    try {
                                        temp.outputStream().use {
                                            it.write(value)
                                            it.fd.sync()
                                        }
                                        check(temp.renameTo(path))
                                    } finally {
                                        temp.delete()
                                    }
                                    directory.setLastModified(System.currentTimeMillis())
                                    promoteIfComplete()
                                    pruneInactive()
                                    value
                                } finally {
                                    synchronized(state) { if (active === token) active = null }
                                }
                            }
                        memory[index] = data
                        while (memory.size > 2) memory.remove(memory.keys.first())
                        data
                    }
            val local = (offset - start).toInt()
            return bytes.copyOfRange(local, local + minOf(count, 65536, bytes.size - local))
        } finally {
            if (synchronized(state) { closed }) memory.clear()
            io.unlock()
        }
    }

    // Если номер файла содержит полный хеш, сверяем его перед публикацией. Повреждённые части
    // придётся скачать
    // заново.
    private fun promoteIfComplete() {
        if (original.isFile) return
        val count = ((length + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        if (
            (0 until count).any {
                File(directory, "$it.chunk").length() !=
                    minOf(CHUNK_SIZE.toLong(), length - it.toLong() * CHUNK_SIZE)
            }
        )
            return
        check(original.parentFile!!.isDirectory || original.parentFile!!.mkdirs())
        val temp = File(original.parentFile, "range-${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { out ->
                for (i in 0 until count) {
                    if (synchronized(state) { closed }) throw MediaException(MediaError.CANCELLED)
                    val bytes = File(directory, "$i.chunk").readBytes()
                    digest.update(bytes)
                    out.write(bytes)
                }
                out.fd.sync()
            }
            if (
                blob.length == 64 &&
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != blob
            ) {
                directory.listFiles()?.filter { it.extension == "chunk" }?.forEach { it.delete() }
                memory.clear()
                throw MediaException(MediaError.CORRUPT)
            }
            if (!original.isFile) check(temp.renameTo(original))
            for (i in 0 until count) File(directory, "$i.chunk").delete()
        } finally {
            temp.delete()
        }
    }

    // Текущий файл может ещё читать плеер. Поэтому при нехватке места удаляем части других файлов.
    private fun pruneInactive() {
        val directories = directory.parentFile?.listFiles()?.filter { it.isDirectory } ?: return
        val sizes = directories.associateWith { it.listFiles()?.sumOf { f -> f.length() } ?: 0L }
        var total = sizes.values.sum()
        for (dir in directories.sortedBy { it.lastModified() }) if (
            total > 64 * 1024 * 1024 && dir != directory
        ) {
            if (dir.deleteRecursively()) total -= sizes.getValue(dir)
        }
    }

    override fun close() {
        val token =
            synchronized(state) {
                closed = true
                active.also { active = null }
            }
        token?.cancel()
        if (io.tryLock()) {
            try {
                memory.clear()
            } finally {
                io.unlock()
            }
        }
    }
}
