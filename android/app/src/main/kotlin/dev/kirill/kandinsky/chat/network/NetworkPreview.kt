// Загружает уменьшенную копию отдельно от оригинала. Номер отправки зависит от вложения, поэтому
// новый вход не заставляет начинать другую загрузку той же копии.

package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import java.io.File
import java.util.UUID

object NetworkPreview {
    // Проверяет тип вложения, готовит маленькое изображение и отправляет его с постоянным
    // номером. Возвращает номер копии, по которому телефон сможет показать её до скачивания
    // оригинала.
    fun upload(root: File, a: AttachmentDraft, api: NetworkAPI, parent: String? = null): String? {
        if (!a.mime.startsWith("image/") && !a.mime.startsWith("video/")) return null
        if (a.mime == "image/gif") return null
        val file = File(root, "media/" + a.relativePath)
        val temporary =
            dev.kirill.kandinsky.chat.media.MediaPreviewGenerator.normalFile(
                root,
                a.relativePath,
                file,
                a.mime,
            )
        val id = UUID.nameUUIDFromBytes((a.id + ":preview-v1").toByteArray()).toString()
        return api.upload(temporary, "image/jpeg", id, parent = parent).getString("blobId")
    }
}
