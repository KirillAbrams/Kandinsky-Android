// Сохраняет фотографию для передачи с проверкой отмены и уборкой незаконченного файла.

package dev.kirill.kandinsky.chat.media

import android.graphics.Bitmap
import dev.kirill.kandinsky.chat.media.MediaError
import dev.kirill.kandinsky.chat.media.MediaException
import java.io.File
import java.io.FileOutputStream

class StudioPhotoEncoder {
    // Сжимает изображение и дожидается записи байтов, проверяя отмену до и после работы. При
    // отказе удаляет неполную копию, чтобы её не предложили к отправке.
    fun jpeg(image: Bitmap, file: File, cancel: () -> Boolean = { false }) {
        if (cancel()) throw MediaException(MediaError.CANCELLED)
        var complete = false
        try {
            FileOutputStream(file).use { output ->
                if (!image.compress(Bitmap.CompressFormat.JPEG, 90, output))
                    throw MediaException(MediaError.IO)
                output.fd.sync()
            }
            if (cancel()) throw MediaException(MediaError.CANCELLED)
            complete = true
        } finally {
            if (!complete) file.delete()
        }
    }
}
