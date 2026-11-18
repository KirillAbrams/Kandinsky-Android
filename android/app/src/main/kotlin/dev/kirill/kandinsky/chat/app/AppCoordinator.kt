package dev.kirill.kandinsky.chat.app

import dev.kirill.kandinsky.chat.domain.ChatID

class AppCoordinator(private val changed: (ChatID?) -> Unit) {
    var currentChatID: ChatID? = null
        private set

    private var allowedChatIDs: Set<ChatID> = emptySet()

    fun setAllowedChats(ids: Set<ChatID>) {
        allowedChatIDs = ids
    }

    fun admitChat(id: ChatID) {
        allowedChatIDs = allowedChatIDs + id
    }

    fun forgetChat(id: ChatID) {
        allowedChatIDs = allowedChatIDs - id
    }

    fun openChat(id: ChatID) {
        if (id !in allowedChatIDs) return
        currentChatID = id
        changed(id)
    }

    fun showChats() {
        currentChatID = null
        changed(null)
    }

    fun close() {
        currentChatID = null
        allowedChatIDs = emptySet()
    }
}
