package dev.kirill.kandinsky.chat.support

import androidx.test.uiautomator.Configurator
import org.junit.*
import org.junit.Assert.*
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TestIsolationContractTest {
    @Test
    fun aChangesOnlyThisTestIdlePolicy() {
        Configurator.getInstance().waitForIdleTimeout = 0
        assertEquals(0L, Configurator.getInstance().waitForIdleTimeout)
    }

    // Предыдущий сценарий не должен передать ей своё временное отключение ожидания.
    @Test
    fun bNextTestReceivesOriginalNativeDefault() {
        assertEquals(
            "A preceding scenario must not leak its zero idle timeout",
            10000L,
            Configurator.getInstance().waitForIdleTimeout,
        )
    }
}
