// Хранит список доступных чатов и текущий выбор на Андроиде. Передаёт экрану изменения, когда
// пользователь открывает чат или возвращается к списку.

package dev.kirill.kandinsky.chat.app

import dev.kirill.kandinsky.chat.domain.ChatID

class AppCoordinator(private val changed: (ChatID?) -> Unit) {
    var currentChatID: ChatID? = null
        private set

    private var allowedChatIDs: Set<ChatID> = emptySet()

    // Заменяет список номеров теми, которые доступны сейчас. Прежняя карточка после смены
    // владельца не даст открыть старый чат.
    fun setAllowedChats(ids: Set<ChatID>) {
        allowedChatIDs = ids
    }

    // Добавляет номер доступного чата к текущему набору. Это позволяет открыть только что
    // созданный или загруженный чат без полной замены списка.
    fun admitChat(id: ChatID) {
        allowedChatIDs = allowedChatIDs + id
    }

    // Убирает номер из доступных. Последующее нажатие на устаревшую карточку не откроет чат,
    // которого уже нет в списке разрешённых.
    fun forgetChat(id: ChatID) {
        allowedChatIDs = allowedChatIDs - id
    }

    // Проверяет номер по списку и только затем выбирает чат и сообщает об этом экрану.
    // Неизвестный номер не переключит текущую историю.
    fun openChat(id: ChatID) {
        if (id !in allowedChatIDs) return
        currentChatID = id
        changed(id)
    }

    // Снимает выбор и сообщает экрану, что нужно показать список. Предыдущий чат больше не
    // считается открытым.
    fun showChats() {
        currentChatID = null
        changed(null)
    }

    // Сбрасывает выбор и весь набор доступных номеров при закрытии владельца. Если объект ещё
    // жив, старый номер не пройдёт проверку.
    fun close() {
        currentChatID = null
        allowedChatIDs = emptySet()
    }
}
