package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.domain.Attachment

data class VideoPlaybackKey(val messageID: Long, val attachmentID: String, val relativePath: String) {
    val cacheKey: String
        get() = "$messageID:${attachmentID.length}:$attachmentID$relativePath"

    constructor(messageID: Long, attachment: Attachment) : this(messageID, attachment.id, attachment.relativePath)
}
