// Проверяет путь от запуска приложения Андроида до двух исходных чатов с помощью элементов
// экрана. Для этого нужен уже сохранённый вход.

package dev.kirill.kandinsky.chat.network

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeNotNull
import org.junit.Test

class AccountLiveFlowTest {
    // Запускает приложение, открывает боковую панель и нажимает на оба названия по очереди.
    // Появление поля ввода подтверждает переход в чат.
    @Test
    fun existingAccountCanOpenBothOriginalHistories() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeNotNull(NetworkCredentials(context).load())
        val device = UiDevice.getInstance(instrumentation)
        val launch =
            requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        for (title in listOf("Плотная медиалента", "Длинная история")) {
            val menu = device.wait(Until.findObject(By.desc("История запросов")), 15000)
            assertNotNull(menu)
            menu.click()
            val row = device.wait(Until.findObject(By.text(title)), 15000)
            assertNotNull(row)
            row.click()
            assertNotNull(device.wait(Until.findObject(By.res("composer.input")), 15000))
        }
    }
}
