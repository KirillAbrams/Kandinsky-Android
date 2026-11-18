package dev.kirill.kandinsky.chat.network

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class NetworkCredentialNavigationTest {
    private fun withCredentials(check: (ContextWrapper, NetworkCredentials) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = base.packageName + ".qa.navigation." + UUID.randomUUID()
        val sandbox = File(base.cacheDir, namespace).apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context =
            object : ContextWrapper(base) {
                override fun getPackageName() = namespace

                override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }

                override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }

                override fun getNoBackupFilesDir() = File(sandbox, "credentials").apply { mkdirs() }

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

    private fun profile(device: String = "first") =
        NetworkProfile(71, device, "Owner", "c".repeat(64), 500)

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
