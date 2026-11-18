// Проводим проверки через обычный запуск приложения, список истории и видимые кнопки. Общие
// действия не приходится повторять в каждом сценарии.

package dev.kirill.kandinsky.chat.support

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import org.junit.Assume.assumeNotNull

class AppFlowDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    val device = UiDevice.getInstance(instrumentation)

    // Проверяем, что вход уже сохранён, и запускаем главное окно с чистой стопкой экранов. Это
    // убирает прежний экран из начала проверки и сохраняет аккаунт.
    fun launch() {
        assumeNotNull(NetworkCredentials(context).load())
        val intent =
            requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    // Ждём элемент по указанному условию до 15 секунд. При отсутствии получаем ошибку с
    // конкретной меткой.
    fun find(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), 15000)) {
            "Элемент не появился: $selector"
        }

    // Открываем меню и строку с заданным названием, затем ждём форму. Пока форма не появилась,
    // следующее действие могло бы попасть в список.
    fun openChat(title: String) {
        assumeNotNull(NetworkCredentials(context).load())
        find(By.desc("История запросов")).click()
        find(By.text(title)).click()
        find(By.res("composer.input"))
    }

    // Ищем кнопку результата по подписи и листаем историю, если она пока вне экрана. Число
    // попыток ограничено, чтобы отсутствующая кнопка завершила проверку.
    fun visibleResult(label: String): UiObject2 {
        val selector = By.desc(label)
        repeat(8) {
            device.wait(Until.findObject(selector), 1200)?.let {
                return it
            }
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight / 3,
                device.displayWidth / 2,
                device.displayHeight * 2 / 3,
                25,
            )
        }
        return find(selector)
    }
}
