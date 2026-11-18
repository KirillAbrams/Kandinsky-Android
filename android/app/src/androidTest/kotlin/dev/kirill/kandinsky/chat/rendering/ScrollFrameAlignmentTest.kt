// Проверяем, что видимые слои получают положение текущей прокрутки перед рисованием. Измеряем и
// инерцию, и движение пальца на настоящем экране.

package dev.kirill.kandinsky.chat.rendering

import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.widget.OverScroller
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ScrollFrameAlignmentTest {
    // Двигаем экран внутри восьми готовых строк и следим за их чтением и слоями. Окно начнёт
    // новое чтение только после изменения геометрии.
    @Test
    fun movingWithinPreparedRowsKeepsTheirLayoutUntilGeometryChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val chat = ChatScrollHost(instrumentation.targetContext)
            try {
                val width = View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY)
                val height = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
                chat.measure(width, height)
                chat.layout(0, 0, 390, 600)
                val messages = (1L..8L).map { id ->
                    Message(
                        id = id,
                        chatID = 2,
                        order = OrderKey(id, id),
                        direction = MessageDirection.INCOMING,
                        savedAtMs = 1_793_700_000_000L + id,
                        kind = MessageKind.TEXT,
                        text = "Строка $id",
                        caption = "",
                        replyToID = null,
                        revision = 1,
                        attachments = emptyList(),
                    )
                }.associateBy { it.id }
                val anchor = ReadingAnchor(followsBottom = false)
                chat.index.replace(messages.values.map { row ->
                    IndexedRow(row.id, row.order, 96.0, row.revision, row.savedAtMs)
                }, anchor, 600.0)
                var reads = 0
                chat.loaded = object : Map<MessageID, Message> by messages {
                    // Возвращаем строку из исходного набора и считаем чтение по номеру. По
                    // счётчику видна лишняя пересборка уже готового окна.
                    override fun get(key: MessageID): Message? {
                        reads++
                        return messages[key]
                    }
                }
                messages.values.forEach { row ->
                    chat.cache.insert(PreparedText(row, chat.layoutKey(row)))
                }
                chat.renderVisibleRows()
                assertEquals("The runway includes all eight ready rows at rest", 8, reads)
                val id = FragmentID(2, 0)
                val node = chat.scene.nodeIdentity(id)
                assertNotNull(node)

                reads = 0
                for (position in 1..8) {
                    chat.offset = position * 2.0
                    chat.renderVisibleRows()
                    assertEquals(node, chat.scene.nodeIdentity(id))
                }
                assertEquals("Reuse prepared rows while their window is unchanged", 0, reads)

                chat.index.updateHeight(1, 116.0, anchor, 600.0)
                chat.renderVisibleRows()
                assertTrue("Changed row positions need a new prepared window", reads > 0)
            } finally {
                chat.close()
            }
        }
    }

    // Проверяем порог начала жеста, перехват инерции и положение слоя при текущем рисовании. Даже
    // при внешнем запросе на кадр сцена должна использовать свежий сдвиг пальца.
    @Test
    fun flingAdvancesNativeSceneBeforeTheCurrentDraw() {
        val i = InstrumentationRegistry.getInstrumentation()
        val d = UiDevice.getInstance(i)
        d.wakeUp()
        val old = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
        var host: ChatScrollHost? = null
        var activity: MainActivity? = null
        var harness: RepositoryHarness? = null
        try {
            activity =
                i.startActivitySync(
                    i.targetContext.packageManager
                        .getLaunchIntentForPackage(i.targetContext.packageName)!!
                        .apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                        }
                ) as MainActivity
            assertTrue(d.wait(Until.hasObject(By.pkg(i.targetContext.packageName)), 15000))
            val h =
                RepositoryHarness.open(
                    File(i.targetContext.cacheDir, "scroll-frame-" + UUID.randomUUID())
                )
            harness = h
            awaitResult<Unit> { cb ->
                h.store.submit(
                    { db ->
                        db.beginTransaction()
                        try {
                            db.compileStatement(
                                    "INSERT INTO messages(id,chatId,sortKey,kind,text," +
                                        "caption,revision,savedAt,direction,searchText) " +
                                        "VALUES(?,2,?,'text',?,'',1,0,'incoming',?)"
                                )
                                .use { statement ->
                                    for (n in 1..1000) {
                                        statement.bindLong(1, n.toLong())
                                        statement.bindLong(2, n.toLong())
                                        statement.bindString(3, "row" + n)
                                        statement.bindString(4, "row" + n)
                                        statement.executeInsert()
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
            lateinit var chat: ChatScrollHost
            i.runOnMainSync {
                chat = ChatScrollHost(activity!!)
                host = chat
                activity!!.setContentView(chat)
            }
            awaitResult<Unit> { cb ->
                i.runOnMainSync {
                    chat.open(2, h.repo, ReadingAnchor(messageID = 990, followsBottom = false), cb)
                }
                Cancellation {}
            }
            // Получаем внутреннее поле прокрутки по имени. Проверка читает готовые системные слои
            // без добавления служебных методов в рабочий экран.
            fun field(name: String) =
                ChatScrollHost::class.java.getDeclaredField(name).apply { isAccessible = true }
            // Извлекаем текущие объекты сообщений из сцены. Затем сравниваем их фактический сдвиг
            // с расчётом по высоте строки и положению прокрутки.
            fun nodes(): List<MessageNode> {
                val scene = field("scene").get(chat) as RetainedChatScene
                return (RetainedChatScene::class
                        .java
                        .getDeclaredField("nodes")
                        .apply { isAccessible = true }
                        .get(scene) as Map<*, *>)
                    .values
                    .filterIsInstance<MessageNode>()
            }
            var ready = false
            val end = SystemClock.uptimeMillis() + 10000
            while (!ready && SystemClock.uptimeMillis() < end) {
                i.runOnMainSync {
                    val index = field("index").get(chat) as HistoryIndex
                    val offset = field("offset").getDouble(chat)
                    val current = nodes()
                    ready =
                        current.isNotEmpty() &&
                            current.all { node ->
                                val f = node.fragment
                                val expected =
                                    index.top(f.id.messageID)!! - offset +
                                        f.prepared.blocks[f.part].offsetY
                                kotlin.math.abs(expected - node.native.translationY) < 0.05
                            }
                }
                if (!ready) SystemClock.sleep(20)
            }
            assertTrue("Prepared native scene fixture", ready)
            i.runOnMainSync {
                val touchSlop = ViewConfiguration.get(chat.context).scaledTouchSlop
                val down = SystemClock.uptimeMillis()
                val y = chat.height / 2f
                // Передаём экрану касание с заданным типом и высотой и освобождаем событие. Это
                // позволяет повторить точные движения пальца и измерить первый сдвиг.
                fun touch(action: Int, position: Float) {
                    val event = MotionEvent.obtain(down, down, action, chat.width / 2f, position, 0)
                    try {
                        chat.onTouchEvent(event)
                    } finally {
                        event.recycle()
                    }
                }

                val initial = chat.offset
                try {
                    touch(MotionEvent.ACTION_DOWN, y)
                    touch(MotionEvent.ACTION_MOVE, y + touchSlop / 2f)
                    assertEquals("Resting drag still honors touch slop", initial, chat.offset, 0.05)
                    touch(MotionEvent.ACTION_MOVE, y + touchSlop + 2f)
                    assertEquals("First resting movement excludes touch slop", initial - 2, chat.offset, 0.05)
                } finally {
                    touch(MotionEvent.ACTION_CANCEL, y)
                }

                chat.scroller.startScroll(0, 0, 0, -1200, 1000)
                val whileGliding = chat.offset
                try {
                    touch(MotionEvent.ACTION_DOWN, y)
                    touch(MotionEvent.ACTION_MOVE, y + 2f)
                    assertEquals("An interrupted fling follows the first move", whileGliding - 2, chat.offset, 0.05)
                } finally {
                    touch(MotionEvent.ACTION_CANCEL, y)
                }
            }
            i.runOnMainSync {
                val scroller = field("scroller").get(chat) as OverScroller
                scroller.abortAnimation()
                field("lastFlingY").setInt(chat, 0)
                val before = field("offset").getDouble(chat)
                scroller.startScroll(0, 0, 0, -1200, 1000)
                SystemClock.sleep(30)
                chat.computeScroll()
                val now = field("offset").getDouble(chat)
                assertTrue(
                    "The actual OverScroller must advance",
                    kotlin.math.abs(now - before) > 1,
                )
                val index = field("index").get(chat) as HistoryIndex
                val current = nodes()
                assertTrue(current.isNotEmpty())
                for (node in current) {
                    val f = node.fragment
                    val expected =
                        index.top(f.id.messageID)!! - now + f.prepared.blocks[f.part].offsetY
                    assertEquals(
                        "Native RenderNode must represent this draw's logical scroll offset",
                        expected,
                        node.native.translationY.toDouble(),
                        0.05,
                    )
                }
            }
            i.runOnMainSync {
                val scroller = field("scroller").get(chat) as OverScroller
                scroller.abortAnimation()
                chat.renderVisibleRows()
                SystemClock.sleep(20)
                chat.offset -= chat.height * 3.0
                chat.renderVisibleRows()
                val area = checkNotNull(chat.scene.imagePreparationViewport)
                assertTrue("Fast media lead must exceed two viewports", area.top <= -chat.height * 2f)
                assertTrue(area.bottom >= chat.height)
            }
            val location = IntArray(2)
            var observedDragFrames = 0
            var staleDragFrames = 0
            // Слой видео может запросить кадр, пока основное окно обрабатывает касание.
            val redraw =
                object : Choreographer.FrameCallback {
                    // Запрашиваем очередное рисование до окончания жеста и снова подписываемся на
                    // кадр. Так воспроизводим ситуацию, когда слой видео просит рисовать
                    // независимо от движения пальца.
                    override fun doFrame(frameTimeNanos: Long) {
                        chat.invalidate()
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            val beforeDraw =
                ViewTreeObserver.OnPreDrawListener {
                    if (chat.dragging) {
                        val offset = chat.offset
                        val current = nodes()
                        if (current.isNotEmpty()) {
                            observedDragFrames++
                            if (current.any { node ->
                                    val fragment = node.fragment
                                    val top = chat.index.top(fragment.id.messageID)!!
                                    val y = top - offset + fragment.prepared.blocks[fragment.part].offsetY
                                    kotlin.math.abs(y - node.native.translationY) >= 0.05
                                }
                            ) staleDragFrames++
                        }
                    }
                    true
                }
            i.runOnMainSync {
                chat.getLocationOnScreen(location)
                chat.viewTreeObserver.addOnPreDrawListener(beforeDraw)
                Choreographer.getInstance().postFrameCallback(redraw)
            }
            try {
                val x = location[0] + chat.width / 2
                val top = location[1] + chat.height / 4
                val bottom = location[1] + chat.height * 3 / 4
                assertTrue(d.swipe(x, top, x, bottom, 80))
            } finally {
                i.runOnMainSync {
                    Choreographer.getInstance().removeFrameCallback(redraw)
                    chat.viewTreeObserver.removeOnPreDrawListener(beforeDraw)
                }
            }
            assertTrue("Real OS drag must reach multiple drawing frames", observedDragFrames >= 8)
            assertEquals("Draws during a drag must use the input frame's offset", 0, staleDragFrames)
        } finally {
            i.runOnMainSync {
                host?.close()
                activity?.finish()
            }
            harness?.close()
            Configurator.getInstance().waitForIdleTimeout = old
        }
    }
}
