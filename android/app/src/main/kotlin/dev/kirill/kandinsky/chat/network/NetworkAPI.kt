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

class NetworkAPI(
    private val profile: NetworkProfile?,
    private val onRequestFinished: (() -> Unit)? = null,
) {
    private val client by lazy {
        OkHttpClient.Builder()
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
                            // Этот сигнал относится к работе соединения и не означает завершение
                            // генерации на сервере
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

    private fun request(path: String): Request.Builder {
        require(path.startsWith("/v1/"))
        return Request.Builder().url(NetworkProfile.ORIGIN + path).also { builder ->
            profile?.let { builder.header("Authorization", "Bearer " + it.token) }
        }
    }

    fun requestEmailCode(email: String, recovery: Boolean) {
        initialize()
        val path = if (recovery) "/v1/demo/password-reset/request" else "/v1/demo/register/request"
        json(path, JSONObject().put("email", email))
    }

    fun resetPassword(email: String, code: String, password: String) {
        initialize()
        val body = JSONObject().put("email", email).put("code", code).put("password", password)
        json("/v1/demo/password-reset/confirm", body)
    }

    fun signIn(email: String, password: String, registrationCode: String? = null): NetworkProfile {
        initialize()
        val path = if (registrationCode == null) "/v1/demo/login" else "/v1/demo/register"
        val body = JSONObject().put("email", email).put("password", password)
        registrationCode?.let { body.put("code", it) }
        return NetworkProfile.decode(json(path, body))
    }

    fun json(path: String, value: JSONObject? = null, method: String? = null): JSONObject {
        val builder = request(path)
        val body = value?.toString()?.toRequestBody("application/json".toMediaType())
        if (method != null) builder.method(method, body) else if (body != null) builder.post(body)
        return executeJSON(builder.build())
    }

    private fun executeJSON(request: Request, transfer: MediaCancellation? = null): JSONObject {
        val call = client.newCall(request)
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

    fun upload(
        file: File,
        mime: String,
        id: String,
        studioSource: Boolean = false,
        transfer: MediaCancellation? = null,
    ): JSONObject {
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
        if (studioSource) request.header("x-kandinsky-source", "1")
        val original = file.asRequestBody(mime.toMediaType())
        val body =
            if (transfer == null) original
            else
                object : RequestBody() {
                    override fun contentType() = original.contentType()

                    override fun contentLength() = size

                    override fun writeTo(sink: BufferedSink) {
                        var sent = 0L
                        var last = 0L
                        val counted =
                            object : ForwardingSink(sink) {
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
        return executeJSON(request.put(body).build(), transfer)
    }

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

    // Использует ту же сессию владельца, что и обычные запросы, чтобы команды и история относились
    // к одной учётной записи
    fun socket(listener: WebSocketListener): WebSocket =
        socketClient.newWebSocket(request("/v1/ws").build(), listener)

    // Отдельное чтение участков видео тоже прекращается, чтобы закрытая учётная запись больше не
    // продолжала
    // старые запросы.
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

    fun initialize() {
        client
        clientInitialized = true
    }
}
