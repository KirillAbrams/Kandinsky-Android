// Перед каждой проверкой запоминаем настройки экрана и ожидания. После проверки восстанавливаем
// их, чтобы последующий сценарий получил прежнее устройство.

package dev.kirill.kandinsky.chat.support

import androidx.test.uiautomator.Configurator
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

class NativeTestIsolationListener : RunListener() {
    private var previous = 10000L
    private var autoRotate = false
    private var userRotation = 0

    // Сохраняем режим поворота и время ожидания, затем ставим книжное положение и обычное
    // ожидание. Измерения получают одинаковое окно независимо от предыдущего теста.
    override fun testStarted(description: Description) {
        previous = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 10000
        val i = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val resolver = i.targetContext.contentResolver
        autoRotate =
            android.provider.Settings.System.getInt(
                resolver,
                android.provider.Settings.System.ACCELEROMETER_ROTATION,
                0,
            ) == 1
        userRotation =
            android.provider.Settings.System.getInt(
                resolver,
                android.provider.Settings.System.USER_ROTATION,
                0,
            )
        check(i.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)) {
            "Native test portrait control"
        }
    }

    // Возвращаем прежний поворот и при необходимости его автоматическую смену. Время ожидания
    // возвращаем даже при ошибке поворота.
    override fun testFinished(description: Description) {
        try {
            val automation =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            check(automation.setRotation(userRotation)) { "Restore prior user rotation" }
            if (autoRotate)
                check(automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)) {
                    "Restore automatic rotation"
                }
        } finally {
            Configurator.getInstance().waitForIdleTimeout = previous
        }
    }
}
