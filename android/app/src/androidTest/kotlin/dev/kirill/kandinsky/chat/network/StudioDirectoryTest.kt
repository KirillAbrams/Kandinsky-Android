package dev.kirill.kandinsky.chat.network

import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudioDirectoryTest {
    private fun chat(id: Long, time: Long, generating: Boolean) =
        StudioThread(id, "Чат $id", 0, 0, false, false, time, generating).wire()

    private fun event(cursor: Long, row: JSONObject) = JSONObject()
        .put("cursor", cursor)
        .put("chats", JSONArray().put(row))
        .put("events", JSONArray().put(JSONObject()
            .put("seq", cursor).put("chatId", row.getLong("id"))
            .put("kind", "deleted").put("messageId", 999)))

    @Test fun existingChatReceivesBusyStateAndPromotionWithoutLoadingHistory() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "directory-${UUID.randomUUID()}")
        var harness = RepositoryHarness.open(root)
        try {
            var cache = NetworkStore(harness.repo, 1)
            val start = event(1, chat(1, 3000, true))
            val active = await<StudioRecentPage> { callback ->
                harness.store.submit({ db ->
                    NetworkSchema.install(db)
                    assertFalse(cache.directoryEvents(db,
                        JSONObject().put("cursor", 0).put("events", JSONArray())).second)
                    cache.mergeChats(db, JSONObject().put("chats", JSONArray()
                        .put(chat(2, 2000, false)).put(chat(1, 1000, false))), true)
                    assertTrue(cache.directoryEvents(db, start).second)
                    assertFalse(cache.directoryEvents(db, event(2, chat(1, 3000, true))).second)
                    cache.studioRecent(db)
                }, callback)
            }
            assertEquals(listOf(1L, 2L), active.threads.map { it.id })
            assertTrue(active.threads.first().generating)
            assertEquals(3000L, active.threads.first().activityAtMs)
            harness = harness.reopen()
            cache = NetworkStore(harness.repo, 1)
            val finished = await<StudioRecentPage> { callback ->
                harness.store.submit({ db ->
                    assertTrue(cache.directoryEvents(db, event(3, chat(1, 3000, false))).second)
                    assertFalse(cache.directoryEvents(db, event(3, chat(1, 3000, false))).second)
                    assertThrows(IllegalArgumentException::class.java) { cache.events(db, start) }
                    val oldSnapshot = JSONObject().put("chats", JSONArray().put(chat(1, 3000, true)))
                    cache.mergeStudioDirectorySnapshot(db, oldSnapshot, startedAtCursor = 2)
                    cache.mergeChats(db, oldSnapshot, preserveDirectoryMetadata = true)
                    cache.studioRecent(db)
                }, callback)
            }
            assertFalse(finished.threads.first().generating)
            assertEquals(3000L, finished.threads.first().activityAtMs)
        } finally {
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test fun oldPayloadDefaultsSafelyAndNewPayloadRoundTrips() {
        val old = StudioThread.from(JSONObject("""{"id":1,"title":"Старый","lastSeq":0,"unreadCount":0,"pinned":false,"archived":false}"""))
        assertEquals(0L, old.activityAtMs)
        assertFalse(old.generating)
        val current = StudioThread.from(chat(2, 4000, true))
        assertTrue(current.generating)
        assertEquals(4000L, current.activityAtMs)
    }

    private fun <T> await(start: ((Result<T>) -> Unit) -> Unit): T {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        start { result = it; done.countDown() }
        check(done.await(30, TimeUnit.SECONDS)) { "Ожидание записи каталога истекло" }
        return checkNotNull(result).getOrThrow()
    }
}
