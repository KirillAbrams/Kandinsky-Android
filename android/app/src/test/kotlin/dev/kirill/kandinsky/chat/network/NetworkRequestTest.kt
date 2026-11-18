// Проверяет запросы на уровне передачи, не обращаясь к серверу. Сверяет тело запроса и признаки,
// которыми сервер связывает загрузку с чатом или исходным файлом.

package dev.kirill.kandinsky.chat.network

import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NetworkRequestTest {
    private val profile = NetworkProfile(
        userID = 7,
        deviceID = "00000000-0000-4000-8000-000000000001",
        name = "fixture",
        token = "1".repeat(64),
        expiresAtMs = Long.MAX_VALUE,
    )

    // Останавливает запрос в перехватчике и возвращает его проверке. Если запрос не дошёл до
    // передачи, тест не принимает раннюю ошибку за нужный результат.
    private fun capture(action: (NetworkAPI) -> Unit): Request {
        val stopped = IOException("isolated request captured")
        var request: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            throw stopped
        }.build()
        try {
            action(NetworkAPI(profile, client))
            fail("Request did not reach the transport")
        } catch (error: IOException) {
            assertSame(stopped, error)
        }
        return requireNotNull(request)
    }

    // Сверяет пустое тело запроса записи и ключ входа. Запрос должен дойти до передачи, даже если
    // передаваемых полей нет.
    @Test fun postWithoutJsonBodyReachesTransport() {
        val request = capture {
            it.json("/v1/kandinsky/threads/27/sources/" + "2".repeat(32), method = "POST")
        }
        assertEquals("POST", request.method)
        assertNotNull(request.body)
        assertEquals(0L, request.body!!.contentLength())
        assertEquals("Bearer " + profile.token, request.header("Authorization"))
    }

    // Проверяет, что запрос удаления сохраняет отсутствие тела. Иначе общее правило для запросов
    // записи могло бы добавить лишние данные.
    @Test fun deleteKeepsAnAbsentBody() {
        val request = capture { it.json("/v1/kandinsky/threads/27", method = "DELETE") }
        assertEquals("DELETE", request.method)
        assertNull(request.body)
    }

    // Передаёт файл как исходник и сверяет чат, устройство, признак источника и байты. Без этих
    // сведений сервер не привяжет загрузку к нужной операции.
    @Test fun sourceUploadCarriesItsChatAndDevice() {
        val file = File.createTempFile("kandinsky-source-wire-", ".jpg")
        try {
            val bytes = "isolated transport fixture".toByteArray()
            file.writeBytes(bytes)
            val request = capture {
                it.upload(file, "image/jpeg", "3".repeat(32), studioSource = true, chat = 27)
            }
            assertEquals("PUT", request.method)
            assertEquals("1", request.header("x-kandinsky-source"))
            assertEquals("27", request.header("x-kandinsky-chat"))
            assertEquals(profile.deviceID, request.header("x-kandinsky-device"))
            val body = Buffer()
            request.body!!.writeTo(body)
            assertArrayEquals(bytes, body.readByteArray())
        } finally {
            file.delete()
        }
    }

    // Передаёт уменьшенную копию с номером исходного файла. Проверяет, что она не отмечена как
    // новый исходник.
    @Test fun previewUploadCarriesItsParent() {
        val file = File.createTempFile("kandinsky-preview-wire-", ".jpg")
        try {
            file.writeBytes("isolated preview fixture".toByteArray())
            val parent = "4".repeat(32)
            val request = capture {
                it.upload(file, "image/jpeg", "5".repeat(32), parent = parent)
            }
            assertEquals(parent, request.header("x-kandinsky-parent"))
            assertNull(request.header("x-kandinsky-source"))
        } finally {
            file.delete()
        }
    }
}
