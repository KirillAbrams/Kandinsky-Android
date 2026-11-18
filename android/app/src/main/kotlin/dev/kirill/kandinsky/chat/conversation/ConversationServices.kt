// Передаёт экрану чата уже созданные службы обмена, файлов и проигрывания. Несколько экранов
// одного аккаунта могут пользоваться теми же ресурсами.

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
