package dev.kirill.kandinsky.chat.support

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Открывает подготовленные файлы из отдельного проверочного пакета
 * Установленное приложение не содержит эту историю и не получает доступ к ней при обычном запуске
 */
object TestFixtures {
    val assets:AssetManager
        get()=InstrumentationRegistry.getInstrumentation().context.assets
}
