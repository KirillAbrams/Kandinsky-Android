// Андроид читает узор фона один раз и готовит рисунок под текущий размер окна в фоне. Пока итог
// готовится, можно показать тот же узор напрямую. При нехватке памяти следующий рисунок создаётся
// меньшего размера.

package dev.kirill.kandinsky.chat.rendering

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.graphics.withClip
import dev.kirill.kandinsky.chat.R
import java.lang.ref.WeakReference

object KandinskyAssets {
    @Volatile
    var pattern: Bitmap? = null
        private set

    private var loading = false
    private var latest: WeakReference<View>? = null

    // Запоминаем последний экран и запускаем только одно чтение рисунка. После готовности
    // перерисовываем ещё видимый экран, а дальнейшие вызовы используют уже загруженный узор.
    @Synchronized
    fun load(context: Context, view: View) {
        latest = WeakReference(view)
        if (pattern != null) {
            view.postInvalidate()
            return
        }
        if (loading) return
        loading = true
        val resources = context.applicationContext.resources
        Thread(
                {
                    pattern =
                        BitmapFactory.decodeResource(resources, R.drawable.kandinsky_evolution)
                    synchronized(this) { loading = false }
                    Handler(Looper.getMainLooper()).post {
                        latest?.get()?.takeIf { it.isAttachedToWindow }?.invalidate()
                    }
                },
                "KandinskyArtwork",
            )
            .start()
    }
}

class KandinskyWallpaper : AutoCloseable {
    private data class Key(
        val width: Int,
        val height: Int,
        val scale: Float,
        val sampling: Float,
        val pattern: Bitmap?,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val main = Handler(Looper.getMainLooper())
    private val worker =
        java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(1),
            { r -> Thread(r, "Kandinsky-Wallpaper").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private var wanted: Key? = null
    private var ready: Key? = null
    private var raster: Bitmap? = null
    private var sampling = 1f
    private var closed = false
    var onReady: (() -> Unit)? = null

    // Заливаем прямоугольник белым и при наличии узора растягиваем его до заполнения с обрезкой
    // краёв. Едва заметный узор сохраняет читаемость текста истории.
    private fun raw(c: Canvas, bounds: RectF, pattern: Bitmap?) {
        val fill = Paint(Paint.FILTER_BITMAP_FLAG)
        fill.color = Color.WHITE
        c.drawRect(bounds, fill)
        if (pattern == null) return
        val ratio = maxOf(bounds.width() / pattern.width, bounds.height() / pattern.height)
        val width = pattern.width * ratio
        val height = pattern.height * ratio
        val rect =
            RectF(
                bounds.centerX() - width / 2,
                bounds.centerY() - height / 2,
                bounds.centerX() + width / 2,
                bounds.centerY() + height / 2,
            )
        fill.alpha = 21
        c.withClip(bounds) {
            c.drawBitmap(pattern, null, rect, fill)
        }
    }

    // Показываем готовый рисунок при совпадении размеров и источника. Иначе рисуем запасной
    // вариант и отправляем один новый расчёт в фон. Устаревший ответ освобождаем, чтобы он не
    // подменил новый размер.
    fun render(c: Canvas, bounds: RectF, scale: Float) {
        if (closed || bounds.width() <= 0 || bounds.height() <= 0) return
        val key =
            Key(
                kotlin.math.ceil(bounds.width().toDouble()).toInt(),
                kotlin.math.ceil(bounds.height().toDouble()).toInt(),
                scale,
                sampling,
                KandinskyAssets.pattern,
            )
        val value = raster
        if (ready == key && value != null) {
            c.drawBitmap(value, null, bounds, paint)
            return
        }
        raw(c, bounds, key.pattern)
        if (wanted == key) return
        wanted = key
        worker.execute {
            val bitmap =
                Bitmap.createBitmap(
                    maxOf(1, (key.width * key.sampling).toInt()),
                    maxOf(1, (key.height * key.sampling).toInt()),
                    Bitmap.Config.ARGB_8888,
                    false,
                )
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(key.sampling, key.sampling)
            raw(canvas, RectF(0f, 0f, key.width.toFloat(), key.height.toFloat()), key.pattern)
            main.post {
                if (closed || wanted != key) bitmap.recycle()
                else {
                    raster = bitmap
                    ready = key
                    onReady?.invoke()
                }
            }
        }
    }

    // Отпускаем готовый фон и снижаем разрешение следующего расчёта. При возврате фон
    // восстановится с меньшим расходом памяти.
    fun trimMemory() {
        sampling = .5f
        raster = null
        ready = null
        wanted = null
    }

    // Перестаём принимать рисунки, сбрасываем ссылки и останавливаем фоновый работник. Закрытый
    // экран больше не получает уведомление готовности.
    override fun close() {
        if (closed) return
        closed = true
        onReady = null
        wanted = null
        ready = null
        raster = null
        worker.shutdownNow()
    }
}
