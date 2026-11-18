// Связывает прогресс просмотра с конкретным сообщением и вложением. Замена файла меняет ключ и не
// подхватывает прежнее состояние.

package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.domain.Attachment

data class VideoPlaybackKey(val messageID: Long, val attachmentID: String, val relativePath: String) {
    val cacheKey: String
        // Складывает номер сообщения, длину номера вложения и оба значения. Длина задаёт границу
        // частей, чтобы разные сочетания не дали один ключ.
        get() = "$messageID:${attachmentID.length}:$attachmentID$relativePath"

    // Берёт номер вложения и путь из показанного файла. Ключ соответствует именно этому видео в
    // строке.
    constructor(messageID: Long, attachment: Attachment) : this(messageID, attachment.id, attachment.relativePath)
}
