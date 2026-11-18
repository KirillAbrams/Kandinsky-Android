package dev.kirill.kandinsky.chat.rendering

import android.content.Context
import android.graphics.*
import android.os.Build
import android.renderscript.*
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.graphics.createBitmap

class NativeGlassSurface(private val context: Context) : AutoCloseable {
    private var gpu: GpuGlassStrip? = null
    private var cpu: CpuGlassStrip? = null
    private val shape = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(c: Canvas, rect: RectF, radius: Float, d: Float, backdrop: (Canvas) -> Unit) {
        if (rect.width() <= 0 || rect.height() <= 0) return
        shape.reset()
        shape.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.withClip(shape) {
            if (Build.VERSION.SDK_INT >= 31 && c.isHardwareAccelerated) {
                val layer = gpu ?: GpuGlassStrip().also { gpu = it }
                layer.draw(c, rect, 14 * d, backdrop)
            } else {
                val layer = cpu ?: CpuGlassStrip(context).also { cpu = it }
                layer.draw(c, rect, 14 * d, backdrop)
            }
            paint.shader =
                LinearGradient(
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    Color.argb(66, 255, 255, 255),
                    Color.argb(20, 255, 255, 255),
                    Shader.TileMode.CLAMP,
                )
            c.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }
    }

    override fun close() {
        if (Build.VERSION.SDK_INT >= 31) gpu?.close()
        gpu = null
        cpu?.close()
        cpu = null
    }
}

@RequiresApi(31)
private class GpuGlassStrip : AutoCloseable {
    private val node = RenderNode("Kandinsky live glass backdrop")
    private var radius = -1f

    // Оставляет поля вокруг области, чтобы размытие не обрезалось по её краям.
    fun draw(c: Canvas, rect: RectF, blur: Float, source: (Canvas) -> Unit) {
        val down = .25f
        val margin = ceil(blur * 2).toFloat()
        val originX = rect.left - margin
        val originY = rect.top - margin
        val w = ceil((rect.width() + 2 * margin) * down).toInt().coerceAtLeast(1)
        val h = ceil((rect.height() + 2 * margin) * down).toInt().coerceAtLeast(1)
        if (radius != blur) {
            radius = blur
            node.setRenderEffect(
                RenderEffect.createBlurEffect(blur * down, blur * down, Shader.TileMode.CLAMP)
            )
        }
        node.setPosition(0, 0, w, h)
        val recording = node.beginRecording(w, h)
        try {
            recording.scale(down, down)
            recording.translate(-originX, -originY)
            source(recording)
        } finally {
            node.endRecording()
        }
        c.withTranslation(originX, originY) {
            c.scale(1 / down, 1 / down)
            c.drawRenderNode(node)
        }
    }

    override fun close() {
        node.discardDisplayList()
        node.setRenderEffect(null)
    }
}

@Suppress("DEPRECATION")
private class CpuGlassStrip(context: Context) : AutoCloseable {
    private val rs = RenderScript.create(context.applicationContext)
    private val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    private var input: Allocation? = null
    private var output: Allocation? = null
    private var source: Bitmap? = null
    private var targets = emptyList<Bitmap>()
    private var cursor = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Для вывода создаёт четыре сменяемых изображения, не освобождая принудительно прежние кадры,
    // которые ещё
    // могут использоваться системой.
    private fun resize(w: Int, h: Int) {
        if (source?.width == w && source?.height == h) return
        input?.destroy()
        output?.destroy()
        source?.recycle()
        // Прежние результаты ещё могут использоваться системой рисования, поэтому здесь убираем
        // только собственные ссылки
        targets = List(4) { createBitmap(w, h) }
        cursor = 0
        source = createBitmap(w, h)
        input = Allocation.createFromBitmap(rs, source)
        output = Allocation.createTyped(rs, input!!.type)
    }

    fun draw(c: Canvas, rect: RectF, radius: Float, backdrop: (Canvas) -> Unit) {
        val down = .2f
        val margin = ceil(radius * 2).toFloat()
        val x = rect.left - margin
        val y = rect.top - margin
        val w = ceil((rect.width() + 2 * margin) * down).toInt().coerceAtLeast(1)
        val h = ceil((rect.height() + 2 * margin) * down).toInt().coerceAtLeast(1)
        resize(w, h)
        val bitmap = source!!
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        canvas.scale(down, down)
        canvas.translate(-x, -y)
        backdrop(canvas)
        input!!.copyFrom(bitmap)
        blur.setRadius((radius * down).coerceIn(.1f, 25f))
        blur.setInput(input)
        blur.forEach(output)
        val target = targets[cursor]
        cursor = (cursor + 1) % targets.size
        output!!.copyTo(target)
        c.drawBitmap(target, null, RectF(x, y, x + w / down, y + h / down), paint)
    }

    override fun close() {
        input?.destroy()
        output?.destroy()
        blur.destroy()
        rs.destroy()
        source?.recycle()
        source = null
        targets = emptyList()
    }
}
