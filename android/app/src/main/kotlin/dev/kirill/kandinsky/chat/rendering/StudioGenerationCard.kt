package dev.kirill.kandinsky.chat.rendering

import android.content.res.Resources
import android.graphics.*
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withTranslation
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.domain.Message
import dev.kirill.kandinsky.chat.domain.StudioGeneration
import dev.kirill.kandinsky.chat.domain.StudioGenerationAction
import kotlin.math.ceil

class StudioGenerationCard(
    val generation: StudioGeneration,
    message: Message,
    key: TextLayoutKey,
    private val time: String,
) {
    data class Control(val action: StudioGenerationAction, val frame: RectF, val icon: Int? = null)

    val unit = key.density.coerceAtLeast(0.1f)

    data class Cell(val slot: StudioGeneration.Slot, val frame: RectF)

    val cells: List<Cell>
    val resultFrames: List<RectF>
    val isBatch
        get() = cells.isNotEmpty()

    val retryCells
        get() = cells.filter { it.slot.canRetry }

    val runningCells
        get() =
            if (isBatch) cells.filter { it.slot.stage == StudioGeneration.Stage.RUNNING }
            else
                image
                    ?.takeIf { generation.stage == StudioGeneration.Stage.RUNNING }
                    ?.let {
                        listOf(
                            Cell(
                                StudioGeneration.Slot(
                                    "single",
                                    generation.stage,
                                    null,
                                    generation.progress,
                                ),
                                it,
                            )
                        )
                    }
                    .orEmpty()

    fun sameStaticInk(old: StudioGenerationCard): Boolean =
        image != null &&
            image == old.image &&
            height == old.height &&
            generation.stage == old.generation.stage &&
            cells.size == old.cells.size &&
            cells.zip(old.cells).all { (a, b) ->
                a.frame == b.frame &&
                    a.slot.id == b.slot.id &&
                    a.slot.stage == b.slot.stage &&
                    a.slot.moderated == b.slot.moderated
            }

    val status = if (generation.isActive) "" else generation.failureText
    val image: RectF?
    val statusFrame: RectF
    val controls: List<Control>
    val width: Float
    val height: Float
    private val failureLayout: StaticLayout?
    private val bubble: Path?
    private val tail: Path?

    init {
        val controlHeight = maxOf(48 * unit, key.fontSize * 14f / 18 * 1.45f + 16 * unit)
        val ratio = generation.ratio
        val maxHeight = if (!generation.isVideo) 380 else if (ratio >= 1) 250 else 360
        val span =
            minOf(300 * unit, key.width - 4 * unit, maxHeight * unit * ratio)
                .coerceAtLeast(40 * unit)
        val inset = 2 * unit
        val single = RectF(inset, inset, span + inset, span / ratio + inset)
        val layouts =
            if (
                generation.slots.size == 1 &&
                    (generation.isActive || message.attachments.isNotEmpty())
            ) {
                listOf(single)
            } else {
                MediaMosaic.layout(
                        generation.slots.map { (ratio * 1000).toInt() to 1000 },
                        maxOf(40 * unit, key.width - 4 * unit),
                        spacing = unit,
                        scale = 1f,
                        balancedFour = true,
                    )
                    .map { it.apply { offset(inset, inset) } }
            }
        cells = generation.slots.zip(layouts, ::Cell)
        val framesByBlob =
            cells.mapNotNull { cell -> cell.slot.blobId?.let { it to cell.frame } }.toMap()
        resultFrames = message.attachments.mapNotNull { framesByBlob[it.thumbnailKey] }
        if (generation.isActive || cells.isNotEmpty()) {
            val frame =
                if (cells.isEmpty()) single
                else RectF(cells.first().frame).apply { cells.drop(1).forEach { union(it.frame) } }
            image = frame
            width = frame.width() + 4 * unit
            val top = frame.bottom + 2 * unit
            height = top
            controls = emptyList()
            statusFrame = RectF()
            failureLayout = null
            bubble = null
            tail = null
        } else {
            width = minOf(316 * unit, key.width.toFloat())
            image = null
            val fontSize = maxOf(13 * unit, key.fontSize * 15f / 18)
            val textPaint =
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = fontSize
                    color = KandinskyPalette.studioMuted
                }
            val contentWidth = (width - 22 * unit).toInt().coerceAtLeast(1)
            val metrics = textPaint.fontMetrics
            val layout =
                StaticLayout.Builder.obtain(status, 0, status.length, textPaint, contentWidth)
                    .setIncludePad(false)
                    .setMaxLines(6)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(
                        maxOf(0f, ceil(fontSize * 1.45f) - (metrics.descent - metrics.ascent)),
                        1f,
                    )
                    .build()
            failureLayout = layout
            val top = 8 * unit + layout.height
            statusFrame = RectF(14 * unit, 8 * unit, width - 8 * unit, top)
            controls =
                if (generation.moderated) emptyList()
                else
                    listOf(
                        Control(
                            StudioGenerationAction.RETRY,
                            RectF(14 * unit, top, 142 * unit, top + controlHeight),
                            R.drawable.kandinsky_generation_refresh,
                        )
                    )
            height = top + if (controls.isEmpty()) 24 * unit else controlHeight + 20 * unit
            bubble =
                Path().apply {
                    val corner = 16 * unit
                    val join = 5 * unit
                    addRoundRect(
                        6 * unit,
                        0f,
                        width,
                        height,
                        floatArrayOf(corner, corner, corner, corner, corner, corner, join, join),
                        Path.Direction.CW,
                    )
                }
            tail =
                Path().apply {
                    moveTo(10 * unit, height - 12 * unit)
                    quadTo(5 * unit, height - 3 * unit, 0f, height - unit)
                    quadTo(5 * unit, height + unit, 13 * unit, height - 2 * unit)
                    close()
                }
        }
    }

    // Плавное изменение процентов выполняется другим элементом, поэтому здесь не запускается
    // анимация.
    fun drawStatic(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val frame = image
        if (frame != null) {
            if (isBatch) {
                for (cell in cells) {
                    drawProgress(canvas, paint, cell.frame, cell.slot.stage, cell.slot.canRetry)
                }
            } else {
                drawProgress(canvas, paint, frame, generation.stage, false)
            }
            controls.firstOrNull()?.let { drawControl(canvas, paint, it) }
        } else {
            paint.color = Color.rgb(242, 248, 244)
            bubble?.let { canvas.drawPath(it, paint) }
            tail?.let { canvas.drawPath(it, paint) }
            canvas.withTranslation(statusFrame.left, statusFrame.top) {
                failureLayout?.draw(canvas)
            }
            controls.firstOrNull()?.let { drawControl(canvas, paint, it) }
            paint.textSize = 10 * unit
            paint.typeface = Typeface.DEFAULT
            paint.color = Color.rgb(86, 112, 100)
            drawLabel(
                canvas,
                paint,
                time,
                width - 8 * unit - paint.measureText(time),
                height - 13 * unit,
            )
        }
    }

    // У выполняемой и завершённой генерации оставляет только фон, поскольку проценты и готовое
    // медиа
    // показываются отдельно.
    private fun drawProgress(
        canvas: Canvas,
        paint: Paint,
        frame: RectF,
        stage: StudioGeneration.Stage,
        canRetry: Boolean,
    ) {
        paint.color = KandinskyPalette.studioSurface
        canvas.drawRoundRect(frame, 12 * unit, 12 * unit, paint)
        if (stage == StudioGeneration.Stage.RUNNING || stage == StudioGeneration.Stage.SUCCEEDED)
            return
        val active = stage == StudioGeneration.Stage.QUEUED
        val radius = minOf(22 * unit, frame.width() * 0.24f, frame.height() * 0.30f)
        val x = frame.centerX()
        val y = frame.centerY()
        if (active) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * unit
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.rgb(218, 236, 226)
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.FILL
        }
        val queued = stage == StudioGeneration.Stage.QUEUED
        val label = if (queued) "В очереди" else progressLabel(stage, null)
        paint.color = KandinskyPalette.studioInk
        paint.typeface = controlTypeface
        if (queued) {
            paint.textSize = 12 * unit
            val percent = "0%"
            drawLabel(canvas, paint, percent, x - paint.measureText(percent) / 2, y)
        }
        paint.textSize = 11 * unit
        val middle =
            if (active) minOf(frame.bottom - 7 * unit, y + radius + 10 * unit)
            else if (canRetry) y - 14 * unit else y
        drawLabel(canvas, paint, label, x - paint.measureText(label) / 2, middle)
        if (canRetry) {
            paint.color = Color.rgb(218, 236, 226)
            canvas.drawRoundRect(
                x - 36 * unit,
                y - unit,
                x + 36 * unit,
                y + 25 * unit,
                13 * unit,
                13 * unit,
                paint,
            )
            paint.color = KandinskyPalette.studioInk
            val button = "Повторить"
            drawLabel(canvas, paint, button, x - paint.measureText(button) / 2, y + 12 * unit)
        }
    }

    fun drawControl(canvas: Canvas, paint: Paint, control: Control) {
        val frame = control.frame
        val retry = control.action == StudioGenerationAction.RETRY
        control.icon?.let { icon ->
            val span = (if (retry) 18 else 20) * unit
            val x = if (retry) frame.left + 12 * unit else frame.centerX() - span / 2
            drawIcon(canvas, paint, icon, x, frame.centerY(), span, KandinskyPalette.studioInk)
            if (!retry) return
        }
        paint.color = KandinskyPalette.studioInk
        paint.textSize = 14 * unit
        paint.typeface = controlTypeface
        val x =
            if (retry) frame.left + 38 * unit
            else frame.centerX() - paint.measureText(control.action.title) / 2
        drawLabel(canvas, paint, control.action.title, x, frame.centerY())
    }

    companion object {
        fun progressLabel(stage: StudioGeneration.Stage, progress: Int?): String =
            when (stage) {
                StudioGeneration.Stage.QUEUED -> "В очереди, 0%"
                StudioGeneration.Stage.RUNNING ->
                    progress?.takeIf { it in 0..99 }?.let { "$it%" } ?: "Создаём"
                StudioGeneration.Stage.FAILED -> "Ошибка"
                StudioGeneration.Stage.CANCELLED -> "Отменено"
                StudioGeneration.Stage.SUCCEEDED -> "Готово"
            }

        private val controlTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        @Volatile private var icons: Map<Int, Bitmap> = emptyMap()

        @Synchronized
        fun load(resources: Resources) {
            if (icons.isNotEmpty()) return
            icons =
                listOf(R.drawable.kandinsky_generation_refresh).associateWith {
                    BitmapFactory.decodeResource(resources, it)
                }
        }

        // Используется при оформлении действий карточки без повторного чтения изображения из
        // ресурсов.
        private fun drawIcon(
            canvas: Canvas,
            paint: Paint,
            id: Int,
            x: Float,
            centerY: Float,
            span: Float,
            color: Int,
        ) {
            val bitmap = icons[id] ?: return
            paint.alpha = 255
            paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(x, centerY - span / 2, x + span, centerY + span / 2),
                paint,
            )
            paint.colorFilter = null
        }

        internal fun drawLabel(
            canvas: Canvas,
            paint: Paint,
            value: String,
            x: Float,
            middle: Float,
        ) {
            val metrics = paint.fontMetrics
            canvas.drawText(value, x, middle - (metrics.ascent + metrics.descent) / 2, paint)
        }
    }
}

internal class StudioProgressView(context: android.content.Context) : android.view.View(context) {
    private val ink =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    private var unit = 1f
    private var deliveringPhoto = false
    private var measured: Int? = null
    private var target = 0f
    private var start = 0f
    private var changedAt = 0L
    private var bound = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    fun bind(cell: StudioGenerationCard.Cell, frame: RectF, density: Float) =
        bind(cell.slot.progress, frame, density)

    fun bind(progressValue: Int?, frame: RectF, density: Float, deliveringPhoto: Boolean = false) {
        unit = density
        this.deliveringPhoto = deliveringPhoto
        val progress = progressValue?.takeIf { it in 0..99 }
        if (!bound || measured != progress) {
            val now = android.os.SystemClock.uptimeMillis()
            start = if (bound) fraction(now) else 0f
            target = progress?.div(100f) ?: 0f
            measured = progress
            changedAt = now
            bound = true
        }
        val w = frame.width().toInt().coerceAtLeast(1)
        val h = frame.height().toInt().coerceAtLeast(1)
        if (measuredWidth != w || measuredHeight != h)
            measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        val x = frame.left.toInt()
        val y = frame.top.toInt()
        if (left != x || top != y || width != w || height != h) layout(x, y, x + w, y + h)
        invalidate()
    }

    private fun fraction(now: Long): Float {
        val t = ((now - changedAt) / 550f).coerceIn(0f, 1f)
        return start + (target - start) * (t * t * (3 - 2 * t))
    }

    // При разрешённых анимациях плавно меняет длину дуги и яркость кольца, иначе сразу показывает
    // целевое
    // значение.
    override fun onDraw(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        val motion = android.animation.ValueAnimator.areAnimatorsEnabled()
        val pulse =
            if (motion) (.75 + .25 * kotlin.math.cos(now % 1600 * (2 * Math.PI / 1600))).toFloat()
            else 1f
        val x = width / 2f
        val y = height / 2f
        val radius = minOf(22 * unit, width * .24f, height * .30f)
        if (deliveringPhoto) {
            ink.style = Paint.Style.FILL
            ink.color = KandinskyPalette.studioSurface
            canvas.drawRoundRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                12 * unit,
                12 * unit,
                ink,
            )
        }
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = 3 * unit
        ink.strokeCap = Paint.Cap.ROUND
        ink.color = Color.rgb(218, 236, 226)
        ink.alpha = (255 * pulse).toInt()
        canvas.drawCircle(x, y, radius, ink)
        val sweep = 360 * (if (motion) fraction(now) else target)
        if (sweep > 0) {
            ink.color = Color.rgb(8, 158, 122)
            ink.alpha = (255 * pulse).toInt()
            canvas.drawArc(x - radius, y - radius, x + radius, y + radius, -90f, sweep, false, ink)
        }
        ink.style = Paint.Style.FILL
        ink.color = KandinskyPalette.studioInk
        ink.alpha = 255
        ink.textSize = (if (measured == null) 11 else 12) * unit
        val label = StudioGenerationCard.progressLabel(StudioGeneration.Stage.RUNNING, measured)
        val labelY = if (measured == null) minOf(height - 7 * unit, y + radius + 10 * unit) else y
        StudioGenerationCard.drawLabel(canvas, ink, label, x - ink.measureText(label) / 2, labelY)
        if (deliveringPhoto) {
            val text = "Загружаем фото"
            ink.textSize = 11 * unit
            StudioGenerationCard.drawLabel(
                canvas,
                ink,
                text,
                x - ink.measureText(text) / 2,
                minOf(height - 7 * unit, y + radius + 10 * unit),
            )
        }
        if (motion && isShown) postInvalidateOnAnimation()
    }
}
