// Проверяем, что добавление старых строк выше экрана сохраняет видимое сообщение во время
// настоящей инерции.

package dev.kirill.kandinsky.chat.rendering

import android.content.Intent
import android.view.MotionEvent
import android.widget.OverScroller
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FlingAnchorTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val context = i.targetContext

    // Выполняем переданное действие на основном потоке телефона. Чтение или изменение сцены с
    // другого потока могло бы дать неверный замер.
    private fun main(action: () -> Unit) = i.runOnMainSync(action)

    // Дожидаемся готового текста и запускаем жест в настоящем окне. Добавляем десять строк сверху
    // и проверяем, что видимое сообщение уехало только на небольшое расстояние от самой
    // прокрутки.
    @Test
    fun prependWhileActualNativeFlingIsActiveKeepsVisibleMessage() {
        val device = UiDevice.getInstance(i)
        device.wakeUp()
        val priorIdle = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        val intent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        val activity = i.startActivitySync(intent) as MainActivity
        assertTrue(device.wait(Until.hasObject(By.pkg(i.targetContext.packageName)), 15000))
        main {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val h = RepositoryHarness.open(File(context.cacheDir, "fling-prepend-" + UUID.randomUUID()))
        awaitResult<Unit> { cb ->
            h.store.submit(
                { db ->
                    db.beginTransaction()
                    try {
                        val s =
                            db.compileStatement(
                                "INSERT INTO messages(id,chatId,sortKey,kind,text,caption," +
                                    "revision,savedAt,direction,searchText) " +
                                    "VALUES(?,2,?,'text',?,'',1,0,'incoming',?)"
                            )
                        s.use {
                            for (n in 1..1000) {
                                it.bindLong(1, n.toLong())
                                it.bindLong(2, n.toLong())
                                it.bindString(3, "row" + n)
                                it.bindString(4, "row" + n)
                                it.executeInsert()
                            }
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                },
                cb,
            )
        }
        lateinit var host: ChatScrollHost
        main {
            host = ChatScrollHost(activity)
            activity.setContentView(host)
        }
        try {
            awaitResult<Unit> { cb ->
                main {
                    host.open(2, h.repo, ReadingAnchor(messageID = 500, followsBottom = false), cb)
                }
                Cancellation {}
            }
            main {
                val insets = activity.window.decorView.rootWindowInsets
                val top =
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        insets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
                    else @Suppress("DEPRECATION") (insets?.systemWindowInsetTop ?: 0)
                val bottom =
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        insets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom
                            ?: 0
                    else @Suppress("DEPRECATION") (insets?.systemWindowInsetBottom ?: 0)
                // Отдельной области истории передаются системные отступы, которые обычно задаёт
                // оформление приложения
                host.updateOverlayInsets(top.toDouble(), bottom.toDouble())
            }
            val visible = device.wait(Until.hasObject(By.res("message.500")), 5000)
            if (!visible)
                main {
                    val kind = ChatScrollHost::class.java
                    val idx =
                        kind.getDeclaredField("index").apply { isAccessible = true }.get(host)
                            as HistoryIndex
                    val at =
                        kind
                            .getDeclaredField("offset")
                            .apply { isAccessible = true }
                            .getDouble(host)
                    val retained =
                        kind.getDeclaredField("scene").apply { isAccessible = true }.get(host)
                            as RetainedChatScene
                    val current =
                        (RetainedChatScene::class
                                .java
                                .getDeclaredField("nodes")
                                .apply { isAccessible = true }
                                .get(retained) as Map<*, *>)
                            .values
                            .filterIsInstance<MessageNode>()
                    println(
                        "FLING_FIXTURE rows=${host.messageCount} loaded=${host.loadedCount} " +
                            "size=${host.width}x${host.height} offset=$at targetTop=${idx.top(500)} " +
                            "nodes=" +
                            current.map { it.fragment.id.messageID }
                    )
                }
            assertTrue("Own fixture row500 must be visible before fling", visible)
            val jobs =
                ChatScrollHost::class.java.getDeclaredField("inFlight").apply {
                    isAccessible = true
                }
            val scrollerField =
                ChatScrollHost::class.java.getDeclaredField("scroller").apply {
                    isAccessible = true
                }
            val end = android.os.SystemClock.uptimeMillis() + 5000
            var pending = 1
            while (pending > 0 && android.os.SystemClock.uptimeMillis() < end) {
                main { pending = (jobs.get(host) as Map<*, *>).size }
                if (pending > 0) Thread.sleep(10)
            }
            assertEquals(
                "Fixture text workers must finish before starting native fling",
                0,
                pending,
            )
            lateinit var scroller: OverScroller

            awaitResult<Unit> { cb ->
                h.store.submit(
                    { db ->
                        db.beginTransaction()
                        try {
                            for (n in 1001..1010) db.execSQL(
                                "INSERT INTO messages(id,chatId,sortKey,kind,text,caption," +
                                    "revision,savedAt,direction,searchText) " +
                                    "VALUES(?,2,?,'text',?,'',1,0,'incoming','older')",
                                arrayOf<Any?>(n.toLong(), (n - 1020).toLong(), "older ".repeat(400)),
                            )
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    },
                    cb,
                )
            }
            val rows =
                awaitResult<HistoryPage> {
                        h.repo.history(PageRequest(2, direction = PageDirection.NEWER), it)
                    }
                    .rows
                    .filter { it.id > 1000 }
            assertEquals(10, rows.size)
            val summary = awaitResult<List<ChatSummary>> { h.repo.chats("", it) }.single()
            val changed = CountDownLatch(1)
            var duringFling = false
            main {
                host.onCountChanged = {
                    if (it == 1010) {
                        duringFling = !scroller.isFinished
                        changed.countDown()
                    }
                }
                scroller = scrollerField.get(host) as OverScroller
                val t = android.os.SystemClock.uptimeMillis()
                for ((delay, action, y) in
                    (0..6).map { n ->
                        Triple(
                            n * 16L,
                            if (n == 0) MotionEvent.ACTION_DOWN
                            else if (n == 6) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,
                            400f + n * 6f,
                        )
                    }) {
                    val e = MotionEvent.obtain(t, t + delay, action, 288f, y, 0)
                    e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    host.onTouchEvent(e)
                    e.recycle()
                }
                assertFalse("Actual SDK OverScroller must be flinging", scroller.isFinished)
                assertTrue(
                    "Native velocity must be nonzero before applying metadata; actual=" +
                        scroller.currVelocity,
                    scroller.currVelocity > 100f,
                )
                host.apply(MessageDelta(inserted = rows, chat = summary))
            }
            assertTrue(changed.await(5, TimeUnit.SECONDS))
            assertTrue("Metadata change must land during real native inertia", duringFling)
            val finish = android.os.SystemClock.uptimeMillis() + 5000
            var stopped = false
            while (!stopped && android.os.SystemClock.uptimeMillis() < finish) {
                main { stopped = scroller.isFinished }
                if (!stopped) Thread.sleep(20)
            }
            assertTrue(stopped)
            assertTrue(
                "Prepending offscreen rows during inertia must preserve the visible row500",
                device.wait(Until.hasObject(By.res("message.500")), 3000),
            )
            assertTrue(
                "Only the small native fling displacement may move the reference row",
                device.findObject(By.res("message.500")).visibleBounds.top < 200,
            )
        } catch (t: Throwable) {
            val out = File(context.getExternalFilesDir(null), "task19").apply { mkdirs() }
            device.takeScreenshot(File(out, "fling-prepend-failure.png"))
            device.dumpWindowHierarchy(File(out, "fling-prepend-failure.xml"))
            throw t
        } finally {
            main {
                host.close()
                activity.finish()
            }
            h.close()
            Configurator.getInstance().waitForIdleTimeout = priorIdle
        }
    }
}
