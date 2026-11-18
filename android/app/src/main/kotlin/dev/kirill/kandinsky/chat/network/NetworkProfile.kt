// Сохраняет активный вход и запомненные аккаунты в зашифрованных файлах вне резервной копии.
// Ведёт отметки об очистке, чтобы удалить данные выбранного владельца даже после перезапуска.

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
import androidx.core.content.edit

class NetworkProfile(
    val userID: Long,
    val deviceID: String,
    val name: String,
    val token: String,
    val expiresAtMs: Long,
    val username: String? = null,
) {
    // Возвращает для журнала номер владельца без ключа входа. Иначе печать всего профиля могла бы
    // раскрыть доступ.
    override fun toString() = "NetworkProfile(userID=$userID, credentials=redacted)"

    companion object {
        const val ORIGIN = dev.kirill.kandinsky.chat.BuildConfig.KANDINSKY_BRIDGE_ORIGIN

        // Читает поля сохранённого входа и проверяет номер владельца и вид ключа. Пустое имя
        // остаётся пустым, а повреждённый вход не подставляется в сетевые запросы.
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

    // Готовит поля профиля к шифрованию и сохраняет имя только если оно задано. Чтение сможет
    // восстановить тот же профиль без догадок.
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

    // Читает положительный номер последнего чата отдельно для каждого владельца. При отсутствии
    // номера приложение выбирает экран обычным путём.
    fun selectedChat(user: Long): Long? = navigation.getLong("chat:$user", -1).takeIf { it > 0 }

    // Записывает текущий чат под номером владельца. Это возвращает человека к его истории при
    // следующем запуске.
    fun selectChat(user: Long, chat: Long) {
        navigation.edit { putLong("chat:$user", chat) }
    }

    // Удаляет выбор чата только для заданного владельца. Без этого после удаления мог бы остаться
    // указатель на закрытую историю.
    internal fun clearSelectedChat(user: Long) {
        navigation.edit { remove("chat:$user") }
    }

    private val namespace = context.packageName
    private val file = AtomicFile(File(root, "kandinsky-session-v1"))
    private val accounts = AtomicFile(File(root, "kandinsky-accounts-v1"))
    private val alias = namespace + ".kandinsky.session.v1"

    private companion object {
        val gate = Any()
    }

    // Находит старые записи по именам, проверяет, переносит и перечитывает перед удалением
    // прежней копии. Если перенос оборвётся, повтор использует уже записанный результат и не
    // перетирает его.
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

    // Ищет единственный ключ в системном хранилище пакета или создаёт его для шифрования и чтения
    // записей. При нескольких ключах останавливается, чтобы не выбрать случайный.
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

    // Проверяет наличие основного или запасного файла, его размер и версию. Системный ключ
    // расшифровывает байты и проверяет их целостность, иначе повреждённый файл мог бы стать
    // входом.
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

    // Шифрует запись с одноразовым значением и заменяет файл только после полной записи. При
    // ошибке отменяет замену, чтобы предыдущий вход оставался читаемым.
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

    // Под общей блокировкой переносит старые записи и читает текущую. Без блокировки чтение могло
    // бы пересечься с изменением входа.
    fun load(): NetworkProfile? =
        synchronized(gate) {
            upgradeRecords()
            read(file)?.let { NetworkProfile.decode(JSONObject(it)) }
        }

    // Расшифровывает список, проверяет предел в восемь записей и разбирает каждый профиль. Если
    // списка ещё нет, возвращает пустой.
    private fun remembered(): List<NetworkProfile> {
        val array = read(accounts)?.let { org.json.JSONArray(it) } ?: return emptyList()
        require(array.length() <= 8)
        return (0 until array.length()).map { NetworkProfile.decode(array.getJSONObject(it)) }
    }

    // Объединяет активный и запомненные входы без повтора и ставит активный первым. Блокировка
    // удерживает обе части списка в согласованном состоянии.
    fun profiles(): List<NetworkProfile> =
        synchronized(gate) {
            val current = load()
            val values = remembered()
            if (current == null) values
            else listOf(current) + values.filter { it.userID != current.userID }
        }

    // Собирает новый список с текущим и прежними входами, убирает повторы и оставляет до восьми.
    // Затем защищённо записывает список и активный профиль.
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

    // Находит активного пользователя и вызывает общее удаление его записи. Без очистки повторный
    // запуск мог бы восстановить вышедший аккаунт.
    fun removeActive() =
        synchronized(gate) {
            val current = load() ?: return@synchronized
            removeAccount(current.userID)
        }

    // Приводит старые записи к нынешним именам и убирает указанный аккаунт. Номер позволяет
    // закончить удаление даже после смены активного входа.
    fun forget(userID: Long) =
        synchronized(gate) {
            upgradeRecords()
            removeAccount(userID)
        }

    // Сохраняет список без этого владельца и при совпадении удаляет текущий вход. Сразу
    // перечитывает его, чтобы обнаружить неудачное удаление файла.
    private fun removeAccount(userID: Long) {
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
    // Находит принятую для приложения папку данных. По этому пути очистка работает и после
    // переноса старого хранилища.
    private fun root(context: Context) =
        dev.kirill.kandinsky.chat.storage.AppDataRoot.resolve(context.filesDir)

    // Строит путь к папке отметок без создания каталога. Обычная проверка наличия очереди поэтому
    // не меняет файлы.
    private fun queue(context: Context) = File(root(context), "erased-accounts")

    // Проверяет номер и создаёт отметку о предстоящем удалении под блокировкой. Следующий запуск
    // сможет продолжить с этой отметки после обрыва.
    @Synchronized
    fun stage(context: Context, userID: Long) {
        require(userID > 0)
        val queue = queue(context)
        check(queue.isDirectory || queue.mkdirs())
        File(queue, userID.toString()).createNewFile()
    }

    // По отметкам убирает вход, указатель на чат, папку владельца и временные файлы общего
    // доступа. Снимает отметку только в конце, чтобы прерванная очистка продолжилась при запуске.
    @Synchronized
    fun finish(context: Context, credentials: NetworkCredentials) {
        val queue = queue(context)
        if (!queue.exists()) return
        for (marker in checkNotNull(queue.listFiles())) {
            val id = marker.name.toLongOrNull() ?: continue
            if (id <= 0 || marker.name != id.toString()) continue
            credentials.forget(id)
            credentials.clearSelectedChat(id)
            val namespace = "net-v1-u$id"
            removePrivateTree(File(root(context), namespace))
            removePrivateTree(File(context.cacheDir, "kandinsky-share/$namespace"))
            java.nio.file.Files.deleteIfExists(marker.toPath())
        }
    }

    // Удаляет содержимое от вложенных записей к корню и не следует по ссылкам. Это освобождает
    // именно выбранную папку, не затрагивая содержимое по чужому пути.
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
