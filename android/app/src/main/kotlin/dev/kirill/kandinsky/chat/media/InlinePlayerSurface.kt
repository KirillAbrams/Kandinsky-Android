// Показывает кадр проигрывателя или сохранённую картинку и переносит вывод при смене экрана.
// Проигрыватель освобождает старую поверхность после переключения.

package dev.kirill.kandinsky.chat.media

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette

// Перенос поверхности между экранами не создаёт второй проигрыватель и не добавляет управление
// поверх кадра
class InlinePlayerSurface(context: Context, private val coordinator: MediaCoordinator?) :
    FrameLayout(context), TextureView.SurfaceTextureListener {
    // Создаёт поверхность без владельца проигрывания. Её можно использовать для кадра до
    // подключения видео.
    constructor(context: Context) : this(context, null)

    private val texture = TextureView(context)
    private val textureTransform = Matrix()
    private val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private var posterFrame: PreparedImage? = null
    private var surface: Surface? = null
    private var retainedTexture: SurfaceTexture? = null
    private var movingPresentation = false
    var studioPreviewBackground = false
        // Обновляет цвет подложки только при смене режима. Повтор того же значения не трогает
        // изображение.
        set(value) {
            if (field != value) {
                field = value
                update()
            }
        }

    var hasVideoFrame = false
        private set

    private var circular = false
    private val solidFill = Color.BLACK.toDrawable()
    private var backgroundTone = Color.BLACK
    private var aspect = 1f

    // Размещает видео и картинку поверх него, подключает наблюдение за выводом и отключает
    // собственные нажатия. Управление остаётся у окружающего экрана.
    init {
        setBackgroundColor(Color.BLACK)
        isClickable = false
        addView(texture, LayoutParams(-1, -1))
        texture.surfaceTextureListener = this
        addView(poster, LayoutParams(-1, -1))
    }

    // При необходимости подключает уже готовую поверхность к текущему видео. Затем меняет фон и
    // видимость картинки без пересоздания кадров.
    fun update() {
        if (
            coordinator?.currentMessageID != null &&
                texture.isAvailable &&
                surface == null &&
                retainedTexture == null
        )
            texture.surfaceTexture?.let {
                onSurfaceTextureAvailable(it, texture.width, texture.height)
            }
        poster.visibility = if (posterFrame == null) View.GONE else View.VISIBLE
        val tone =
            if (circular) Color.TRANSPARENT
            else if (studioPreviewBackground) KandinskyPalette.studioSheet else Color.BLACK
        if (backgroundTone != tone) {
            solidFill.color = tone
            backgroundTone = tone
        }
        background = solidFill
    }

    // Вычисляет размеры изображения по рамке и пропорциям видео. И картинка, и видео получают
    // одинаковую мерку, чтобы переход не менял размер.
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        val vw =
            if (circular) minOf(width, height)
            else if (width / height.coerceAtLeast(1).toFloat() > aspect) (height * aspect).toInt()
            else width
        val vh = if (circular) vw else (vw / aspect).toInt()
        val widthSpec = MeasureSpec.makeMeasureSpec(maxOf(1, vw), MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(maxOf(1, vh), MeasureSpec.EXACTLY)
        texture.measure(widthSpec, heightSpec)
        poster.measure(widthSpec, heightSpec)
    }

    // Ставит оба слоя в одну область и подгоняет заполнение круглой рамки. Обычное видео остаётся
    // по центру без растяжения.
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val vw = texture.measuredWidth
        val vh = texture.measuredHeight
        val x = if (circular) 0 else (width - vw) / 2
        val y = (height - vh) / 2
        texture.layout(x, y, x + vw, y + vh)
        textureTransform.reset()
        if (circular)
            textureTransform.setScale(maxOf(1f, aspect), maxOf(1f, 1 / aspect), vw / 2f, vh / 2f)
        texture.setTransform(textureTransform)
        poster.layout(x, y, x + vw, y + vh)
        poster.clipToOutline = circular
        poster.outlineProvider = texture.outlineProvider
    }

    // Заменяет картинку и освобождает прежнее удержание. Размытый кадр не вытесняет чёткий,
    // который уже виден.
    fun setPoster(frame: PreparedImage?) {
        if (frame?.isTinyPreview == true && posterFrame?.isTinyPreview == false) {
            frame.close()
            return
        }
        if (frame == null && posterFrame == null && poster.drawable == null) return
        poster.setImageDrawable(null)
        posterFrame?.close()
        posterFrame = frame
        poster.setImageBitmap(frame?.bitmap)
        poster.visibility = if (frame == null) View.GONE else View.VISIBLE
    }

    // Помечает перенос между экранами до снятия поверхности. Во время переноса уничтожение слоя
    // не должно закрыть текущий вывод.
    fun beginPresentationMove() {
        movingPresentation = true
    }

    // После переноса проверяет, осталась ли сохранённая поверхность. Если её нет, выключает режим
    // переноса на следующем проходе экрана.
    fun finishPresentationMove() {
        post { if (retainedTexture == null) movingPresentation = false }
    }

    // Включает круглую обрезку и пересчитывает фон и размеры. Смена формы не требует нового
    // источника.
    fun circular(value: Boolean) {
        if (circular == value) return
        circular = value
        // Цвет заднего плана задаётся общим обновлением состояния поверхности
        texture.clipToOutline = value
        texture.outlineProvider =
            object : ViewOutlineProvider() {
                // Выбирает овал или прямоугольник по размеру слоя. Эта граница применяется и к
                // картинке поверх видео.
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    if (value) outline.setOval(0, 0, view.width, view.height)
                    else outline.setRect(0, 0, view.width, view.height)
                }
            }
        update()
        requestLayout()
    }

    // Принимает новые пропорции видео и запрашивает разметку при заметном изменении. Нулевой
    // размер не используется как делитель.
    fun aspect(width: Int, height: Int) {
        val next = maxOf(1, width).toFloat() / maxOf(1, height)
        if (kotlin.math.abs(next - aspect) > 0.0001f) {
            aspect = next
            requestLayout()
        }
    }

    // Берёт кадр с готовой поверхности и передаёт его в хранилище с допустимым размером.
    // Сохранённая картинка пригодится при возврате к ролику.
    internal fun rememberCurrentFrame() {
        if (hasVideoFrame && texture.isAvailable) coordinator?.rememberFrame { cache, key, generation ->
            cache.rememberFrame(key, generation, texture.width, texture.height) { w, h ->
                texture.getBitmap(w, h)
            }
        }
    }

    // Сначала сохраняет видимый кадр, затем снимает вывод и картинку. Освобождает старые
    // поверхности после отключения проигрывателя, чтобы он не рисовал в закрытый ресурс.
    fun detachPlayer() {
        rememberCurrentFrame()
        hasVideoFrame = false
        setPoster(null)
        movingPresentation = false
        val old = surface
        surface = null
        val held = retainedTexture
        retainedTexture = null
        if (old == null && held == null) return
        val player = coordinator
        if (player != null) {
            player.attach(null) {
                old?.release()
                held?.release()
            }
        } else {
            old?.release()
            held?.release()
        }
    }

    // Возвращает сохранённую поверхность после переноса или подключает новую к текущему ролику.
    // Освобождает предыдущий вывод только после переключения.
    override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
        val held = retainedTexture
        if (held != null) {
            retainedTexture = null
            movingPresentation = false
            if (held !== t) texture.setSurfaceTexture(held)
            return
        }
        movingPresentation = false
        if (coordinator?.currentMessageID == null) return
        val old = surface
        val next = Surface(t)
        surface = next
        coordinator.attach(next) { old?.release() }
    }

    // Принимает системное уведомление без новой работы. Размеры уже согласованы разметкой,
    // поэтому менять проигрыватель повторно не нужно.
    override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}

    // При переносе сохраняет поверхность для следующего экрана. При обычном закрытии отсоединяет
    // её и освобождает после остановки вывода.
    override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
        if (movingPresentation && surface != null) {
            retainedTexture = t
            return false
        }
        val old = surface
        surface = null
        if (old != null && coordinator != null) {
            coordinator.attach(null) {
                old.release()
                t.release()
            }
        } else {
            old?.release()
            t.release()
        }
        return false
    }

    // После появления готового кадра показывает видео и убирает картинку. До готовности сохраняет
    // прежний кадр, чтобы не оставлять пустой экран.
    override fun onSurfaceTextureUpdated(t: SurfaceTexture) {
        if (coordinator?.isReady == true) {
            hasVideoFrame = true
            setPoster(null)
        }
    }
}
