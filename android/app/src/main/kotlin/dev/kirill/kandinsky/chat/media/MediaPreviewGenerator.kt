// Готовит уменьшенные фото и кадры видео и сглаживает маленькие картинки до показа. Производные
// файлы живут отдельно от исходников.

package dev.kirill.kandinsky.chat.media

import android.graphics.*
import android.media.ThumbnailUtils
import android.util.Base64
import android.util.Size
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import androidx.core.graphics.scale

object MediaPreviewGenerator {
    // Считает контрольную сумму относительного пути и получает безопасное постоянное имя.
    // Повторный запрос найдёт ту же копию.
    fun derivedName(relative: String) =
        "local-" +
            MessageDigest.getInstance("SHA-256").digest(relative.toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 255)
            } +
            ".jpg"

    // Возвращает готовую миниатюру или извлекает кадр и уменьшает его. Проверяет и переносит
    // временный файл целиком, освобождает рисунки и удаляет старые производные.
    fun normalFile(root: File, relative: String, file: File, mime: String): File {
        val directory = File(root.canonicalFile, "media/previews")
        require(directory.canonicalFile == directory.absoluteFile)
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, derivedName(relative))
        if (target.isFile) return target
        val bitmap =
            if (mime.startsWith("video/"))
                ThumbnailUtils.createVideoThumbnail(file, Size(320, 320), null)
            else
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val scale = minOf(1.0, 320.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        maxOf(1, (info.size.width * scale).toInt()),
                        maxOf(1, (info.size.height * scale).toInt()),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
        val scale = minOf(1.0, 320.0 / maxOf(bitmap.width, bitmap.height))
        val image =
            if (scale < 1)
                bitmap.scale(
                    maxOf(1, (bitmap.width * scale).toInt()),
                    maxOf(1, (bitmap.height * scale).toInt()),
                )
            else bitmap
        val temp = File(directory, "encode-${UUID.randomUUID()}.part")
        try {
            temp.outputStream().use {
                check(image.compress(Bitmap.CompressFormat.JPEG, 78, it))
                it.fd.sync()
            }
            require(temp.length() <= 256 * 1024)
            check(temp.renameTo(target))
        } finally {
            temp.delete()
            if (image !== bitmap) image.recycle()
            bitmap.recycle()
        }
        val files = directory.listFiles()?.filter { it.name.startsWith("local-") } ?: emptyList()
        files
            .filter { it != target }
            .sortedBy { it.lastModified() }
            .take(maxOf(0, files.size - 128))
            .forEach { it.delete() }
        return target
    }

    // Сглаживает маленькую картинку по горизонтали и вертикали дважды. Для видео использует более
    // сильное сглаживание, чтобы при увеличении границы точек были менее заметны.
    internal fun softenTinyPreview(image: Bitmap, video: Boolean) {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        val horizontal = IntArray(pixels.size)
        image.getPixels(pixels, 0, width, 0, 0, width, height)
        val radius = maxOf(2, (maxOf(width, height) + 4) / (if (video) 4 else 5))
        repeat(2) {
            softenAxis(pixels, horizontal, width, height, 1, width, radius)
            softenAxis(horizontal, pixels, height, width, width, 1, radius)
        }
        image.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    // Усредняет соседние цвета вдоль одной оси с учётом видимости точки и удалённости. Возле
    // краёв повторяет крайние значения, чтобы не добавить прозрачную полосу.
    private fun softenAxis(
        input: IntArray, output: IntArray, length: Int, lines: Int,
        pixelStride: Int, lineStride: Int, maximumRadius: Int,
    ) {
        val radius = minOf(maximumRadius, (length - 1) / 2)
        val weightSum = (radius + 1) * (radius + 1)
        for (line in 0 until lines) {
            val start = line * lineStride
            for (position in 0 until length) {
                var alpha = 0
                var red = 0
                var green = 0
                var blue = 0
                for (offset in -radius..radius) {
                    val neighbor = (position + offset).coerceIn(0, length - 1)
                    val color = input[start + neighbor * pixelStride]
                    val weight = (radius + 1 - kotlin.math.abs(offset)) * Color.alpha(color)
                    alpha += weight
                    red += Color.red(color) * weight
                    green += Color.green(color) * weight
                    blue += Color.blue(color) * weight
                }
                output[start + position * pixelStride] = if (alpha == 0) Color.TRANSPARENT else Color.argb(
                    (alpha + weightSum / 2) / weightSum, (red + alpha / 2) / alpha,
                    (green + alpha / 2) / alpha, (blue + alpha / 2) / alpha,
                )
            }
        }
    }

    // Уменьшает готовое изображение до малого размера, размывает соседние точки и сжимает до
    // нужного числа байтов. Возвращает строку для передачи и освобождает временные рисунки.
    fun tiny(normal: File): String {
        val source =
            BitmapFactory.decodeFile(normal.path) ?: throw MediaException(MediaError.CORRUPT)
        val ratio = minOf(1.0, 48.0 / maxOf(source.width, source.height))
        val image =
            source.scale(
                maxOf(1, (source.width * ratio).toInt()),
                maxOf(1, (source.height * ratio).toInt()),
            )
        try {
            val w = image.width
            val h = image.height
            val pixels = IntArray(w * h)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            val blurred = IntArray(pixels.size)
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val c = pixels[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                    r += Color.red(c)
                    g += Color.green(c)
                    b += Color.blue(c)
                    n++
                }
                blurred[y * w + x] = Color.rgb(r / n, g / n, b / n)
            }
            val output = Bitmap.createBitmap(blurred, w, h, Bitmap.Config.ARGB_8888)
            try {
                val bytes = java.io.ByteArrayOutputStream()
                check(output.compress(Bitmap.CompressFormat.JPEG, 40, bytes))
                require(bytes.size() <= 3072)
                return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            } finally {
                output.recycle()
            }
        } finally {
            if (image !== source) image.recycle()
            source.recycle()
        }
    }
}
