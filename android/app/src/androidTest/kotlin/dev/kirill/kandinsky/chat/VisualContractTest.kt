package dev.kirill.kandinsky.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualContractTest {
    @Test
    fun storedAudioKeepsCaptionWithoutVoiceControls() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val attachment =
                dev.kirill.kandinsky.chat.domain.Attachment(
                    "old-audio",
                    "old/audio.m4a",
                    "audio/mp4",
                    147046,
                    0,
                    0,
                    1,
                    7000,
                    "",
                    waveform = List(64) { 255 },
                )
            val row =
                dev.kirill.kandinsky.chat.domain.Message(
                    901,
                    2,
                    dev.kirill.kandinsky.chat.domain.OrderKey(1, 901),
                    dev.kirill.kandinsky.chat.domain.MessageDirection.INCOMING,
                    0,
                    dev.kirill.kandinsky.chat.domain.MessageKind.VOICE,
                    "",
                    "Сохранённая подпись",
                    null,
                    1,
                    listOf(attachment),
                )
            val prepared =
                dev.kirill.kandinsky.chat.rendering.PreparedText(
                    row,
                    dev.kirill.kandinsky.chat.rendering.TextLayoutKey(901, 1, 300, 17, 1, 1f),
                )
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    kotlin.math.ceil(prepared.width).toInt(),
                    kotlin.math.ceil(prepared.height).toInt(),
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            try {
                assertEquals("◉ Голосовое сообщение\nСохранённая подпись", prepared.text)
                assertEquals(
                    10f,
                    prepared.accessibleLines(0f, prepared.height, 20).first().frame.left,
                    0.1f,
                )
                assertFalse(prepared.text.contains("00:07"))
                prepared.draw(0, android.graphics.Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertFalse(
                    pixels.any {
                        android.graphics.Color.red(it) < 60 &&
                            android.graphics.Color.green(it) in 91..179 &&
                            android.graphics.Color.blue(it) > 220 &&
                            android.graphics.Color.alpha(it) > 200
                    }
                )
                assertEquals(List(64) { 255 }, row.attachments.first().waveform)
            } finally {
                bitmap.recycle()
                prepared.close()
            }
        }
    }

    @Test
    fun generationArtworkRemainsInApplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val images =
            listOf(
                R.drawable.kandinsky_attachment_camera,
                R.drawable.kandinsky_attachment_photo,
                R.drawable.kandinsky_drawer_add,
                R.drawable.kandinsky_drawer_archive,
                R.drawable.kandinsky_drawer_back,
                R.drawable.kandinsky_drawer_logout,
                R.drawable.kandinsky_drawer_menu,
                R.drawable.kandinsky_drawer_pin,
                R.drawable.kandinsky_drawer_search,
                R.drawable.kandinsky_evolution,
                R.drawable.kandinsky_generation_refresh,
                R.drawable.kandinsky_model_check,
                R.drawable.kandinsky_model_image,
                R.drawable.kandinsky_model_video,
                R.drawable.kandinsky_recent_video,
                R.drawable.kandinsky_result_expand,
                R.drawable.kandinsky_result_save,
                R.drawable.kandinsky_result_share,
                R.drawable.kandinsky_upload_close,
            )
        for (id in images) {
            val bitmap =
                checkNotNull(android.graphics.BitmapFactory.decodeResource(context.resources, id))
            try {
                assertTrue(bitmap.width > 0)
                assertTrue(bitmap.height > 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    // Подготовленная история не должна находиться в обычном приложении, но остаётся доступной
    // проверкам.
    @Test
    fun fixtureFilesBelongOnlyToTestPackage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertFalse(instrumentation.targetContext.assets.list("")!!.contains("manifest.json"))
        val assets = dev.kirill.kandinsky.chat.support.TestFixtures.assets
        assertTrue(assets.list("")!!.contains("manifest.json"))
        assertTrue(assets.open("media/photo-000.jpg").use { it.readBytes() }.isNotEmpty())
    }

    @Test
    fun iconPaintStyleDoesNotDependOnPreviousGlyph() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun snapshot(icon: dev.kirill.kandinsky.chat.rendering.IconID): IntArray {
                val image =
                    android.graphics.Bitmap.createBitmap(
                        24,
                        24,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                try {
                    image.eraseColor(android.graphics.Color.WHITE)
                    dev.kirill.kandinsky.chat.rendering.KandinskyIconPaths.draw(
                        android.graphics.Canvas(image),
                        icon,
                        android.graphics.RectF(0f, 0f, 24f, 24f),
                        android.graphics.Color.BLACK,
                    )
                    return IntArray(24 * 24).also { image.getPixels(it, 0, 24, 0, 0, 24, 24) }
                } finally {
                    image.recycle()
                }
            }
            for (icon in
                listOf(
                    dev.kirill.kandinsky.chat.rendering.IconID.PLUS,
                    dev.kirill.kandinsky.chat.rendering.IconID.SEND,
                    dev.kirill.kandinsky.chat.rendering.IconID.COMPOSE,
                    dev.kirill.kandinsky.chat.rendering.IconID.TUNE,
                )) {
                val before = snapshot(icon)
                snapshot(dev.kirill.kandinsky.chat.rendering.IconID.MENU)
                assertArrayEquals(
                    "Заливка значка меню не должна менять отрисовку $icon",
                    before,
                    snapshot(icon),
                )
            }
        }
    }
}
