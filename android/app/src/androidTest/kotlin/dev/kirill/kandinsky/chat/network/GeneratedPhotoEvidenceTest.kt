// Сверяем четыре уже скачанные фотографии с ожидаемыми серверными оригиналами у указанного
// проверочного аккаунта.

package dev.kirill.kandinsky.chat.network

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.storage.AppDataRoot
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class GeneratedPhotoEvidenceTest {
    // Сверяем владельца и читаем скачанные после просмотра файлы. Контрольная сумма и размеры
    // каждого фото подтверждают, что сохранился исходный файл в полном размере.
    @Test
    fun downloadedPhotosMatchAllFourServerOriginals() {
        val arguments = InstrumentationRegistry.getArguments()
        val owner = arguments.getString("expected-user-id")
        val expected = arguments.getString("expected-photos")
        assumeNotNull(owner, expected)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = checkNotNull(NetworkCredentials(context).load())
        assertEquals(owner!!.toLong(), profile.userID)
        val root = AppDataRoot.resolve(context.filesDir)
        val directory = File(root, "net-v1-u${profile.userID}/media/remote")
        val photos = JSONArray(expected)
        assertEquals(4, photos.length())
        repeat(photos.length()) { index ->
            val photo = photos.getJSONObject(index)
            val blob = photo.getString("blob")
            require(blob.matches(Regex("[a-f0-9]{32}")))
            val file = File(directory, "$blob.png")
            assertTrue("Original photo $index must be downloaded", file.isFile)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals(
                "Original photo $index must match server bytes",
                photo.getString("sha256"),
                actual,
            )
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, dimensions)
            assertEquals(photo.getInt("width"), dimensions.outWidth)
            assertEquals(photo.getInt("height"), dimensions.outHeight)
        }
    }
}
