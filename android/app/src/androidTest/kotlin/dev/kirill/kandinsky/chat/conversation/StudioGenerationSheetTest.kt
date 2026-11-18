package dev.kirill.kandinsky.chat.conversation

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.attachments.AttachmentSource
import dev.kirill.kandinsky.chat.domain.KandinskyOptions
import dev.kirill.kandinsky.chat.domain.StudioCapabilities
import dev.kirill.kandinsky.chat.domain.StudioChoice
import dev.kirill.kandinsky.chat.domain.StudioGuidance
import dev.kirill.kandinsky.chat.domain.StudioModel
import dev.kirill.kandinsky.chat.domain.StudioUpload
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StudioGenerationSheetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: Activity

    @Before
    fun openIsolatedActivity() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        check(NetworkCredentials(context).load() == null)
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        activity = instrumentation.startActivitySync(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        instrumentation.waitForIdleSync()
    }

    @After
    fun closeActivity() {
        if (::activity.isInitialized) onMain { activity.finish() }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun show(open: () -> Unit): View {
        val before = WindowInspector.getGlobalWindowViews().toSet()
        open()
        return WindowInspector.getGlobalWindowViews().single { it !in before }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun View.text(label: String): TextView =
        descendants(this).filterIsInstance<TextView>().single { it.text.toString() == label && it.isShown }

    private fun View.control(label: String): View =
        descendants(this).single { it.contentDescription == label && it.isShown }

    private fun click(view: View) = assertTrue("У элемента должно быть действие", view.performClick())

    @Test
    fun advancedSettingsValidateSeedAndKeepSelectedDimensionsCountAndGuidance() {
        val options = KandinskyOptions(model = "video", aspect = "5:3", seconds = 3, count = 2)
        val capabilities = StudioCapabilities(
            quality = listOf(StudioChoice("standard", "Обычное"), StudioChoice("draft", "Черновое")),
            resolutions = listOf(
                StudioChoice("standard", "Стандартный", mapOf("5:3" to listOf(800, 480), "3:5" to listOf(480, 800))),
                StudioChoice("compact", "Компактный", mapOf("5:3" to listOf(640, 384), "3:5" to listOf(384, 640))),
            ),
            aspects = listOf("5:3", "3:5"),
            durations = listOf(1, 3, 5),
            maxCount = 4,
            negativePrompt = true,
            guidance = StudioGuidance(1.0, 8.0, 4.0),
        )
        val counts = mutableListOf<Int>()
        val submitted = mutableListOf<KandinskyOptions>()
        lateinit var window: View
        onMain {
            window = show {
                StudioGenerationSheet(activity, options).showSettings(capabilities, counts::add, submitted::add)
            }
            assertTrue(window.text("5:3").isSelected)
            assertTrue(window.text("800 × 480").isSelected)
            click(window.text("3:5"))
            assertTrue(window.text("480 × 800").isSelected)
            click(window.text("384 × 640"))
            click(window.text("Черновое"))
            click(window.text("1 с"))
            click(window.text("4"))
            click(window.text("Дополнительно"))
        }
        instrumentation.waitForIdleSync()
        onMain {
            val negative = window.control("Что исключить") as EditText
            val seed = window.control("Seed") as EditText
            val slider = window.control("Следование описанию") as SeekBar
            assertTrue(slider.width > slider.paddingLeft + slider.paddingRight)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val touch = MotionEvent.obtain(
                    now, now, action, (slider.width - slider.paddingRight).toFloat(),
                    slider.height / 2f, 0,
                )
                try { assertTrue(slider.onTouchEvent(touch)) } finally { touch.recycle() }
            }
            val limit = "a".repeat(1999) + "🤝🏽"
            negative.setText(limit + "z")
            assertEquals(limit, negative.text.toString())
            negative.text.append("z")
            assertEquals(limit, negative.text.toString())
            negative.text.delete(1999, negative.text.length)
            assertEquals("a".repeat(1999), negative.text.toString())
            negative.setText("  дым  ")
            seed.setText("2147483648")
            click(window.text("Готово"))
            assertTrue(submitted.isEmpty())
            assertTrue(window.text("Seed: число от 0 до 2147483647").isShown)
            seed.setText("2147483647")
            assertTrue(seed.requestFocus())
            assertTrue(seed.performClick())
        }
        instrumentation.waitForIdleSync()
        onMain {
            val seed = window.control("Seed") as EditText
            assertTrue(seed.hasFocus())
            click(window.text("Дополнительно"))
            assertFalse(seed.isShown)
            assertFalse(seed.hasFocus())
            click(window.text("Готово"))
            assertEquals(listOf(4), counts)
            assertEquals(
                listOf(KandinskyOptions("video", "3:5", 1, "draft", "compact", 4, Int.MAX_VALUE, "дым", 8.0)),
                submitted,
            )
        }
    }

    @Test
    fun longQualityLabelsWrapInsideThePanelAndCanStillBeSelected() {
        val first = "Быстрая генерация для проверки композиции и основных деталей изображения"
        val second = "Подробная генерация для печати и просмотра результата в полном размере"
        val capabilities = StudioCapabilities(
            listOf(StudioChoice("draft", first), StudioChoice("standard", second)),
            emptyList(), emptyList(), emptyList(), 1, negativePrompt = false, guidance = null,
        )
        val submitted = mutableListOf<KandinskyOptions>()
        lateinit var window: View
        onMain {
            window = show {
                StudioGenerationSheet(activity, KandinskyOptions())
                    .showSettings(capabilities, {}, submitted::add)
            }
        }
        instrumentation.waitForIdleSync()
        onMain {
            val draft = window.text(first)
            val standard = window.text(second)
            val row = draft.parent as ViewGroup
            assertSame(row, standard.parent)
            assertTrue(draft.bottom <= standard.top)
            assertEquals(draft.left, standard.left)
            assertTrue(draft.right <= row.width && standard.right <= row.width)
            assertTrue(standard.isSelected)
            click(draft)
            assertTrue(draft.isSelected)
            click(window.text("Готово"))
            assertEquals("draft", submitted.single().quality)
        }
    }

    @Test
    fun shortGripDragKeepsSettingsOpenAndDownwardSwipeDiscardsChanges() {
        val submitted = mutableListOf<KandinskyOptions>()
        val capabilities = StudioCapabilities(
            emptyList(), emptyList(), emptyList(), emptyList(), 2, negativePrompt = false, guidance = null,
        )
        lateinit var window: View
        onMain {
            window = show {
                StudioGenerationSheet(activity, KandinskyOptions())
                    .showSettings(capabilities, {}, submitted::add)
            }
            click(window.text("2"))
        }
        instrumentation.waitForIdleSync()
        onMain {
            val grip = descendants(window).filterIsInstance<FrameLayout>().single {
                it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO &&
                    it.isClickable && it.childCount == 1
            }
            fun drag(distance: Float) {
                val now = SystemClock.uptimeMillis()
                val start = grip.height / 4f
                val points = listOf(
                    MotionEvent.ACTION_DOWN to start,
                    MotionEvent.ACTION_MOVE to start + distance / 2,
                    MotionEvent.ACTION_UP to start + distance,
                )
                for ((action, y) in points) {
                    val touch = MotionEvent.obtain(now, now, action, grip.width / 2f, y, 0)
                    try {
                        assertTrue(grip.dispatchTouchEvent(touch))
                    } finally {
                        touch.recycle()
                    }
                }
            }
            drag(grip.height / 8f)
            assertTrue(window.text("2").isSelected)
            drag(grip.height * 2f / 3)
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertFalse(WindowInspector.getGlobalWindowViews().contains(window))
            assertTrue(submitted.isEmpty())
        }
    }

    @Test
    fun savedOptionsAreClampedWhenTheModelStopsOfferingThem() {
        val original = KandinskyOptions(
            model = "video_sr", aspect = "3:5", seconds = 3, quality = "draft",
            resolution = "compact", count = 4, seed = 12, negativePrompt = "дым", guidanceScale = 6.0,
        )
        val supported = StudioCapabilities(
            emptyList(), emptyList(), emptyList(), emptyList(), 1, negativePrompt = false, guidance = null,
        )
        val submitted = mutableListOf<KandinskyOptions>()
        onMain {
            val window = show { StudioGenerationSheet(activity, original).showSettings(supported, {}, submitted::add) }
            assertTrue(window.text("1").isSelected)
            click(window.text("Дополнительно"))
            assertFalse(descendants(window).any { it.contentDescription == "Что исключить" })
            assertFalse(descendants(window).any { it.contentDescription == "Следование описанию" })
            (window.control("Seed") as EditText).setText("")
            click(window.text("Готово"))
            assertEquals(
                listOf(KandinskyOptions("video_sr", quality = "standard", resolution = "standard", count = 1)),
                submitted,
            )
        }
    }

    @Test
    fun emptyCatalogueExplainsFailureAndLoadedModelsReturnTheChosenEntry() {
        val selected = mutableListOf<StudioModel>()
        val image = StudioModel("image", "Изображение", "По описанию", null, null)
        val video = StudioModel("video", "Видео", "По фото", null, null)
        onMain {
            val empty = show { StudioGenerationSheet(activity, KandinskyOptions()).showModels(emptyList(), selected::add) }
            assertTrue(empty.text("Не удалось загрузить модели").isShown)
            assertTrue(selected.isEmpty())
            empty.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            empty.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
            val catalogue = show {
                StudioGenerationSheet(activity, KandinskyOptions()).showModels(listOf(image, video), selected::add)
            }
            assertTrue(catalogue.control("Изображение, По описанию").isSelected)
            assertFalse(catalogue.control("Видео, По фото").isSelected)
            assertEquals(
                "android.widget.Button",
                catalogue.control("Видео, По фото").createAccessibilityNodeInfo().className,
            )
            click(catalogue.control("Видео, По фото"))
            assertEquals(listOf(video), selected)
        }
    }

    @Test
    fun attachmentOptionsAndRecentUploadsReturnTheirOwnValues() {
        val sources = mutableListOf<AttachmentSource>()
        val uploads = (0..5).map { index ->
            StudioUpload("fixture-$index", if (index % 2 == 0) "video/mp4" else "image/jpeg", 101)
        }
        val picked = mutableListOf<StudioUpload>()
        onMain {
            for (label in listOf(
                "Оживить фото из галереи, Видео из фотографии",
                "Снять фото",
                "Загрузить и посмотреть видео, MP4 до 20 МБ и 60 секунд",
            )) {
                val window = show { StudioGenerationSheet(activity, KandinskyOptions()).showAttachments(sources::add) }
                click(window.control(label))
            }
            assertEquals(listOf(AttachmentSource.PHOTO, AttachmentSource.CAMERA, AttachmentSource.VIDEO), sources)
            val sheet = StudioGenerationSheet(activity, KandinskyOptions())
            sheet.appendRecentUploads(uploads, picked::add)
            val window = show { sheet.showAttachments { fail("Выбираем недавнюю загрузку") } }
            sheet.appendRecentUploads(emptyList(), picked::add)
            assertFalse(descendants(window).any { it is TextView && it.text == "Недавние загрузки" })
            sheet.appendRecentUploads(uploads, picked::add)
            val recent = descendants(window).filter { it.isClickable && it.contentDescription in uploads.map(StudioUpload::title) }.toList()
            assertEquals(5, recent.size)
            assertEquals(uploads.take(5).map(StudioUpload::title), recent.map { it.contentDescription.toString() })
            click(recent[1])
            assertEquals(listOf(uploads[1]), picked)
        }
    }
}
