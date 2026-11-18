package dev.kirill.kandinsky.chat.conversation

import dev.kirill.kandinsky.chat.media.MediaAdmission
import dev.kirill.kandinsky.chat.media.MediaCoordinator
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.network.NetworkSync

internal class ConversationServices(
    val network: NetworkSync?,
    val media: MediaStore,
    val admission: MediaAdmission,
    val playback: MediaCoordinator,
)
