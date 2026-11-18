// Даём проверкам доступ к подготовленным данным из собственного набора.

package dev.kirill.kandinsky.chat.support

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry

object TestFixtures {
    val assets: AssetManager
        // Возвращаем ресурсы именно пакета проверок. Рабочее приложение собирается отдельно и не
        // должно содержать этот набор фото и истории.
        get() = InstrumentationRegistry.getInstrumentation().context.assets
}
