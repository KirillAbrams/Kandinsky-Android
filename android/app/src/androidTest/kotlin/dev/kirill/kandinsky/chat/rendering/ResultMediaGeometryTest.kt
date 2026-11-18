package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import dev.kirill.kandinsky.chat.domain.*
import org.junit.Assert.*
import org.junit.Test

class ResultMediaGeometryTest {
    @Test
    fun singleGenerationKeepsItsProgressAndOnlyOffersAllowedRetries() {
        val resources =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.resources
        StudioGenerationCard.load(resources)
        fun prepare(
            stage: StudioGeneration.Stage,
            error: String? = null,
            moderated: Boolean = false,
            progress: Int? = null,
        ): PreparedText {
            val row =
                Message(
                    1,
                    2,
                    OrderKey(1, 1),
                    MessageDirection.INCOMING,
                    0,
                    MessageKind.TEXT,
                    "",
                    "",
                    null,
                    1,
                    emptyList(),
                    studioGeneration = StudioGeneration(
                        stage, "video", "invalid", null, error, moderated, progress = progress,
                    ),
                )
            return PreparedText(row, TextLayoutKey(1, 1, 300, 17, 1))
        }

        val running = prepare(StudioGeneration.Stage.RUNNING, progress = 41)
        val advanced = prepare(StudioGeneration.Stage.RUNNING, progress = 72)
        val failed = prepare(StudioGeneration.Stage.FAILED)
        val blocked = prepare(
            StudioGeneration.Stage.FAILED,
            error = "Генерация заблокирована после проверки",
            moderated = true,
        )
        val cancelled = prepare(StudioGeneration.Stage.CANCELLED, error = "Не показывать")
        try {
            val runningCard = requireNotNull(running.generationCard)
            val runningCell = runningCard.runningCells.single()
            assertEquals("single", runningCell.slot.id)
            assertEquals(41, runningCell.slot.progress)
            assertEquals(runningCard.image, runningCell.frame)
            assertEquals(250f, runningCell.frame.height(), 0.01f)
            assertTrue(runningCard.sameStaticInk(requireNotNull(advanced.generationCard)))
            assertTrue(runningCard.controls.isEmpty())
            assertEquals(
                "Создаём",
                StudioGenerationCard.progressLabel(StudioGeneration.Stage.RUNNING, 100),
            )

            val failureCard = requireNotNull(failed.generationCard)
            assertEquals("Не удалось создать. Попробуйте ещё раз.", failureCard.status)
            assertNull(failureCard.image)
            assertTrue(failureCard.runningCells.isEmpty())
            val retry = failureCard.controls.single()
            assertEquals(StudioGenerationAction.RETRY, retry.action)
            assertTrue(retry.frame.top >= failureCard.statusFrame.bottom)
            assertTrue(retry.frame.bottom < failureCard.height)

            val blockedCard = requireNotNull(blocked.generationCard)
            assertEquals("Генерация заблокирована после проверки", blockedCard.status)
            assertTrue(blockedCard.controls.isEmpty())
            assertEquals("Генерация отменена", requireNotNull(cancelled.generationCard).status)

            val bitmap = Bitmap.createBitmap(
                failed.width.toInt(), failed.height.toInt(), Bitmap.Config.ARGB_8888,
            )
            try {
                failed.draw(0, Canvas(bitmap))
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                val retryPixel = bitmap.getPixel(
                    retry.frame.centerX().toInt(), retry.frame.centerY().toInt(),
                )
                assertEquals(255, Color.alpha(retryPixel))
            } finally {
                bitmap.recycle()
            }
        } finally {
            listOf(running, advanced, failed, blocked, cancelled).forEach { it.close() }
        }
    }

    @Test
    fun waitingForTheFirstMeasuredPercentDoesNotPaintFakeProgress() {
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bounds = RectF(0f, 0f, 180f, 116f)
        lateinit var indicator: StudioProgressView
        instrument.runOnMainSync {
            indicator = StudioProgressView(instrument.targetContext)
            indicator.bind(null, bounds, 1f)
        }
        android.os.SystemClock.sleep(650)
        instrument.runOnMainSync {
            val image = Bitmap.createBitmap(180, 116, Bitmap.Config.ARGB_8888)
            try {
                indicator.draw(Canvas(image))
                val pixel = image.getPixel(104, 41)
                assertTrue(
                    "The indeterminate ring cannot display a filled progress segment before the first measurement",
                    Color.green(pixel) - Color.red(pixel) < 50,
                )
            } finally {
                image.recycle()
            }
        }
    }

    @Test
    fun progressArcEasesWithoutMovingTheCell() {
        val instrument = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bounds = RectF(20f, 28f, 200f, 144f)
        val frame = RectF(0f, 0f, bounds.width(), bounds.height())
        lateinit var indicator: StudioProgressView
        fun cell(percent: Int) =
            StudioGenerationCard.Cell(
                StudioGeneration.Slot("video", StudioGeneration.Stage.RUNNING, null, percent),
                frame,
            )
        fun lowerArc(): Boolean {
            val image = Bitmap.createBitmap(180, 116, Bitmap.Config.ARGB_8888)
            try {
                indicator.draw(Canvas(image))
                val pixel = image.getPixel(90, 80)
                return Color.alpha(pixel) > 0 && Color.green(pixel) - Color.red(pixel) > 70
            } finally {
                image.recycle()
            }
        }
        instrument.runOnMainSync {
            indicator = StudioProgressView(instrument.targetContext)
            indicator.bind(cell(20), bounds, 1f)
        }
        android.os.SystemClock.sleep(650)
        instrument.runOnMainSync {
            assertFalse(lowerArc())
            indicator.bind(cell(75), bounds, 1f)
            assertFalse("The arc should initially keep the previous sweep", lowerArc())
        }
        android.os.SystemClock.sleep(650)
        instrument.runOnMainSync {
            assertTrue(lowerArc())
            assertEquals(
                bounds,
                RectF(
                    indicator.left.toFloat(),
                    indicator.top.toFloat(),
                    indicator.right.toFloat(),
                    indicator.bottom.toFloat(),
                ),
            )
        }
    }

    @Test
    fun studioBatchKeepsFourCellsWhenTheThirdFileArrives() {
        for ((model, kind, mime) in
            listOf(
                Triple("image", MessageKind.PHOTO, "image/png"),
                Triple("video", MessageKind.VIDEO, "video/mp4"),
            )) {
            val ids = listOf("first", "second", "third", "fourth")
            val waiting =
                ids.map { StudioGeneration.Slot(it, StudioGeneration.Stage.QUEUED, null, null) }
            val partial =
                waiting.toMutableList().apply {
                    this[0] = this[0].copy(stage = StudioGeneration.Stage.RUNNING, progress = 37)
                    this[2] =
                        this[2].copy(
                            stage = StudioGeneration.Stage.SUCCEEDED,
                            blobId = "third-file",
                        )
                }
            val attachment =
                Attachment(
                    "net-1-result-third-file",
                    "remote/third-file",
                    mime,
                    100,
                    512,
                    512,
                    1,
                    null,
                    "third-file",
                )
            fun prepare(slots: List<StudioGeneration.Slot>, media: List<Attachment>): PreparedText {
                val state =
                    StudioGeneration(
                        if (slots.all { it.stage == StudioGeneration.Stage.SUCCEEDED })
                            StudioGeneration.Stage.SUCCEEDED
                        else StudioGeneration.Stage.RUNNING,
                        model,
                        "1:1",
                        null,
                        null,
                        slots = slots,
                    )
                val row =
                    Message(
                        1,
                        2,
                        OrderKey(1, 1),
                        MessageDirection.INCOMING,
                        0,
                        if (media.isEmpty()) MessageKind.TEXT else kind,
                        "",
                        "",
                        null,
                        1,
                        media,
                        studioGeneration = state,
                    )
                return PreparedText(row, TextLayoutKey(1, 1, 300, 17, 1))
            }
            val initial = prepare(waiting, emptyList())
            val updated = prepare(partial, listOf(attachment))
            try {
                val before = initial.generationCard!!
                val after = updated.generationCard!!
                assertEquals(4, before.cells.size)
                assertTrue(
                    "Active generation actions belong in the composer",
                    before.controls.isEmpty(),
                )
                assertEquals(before.image!!.bottom + 2 * before.unit, before.height, 0.01f)
                val tops = before.cells.map { it.frame.top }
                assertEquals(listOf(tops[0], tops[0], tops[2], tops[2]), tops)
                assertEquals(before.cells[0].frame.width(), before.cells[3].frame.width(), 1f)
                assertEquals(before.cells.map { it.frame }, after.cells.map { it.frame })
                assertEquals(listOf(after.cells[2].frame), updated.mediaItemRects)
                assertTrue(initial.mediaItemRects.isEmpty())
                assertEquals(
                    "37%",
                    StudioGenerationCard.progressLabel(
                        after.cells[0].slot.stage,
                        after.cells[0].slot.progress,
                    ),
                )
                val advanced = partial.toMutableList()
                advanced[0] = advanced[0].copy(progress = 61)
                val ticking = prepare(advanced, listOf(attachment))
                try {
                    assertTrue(ticking.generationCard!!.sameStaticInk(after))
                } finally {
                    ticking.close()
                }

                advanced[1] = advanced[1].copy(stage = StudioGeneration.Stage.FAILED)
                val interrupted = prepare(advanced, listOf(attachment))
                try {
                    assertEquals(
                        listOf(ids[1]),
                        interrupted.generationCard!!.retryCells.map { it.slot.id },
                    )
                    assertEquals(
                        before.cells.map { it.frame },
                        interrupted.generationCard!!.cells.map { it.frame },
                    )
                    assertEquals(initial.height, interrupted.height, 0.01f)
                } finally {
                    interrupted.close()
                }

                val allMedia =
                    ids.map {
                        Attachment(
                            "net-1-result-$it",
                            "remote/$it",
                            mime,
                            100,
                            512,
                            512,
                            1,
                            null,
                            it,
                        )
                    }
                val finished =
                    prepare(
                        ids.map {
                            StudioGeneration.Slot(it, StudioGeneration.Stage.SUCCEEDED, it, null)
                        },
                        allMedia,
                    )
                try {
                    assertEquals(before.cells.map { it.frame }, finished.mediaItemRects)
                    assertEquals(initial.height, finished.height, 0.01f)
                } finally {
                    finished.close()
                }
            } finally {
                initial.close()
                updated.close()
            }
        }
    }

    // Наклейка не должна получать скругление, квадратный низ или прямоугольную подложку фото.
    @Test
    fun regularStickerHasFitGeometryAndNoPhotoCard() {
        val p = layout(MessageKind.STICKER, 600, 424)
        val bitmap = Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            assertEquals("Regular sticker display envelope", 180f, p.mediaRect!!.width(), .5f)
            assertEquals(127.2f, p.mediaRect.height(), .5f)
            assertEquals(0f, p.mediaCornerRadius, .01f)
            assertFalse(p.mediaBottomSquare)
            p.draw(0, Canvas(bitmap))
            assertEquals(
                "A sticker must not have a rectangular photo bubble",
                0,
                Color.alpha(bitmap.getPixel(4, 40)),
            )
            assertTrue(
                "Timestamp sits outside the transparent sticker",
                p.height - p.mediaRect.bottom >= 20,
            )
        } finally {
            bitmap.recycle()
            p.close()
        }
    }

    @Test
    fun incomingEmojiTextUsesNativeLargeGlyphsWithoutAnOrdinaryBubble() {
        for (value in listOf("😀", "👨‍👩‍👧‍👦", "🇷🇺", "1️⃣", "❤️")) {
            val row =
                Message(
                    1,
                    2,
                    OrderKey(1, 1),
                    MessageDirection.INCOMING,
                    0,
                    MessageKind.TEXT,
                    value,
                    "",
                    null,
                    1,
                    emptyList(),
                )
            val p = PreparedText(row, TextLayoutKey(1, 1, 300, 17, 1))
            val bitmap =
                Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), Bitmap.Config.ARGB_8888)
            try {
                val paint =
                    PreparedText::class
                        .java
                        .getDeclaredField("paint")
                        .apply { isAccessible = true }
                        .get(p) as android.text.TextPaint
                assertEquals(
                    "Android native emoji reference envelope: $value",
                    45f,
                    paint.textSize,
                    .1f,
                )
                assertTrue(p.height >= 55)
                p.draw(0, Canvas(bitmap))
                assertEquals(0, Color.alpha(bitmap.getPixel(4, 4)))
            } finally {
                bitmap.recycle()
                p.close()
            }
        }
    }

    private fun layout(
        kind: MessageKind,
        w: Int,
        h: Int,
        caption: String = "",
        bound: Int = 304,
        orientation: Int = 1,
    ): PreparedText {
        val a = Attachment("visual", "seed/visual", "image/jpeg", 100, w, h, orientation, 7000, "")
        val m =
            Message(
                1,
                2,
                OrderKey(1, 1),
                MessageDirection.OUTGOING,
                0,
                kind,
                "",
                caption,
                null,
                1,
                listOf(a),
            )
        return PreparedText(m, TextLayoutKey(1, 1, bound, 17, 1))
    }

    @Test
    fun roundUsesItsAllocatedDiameter() {
        val p = layout(MessageKind.ROUND, 480, 480, bound = 216)
        try {
            assertEquals(216f, p.mediaRect!!.width(), 0.5f)
            assertEquals(216f, p.mediaRect.height(), 0.5f)
        } finally {
            p.close()
        }
    }

    @Test
    fun portraitKeepsAspectInsideTelegramHeightEnvelope() {
        val p = layout(MessageKind.PHOTO, 600, 900)
        try {
            assertEquals(400f, p.mediaRect!!.height(), 0.5f)
            assertEquals(266.667f, p.mediaRect.width(), 0.5f)
        } finally {
            p.close()
        }
    }

    @Test
    fun shortCaptionSharesTheTimeRow() {
        val p = layout(MessageKind.PHOTO, 1600, 900, "Привет")
        try {
            assertTrue("No unconditional blank status row", p.height - p.mediaRect!!.bottom <= 37)
        } finally {
            p.close()
        }
    }

    @Test
    fun roundCornerStaysTransparentWithCaption() {
        val p = layout(MessageKind.ROUND, 480, 480, "Подпись", 216)
        val image = Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            p.draw(0, Canvas(image))
            assertEquals(
                "No rectangular card around a video note",
                0,
                Color.alpha(image.getPixel(4, 40)),
            )
        } finally {
            image.recycle()
            p.close()
        }
    }

    @Test
    fun roundReferencePillsShareBottomBaselineAndMuteSitsAtTop() {
        val p = layout(MessageKind.ROUND, 480, 480, bound = 216)
        val r = p.mediaRect!!
        val bitmap = Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            p.drawMediaStamp(Canvas(bitmap))
            assertTrue(
                "Duration hugs the left circle edge",
                Color.alpha(bitmap.getPixel(6, r.bottom.toInt() - 12)) > 0,
            )
            assertTrue(
                "Send time shares the duration baseline",
                Color.alpha(bitmap.getPixel(p.width.toInt() - 20, r.bottom.toInt() - 12)) > 0,
            )
            assertTrue(
                "Muted preview has a top speaker object",
                Color.alpha(bitmap.getPixel(r.centerX().toInt(), r.top.toInt() + 12)) > 0,
            )
            assertTrue("No extra status row below circle", p.height - r.bottom <= 3)
        } finally {
            bitmap.recycle()
            p.close()
        }
    }

    @Test
    fun geometryStaysFiniteAndBoundedAcrossDimensionsAndRotations() {
        for (kind in
            listOf(
                MessageKind.PHOTO,
                MessageKind.VIDEO,
                MessageKind.GIF,
                MessageKind.ROUND,
            )) for (bound in listOf(80, 180, 304, 600)) for ((w, h) in
            listOf(0 to 0, -1 to 80, 10000 to 1, 1 to 10000, 600 to 900)) for (orientation in
            1..8) {
            val p = layout(kind, w, h, bound = bound, orientation = orientation)
            try {
                val r = p.mediaRect!!
                assertTrue(r.width().isFinite() && r.height().isFinite() && p.height.isFinite())
                assertTrue(r.width() > 0 && r.height() > 0)
                assertTrue(p.width <= bound)
                assertTrue(r.right <= p.width)
                assertTrue(r.bottom <= p.height)
            } finally {
                p.close()
            }
        }
    }

    @Test
    fun longAndRtlCaptionsStayInsideCard() {
        for (text in
            listOf(
                "Длинная подпись ".repeat(30),
                "مرحبا كيف حالك اليوم",
                "Строка\nВторая строка",
            )) {
            val p = layout(MessageKind.PHOTO, 600, 900, text)
            try {
                val lines = p.accessibleLines(0f, p.height, 100)
                assertTrue(lines.isNotEmpty())
                assertTrue(lines.last().frame.bottom <= p.height - 5)
            } finally {
                p.close()
            }
        }
    }

    @Test
    fun deliveredStampCannotImplyReadAcrossTextAndMedia() {
        val a = Attachment("receipt", "receipt", "video/mp4", 100, 480, 480, 1, 7000, "")
        val field = PreparedText::class.java.getDeclaredField("stamp").apply { isAccessible = true }
        val expected =
            mapOf(
                MessageDelivery.ACCEPTED to " ✓",
                MessageDelivery.DELIVERED to " ✓",
                MessageDelivery.READ to " ✓✓",
                MessageDelivery.PENDING to " ◷",
                MessageDelivery.FAILED to " !",
            )
        for (kind in
            listOf(
                MessageKind.TEXT,
                MessageKind.VOICE,
                MessageKind.PHOTO,
                MessageKind.ROUND,
            )) for ((state, suffix) in expected) {
            val row =
                Message(
                    1,
                    2,
                    OrderKey(1, 1),
                    MessageDirection.OUTGOING,
                    0,
                    kind,
                    "Receipt",
                    "",
                    null,
                    1,
                    if (kind == MessageKind.TEXT) emptyList() else listOf(a),
                    state,
                )
            val p = PreparedText(row, TextLayoutKey(1, 1, 300, 17, 1))
            try {
                val wanted =
                    if (
                        kind == MessageKind.TEXT &&
                            state in
                                listOf(
                                    MessageDelivery.ACCEPTED,
                                    MessageDelivery.DELIVERED,
                                    MessageDelivery.READ,
                                )
                    )
                        ""
                    else suffix
                assertEquals(
                    "Generation prompt and legacy media keep their own timestamp rules, for $kind/$state",
                    wanted,
                    (field.get(p) as String).substring(5),
                )
            } finally {
                p.close()
            }
        }
        val row =
            Message(
                1,
                2,
                OrderKey(1, 1),
                MessageDirection.INCOMING,
                0,
                MessageKind.TEXT,
                "Incoming",
                "",
                null,
                1,
                emptyList(),
                MessageDelivery.READ,
            )
        val p = PreparedText(row, TextLayoutKey(1, 1, 300, 17, 1))
        try {
            assertEquals(
                "Incoming messages have no outgoing check suffix",
                5,
                (field.get(p) as String).length,
            )
        } finally {
            p.close()
        }
    }
}
