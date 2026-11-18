package dev.kirill.kandinsky.chat.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AppDataRootTest {
    private fun withFolder(action: (File) -> Unit) {
        val base = Files.createTempDirectory("kandinsky-root-").toFile()
        try {
            action(base)
        } finally {
            base.deleteRecursively()
        }
    }

    private fun previous(base: File, name: String): File {
        val root = File(base, name)
        val user = File(root, "net-v1-u42")
        File(user, "media/objects").mkdirs()
        File(user, "chat.sqlite3").writeText("История и место чтения")
        File(user, "chat.sqlite3-wal").writeText("Незавершённая запись")
        File(user, "media/objects/original.jpg").writeBytes(byteArrayOf(0, 17, -128, -1))
        return root
    }

    @Test
    fun existingDataMovesTogetherAndRepeatKeepsIt() = withFolder { base ->
        val old = previous(base, "PreviousNative")
        val target = AppDataRoot.resolve(base)
        assertEquals("Kandinsky", target.name)
        assertFalse(old.exists())
        assertEquals("История и место чтения", File(target, "net-v1-u42/chat.sqlite3").readText())
        assertEquals("Незавершённая запись", File(target, "net-v1-u42/chat.sqlite3-wal").readText())
        assertArrayEquals(
            byteArrayOf(0, 17, -128, -1),
            File(target, "net-v1-u42/media/objects/original.jpg").readBytes(),
        )
        assertEquals(target, AppDataRoot.resolve(base))
    }

    @Test
    fun ambiguousRootsRemainUntouched() = withFolder { base ->
        val first = previous(base, "FirstNative")
        val second = previous(base, "SecondNative")
        assertTrue(runCatching { AppDataRoot.resolve(base) }.isFailure)
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun pendingErasureMovesWithoutRemainingDatabase() = withFolder { base ->
        val old = File(base, "PreviousNative")
        val queue = File(old, "erased-accounts").apply { mkdirs() }
        File(queue, "42").writeBytes(byteArrayOf())
        val target = AppDataRoot.resolve(base)
        assertArrayEquals(byteArrayOf(), File(target, "erased-accounts/42").readBytes())
        assertFalse(old.exists())
    }

    @Test
    fun existingCurrentRootIsNeverOverwritten() = withFolder { base ->
        val current = previous(base, "Kandinsky")
        val old = previous(base, "PreviousNative")
        assertEquals(current, AppDataRoot.resolve(base))
        assertTrue(old.exists())
    }

    @Test
    fun directorySymlinkIsNotAnUpgradeSource() = withFolder { base ->
        withFolder { external ->
            val old = previous(external, "OtherNative")
            Files.createSymbolicLink(File(base, "Alias").toPath(), old.toPath())
            val target = AppDataRoot.resolve(base)
            assertTrue(old.exists())
            assertFalse(File(target, "net-v1-u42").exists())
        }
    }
}
