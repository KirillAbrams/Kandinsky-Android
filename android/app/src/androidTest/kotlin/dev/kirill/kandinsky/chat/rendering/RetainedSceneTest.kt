package dev.kirill.kandinsky.chat.rendering

import android.content.Intent
import android.os.SystemClock
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.support.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RetainedSceneTest {
    @Test
    fun firstAlbumFrameUsesMeasuredPromptHeightWhenLayoutsAreAlreadyCached() {
        val i = InstrumentationRegistry.getInstrumentation()
        val h =
            RepositoryHarness.open(
                File(i.targetContext.cacheDir, "album-frame-" + UUID.randomUUID())
            )
        val prompt =
            Message(
                11,
                2,
                OrderKey(11, 11),
                MessageDirection.OUTGOING,
                1,
                MessageKind.TEXT,
                "Красивая собака\nулыбается\nна улице\nсмотрит в камеру",
                "",
                null,
                2,
                emptyList(),
                delivery = MessageDelivery.ACCEPTED,
            )
        val slots =
            (1..4).map { slot ->
                StudioGeneration.Slot(slot.toString(), StudioGeneration.Stage.QUEUED, null, 0)
            }
        val answer =
            Message(
                12,
                2,
                OrderKey(12, 12),
                MessageDirection.INCOMING,
                1,
                MessageKind.TEXT,
                "В очереди",
                "",
                null,
                1,
                emptyList(),
                studioGeneration =
                    StudioGeneration(
                        StudioGeneration.Stage.QUEUED,
                        "image",
                        "1:1",
                        null,
                        null,
                        slots = slots,
                    ),
            )
        val repo =
            object : dev.kirill.kandinsky.chat.storage.ChatRepository by h.repo {
                override fun index(
                    chatID: ChatID,
                    callback: (Result<List<IndexedRow>>) -> Unit,
                ): Cancellation {
                    callback(
                        Result.success(
                            listOf(
                                IndexedRow(
                                    prompt.id,
                                    prompt.order,
                                    70.0,
                                    prompt.revision,
                                    prompt.savedAtMs,
                                ),
                                IndexedRow(
                                    answer.id,
                                    answer.order,
                                    49.0,
                                    answer.revision,
                                    answer.savedAtMs,
                                ),
                            )
                        )
                    )
                    return Cancellation {}
                }

                override fun history(
                    request: PageRequest,
                    callback: (Result<HistoryPage>) -> Unit,
                ): Cancellation {
                    callback(Result.success(HistoryPage(listOf(prompt, answer), null, null)))
                    return Cancellation {}
                }
            }
        val nodesField =
            RetainedChatScene::class.java.getDeclaredField("nodes").apply { isAccessible = true }
        val opened = java.util.concurrent.CountDownLatch(1)
        lateinit var host: ChatScrollHost
        try {
            i.runOnMainSync {
                host = ChatScrollHost(i.targetContext)
                host.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                )
                host.layout(0, 0, 1080, 2000)
                for (row in listOf(prompt, answer)) {
                    host.cache.insert(PreparedText(row, host.layoutKey(row)))
                }
                host.open(2, repo, ReadingAnchor(messageID = prompt.id, followsBottom = false)) {
                    if (it.isSuccess) opened.countDown()
                }
            }
            assertTrue(opened.await(4, java.util.concurrent.TimeUnit.SECONDS))
            var promptBottom = 0f
            var gridTop = 0f
            var cellCount = 0
            i.runOnMainSync {
                // Страница уже содержит измеренные рисунки, а указатель пока хранит приблизительные
                // высоты
                host.renderVisibleRows()
                host.renderVisibleRows()
                val nodes = nodesField.get(host.scene) as Map<*, *>
                val promptFrame = (nodes[FragmentID(prompt.id, 0)] as MessageNode).fragment
                val albumFrame = (nodes[FragmentID(answer.id, 0)] as MessageNode).fragment
                val card = checkNotNull(albumFrame.prepared.generationCard)
                promptBottom = promptFrame.frame.bottom
                gridTop = albumFrame.frame.top + card.cells.minOf { it.frame.top }
                cellCount = card.cells.size
            }
            assertEquals(4, cellCount)
            assertTrue(
                "First album frame overlaps the prompt: grid=$gridTop promptBottom=$promptBottom",
                gridTop >= promptBottom,
            )
        } finally {
            i.runOnMainSync { host.close() }
            h.close()
        }
    }

    // Поздняя доставка прежнего ответа не должна восстановить закрытый чат или число его сообщений.
    @Test
    fun closingDuringInitialMetadataCancelsOpeningExactlyOnce() {
        val i = InstrumentationRegistry.getInstrumentation()
        val h =
            RepositoryHarness.open(
                File(i.targetContext.cacheDir, "opening-close-" + UUID.randomUUID())
            )
        val held = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        var release: (() -> Unit)? = null
        val repo =
            object : dev.kirill.kandinsky.chat.storage.ChatRepository by h.repo {
                override fun index(chatID: ChatID, callback: (Result<List<IndexedRow>>) -> Unit) =
                    h.repo.index(chatID) { result ->
                        release = { callback(result) }
                        held.countDown()
                    }
            }
        lateinit var host: ChatScrollHost
        var completions = 0
        var error: Throwable? = null
        i.runOnMainSync {
            host = ChatScrollHost(i.targetContext)
            host.open(2, repo, null) {
                completions++
                error = it.exceptionOrNull()
                done.countDown()
            }
        }
        try {
            assertTrue(held.await(3, java.util.concurrent.TimeUnit.SECONDS))
            i.runOnMainSync { host.close() }
            assertTrue(
                "Closing must retire the pending opening continuation",
                done.await(2, java.util.concurrent.TimeUnit.SECONDS),
            )
            i.runOnMainSync { release!!() }
            i.waitForIdleSync()
            i.runOnMainSync {
                assertEquals(1, completions)
                assertEquals(RepoError.CANCELLED, (error as? RepoException)?.reason)
                assertEquals(0, host.messageCount)
            }
        } finally {
            i.runOnMainSync { host.close() }
            h.close()
        }
    }

    @Test
    fun newerSyncIndexStillCompletesAnOpeningWhoseFirstIndexWasDelayed() {
        val i = InstrumentationRegistry.getInstrumentation()
        val h =
            RepositoryHarness.open(
                File(i.targetContext.cacheDir, "opening-sync-" + UUID.randomUUID())
            )
        awaitResult<MessageDelta> {
            h.repo.send(SendCommand(UUID.randomUUID().toString(), 2, text = "existing"), it)
        }
        val held = java.util.concurrent.CountDownLatch(1)
        val applied = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.CountDownLatch(1)
        var release: (() -> Unit)? = null
        var first = true
        val repo =
            object : dev.kirill.kandinsky.chat.storage.ChatRepository by h.repo {
                override fun index(
                    chatID: ChatID,
                    callback: (Result<List<IndexedRow>>) -> Unit,
                ): Cancellation {
                    if (!first) return h.repo.index(chatID, callback)
                    first = false
                    return h.repo.index(chatID) { result ->
                        release = { callback(result) }
                        held.countDown()
                    }
                }
            }
        lateinit var host: ChatScrollHost
        var completions = 0
        i.runOnMainSync {
            host = ChatScrollHost(i.targetContext)
            host.open(2, repo, null) {
                assertTrue(it.isSuccess)
                completions++
                opened.countDown()
            }
            host.onCountChanged = { if (it == 2) applied.countDown() }
        }
        try {
            assertTrue(
                "Initial metadata must be held before delivering sync",
                held.await(3, java.util.concurrent.TimeUnit.SECONDS),
            )
            val delta =
                awaitResult<MessageDelta> {
                        h.repo.send(
                            SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                text = "sync won the race",
                            ),
                            it,
                        )
                    }
                    .copy(reason = MessageChangeReason.SYNC)
            i.runOnMainSync { host.apply(delta) }
            assertTrue(
                "The newer metadata must paint before the initial query returns",
                applied.await(3, java.util.concurrent.TimeUnit.SECONDS),
            )
            i.runOnMainSync { release!!() }
            assertTrue(
                "A current index must finish opening exactly once instead of leaving composer controls disabled",
                opened.await(2, java.util.concurrent.TimeUnit.SECONDS),
            )
            i.runOnMainSync {
                assertEquals(1, completions)
                assertEquals(2, host.messageCount)
            }
        } finally {
            i.runOnMainSync { host.close() }
            h.close()
        }
    }

    // Сверка с внешним значением не должна менять текст и область текущего составления.
    @Test
    fun nativeComposingRangeSurvivesStateRefresh() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val input =
                android.widget.EditText(InstrumentationRegistry.getInstrumentation().targetContext)
            input.setText("👨‍👩‍👧‍👦 é مرحبا")
            val connection = input.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
            connection.setComposingRegion(0, input.length())
            assertTrue(
                android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.text) >= 0
            )
            val composing = input.text.toString()
            dev.kirill.kandinsky.chat.conversation.ComposerInputBridge.synchronize(input, "remote")
            assertEquals(composing, input.text.toString())
            assertTrue(
                android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.text) >= 0
            )
            dev.kirill.kandinsky.chat.conversation.ComposerInputBridge.finish(input)
            dev.kirill.kandinsky.chat.conversation.ComposerInputBridge.synchronize(
                input,
                "Привет 👨‍👩‍👧‍👦 é مرحبا",
            )
            assertEquals("Привет 👨‍👩‍👧‍👦 é مرحبا", input.text.toString())
            assertEquals(
                -1,
                android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.text),
            )
        }
    }

    private fun message(text: String) =
        Message(
            1,
            4,
            OrderKey(1, 1),
            MessageDirection.INCOMING,
            1,
            MessageKind.TEXT,
            text,
            "",
            null,
            1,
            emptyList(),
        )

    // Многократно перемещает те же фрагменты и требует сохранения системного узла без повторной
    // записи
    // рисунка.
    @Test
    fun positionOnlyRetainsNodesAndRejectsLateLayout() {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val scene = RetainedChatScene()
            val key = TextLayoutKey(1, 1, 200, 17, 1)
            val resource = PreparedText(message("مرحبا 👨‍👩‍👧‍👦 é text\n".repeat(80)), key)
            assertTrue(resource.blocks.size > 5)
            fun snapshot(y: Float) =
                SceneSnapshot(
                    scene.ticket,
                    (0 until 5).map {
                        SceneFragment(
                            resource,
                            it,
                            android.graphics.RectF(
                                10f,
                                y + it * 380,
                                210f,
                                y + it * 380 + resource.blocks[it].height,
                            ),
                        )
                    },
                )
            scene.apply(snapshot(0f))
            val identity = scene.nodeIdentity(FragmentID(1, 0))
            val count = scene.metrics.recordings
            assertTrue(count > 0)
            for (n in 0 until 1000) scene.apply(snapshot((n % 30).toFloat()))
            assertEquals(identity, scene.nodeIdentity(FragmentID(1, 0)))
            assertEquals(count, scene.metrics.recordings)
            scene.discardDisplayList(FragmentID(1, 0))
            scene.prepareForDraw()
            assertEquals(count + 1, scene.metrics.recordings)
            scene.prepareForDraw()
            assertEquals(count + 1, scene.metrics.recordings)
            val oldTicket = scene.ticket
            scene.advanceGeneration()
            val late = PreparedText(message("late"), key)
            assertFalse(scene.accept(late, oldTicket))
            late.close()
            assertEquals(1, late.closeCount)
            val long = PreparedText(message("a\n".repeat(5000)), TextLayoutKey(1, 2, 100, 3, 1))
            assertTrue(long.blocks.size > 200)
            assertTrue(long.blocks.all { it.height > 0 && it.height <= 512 })
            scene.apply(
                SceneSnapshot(
                    scene.ticket,
                    (0 until 200).map {
                        SceneFragment(
                            long,
                            it,
                            android.graphics.RectF(0f, 0f, 100f, long.blocks[it].height),
                        )
                    },
                )
            )
            assertTrue(scene.metrics.liveFragments <= 128)
            scene.close()
            assertEquals(0, scene.metrics.liveFragments)
            assertEquals(0, scene.metrics.activeFrameClocks)
            val afterClose = PreparedText(message("closed"), key)
            assertFalse(scene.accept(afterClose, scene.ticket))
            assertEquals(1, afterClose.closeCount)
        }
    }

    @Test
    fun fractionalScrollMovesNativeLayersWithoutResizingTheirContent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resource = PreparedText(
                message("Строка текста для проверки переноса.\n".repeat(60)),
                TextLayoutKey(1, 1, 260, 17, 1),
            )
            val width = resource.width
            val height = resource.blocks.first().height
            fun fragment(y: Float) =
                SceneFragment(resource, 0, android.graphics.RectF(18f, y, 18f + width, y + height))
            val node = MessageNode(fragment(0f))
            node.position()
            val initialBounds = listOf(
                node.native.width, node.native.height, node.overlay.width, node.overlay.height,
            )

            for (index in -20..40) {
                val y = index * 17.3f + .041f
                node.fragment = fragment(y)
                node.position()
                assertEquals(
                    "Moving the same prepared fragment changed its native bounds at y=$y",
                    initialBounds,
                    listOf(node.native.width, node.native.height, node.overlay.width, node.overlay.height),
                )
                assertEquals(y, node.native.translationY, .0001f)
                assertEquals(y, node.overlay.translationY, .0001f)
            }
            resource.close()
        }
    }

    @Test
    fun repositoryDeltaRetainsUnchangedNativeNodesThroughFullCoordinator() {
        val i = InstrumentationRegistry.getInstrumentation()
        val d = UiDevice.getInstance(i)
        Configurator.getInstance().waitForIdleTimeout = 0
        val intent =
            i.targetContext.packageManager
                .getLaunchIntentForPackage(i.targetContext.packageName)!!
                .apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
        val activity = i.startActivitySync(intent) as MainActivity
        assertNotNull(d.wait(Until.findObject(By.pkg(i.targetContext.packageName)), 15000))
        val h =
            RepositoryHarness.open(
                File(i.targetContext.cacheDir, "retained-full-" + UUID.randomUUID())
            )
        val ids =
            (0 until 10).map { n ->
                awaitResult<MessageDelta> {
                        h.repo.send(SendCommand("retained-" + n, 2, text = "row " + n), it)
                    }
                    .inserted
                    .first()
                    .id
            }
        lateinit var host: ChatScrollHost
        i.runOnMainSync {
            host = ChatScrollHost(activity)
            activity.setContentView(host)
        }
        fun waitFor(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 6000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < end) {
                i.runOnMainSync { ready = condition() }
                if (!ready) SystemClock.sleep(10)
            }
            assertTrue("Full coordinator must become ready", ready)
        }
        try {
            awaitResult<Unit> { cb ->
                i.runOnMainSync {
                    host.open(
                        2,
                        h.repo,
                        ReadingAnchor(messageID = ids.first(), followsBottom = false),
                        cb,
                    )
                }
                Cancellation {}
            }
            waitFor { host.metrics.liveFragments == 10 }
            lateinit var scene: RetainedChatScene
            lateinit var native: android.graphics.RenderNode
            var recorded = 0
            val nodesField =
                RetainedChatScene::class.java.getDeclaredField("nodes").apply {
                    isAccessible = true
                }
            fun nativeNode(): android.graphics.RenderNode {
                val node = (nodesField.get(scene) as Map<*, *>)[FragmentID(ids.first(), 0)]!!
                val field = node.javaClass.getDeclaredField("native").apply { isAccessible = true }
                return field.get(node) as android.graphics.RenderNode
            }
            i.runOnMainSync {
                val field =
                    ChatScrollHost::class.java.getDeclaredField("scene").apply {
                        isAccessible = true
                    }
                scene = field.get(host) as RetainedChatScene
                native = nativeNode()
                recorded = host.metrics.recordings
            }
            val delta =
                awaitResult<MessageDelta> {
                    h.repo.send(SendCommand("retained-new", 2, text = "new"), it)
                }
            i.runOnMainSync { host.apply(delta) }
            waitFor { host.metrics.liveFragments == 11 }
            i.runOnMainSync {
                assertSame(
                    "Unchanged native RenderNode must survive SQLite metadata/page refresh",
                    native,
                    nativeNode(),
                )
                assertTrue(native.hasDisplayList())
                assertEquals(
                    "Only the added short message requires recording",
                    recorded + 1,
                    host.metrics.recordings,
                )
            }
        } finally {
            i.runOnMainSync {
                host.close()
                activity.finish()
            }
            h.close()
        }
    }

    @Test
    fun clippedBackdropSubmissionIsIndependentOfOffscreenRows() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resource =
                PreparedText(message("line\n".repeat(5000)), TextLayoutKey(1, 9, 200, 17, 1))
            fun recordedBytes(count: Int): Long {
                val scene = RetainedChatScene()
                val root = android.graphics.RenderNode("bounded strip")
                try {
                    scene.apply(
                        SceneSnapshot(
                            scene.ticket,
                            (0 until count).map { part ->
                                SceneFragment(
                                    resource,
                                    part,
                                    android.graphics.RectF(
                                        0f,
                                        part * 600f,
                                        200f,
                                        part * 600f + resource.blocks[part].height,
                                    ),
                                )
                            },
                        )
                    )
                    val canvas = root.beginRecording(200, 64)
                    try {
                        canvas.clipRect(0, 0, 200, 64)
                        scene.draw(canvas)
                    } finally {
                        root.endRecording()
                    }
                    return root.computeApproximateMemoryUsage()
                } finally {
                    root.discardDisplayList()
                    scene.close()
                }
            }
            try {
                val sparse = recordedBytes(1)
                val crowded = recordedBytes(128)
                assertTrue(
                    "Offscreen rows must not inflate submitted display list: sparse=$sparse crowded=$crowded",
                    crowded <= sparse + 1024,
                )
            } finally {
                resource.close()
            }
        }
    }
}
