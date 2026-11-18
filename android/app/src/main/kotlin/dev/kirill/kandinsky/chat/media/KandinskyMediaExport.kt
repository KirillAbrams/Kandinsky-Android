package dev.kirill.kandinsky.chat.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore.Downloads
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.kirill.kandinsky.chat.domain.Attachment
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KandinskyResultFileProvider : FileProvider()

internal class KandinskyMediaExport(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var download: Cancellation? = null
    private var exporting = false
    private var closed = false

    /**
     * Получает оригинал выбранного результата, не допуская параллельную операцию экспорта
     * В собственной очереди сохраняет его в загрузки либо подготавливает системную отправку
     * В потоке экрана сообщает об успехе или ошибке только открытому владельцу
     */
    fun start(media: MediaStore, attachment: Attachment, share: Boolean, onError: (Throwable) -> Unit) {
        if (exporting || closed) return
        exporting = true
        download = media.ensureOriginal(attachment) { result ->
            download = null
            if (closed) return@ensureOriginal
            result.fold({ file ->
                io.execute {
                    val exported = runCatching {
                        if (share) stageShare(file, media.store.root.name) else {
                            save(file, attachment.mime)
                            null
                        }
                    }
                    main.post {
                        if (!closed) {
                            exporting = false
                            exported.onSuccess { uri ->
                                if (uri != null) {
                                    runCatching { launchShare(uri, attachment.mime) }.onFailure(onError)
                                } else {
                                    Toast.makeText(context, "Сохранено в «Загрузки»", Toast.LENGTH_SHORT).show()
                                }
                            }.onFailure(onError)
                        }
                    }
                }
            }, {
                exporting = false
                onError(it)
            })
        }
    }

    /**
     * Создаёт ещё не опубликованную запись результата в пользовательской папке загрузок
     * Копирует файл и после проверки прерывания делает запись видимой системе
     * При ошибке удаляет незавершённую запись перед возвратом причины
     */
    private fun save(file: File, mime: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(Downloads.DISPLAY_NAME, file.name)
            put(Downloads.MIME_TYPE, mime)
            put(Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kandinsky")
            put(Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create Kandinsky download")
        try {
            val target = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open Kandinsky download")
            target.use { output -> file.inputStream().use { it.copyTo(output) } }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (resolver.update(uri, ContentValues().apply { put(Downloads.IS_PENDING, 0) }, null, null) != 1) {
                throw IOException("Could not publish Kandinsky download")
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /**
     * Выбирает папку временной отправки с учётом допустимого имени владельца данных
     * Удаляет старые копии и при необходимости создаёт новую через временный файл
     * Возвращает системный адрес с ограниченным доступом к подготовленному результату
     */
    private fun stageShare(file: File, namespace: String): Uri {
        val shareRoot = File(context.cacheDir, "kandinsky-share")
        val folder = if (namespace.matches(Regex("net-v1-u[1-9][0-9]*"))) File(shareRoot, namespace) else shareRoot
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Could not create sharing folder")
        val expired = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        shareRoot.listFiles()?.filter { it.isFile && it.lastModified() < expired }?.forEach { it.delete() }
        if (folder != shareRoot) {
            folder.listFiles()?.filter { it.isFile && it.lastModified() < expired }?.forEach { it.delete() }
        }
        val target = File(folder, file.name)
        if (!target.isFile || target.length() != file.length()) {
            val staging = File.createTempFile("result-", ".tmp", folder)
            try {
                file.copyTo(staging, overwrite = true)
                if (!staging.renameTo(target)) throw IOException("Could not stage Kandinsky result")
            } finally {
                staging.delete()
            }
        }
        target.setLastModified(System.currentTimeMillis())
        return FileProvider.getUriForFile(context, context.packageName + ".kandinsky-results", target)
    }

    /**
     * Составляет системный запрос передачи файла с его видом содержимого
     * Выдаёт получателю временное разрешение чтения и открывает выбор приложения
     */
    private fun launchShare(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Kandinsky", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    /**
     * Отмечает владельца экспорта закрытым и отменяет ожидание оригинала
     * Прерывает собственную очередь подготовки, чтобы новые ответы не изменяли экран
     */
    fun close() {
        closed = true
        download?.cancel()
        download = null
        io.shutdownNow()
    }
}
