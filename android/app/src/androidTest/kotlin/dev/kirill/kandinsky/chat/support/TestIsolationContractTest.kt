// Проверяем, что временные изменения настроек ожидания не переходят к следующему сценарию.

package dev.kirill.kandinsky.chat.support

import androidx.test.uiautomator.Configurator
import org.junit.*
import org.junit.Assert.*
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TestIsolationContractTest {
    // Отключаем ожидание покоя и проверяем новое значение в текущем сценарии. Так второй сценарий
    // получает настоящую проверку восстановления.
    @Test
    fun aChangesOnlyThisTestIdlePolicy() {
        Configurator.getInstance().waitForIdleTimeout = 0
        assertEquals(0L, Configurator.getInstance().waitForIdleTimeout)
    }

    // Сверяем исходное ожидание в следующем сценарии. Нулевое значение показало бы, что
    // предыдущая проверка оставила свою настройку.
    @Test
    fun bNextTestReceivesOriginalNativeDefault() {
        assertEquals(
            "A preceding scenario must not leak its zero idle timeout",
            10000L,
            Configurator.getInstance().waitForIdleTimeout,
        )
    }
}
