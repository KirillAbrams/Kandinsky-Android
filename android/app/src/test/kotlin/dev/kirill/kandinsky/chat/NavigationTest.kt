// Проверяем переход из списка в чат и обратно, включая попытку открыть неизвестный разговор.

package dev.kirill.kandinsky.chat

import dev.kirill.kandinsky.chat.app.AppCoordinator
import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
    // Открываем известный чат, возвращаемся и пробуем открыть отсутствующий. Выбранный номер
    // появляется только при удачном переходе.
    @Test
    fun openBackAndUnknownChat() {
        val coordinator = AppCoordinator {}
        coordinator.openChat(2L)
        assertNull(coordinator.currentChatID)
        coordinator.setAllowedChats(setOf(2L, 3L))
        coordinator.openChat(2L)
        assertEquals(2L, coordinator.currentChatID)
        coordinator.showChats()
        assertNull(coordinator.currentChatID)
        coordinator.openChat(999L)
        assertNull(coordinator.currentChatID)
        coordinator.close()
        assertNull(coordinator.currentChatID)
    }

    // Несколько раз открываем разные известные чаты и возвращаемся. Каждый переход должен выбрать
    // свой номер, а возврат убрать его.
    @Test
    fun repeatedRoundTripPreservesSelectedIdentity() {
        val coordinator = AppCoordinator {}
        coordinator.openChat(2L)
        assertNull(coordinator.currentChatID)
        coordinator.setAllowedChats(setOf(2L, 3L))
        repeat(100) { step ->
            val id = if (step % 2 == 0) 2L else 3L
            coordinator.openChat(id)
            assertEquals(id, coordinator.currentChatID)
            coordinator.showChats()
            assertNull(coordinator.currentChatID)
        }
    }
}
