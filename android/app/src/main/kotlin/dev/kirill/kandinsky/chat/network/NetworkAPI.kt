// Выполняет сетевые запросы выбранного профиля, передаёт изображения и видео и открывает
// постоянное соединение. Ограничения ответов и проверка файлов помогают сохранить память и
// целостность данных.

package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.media.MediaCancellation
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject

class NetworkFailure(val status: Int, val code: String) :
    IOException("Network request failed ($status, $code)")

// Основное создание собирает профиль и необязательные подмены сети для обычной работы и проверок.
class NetworkAPI private constructor(
    private val profile: NetworkProfile?,
    private val onRequestFinished: (() -> Unit)?,
    private val configuredClient: OkHttpClient?,
    private val configuredSockets: WebSocket.Factory?,
) {
    // Создаёт обычный сетевой клиент с профилем и отметкой о законченном запросе. Связь готовится
    // по мере первого использования.
    constructor(profile: NetworkProfile?, onRequestFinished: (() -> Unit)? = null) :
        this(profile, onRequestFinished, null, null)

    // Подставляет заданный клиент и способ открыть постоянную связь. Это позволяет проверить
    // запросы без рабочего сервера.
    internal constructor(
        profile: NetworkProfile?,
        client: OkHttpClient,
        sockets: WebSocket.Factory? = null,
    ) : this(profile, null, client, sockets)

    private val client by lazy {
        configuredClient ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(3, 60, TimeUnit.SECONDS))
            .also { builder ->
                onRequestFinished?.let { finished ->
                    builder.eventListener(
                        object : EventListener() {
                            // Сообщает о завершении работы соединения. Ответ от сервера может
                            // прийти раньше окончания генерации, поэтому сигнал лишь учитывает
                            // сетевую передачу.
                            override fun callEnd(call: Call) {
                                finished()
                            }
                        }
                    )
                }
            }
            .build()
    }
    private val socketClient by lazy {
        client.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
    }
    private val sourceClient by lazy {
        client
            .newBuilder()
            .callTimeout(1, TimeUnit.HOURS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    // Добавляет путь службы к адресу из сборки и прикладывает ключ профиля. Проверка начала пути
    // защищает от ошибочной отправки по произвольному адресу.
    private fun request(path: String): Request.Builder {
        require(path.startsWith("/v1/"))
        return Request.Builder().url(NetworkProfile.ORIGIN + path).also { builder ->
            profile?.let { builder.header("Authorization", "Bearer " + it.token) }
        }
    }

    // Выбирает обработку регистрации или восстановления и передаёт адресу сервера почту. Это не
    // смешивает коды двух разных действий.
    fun requestEmailCode(email: String, recovery: Boolean) {
        initialize()
        val path = if (recovery) "/v1/demo/password-reset/request" else "/v1/demo/register/request"
        json(path, JSONObject().put("email", email))
    }

    // Отправляет адрес, код и новый пароль, чтобы сервер подтвердил смену. Неудачный ответ
    // возвращается вызывающей стороне.
    fun resetPassword(email: String, code: String, password: String) {
        initialize()
        val body = JSONObject().put("email", email).put("code", code).put("password", password)
        json("/v1/demo/password-reset/confirm", body)
    }

    // Использует почту и пароль, а при наличии кода завершает регистрацию. Возвращает разобранный
    // профиль, который можно сохранить для следующего запуска.
    fun signIn(email: String, password: String, registrationCode: String? = null): NetworkProfile {
        initialize()
        val path = if (registrationCode == null) "/v1/demo/login" else "/v1/demo/register"
        val body = JSONObject().put("email", email).put("password", password)
        registrationCode?.let { body.put("code", it) }
        return NetworkProfile.decode(json(path, body))
    }

    // Собирает метод и тело запроса, даже если выбранное действие передаёт сведения только в
    // адресе. Общий разбор ответа проверяет границы и ошибки.
    fun json(path: String, value: JSONObject? = null, method: String? = null): JSONObject {
        val builder = request(path)
        val body = value?.toString()?.toRequestBody("application/json".toMediaType())
        // Библиотека требует тело для отправки запроса. Номер уже передан в адресе.
        val requestBody = if (method == "POST" && body == null) ByteArray(0).toRequestBody() else body
        if (method != null) builder.method(method, requestBody) else if (body != null) builder.post(body)
        return executeJSON(builder.build())
    }

    // Связывает отмену с запросом, читает ограниченный ответ порциями и проверяет состояние.
    // Слишком большой ответ прерывается, чтобы не занять память целиком.
    private fun executeJSON(
        request: Request,
        transfer: MediaCancellation? = null,
        transport: OkHttpClient = client,
    ): JSONObject {
        val call = transport.newCall(request)
        transfer?.onCancel { call.cancel() }
        call.execute().use { response ->
            val body = response.body ?: throw IOException("Empty response")
            val bytes =
                body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 2 * 1024 * 1024)
                            throw IOException("Response exceeds budget")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            if (bytes.size > 2 * 1024 * 1024) throw IOException("Response exceeds budget")
            if (response.code == 204) return JSONObject()
            val value = JSONObject(String(bytes, Charsets.UTF_8))
            if (!response.isSuccessful)
                throw NetworkFailure(
                    response.code,
                    value.optJSONObject("error")?.optString("code") ?: "http_error",
                )
            return value
        }
    }

    // Проверяет размер и считает контрольную сумму файла небольшими порциями. Передаёт номер
    // чата, исходника или устройства по необходимости. Для большого исходника выбирает соединение
    // с более долгим ожиданием.
    fun upload(
        file: File,
        mime: String,
        id: String,
        studioSource: Boolean = false,
        transfer: MediaCancellation? = null,
        chat: Long? = null,
        parent: String? = null,
    ): JSONObject {
        initialize()
        val size = file.length()
        require(size in 1..67108864L)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val request = request("/v1/uploads/$id").header("x-content-sha256", hash)
        chat?.let { request.header("x-kandinsky-chat", it.toString()) }
        parent?.let { request.header("x-kandinsky-parent", it) }
        if (studioSource) {
            request.header("x-kandinsky-source", "1")
            profile?.let { request.header("x-kandinsky-device", it.deviceID) }
        }
        val original = file.asRequestBody(mime.toMediaType())
        val body =
            if (transfer == null) original
            else
                object : RequestBody() {
                    // Берёт тип из исходного файла, который оборачивает счётчик отправки. Сервер
                    // получает тот же тип, что и при отправке без подсчёта.
                    override fun contentType() = original.contentType()

                    // Сообщает известное число байтов файла. По нему сервер проверяет полноту и
                    // может показать ход передачи.
                    override fun contentLength() = size

                    // Передаёт исходные байты через счётчик и сбрасывает его буфер в конце. Иначе
                    // последняя порция могла бы не дойти после возврата из записи.
                    override fun writeTo(sink: BufferedSink) {
                        var sent = 0L
                        var last = 0L
                        val counted =
                            object : ForwardingSink(sink) {
                                    // Записывает очередную порцию и добавляет её длину к
                                    // счётчику. Сообщает долю с ограничением частоты и при
                                    // завершении, чтобы не обновлять экран на каждом блоке.
                                    override fun write(source: Buffer, byteCount: Long) {
                                        super.write(source, byteCount)
                                        sent += byteCount
                                        val now = System.nanoTime()
                                        if (now - last >= 80_000_000 || sent == size) {
                                            last = now
                                            transfer.reportProgress(sent.toDouble() / size)
                                        }
                                    }
                                }
                                .buffer()
                        original.writeTo(counted)
                        counted.emit()
                    }
                }
        return executeJSON(
            request.put(body).build(),
            transfer,
            if (studioSource) sourceClient else client,
        )
    }

    // Берёт готовый файл или пишет во временное место, пока проверяет длину и контрольную сумму.
    // Отмена или отказ удаляют временный файл, а готовый появляется по постоянному пути только
    // после проверки.
    fun download(
        blob: String,
        destination: File,
        limit: Long,
        token: dev.kirill.kandinsky.chat.media.MediaCancellation,
    ) {
        require(blob.matches(Regex("[a-f0-9]{32}|[a-f0-9]{64}")) && limit in 1..67108864L)
        if (destination.isFile) {
            destination.setLastModified(System.currentTimeMillis())
            return
        }
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            val call = client.newCall(request("/v1/media/$blob").build())
            token.onCancel { call.cancel() }
            if (token.isCancelled) throw IOException("Cancelled media")
            token.reportProgress(0.0)
            call.execute().use { response ->
                if (!response.isSuccessful) throw NetworkFailure(response.code, "media_download")
                val body = response.body ?: throw IOException("Empty media")
                if (body.contentLength() > limit) throw IOException("Media exceeds budget")
                var size = 0L
                var lastProgress = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > limit) throw IOException("Media exceeds budget")
                            output.write(buffer, 0, count)
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastProgress >= 80 || size == body.contentLength()) {
                                lastProgress = now
                                token.reportProgress(
                                    size.toDouble() / maxOf(1, body.contentLength())
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (body.contentLength() >= 0 && size != body.contentLength())
                    throw IOException("Incomplete media")
                if (blob.length == 64) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                    }
                    require(digest.digest().joinToString("") { "%02x".format(it) } == blob)
                }
                if (!temporary.renameTo(destination)) throw IOException("Cannot commit media")
            }
        } finally {
            temporary.delete()
        }
    }

    // Проверяет границы и читает только указанный отрезок без сжатия. Сверяет ответ и точное
    // число байтов, чтобы проигрыватель не получил полный файл или обрыв вместо части.
    fun range(
        blob: String,
        start: Long,
        end: Long,
        length: Long,
        token: dev.kirill.kandinsky.chat.media.MediaCancellation,
    ): ByteArray {
        require(
            blob.matches(Regex("[a-f0-9]{32}|[a-f0-9]{64}")) &&
                start >= 0 &&
                end >= start &&
                end < length &&
                end - start < dev.kirill.kandinsky.chat.media.MediaRangeCache.CHUNK_SIZE
        )
        val expected = (end - start + 1).toInt()
        val call =
            client.newCall(
                request("/v1/media/$blob")
                    .header("Range", "bytes=$start-$end")
                    .header("Accept-Encoding", "identity")
                    .build()
            )
        token.onCancel { call.cancel() }
        if (token.isCancelled) throw IOException("Cancelled range")
        call.execute().use { response ->
            val body = response.body ?: throw IOException("Empty range")
            if (
                response.code != 206 ||
                    response.header("Content-Range") != "bytes $start-$end/$length" ||
                    body.contentLength() >= 0 && body.contentLength() != expected.toLong()
            )
                throw IOException("Invalid media range")
            val output = java.io.ByteArrayOutputStream(expected)
            val buffer = ByteArray(16384)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > expected) throw IOException("Range exceeds budget")
                    output.write(buffer, 0, n)
                }
            }
            if (output.size() != expected || token.isCancelled)
                throw IOException("Incomplete range")
            return output.toByteArray()
        }
    }

    // Открывает постоянное соединение от того же владельца, что и обычные запросы. Оно получает
    // изменения именно для текущего сеанса.
    fun socket(listener: WebSocketListener): WebSocket =
        (configuredSockets ?: socketClient).newWebSocket(request("/v1/ws").build(), listener)

    // Останавливает запросы, освобождает соединения и закрывает очередь, если клиент уже создан.
    // Это прекращает и незавершённое чтение медиа.
    fun close() {
        if (clientInitialized) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    // Плановое создание соединений выполняется в рабочем потоке сети
    // При закрытии признак позволяет не создавать ещё не использованный клиент только ради его
    // остановки
    private var clientInitialized = false

    // Готовит клиент в рабочем потоке и помечает его созданным. Позднее закрытие не поднимет
    // новый клиент только ради его остановки.
    fun initialize() {
        client
        clientInitialized = true
    }
}
