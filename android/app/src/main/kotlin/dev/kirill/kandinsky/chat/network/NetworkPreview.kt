package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import java.io.File
import java.util.UUID

object NetworkPreview {
    // Номер загрузки привязан к вложению и сохраняется при повторном входе в аккаунт.
    fun upload(root: File, a: AttachmentDraft, api: NetworkAPI): String? {
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
        return api.upload(temporary, "image/jpeg", id).getString("blobId")
    }
}
