package dev.kirill.kandinsky.chat.network

import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkCredentialTest {
    @Test
    fun releaseClientReachesProtectedRouteOverTls() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("verify-live-tls") == "1"
        )
        assertFalse(
            android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted
        )
        val api = NetworkAPI(null)
        try {
            val error =
                assertThrows(NetworkFailure::class.java) {
                    api.json("/v1/kandinsky/activity", JSONObject())
                }
            assertEquals(401, error.status)
        } finally {
            api.close()
        }
    }

    @Test
    fun erasureRemovesOnlyTheOwnersHistoryDraftAndSharedFiles() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "erase-" + UUID.randomUUID()).apply { mkdirs() }
        val namespace = base.packageName + ".qa." + UUID.randomUUID()
        val context =
            object : ContextWrapper(base) {
                override fun getPackageName() = namespace

                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences("$namespace.$name", mode)

                override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }

                override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }

                override fun getNoBackupFilesDir() = File(sandbox, "credentials").apply { mkdirs() }
            }
        val credentials = NetworkCredentials(context)
        try {
            val root = dev.kirill.kandinsky.chat.storage.AppDataRoot.resolve(context.filesDir)
            for (id in listOf(71L, 72L)) {
                val directory = File(root, "net-v1-u$id").apply { mkdirs() }
                for (name in listOf("chat.sqlite3", "draft", "preview")) {
                    File(directory, name).writeText("private")
                }
                val share = File(context.cacheDir, "kandinsky-share/net-v1-u$id").apply { mkdirs() }
                File(share, "result.png").writeText("private")
                credentials.save(NetworkProfile(id, "device", "profile", "c".repeat(64), 500))
            }
            LocalAccountErasure.stage(context, 71)
            LocalAccountErasure.finish(context, credentials)
            assertFalse(File(root, "net-v1-u71").exists())
            assertFalse(File(context.cacheDir, "kandinsky-share/net-v1-u71").exists())
            assertTrue(File(root, "net-v1-u72/draft").isFile)
            assertTrue(File(context.cacheDir, "kandinsky-share/net-v1-u72/result.png").isFile)
            assertEquals(listOf(72L), credentials.profiles().map { it.userID })
        } finally {
            base.deleteSharedPreferences(namespace + ".studio.navigation")
            sandbox.deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keys.deleteEntry(namespace + ".kandinsky.session.v1")
        }
    }

    // Архив начинается только с резервного файла, чтобы повторить незавершённую замену записи.
    @Test
    fun previousEncryptedRecordsMoveWithoutChangingAccountOrKey() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root =
            File(base.cacheDir, "credentials-upgrade-" + UUID.randomUUID()).apply { mkdirs() }
        val namespace = base.packageName + ".qa." + UUID.randomUUID()
        val alias = namespace + ".previous.session.v1"
        val context =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir() = root

                override fun getPackageName() = namespace

                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences("$namespace.$name", mode)
            }
        try {
            val key =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
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
            val session =
                JSONObject()
                    .put("userId", 42)
                    .put("deviceId", "previous-device")
                    .put("name", "Saved account")
                    .put("token", "d".repeat(64))
                    .put("expiresAtMs", 123456789L)
                    .put("username", "saved")
            val second = JSONObject(session.toString()).put("userId", 43).put("username", "second")
            fun encrypted(text: String): ByteArray {
                val cipher =
                    Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                return byteArrayOf(1) + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            }
            val activeBytes = encrypted(session.toString())
            val accountBytes = encrypted(JSONArray().put(session).put(second).toString())
            File(root, "previous-session-v1").writeBytes(activeBytes)
            File(root, "previous-accounts-v1.bak").writeBytes(accountBytes)
            val upgraded = NetworkCredentials(context)
            assertEquals(42L, upgraded.load()!!.userID)
            assertEquals("saved", upgraded.load()!!.username)
            assertEquals(listOf(42L, 43L), upgraded.profiles().map { it.userID })
            assertArrayEquals(activeBytes, File(root, "kandinsky-session-v1").readBytes())
            assertArrayEquals(accountBytes, File(root, "kandinsky-accounts-v1").readBytes())
            assertEquals(42L, NetworkCredentials(context).load()!!.userID)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue(store.containsAlias(alias))
            assertFalse(store.containsAlias(namespace + ".kandinsky.session.v1"))
            upgraded.removeActive()
            assertNull(NetworkCredentials(context).load())
            assertEquals(listOf(43L), NetworkCredentials(context).profiles().map { it.userID })
        } finally {
            base.deleteSharedPreferences(namespace + ".studio.navigation")
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    @Test
    fun switchingRetainsOldSessionEncryptsAndBoundsAccounts() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "credentials-" + UUID.randomUUID()).apply { mkdirs() }
        val namespace = base.packageName + ".qa." + UUID.randomUUID()
        val context =
            object : ContextWrapper(base) {
                override fun getNoBackupFilesDir() = root

                override fun getPackageName() = namespace

                override fun getSharedPreferences(name: String, mode: Int) =
                    base.getSharedPreferences("$namespace.$name", mode)
            }
        try {
            val credentials = NetworkCredentials(context)
            val legacy = NetworkProfile(1, "legacy", "Old invitation", "a".repeat(64), 123)
            credentials.save(legacy)
            credentials.save(NetworkProfile(1000, "new", "Demo", "b".repeat(64), 456, "demo"))
            assertEquals(listOf(1000L, 1L), credentials.profiles().map { it.userID })
            assertEquals(1000L, NetworkCredentials(context).load()!!.userID)
            credentials.save(legacy)
            assertNull(credentials.load()!!.username)
            for (id in 1001L..1010L) credentials.save(
                NetworkProfile(id, "qa", "Demo", "c".repeat(64), 456)
            )
            assertEquals(8, credentials.profiles().size)
            for (file in root.listFiles()!!) assertFalse(
                String(file.readBytes(), Charsets.ISO_8859_1).contains("c".repeat(64))
            )
            val archive = File(root, "kandinsky-accounts-v1")
            val valid = archive.readBytes()
            val changed = valid.copyOf()
            changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
            archive.writeBytes(changed)
            assertTrue(runCatching { credentials.profiles() }.isFailure)
            assertEquals(1010L, credentials.load()!!.userID)
            archive.writeBytes(valid)
            assertEquals(8, credentials.profiles().size)
        } finally {
            base.deleteSharedPreferences(namespace + ".studio.navigation")
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(namespace + ".kandinsky.session.v1")
            }
        }
    }
}
