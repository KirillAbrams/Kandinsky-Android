package dev.kirill.kandinsky.chat

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.rendering.ChatScrollHost
import dev.kirill.kandinsky.chat.rendering.HistoryIndex
import dev.kirill.kandinsky.chat.rendering.goTo
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Test

class ScrollingDateTest {
    @Test
    fun calendarDividerMovesWithHistoryInsteadOfStickingToChrome() {
        val i = InstrumentationRegistry.getInstrumentation()
        val d = UiDevice.getInstance(i)
        d.wakeUp()
        val previous = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        try {
            val activity =
                i.startActivitySync(
                    i.targetContext.packageManager
                        .getLaunchIntentForPackage(i.targetContext.packageName)!!
                        .apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                        }
                ) as MainActivity
            dev.kirill.kandinsky.chat.support.AppFlowDriver().openChat("Длинная история")
            assertTrue(d.wait(Until.hasObject(By.res("composer.input").enabled(true)), 15000))
            var firstMessage = 0L
            lateinit var host: ChatScrollHost
            i.runOnMainSync {
                fun locate(v: View): ChatScrollHost? {
                    if (v is ChatScrollHost) return v
                    if (v is ViewGroup)
                        for (n in 0 until v.childCount) locate(v.getChildAt(n))?.let {
                            return it
                        }
                    return null
                }
                host = checkNotNull(locate(activity.window.decorView))
                val f =
                    ChatScrollHost::class.java.getDeclaredField("index").apply {
                        isAccessible = true
                    }
                val index = f.get(host) as HistoryIndex
                firstMessage = index.rows.first().messageID
                host.goTo(firstMessage)
            }
            assertTrue(d.wait(Until.hasObject(By.res("message.$firstMessage")), 15000))
            val selector = By.res(Pattern.compile("^chat\\.(date|day\\.$firstMessage)$"))
            val badge =
                d.wait(Until.findObject(selector), 10000)
                    ?: error("First day divider must be exposed")
            val id = badge.resourceName
            val initial = badge.visibleBounds.top
            val xy = IntArray(2)
            var height = 0
            var width = 0
            i.runOnMainSync {
                host.getLocationOnScreen(xy)
                height = host.height
                width = host.width
            }
            assertTrue(
                d.swipe(
                    xy[0] + width / 2,
                    xy[1] + height * 3 / 4,
                    xy[0] + width / 2,
                    xy[1] + height / 4,
                    35,
                )
            )
            SystemClock.sleep(500)
            val after = d.findObject(By.res(id))
            assertTrue(
                "Calendar divider must leave viewport or move with content, never stay fixed",
                after == null || after.visibleBounds.top < initial - 5,
            )
        } finally {
            Configurator.getInstance().waitForIdleTimeout = previous
        }
    }
}
