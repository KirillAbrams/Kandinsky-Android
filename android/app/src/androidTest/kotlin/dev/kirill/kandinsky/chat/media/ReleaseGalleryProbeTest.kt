package dev.kirill.kandinsky.chat.media

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReleaseGalleryProbeTest {
    @Test
    fun pageExistingPublicMediaForwardAndBack() {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val device = UiDevice.getInstance(i)
        org.junit.Assume.assumeTrue(
            "Explicit owned-device Release inspection only",
            InstrumentationRegistry.getArguments().containsKey("gallery-tag"),
        )
        assertFalse(
            "This probe measures the normal Release",
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE !=
                0,
        )
        Configurator.getInstance().waitForIdleTimeout = 0
        device.wakeUp()
        val activity =
            i.startActivitySync(
                context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            ) as dev.kirill.kandinsky.chat.app.MainActivity
        fun find(id: String): UiObject2 =
            checkNotNull(device.wait(Until.findObject(By.res(id)), 15000)) {
                device.takeScreenshot(
                    File(
                        context.getExternalFilesDir("kandinsky-test-metrics"),
                        "gallery-probe-failure.png",
                    )
                )
                "Missing actual UI: $id"
            }
        dev.kirill.kandinsky.chat.support.AppFlowDriver().openChat("Плотная медиалента")
        find("chat.history")
        lateinit var repository: dev.kirill.kandinsky.chat.storage.ChatRepository
        i.runOnMainSync {
            val field = activity.javaClass.getDeclaredField("repository")
            field.isAccessible = true
            repository = field.get(activity) as dev.kirill.kandinsky.chat.storage.ChatRepository
        }
        val chats =
            dev.kirill.kandinsky.chat.support.awaitResult<
                List<dev.kirill.kandinsky.chat.domain.ChatSummary>
            > {
                repository.chats("", it)
            }
        val chatID = chats.single { it.title == "Плотная медиалента" }.id
        val page =
            dev.kirill.kandinsky.chat.support.awaitResult<
                dev.kirill.kandinsky.chat.domain.HistoryPage
            > {
                repository.history(
                    dev.kirill.kandinsky.chat.domain.PageRequest(
                        chatID,
                        limit = 64,
                        visualOnly = true,
                    ),
                    it,
                )
            }
        val indexed =
            dev.kirill.kandinsky.chat.support.awaitResult<
                List<dev.kirill.kandinsky.chat.domain.IndexedRow>
            > {
                repository.index(chatID, it)
            }
        val row =
            page.rows
                .filter { it.kind == dev.kirill.kandinsky.chat.domain.MessageKind.PHOTO }
                .minBy { kotlin.math.abs(page.rows.indexOf(it) - page.rows.size / 2) }
        lateinit var host: dev.kirill.kandinsky.chat.rendering.ChatScrollHost
        i.runOnMainSync {
            fun descendants(v: android.view.View): List<android.view.View> =
                if (v is android.view.ViewGroup)
                    (0 until v.childCount).flatMap {
                        listOf(v.getChildAt(it)) + descendants(v.getChildAt(it))
                    }
                else emptyList()
            host =
                descendants(activity.window.decorView)
                    .filterIsInstance<dev.kirill.kandinsky.chat.rendering.ChatScrollHost>()
                    .single()
        }
        val ready = SystemClock.uptimeMillis() + 10000
        var count = 0
        while (SystemClock.uptimeMillis() < ready) {
            i.runOnMainSync { count = host.messageCount }
            if (count >= indexed.size) break
            SystemClock.sleep(20)
        }
        assertEquals(
            "Position only after the actual history index is installed",
            indexed.size,
            count,
        )
        i.runOnMainSync {
            host.goTo(row.id)
            println(
                "GALLERY_PREP index=$count target=${row.id} anchor=${host.readingAnchor().messageID}"
            )
            assertEquals(row.id, host.readingAnchor().messageID)
        }
        val photoBounds = find("media.image." + row.id).visibleBounds
        assertTrue(
            device.click(
                photoBounds.left + photoBounds.width() * 3 / 4,
                photoBounds.top + photoBounds.height() / 3,
            )
        )
        find("viewer.pages")
        var windows = 0
        i.runOnMainSync { windows = WindowInspector.getGlobalWindowViews().size }
        fun selectedID(): String {
            var id = ""
            i.runOnMainSync {
                fun descendants(v: android.view.View): List<android.view.View> =
                    if (v is android.view.ViewGroup)
                        (0 until v.childCount).flatMap {
                            listOf(v.getChildAt(it)) + descendants(v.getChildAt(it))
                        }
                    else emptyList()
                val pager =
                    WindowInspector.getGlobalWindowViews()
                        .flatMap { descendants(it) }
                        .filterIsInstance<android.view.ViewGroup>()
                        .first { it.javaClass.simpleName == "Pager" }
                val page =
                    (0 until pager.childCount)
                        .map { pager.getChildAt(it) }
                        .minBy { kotlin.math.abs(it.left - pager.scrollX) }
                id = page.createAccessibilityNodeInfo().viewIdResourceName
            }
            return id
        }
        val tag = InstrumentationRegistry.getArguments().getString("gallery-tag") ?: "gallery27"
        require(tag.matches(Regex("gallery(27|28)[a-z]?")))
        val output =
            File(context.getExternalFilesDir("kandinsky-test-metrics"), tag).apply {
                check(!exists())
                mkdirs()
            }
        fun shot(name: String) {
            val b = checkNotNull(i.uiAutomation.takeScreenshot())
            try {
                File(output, "$name.png").outputStream().use {
                    b.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                b.recycle()
            }
        }
        shot("first")
        val firstID = selectedID()
        val visited = mutableListOf(firstID)
        val begin = SystemClock.elapsedRealtimeNanos()
        for (index in 1..12) {
            device.swipe(
                device.displayWidth * 4 / 5,
                device.displayHeight / 2,
                device.displayWidth / 5,
                device.displayHeight / 2,
                12,
            )
            assertTrue(device.hasObject(By.res("viewer.pages")))
            i.runOnMainSync { assertEquals(windows, WindowInspector.getGlobalWindowViews().size) }
            visited += selectedID()
            if (index % 4 == 0) shot("next-$index")
        }
        for (index in 1..12) {
            device.swipe(
                device.displayWidth / 5,
                device.displayHeight / 2,
                device.displayWidth * 4 / 5,
                device.displayHeight / 2,
                12,
            )
            assertTrue(device.hasObject(By.res("viewer.pages")))
            i.runOnMainSync { assertEquals(windows, WindowInspector.getGlobalWindowViews().size) }
            visited += selectedID()
        }
        SystemClock.sleep(220)
        val finalID = selectedID()
        val end = SystemClock.elapsedRealtimeNanos()
        shot("back-first")
        val close = device.findObject(By.res("photo.close")) ?: find("player.inline")
        close.click()
        find("chat.history")
        File(output, "window.json")
            .writeText(
                org.json
                    .JSONObject()
                    .put("beginElapsedNanos", begin)
                    .put("endElapsedNanos", end)
                    .put("swipes", 24)
                    .put("gesture", "UiAutomator.swipe,12steps")
                    .put("normalRelease", true)
                    .put("visited", org.json.JSONArray(visited))
                    .put("firstID", firstID)
                    .put("finalID", finalID)
                    .toString()
            )
    }
}
