package dev.kirill.kandinsky.chat.media

import dev.kirill.kandinsky.chat.storage.*

class LocalRuntime(
    context: android.content.Context,
    val store: SQLiteStore,
    val network: dev.kirill.kandinsky.chat.network.NetworkSync? = null,
) {
    val repository: ChatRepository =
        network?.let { dev.kirill.kandinsky.chat.network.NetworkChatRepository(it.local, it) }
            ?: SQLiteChatRepository(store)
    val media = MediaStore(store, repository, studioOriginals = network != null)

    init {
        network?.let {
            val remote =
                dev.kirill.kandinsky.chat.network.NetworkMedia(
                    store,
                    it.api,
                    cacheLimitBytes = 256L * 1024 * 1024,
                )
            it.studioMedia = remote
            it.onStudioMediaEvicted = { media.releaseIdleImages() }
            media.remoteFile = remote::resolve
            media.remotePlayback = remote::playback
            media.pendingReferences =
                dev.kirill.kandinsky.chat.network.NetworkMedia::pendingReferences
        }
    }

    val admission = MediaAdmission()
    val playback = MediaCoordinator(admission, media)

    fun close() {
        network?.close()
        playback.close()
        media.close()
        admission.close()
        store.close {}
    }
}
