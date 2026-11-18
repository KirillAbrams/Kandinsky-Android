package dev.kirill.kandinsky.chat.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class NetworkProfile(
    val userID: Long,
    val deviceID: String,
    val name: String,
    val token: String,
    val expiresAtMs: Long,
    val username: String? = null,
) {
    // Скрываем ключ сессии, чтобы он случайно не попал в журнал вместе с профилем.
    override fun toString() = "NetworkProfile(userID=$userID, credentials=redacted)"

    companion object {
        const val ORIGIN = dev.kirill.kandinsky.chat.BuildConfig.KANDINSKY_BRIDGE_ORIGIN

        fun decode(json: JSONObject) =
            NetworkProfile(
                    json.getLong("userId"),
                    json.getString("deviceId"),
                    json.getString("name"),
                    json.getString("token"),
                    json.getLong("expiresAtMs"),
                    json.optString("username").takeUnless { it.isBlank() || it == "null" },
                )
                .also { require(it.userID > 0 && it.token.matches(Regex("[a-f0-9]{64}"))) }
    }

    fun encode() =
        JSONObject()
            .put("userId", userID)
            .put("deviceId", deviceID)
            .put("name", name)
            .put("token", token)
            .put("expiresAtMs", expiresAtMs)
            .also { json -> username?.let { json.put("username", it) } }
}

class NetworkCredentials(context: Context) {
    private val root = context.noBackupFilesDir
    private val navigation = context.getSharedPreferences("studio.navigation", Context.MODE_PRIVATE)

    fun selectedChat(user: Long): Long? = navigation.getLong("chat:$user", -1).takeIf { it > 0 }

    fun selectChat(user: Long, chat: Long) {
        navigation.edit().putLong("chat:$user", chat).apply()
    }

    private val namespace = context.packageName
    private val file = AtomicFile(File(root, "kandinsky-session-v1"))
    private val accounts = AtomicFile(File(root, "kandinsky-accounts-v1"))
    private val alias = namespace + ".kandinsky.session.v1"

    private companion object {
        val gate = Any()
    }

    // Уже перенесённые записи пропускает, поэтому прерванный переход можно повторить.
    private fun upgradeRecords() {
        for ((target, suffix) in listOf(file to "-session-v1", accounts to "-accounts-v1")) {
            val candidates =
                (root.listFiles() ?: emptyArray())
                    .filter {
                        it.isFile &&
                            !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                            (it.name.endsWith(suffix) || it.name.endsWith(suffix + ".bak"))
                    }
                    .map { File(root, it.name.removeSuffix(".bak")) }
                    .filter { it.name != target.baseFile.name }
                    .distinctBy { it.path }
            require(candidates.size <= 1) { "Найдено несколько прежних записей входа" }
            val source = candidates.singleOrNull()?.let(::AtomicFile) ?: continue
            val current = read(target)
            val text = current ?: read(source) ?: continue
            if (target === file) {
                NetworkProfile.decode(JSONObject(text))
            } else {
                val values = org.json.JSONArray(text)
                require(values.length() <= 8)
                for (index in 0 until values.length()) NetworkProfile.decode(
                    values.getJSONObject(index)
                )
            }
            if (current == null) {
                val bytes = source.openRead().use { it.readBytes() }
                val output = target.startWrite()
                try {
                    output.write(bytes)
                    target.finishWrite(output)
                } catch (error: Throwable) {
                    target.failWrite(output)
                    throw error
                }
            }
            check(read(target) == text) { "Не удалось проверить перенесённую запись входа" }
            source.delete()
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val prefix = namespace + "."
        val existing =
            store.aliases().toList().filter { name ->
                name.startsWith(prefix) &&
                    name.removePrefix(prefix).matches(Regex("^[^.]+\\.session\\.v1$"))
            }
        require(existing.size <= 1) { "Найдено несколько ключей сохранённого входа" }
        existing.singleOrNull()?.let { name ->
            return store.getKey(name, null) as? SecretKey
                ?: throw IllegalStateException("Сохранённый ключ входа недоступен")
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    private fun read(source: AtomicFile): String? {
        if (!source.baseFile.exists() && !File(source.baseFile.path + ".bak").exists()) return null
        val bytes =
            source.openRead().use { input ->
                require(input.available() <= 65536)
                input.readBytes()
            }
        require(bytes.size > 29 && bytes[0].toInt() == 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
    }

    private fun write(target: AtomicFile, text: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (error: Throwable) {
            target.failWrite(output)
            throw error
        }
    }

    fun load(): NetworkProfile? =
        synchronized(gate) {
            upgradeRecords()
            read(file)?.let { NetworkProfile.decode(JSONObject(it)) }
        }

    private fun remembered(): List<NetworkProfile> {
        val array = read(accounts)?.let { org.json.JSONArray(it) } ?: return emptyList()
        require(array.length() <= 8)
        return (0 until array.length()).map { NetworkProfile.decode(array.getJSONObject(it)) }
    }

    fun profiles(): List<NetworkProfile> =
        synchronized(gate) {
            val current = load()
            val values = remembered()
            if (current == null) values
            else listOf(current) + values.filter { it.userID != current.userID }
        }

    fun save(profile: NetworkProfile) =
        synchronized(gate) {
            val prior = load()
            val values =
                (listOf(profile) + listOfNotNull(prior) + remembered())
                    .distinctBy { it.userID }
                    .take(8)
            write(
                accounts,
                org.json.JSONArray().apply { values.forEach { put(it.encode()) } }.toString(),
            )
            write(file, profile.encode().toString())
        }

    fun removeActive() =
        synchronized(gate) {
            val current = load() ?: return@synchronized
            removeAccount(current.userID)
        }

    fun forget(userID: Long) =
        synchronized(gate) {
            upgradeRecords()
            removeAccount(userID)
        }

    private fun removeAccount(userID: Long) {
        navigation.edit().remove("chat:$userID").apply()
        val remaining = remembered().filter { it.userID != userID }
        write(
            accounts,
            org.json.JSONArray().apply { remaining.forEach { put(it.encode()) } }.toString(),
        )
        if (load()?.userID == userID) {
            file.delete()
            check(load()?.userID != userID)
        }
    }
}

internal object LocalAccountErasure {
    private fun root(context: Context) =
        dev.kirill.kandinsky.chat.storage.AppDataRoot.resolve(context.filesDir)

    // Сама функция не создаёт её, чтобы обычная проверка наличия очереди не меняла файловую систему
    private fun queue(context: Context) = File(root(context), "erased-accounts")

    @Synchronized
    fun stage(context: Context, userID: Long) {
        require(userID > 0)
        val queue = queue(context)
        check(queue.isDirectory || queue.mkdirs())
        File(queue, userID.toString()).createNewFile()
    }

    // Снимаем отметку после полной очистки. Если приложение закроется раньше, продолжим при
    // запуске.
    @Synchronized
    fun finish(context: Context, credentials: NetworkCredentials) {
        val queue = queue(context)
        if (!queue.exists()) return
        for (marker in checkNotNull(queue.listFiles())) {
            val id = marker.name.toLongOrNull() ?: continue
            if (id <= 0 || marker.name != id.toString()) continue
            credentials.forget(id)
            val namespace = "net-v1-u$id"
            removePrivateTree(File(root(context), namespace))
            removePrivateTree(File(context.cacheDir, "kandinsky-share/$namespace"))
            java.nio.file.Files.deleteIfExists(marker.toPath())
        }
    }

    // Обходит дерево без следования ссылкам и удаляет записи от самых вложенных к корню, освобождая
    // только выбранный каталог
    private fun removePrivateTree(directory: File) {
        val path = directory.toPath()
        if (!java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        java.nio.file.Files.walk(path).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach {
                java.nio.file.Files.deleteIfExists(it)
            }
        }
    }
}
