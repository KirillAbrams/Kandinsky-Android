// Проверяем, что вход и выбранный чат хранятся отдельно для каждого владельца. Сценарии
// используют отдельные папки и настройки.

package dev.kirill.kandinsky.chat.network

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NetworkCredentialNavigationTest {
    // Создаём временную область с уникальным именем, передаём её хранилищу и затем очищаем.
    // Проверочные сеансы не попадут в рабочие настройки приложения.
    private fun withCredentials(check: (ContextWrapper, NetworkCredentials) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = base.packageName + ".qa.navigation." + UUID.randomUUID()
        val sandbox = File(base.cacheDir, namespace).apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context =
            object : ContextWrapper(base) {
                // Возвращаем отдельное имя области. Оно отделяет служебные ключи проверки от
                // ключей обычной установки.
                override fun getPackageName() = namespace

                // Даём хранилищу отдельную папку файлов и создаём её при первом обращении.
                // Рабочая папка приложения при этом не используется.
                override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }

                // Возвращаем собственную папку временных файлов. Её содержимое можно убрать после
                // проверки вместе с остальными данными сценария.
                override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }

                // Возвращаем отдельную папку для данных входа, исключаемых из копирования.
                // Проверка сохраняет обычное разделение папок без рабочих ключей.
                override fun getNoBackupFilesDir() = File(sandbox, "credentials").apply { mkdirs() }

                // Добавляем уникальное начало к каждому имени настроек и запоминаем его. При
                // очистке можно удалить только те записи, которые создала эта проверка.
                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences("$namespace.$name".also { preferences.add(it) }, mode)
            }
        try {
            check(context, NetworkCredentials(context))
        } finally {
            preferences.forEach { base.deleteSharedPreferences(it) }
            sandbox.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(namespace + ".kandinsky.session.v1")
            }
        }
    }

    // Собираем проверочный сеанс одного владельца с указанным именем устройства. Смена устройства
    // позволяет отличить старый вход от нового при том же владельце.
    private fun profile(device: String = "first") =
        NetworkProfile(71, device, "Owner", "c".repeat(64), 500)

    // Выходим из сохранённого сеанса и снова входим на условном другом устройстве. Проверяем, что
    // закрытый сеанс удалён, а его выбранный чат остался отдельно от чата другого владельца.
    @Test
    fun logoutRetainsOnlyTheOwnersSelectedChatUntilTheNextSignIn() = withCredentials { _, store ->
        store.save(profile())
        store.selectChat(71, 3151)
        store.selectChat(72, 4000)
        store.removeActive()
        assertNull(store.load())
        assertTrue(store.profiles().isEmpty())
        assertEquals(3151L, store.selectedChat(71))
        store.save(profile("second"))
        assertEquals("second", store.load()!!.deviceID)
        assertEquals(3151L, store.selectedChat(71))
        assertEquals(4000L, store.selectedChat(72))
    }

    // Забываем просроченный вход и сверяем список сохранённых сеансов и выбранный чат. Повторный
    // вход сможет открыть прежнее место, хотя старый ключ уже удалён.
    @Test
    fun expiredSessionKeepsTheSelectedChatWithoutKeepingItsCredentials() =
        withCredentials { _, store ->
            store.save(profile())
            store.selectChat(71, 3151)
            store.forget(71)
            assertNull(store.load())
            assertTrue(store.profiles().isEmpty())
            assertEquals(3151L, store.selectedChat(71))
        }

    // Удаляем данные выбранного владельца и проверяем его вход и чат. Выбор другого владельца
    // должен сохраниться.
    @Test
    fun permanentErasureClearsOnlyTheDeletedOwnersSelection() = withCredentials { context, store ->
        store.save(profile())
        store.selectChat(71, 3151)
        store.selectChat(72, 4000)
        LocalAccountErasure.stage(context, 71)
        LocalAccountErasure.finish(context, store)
        assertNull(store.load())
        assertTrue(store.profiles().isEmpty())
        assertNull(store.selectedChat(71))
        assertEquals(4000L, store.selectedChat(72))
        LocalAccountErasure.finish(context, store)
        assertNull(store.selectedChat(71))
    }
}
