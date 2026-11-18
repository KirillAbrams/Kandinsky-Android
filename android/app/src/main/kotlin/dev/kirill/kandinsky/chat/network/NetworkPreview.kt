package dev.kirill.kandinsky.chat.network

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.util.Size
import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object NetworkPreview {
    /**
     * Готовит уменьшенное изображение для фото или видео и загружает его отдельно от оригинала
     * Номер загрузки вычисляется из устройства и вложения, поэтому повтор относится к тому же предварительному изображению
     * Для звука и анимаций отдельная копия здесь не создаётся
     */
    fun upload(root:File,a:AttachmentDraft,api:NetworkAPI,deviceID:String):String? {
        if(!a.mime.startsWith("image/") && !a.mime.startsWith("video/"))return null
        if(a.mime=="image/gif")return null
        val file=File(root,"media/"+a.relativePath)
        val temporary=dev.kirill.kandinsky.chat.media.MediaPreviewGenerator.normalFile(root,a.relativePath,file,a.mime)
            val id=UUID.nameUUIDFromBytes((deviceID+":"+a.id+":preview-v1").toByteArray()).toString()
            return api.upload(temporary,"image/jpeg",id).getString("blobId")

    }
}
