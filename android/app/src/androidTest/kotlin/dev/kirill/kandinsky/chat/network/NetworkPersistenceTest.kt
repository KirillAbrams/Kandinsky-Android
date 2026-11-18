package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import dev.kirill.kandinsky.chat.domain.HistoryPage
import dev.kirill.kandinsky.chat.domain.MessageDelivery
import dev.kirill.kandinsky.chat.domain.MessageDelta
import dev.kirill.kandinsky.chat.domain.PageRequest
import dev.kirill.kandinsky.chat.domain.SendCommand
import dev.kirill.kandinsky.chat.storage.SQLiteChatRepository
import dev.kirill.kandinsky.chat.storage.SQLiteStore
import dev.kirill.kandinsky.chat.support.TestFixtures
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkPersistenceTest {
    @Test
    fun retryNumberSurvivesReopenUntilItsOwnReplyIsSaved() {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "retry-" + UUID.randomUUID(),
            )
        var harness = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
        val key = "generationRetry:2:9"
        try {
            var cache = NetworkStore(harness.repo, 1)
            val first =
                await<String> { cb ->
                    harness.store.submit(
                        { db ->
                            NetworkSchema.install(db)
                            cache.pendingRetry(db, key, null)
                        },
                        cb,
                    )
                }
            harness = harness.reopen()
            cache = NetworkStore(harness.repo, 1)
            val response = JSONObject().put("messages", JSONArray())
            val values =
                await<List<String>> { cb ->
                    harness.store.submit(
                        { db ->
                            val recovered = cache.pendingRetry(db, key, UUID.randomUUID())
                            cache.generationRows(db, 2, response, key, UUID.randomUUID().toString())
                            val afterLateReply = cache.pendingRetry(db, key, null)
                            cache.generationRows(db, 2, response, key, recovered)
                            listOf(recovered, afterLateReply, cache.pendingRetry(db, key, null))
                        },
                        cb,
                    )
                }
            assertEquals(listOf(first, first), values.take(2))
            assertNotEquals(first, values.last())
        } finally {
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun retiredOperationsDoNotConsumePromptCapacity() {
        val root =
            java.io.File(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .cacheDir,
                "retired-" + java.util.UUID.randomUUID(),
            )
        val harness = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
        try {
            await<Unit> { cb ->
                harness.store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        repeat(256) {
                            db.execSQL(
                                "INSERT INTO net_outbox(id,action,payload,payloadBytes) VALUES(?,'command','{}',2)",
                                arrayOf("old-$it"),
                            )
                        }
                    },
                    cb,
                )
            }
            val local =
                dev.kirill.kandinsky.chat.storage.SQLiteChatRepository(
                    harness.store,
                    NetworkJournal(1),
                )
            val command =
                SendCommand(
                    java.util.UUID.randomUUID().toString(),
                    2,
                    text = "Новый запрос генерации",
                )
            val delta = await<MessageDelta> { local.send(command, it) }
            assertEquals(command.text, delta.inserted.first().text)
            assertEquals(
                256,
                harness.rawScalar("SELECT count(*) FROM net_outbox WHERE action='command'"),
            )
            assertEquals(
                1,
                harness.rawScalar("SELECT count(*) FROM net_outbox WHERE action='send'"),
            )
            val ready =
                await<List<Pair<String, String>>> { callback ->
                    harness.store.submit({ NetworkJournal.readyOperations(it) }, callback)
                }
            assertEquals(listOf("send"), ready.map { it.first })
            assertEquals(
                command.operationID,
                dev.kirill.kandinsky.chat.storage.ChatStorageCodec.readCommand(
                        ready.single().second
                    )
                    .operationID,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun queuedSlotClearsProgressWhileRunningSlotKeepsItsMeasurement() = withNetworkStore { h, _ ->
        val cache = NetworkStore(SQLiteChatRepository(h.store, NetworkJournal(1)), 1)
        fun publish(
            revision: Int,
            stage: String,
            progress: Int?,
            secondProgress: Int?,
            run: String? = null,
            startedAt: Long = 1,
        ): List<dev.kirill.kandinsky.chat.domain.StudioGeneration.Slot> {
            fun slot(id: String, status: String, value: Int?, runId: String?, start: Long) =
                JSONObject()
                    .put("id", id)
                    .put("status", status)
                    .put("blobId", JSONObject.NULL)
                    .put("progress", value ?: JSONObject.NULL)
                    .put("runId", runId ?: JSONObject.NULL)
                    .put("startedAtMs", start)
            val wire =
                serverMessage(947, 1, 2)
                    .put("clientId", "test-generation")
                    .put("revision", revision)
                    .put("text", "Генерация продолжается")
                    .put(
                        "metadata",
                        JSONObject()
                            .put("kandinskyJobId", "same-batch")
                            .put("kandinskyStatus", "running")
                            .put(
                                "kandinskySlots",
                                JSONArray()
                                    .put(slot("one", stage, progress, run, startedAt))
                                    .put(slot("two", "running", secondProgress, null, 1)),
                            ),
                    )
            val delta =
                await<MessageDelta> { cb ->
                    h.store.submit(
                        { db ->
                            cache
                                .generationRows(
                                    db,
                                    2,
                                    JSONObject().put("messages", JSONArray().put(wire)),
                                )
                                .single()
                        },
                        cb,
                    )
                }
            return checkNotNull(delta.updated.single().studioGeneration).slots
        }
        assertEquals(listOf(12, 36), publish(1, "running", 12, 36).map { it.progress })
        val paused = publish(2, "queued", null, 30)
        assertEquals(listOf(null, 36), paused.map { it.progress })
        assertEquals(
            "В очереди, 0%",
            dev.kirill.kandinsky.chat.rendering.StudioGenerationCard.progressLabel(
                paused[0].stage,
                12,
            ),
        )
        assertEquals(listOf(0, 42), publish(3, "running", 0, 42).map { it.progress })
        assertEquals(listOf(20, 44), publish(4, "running", 20, 44).map { it.progress })
        assertEquals(
            listOf(null, 46),
            publish(5, "queued", null, 46, "manual-retry").map { it.progress },
        )
        assertEquals(
            listOf(0, 48),
            publish(6, "running", 0, 48, "manual-retry").map { it.progress },
        )
        assertEquals(
            listOf(26, 50),
            publish(7, "running", 26, 50, "manual-retry").map { it.progress },
        )
        assertEquals(
            listOf(2, 52),
            publish(8, "running", 2, 52, "manual-retry", startedAt = 2).map { it.progress },
        )
    }

    @Test
    fun recentStudioThreadsComeFromTheSavedCatalogWhileOffline() = withNetworkStore { h, cache ->
        val saved =
            JSONObject(
                """
            {"version":1,"userId":1,"cursor":0,"messages":[],"chats":[
              {"id":9,"title":"Pinned","lastSeq":0,"unreadCount":0,"pinned":true},
              {"id":7,"title":"Newer","lastSeq":0,"unreadCount":0,"pinned":false},
              {"id":6,"title":"Older","lastSeq":0,"unreadCount":0,"pinned":false}]}
        """
                    .trimIndent()
            )
        val archived =
            JSONObject(
                """
            {"chats":[{"id":10,"title":"Archived","lastSeq":0,"unreadCount":0,"pinned":false,"archived":true}]}
        """
                    .trimIndent()
            )
        await<Unit> { callback ->
            h.store.submit(
                { db ->
                    db.execSQL("DELETE FROM net_chats WHERE chatID=2")
                    cache.bootstrap(db, saved)
                    cache.mergeChats(db, archived)
                    Unit
                },
                callback,
            )
        }
        val profile =
            NetworkProfile(
                1,
                UUID.randomUUID().toString(),
                "Offline fixture",
                "a".repeat(64),
                Long.MAX_VALUE,
            )
        val sync = NetworkSync(profile, h.store, h.repo)
        try {
            repeat(2) {
                val page = await<StudioRecentPage>(sync::cachedStudioRecent)
                assertEquals(listOf(9L, 7L, 6L), page.threads.map { it.id })
                assertNull(page.nextOffset)
            }
        } finally {
            sync.close()
        }
    }

    @Test
    fun unconfirmedPromptsStayAtTailThroughReplayAndLegacyUpgrade() = withNetworkStore { h, cache ->
        val local = SQLiteChatRepository(h.store, NetworkJournal(1))
        val serverBase = 1_837_550_888_340_132_174L
        val first = SendCommand(UUID.randomUUID().toString(), 2, text = "First queued prompt")
        val second = SendCommand(UUID.randomUUID().toString(), 2, text = "Second queued prompt")
        fun history(vararg rows: JSONObject) =
            JSONObject().put("messages", JSONArray(rows.toList())).put("hasOlder", false)
        fun wire(id: Long, seq: Long, sender: Long, text: String) =
            serverMessage(id, seq, sender).put("text", text).put("savedAtMs", 1_794_483_289_395L)
        fun localRows() = await<HistoryPage> { local.history(PageRequest(2), it) }.rows

        val previous = wire(90, serverBase, 0, "Previous answer")
        await<Unit> { callback ->
            h.store.submit(
                { db ->
                    cache.history(db, 2, history(previous))
                    Unit
                },
                callback,
            )
        }
        val firstID = await<MessageDelta> { local.send(first, it) }.inserted.single().id
        val secondID = await<MessageDelta> { local.send(second, it) }.inserted.single().id
        assertEquals(
            listOf("Previous answer", first.text, second.text),
            localRows().map { it.text },
        )

        val accepted =
            wire(91, serverBase + 2_048, 1, first.text).put("clientId", first.operationID)
        val answer = wire(92, serverBase + 2_049, 0, "New answer")
        val ack = JSONObject().put("messages", JSONArray().put(accepted))
        await<Unit> { callback ->
            h.store.submit(
                { db ->
                    cache.accepted(db, ack)
                    cache.history(db, 2, history(answer, accepted))
                    cache.accepted(db, ack)
                    db.execSQL("UPDATE messages SET sortKey=savedAt WHERE id=?", arrayOf(secondID))
                    NetworkSchema.install(db)
                },
                callback,
            )
        }
        val recovered = localRows()
        assertEquals(
            listOf("Previous answer", first.text, "New answer", second.text),
            recovered.map { it.text },
        )
        assertEquals(firstID, recovered[1].id)
        assertEquals(serverBase + 2_048, recovered[1].order.sortKey)
        assertEquals(secondID, recovered.last().id)
        assertEquals(MessageDelivery.PENDING, recovered.last().delivery)
        val outbox =
            await<List<Long>> { callback ->
                h.store.submit(
                    { db ->
                        db.rawQuery("SELECT localID FROM net_outbox WHERE action='send'", null)
                            .use { rows ->
                                buildList { while (rows.moveToNext()) add(rows.getLong(0)) }
                            }
                    },
                    callback,
                )
            }
        assertEquals(listOf(secondID), outbox)
    }

    private fun withNetworkStore(
        action: (dev.kirill.kandinsky.chat.support.RepositoryHarness, NetworkStore) -> Unit
    ) {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "network-store-${UUID.randomUUID()}",
            )
        val h = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
        try {
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        db.execSQL("INSERT INTO net_chats(chatID,title) VALUES(2,'Генерации')")
                    },
                    cb,
                )
            }
            action(h, NetworkStore(h.repo, 1))
        } finally {
            h.close()
            root.deleteRecursively()
        }
    }

    private fun serverMessage(id: Long, seq: Long, sender: Long = 1) =
        org.json
            .JSONObject()
            .put("id", id)
            .put("chatId", 2)
            .put("seq", seq)
            .put("senderId", sender)
            .put("clientId", UUID.randomUUID().toString())
            .put("kind", "text")
            .put("text", "Результат генерации")
            .put("caption", "")
            .put("replyToId", org.json.JSONObject.NULL)
            .put("savedAtMs", 1000)
            .put("revision", 1)
            .put("attachments", org.json.JSONArray())

    private fun <T> await(start: ((Result<T>) -> Unit) -> Unit): T {
        val done = CountDownLatch(1)
        var value: Result<T>? = null
        start {
            value = it
            done.countDown()
        }
        check(done.await(10, TimeUnit.SECONDS)) { "SQLite callback timed out" }
        return checkNotNull(value).getOrThrow()
    }

    @Test
    fun savedCursorWaitsForConfirmationAcrossReopen() = withNetworkStore { harness, cache ->
        await<Unit> { done ->
            harness.store.submit({ db ->
                cache.state(db, "cursor", "12")
                cache.state(db, "ackedCursor", "10")
            }, done)
        }
        val reopened = harness.reopen()
        try {
            await<Unit> { done ->
                reopened.store.submit({ db ->
                    assertEquals(12L, cache.cursorAwaitingConfirmation(db))
                    cache.state(db, "ackedCursor", "12")
                    assertNull(cache.cursorAwaitingConfirmation(db))
                    cache.state(db, "cursor", "13")
                    assertEquals(13L, cache.cursorAwaitingConfirmation(db))
                }, done)
            }
        } finally {
            reopened.close()
        }
    }

    @Test
    fun chatRefreshPreservesPagingAndDoesNotDoubleCountReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "chat-refresh-${UUID.randomUUID()}")
        val store = await<SQLiteStore> { SQLiteStore.open(root, callback = it) }
        try {
            val local =
                dev.kirill.kandinsky.chat.storage.SQLiteChatRepository(store, NetworkJournal(1000))
            val cache = NetworkStore(local, 1000)
            val message =
                org.json
                    .JSONObject()
                    .put("id", 901)
                    .put("chatId", 4000)
                    .put("seq", 1)
                    .put("senderId", 0)
                    .put("clientId", UUID.randomUUID().toString())
                    .put("kind", "text")
                    .put("text", "First remote message")
                    .put("caption", "")
                    .put("replyToId", org.json.JSONObject.NULL)
                    .put("savedAtMs", 1000)
                    .put("revision", 1)
                    .put("attachments", org.json.JSONArray())
            val chats =
                org.json
                    .JSONObject()
                    .put(
                        "chats",
                        org.json
                            .JSONArray()
                            .put(
                                org.json
                                    .JSONObject()
                                    .put("id", 4000)
                                    .put("title", "Результаты")
                                    .put("pinned", false)
                                    .put("unreadCount", 0)
                                    .put("lastSeq", 0)
                            ),
                    )
            val events =
                org.json
                    .JSONObject()
                    .put("type", "events")
                    .put("cursor", 1)
                    .put(
                        "events",
                        org.json
                            .JSONArray()
                            .put(
                                org.json
                                    .JSONObject()
                                    .put("seq", 1)
                                    .put("chatId", 4000)
                                    .put("kind", "message")
                                    .put("message", message)
                            ),
                    )
            await<Unit> { callback ->
                store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        assertTrue(cache.hasUnknownChats(db, events))
                        cache.mergeChats(db, chats)
                        db.execSQL("UPDATE net_chats SET hasOlder=0 WHERE chatID=4000")
                        cache.mergeChats(db, chats)
                        cache.events(db, events)
                        cache.events(db, events)
                        cache.mergeChats(db, chats)
                        fun scalar(q: String) =
                            db.rawQuery(q, null).use {
                                it.moveToFirst()
                                it.getLong(0)
                            }
                        assertEquals(1L, scalar("SELECT count(*) FROM messages WHERE chatId=4000"))
                        assertEquals(1L, scalar("SELECT unreadCount FROM chats WHERE id=4000"))
                        assertEquals(0L, scalar("SELECT hasOlder FROM net_chats WHERE chatID=4000"))
                        assertFalse(cache.hasUnknownChats(db, events))
                    },
                    callback,
                )
            }
        } finally {
            await<Unit> { store.close(it) }
            root.deleteRecursively()
        }
    }

    @Test
    fun visibleMessageAndOutboxShareTheCommitAndSurviveLostReply() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "network-outbox-${UUID.randomUUID()}")
        val store = await<SQLiteStore> { SQLiteStore.open(root, callback = it) }
        try {
            await<Unit> { callback ->
                store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        db.execSQL("INSERT INTO chats(id,title) VALUES(2,'Генерации')")
                    },
                    callback,
                )
            }
            val repository =
                dev.kirill.kandinsky.chat.storage.SQLiteChatRepository(store, NetworkJournal(1))
            val command =
                dev.kirill.kandinsky.chat.domain.SendCommand(
                    UUID.randomUUID().toString(),
                    2,
                    text = "Keep this across reconnect",
                )
            store.faults.arm(dev.kirill.kandinsky.chat.domain.RepoFault.BEFORE_COMMIT)
            assertTrue(
                runCatching {
                        await<dev.kirill.kandinsky.chat.domain.MessageDelta> {
                            repository.send(command, it)
                        }
                    }
                    .isFailure
            )
            fun counts() =
                await<Pair<Long, Long>> { callback ->
                    store.submit(
                        { db ->
                            val messages =
                                db.rawQuery("SELECT count(*) FROM messages", null).use {
                                    it.moveToFirst()
                                    it.getLong(0)
                                }
                            val outgoing =
                                db.rawQuery("SELECT count(*) FROM net_outbox", null).use {
                                    it.moveToFirst()
                                    it.getLong(0)
                                }
                            messages to outgoing
                        },
                        callback,
                    )
                }
            assertEquals(0L to 0L, counts())
            store.faults.arm(dev.kirill.kandinsky.chat.domain.RepoFault.AFTER_COMMIT_BEFORE_REPLY)
            assertTrue(
                runCatching {
                        await<dev.kirill.kandinsky.chat.domain.MessageDelta> {
                            repository.send(command, it)
                        }
                    }
                    .isFailure
            )
            assertEquals(
                "A saved visible message must have its durable send command",
                1L to 1L,
                counts(),
            )
            val replay =
                await<dev.kirill.kandinsky.chat.domain.MessageDelta> {
                    repository.send(command, it)
                }
            assertEquals(
                dev.kirill.kandinsky.chat.domain.MessageDelivery.PENDING,
                replay.inserted.single().delivery,
            )
            assertEquals(1L to 1L, counts())
        } finally {
            await<Unit> { store.close(it) }
            root.deleteRecursively()
        }
    }

    @Test
    fun replayIsAtomicDeduplicatedAndCannotResurrectLocalDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "network-replay-${UUID.randomUUID()}")
        val store = await<SQLiteStore> { SQLiteStore.open(root, callback = it) }
        val local = dev.kirill.kandinsky.chat.storage.SQLiteChatRepository(store, NetworkJournal(1))
        val cache = NetworkStore(local, 1)
        fun runSQL(action: (SQLiteDatabase) -> Unit) = await<Unit> { store.submit(action, it) }
        fun incoming(id: Long, client: String) =
            org.json
                .JSONObject()
                .put("id", id)
                .put("chatId", 2)
                .put("seq", id)
                .put("senderId", 2)
                .put("clientId", client)
                .put("kind", "text")
                .put("text", "From independent fixture")
                .put("caption", "")
                .put("replyToId", org.json.JSONObject.NULL)
                .put("savedAtMs", 1000)
                .put("revision", 1)
                .put("attachments", org.json.JSONArray())
        val message = incoming(101, UUID.randomUUID().toString())
        val first =
            org.json
                .JSONObject()
                .put("seq", 1)
                .put("kind", "message")
                .put("chatId", 2)
                .put("message", message)
        fun frame(events: org.json.JSONArray, cursor: Long) =
            org.json.JSONObject().put("type", "events").put("events", events).put("cursor", cursor)
        try {
            runSQL { db ->
                NetworkSchema.install(db)
                db.execSQL("INSERT INTO chats(id,title) VALUES(2,'Генерации')")
                db.execSQL("INSERT INTO net_chats(chatID,title) VALUES(2,'Генерации')")
            }
            val invalid =
                frame(
                    org.json
                        .JSONArray()
                        .put(first)
                        .put(
                            org.json
                                .JSONObject()
                                .put("seq", 2)
                                .put("kind", "unknown")
                                .put("chatId", 2)
                        ),
                    2,
                )
            assertTrue(runCatching { runSQL { cache.events(it, invalid) } }.isFailure)
            runSQL { db ->
                assertEquals(0L, cache.cursor(db))
                db.rawQuery("SELECT count(*) FROM messages", null).use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
            val good = frame(org.json.JSONArray().put(first), 1)
            runSQL {
                cache.events(it, good)
                cache.events(it, good)
            }
            val id =
                await<Long> {
                    store.submit(
                        { db ->
                            db.rawQuery("SELECT localID FROM net_messages WHERE serverID=101", null)
                                .use {
                                    it.moveToFirst()
                                    it.getLong(0)
                                }
                        },
                        it,
                    )
                }
            runSQL { db ->
                assertEquals(1L, cache.cursor(db))
                db.rawQuery("SELECT count(*) FROM messages", null).use {
                    it.moveToFirst()
                    assertEquals(1, it.getInt(0))
                }
                db.rawQuery("SELECT count(*) FROM net_pending_receipts", null).use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
            await<dev.kirill.kandinsky.chat.domain.MessageDelta> { local.delete(id, it) }
            runSQL { db ->
                cache.history(
                    db,
                    2,
                    org.json
                        .JSONObject()
                        .put("messages", org.json.JSONArray().put(message))
                        .put("hasOlder", false),
                )
                db.rawQuery("SELECT count(*) FROM messages", null).use {
                    it.moveToFirst()
                    assertEquals("Old HTTP page must not resurrect hidden rows", 0, it.getInt(0))
                }
            }
        } finally {
            await<Unit> { store.close(it) }
            root.deleteRecursively()
        }
    }

    @Test
    fun removingRejectedMessageCannotLeaveABlockingDeleteCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "network-failed-${UUID.randomUUID()}")
        val store = await<SQLiteStore> { SQLiteStore.open(root, callback = it) }
        try {
            await<Unit> {
                store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        db.execSQL("INSERT INTO chats(id,title) VALUES(2,'Генерации')")
                    },
                    it,
                )
            }
            val local =
                dev.kirill.kandinsky.chat.storage.SQLiteChatRepository(store, NetworkJournal(1))
            val command =
                dev.kirill.kandinsky.chat.domain.SendCommand(
                    UUID.randomUUID().toString(),
                    2,
                    text = "Rejected by server",
                )
            val message =
                await<dev.kirill.kandinsky.chat.domain.MessageDelta> { local.send(command, it) }
                    .inserted
                    .single()
            await<Unit> {
                store.submit(
                    { db ->
                        db.execSQL(
                            "UPDATE net_outbox SET errorCode='invalid_text' WHERE id=?",
                            arrayOf(command.operationID),
                        )
                        db.execSQL(
                            "UPDATE messages SET deliveryState='failed' WHERE id=?",
                            arrayOf(message.id),
                        )
                    },
                    it,
                )
            }
            await<dev.kirill.kandinsky.chat.domain.MessageDelta> { local.delete(message.id, it) }
            val pending =
                await<Long> { callback ->
                    store.submit(
                        { db ->
                            db.rawQuery("SELECT count(*) FROM net_outbox", null).use {
                                it.moveToFirst()
                                it.getLong(0)
                            }
                        },
                        callback,
                    )
                }
            assertEquals("Rejected sends have no remote message to delete", 0L, pending)
        } finally {
            await<Unit> { store.close(it) }
            root.deleteRecursively()
        }
    }

    @Test
    fun migrationPreservesVersionTwoRowsAndAddsDeliveryState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "network-migration-${UUID.randomUUID()}")
        check(root.mkdirs())
        val legacy = SQLiteDatabase.openOrCreateDatabase(File(root, "chat.sqlite3"), null)
        // Сохранённая схема второй версии создаётся независимо от проверяемого обновления
        legacy.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        legacy.execSQL(
            "CREATE TABLE chats(id INTEGER PRIMARY KEY,title TEXT NOT NULL," +
                "avatarKind TEXT NOT NULL DEFAULT 'initial',lastMessageId INTEGER," +
                "updatedSortKey INTEGER NOT NULL DEFAULT 0," +
                "unreadCount INTEGER NOT NULL DEFAULT 0,pinned INTEGER NOT NULL DEFAULT 0)"
        )
        legacy.execSQL(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "chatId INTEGER NOT NULL REFERENCES chats(id),sortKey INTEGER NOT NULL," +
                "kind TEXT NOT NULL,text TEXT NOT NULL DEFAULT ''," +
                "caption TEXT NOT NULL DEFAULT '',replyToId INTEGER," +
                "revision INTEGER NOT NULL DEFAULT 1,savedAt INTEGER NOT NULL," +
                "direction TEXT NOT NULL DEFAULT 'incoming',searchText TEXT NOT NULL DEFAULT '')"
        )
        legacy.execSQL(
            "CREATE TABLE attachments(id TEXT PRIMARY KEY,messageId INTEGER NOT NULL," +
                "relativePath TEXT NOT NULL,mime TEXT NOT NULL,byteSize INTEGER NOT NULL," +
                "width INTEGER NOT NULL DEFAULT 0,height INTEGER NOT NULL DEFAULT 0," +
                "durationMs INTEGER,thumbnailKey TEXT NOT NULL DEFAULT ''," +
                "orientation INTEGER NOT NULL DEFAULT 1)"
        )
        legacy.execSQL(
            "CREATE TABLE drafts(chatId INTEGER PRIMARY KEY,text TEXT NOT NULL," +
                "attachmentDraft TEXT,revision INTEGER NOT NULL DEFAULT 0)"
        )
        legacy.execSQL(
            "CREATE TABLE reading_positions(chatId INTEGER PRIMARY KEY,messageId INTEGER," +
                "offsetWithin TEXT NOT NULL,followsBottom INTEGER NOT NULL," +
                "layoutVersion INTEGER NOT NULL)"
        )
        legacy.execSQL(
            "CREATE TABLE operations(token TEXT PRIMARY KEY,payloadHash TEXT NOT NULL,messageId INTEGER NOT NULL)"
        )
        legacy.execSQL("INSERT INTO chats(id,title) VALUES(2,'Existing chat')")
        legacy.execSQL(
            "INSERT INTO messages(id,chatId,sortKey,kind,text,savedAt) VALUES(17,2,100,'text','preserve me',100)"
        )
        legacy.version = 2
        legacy.close()
        val store = await<SQLiteStore> { SQLiteStore.open(root, callback = it) }
        try {
            val columns =
                await<Set<String>> { callback ->
                    store.submit(
                        { db ->
                            val names = mutableSetOf<String>()
                            db.rawQuery("PRAGMA table_info(messages)", null).use { rows ->
                                while (rows.moveToNext()) names.add(
                                    rows.getString(rows.getColumnIndexOrThrow("name"))
                                )
                            }
                            names
                        },
                        callback,
                    )
                }
            assertTrue("Network migration must add a delivery state", "deliveryState" in columns)
            val row =
                await<Pair<String, String>> { callback ->
                    store.submit(
                        { db ->
                            db.rawQuery("SELECT text,deliveryState FROM messages WHERE id=17", null)
                                .use { rows ->
                                    check(rows.moveToFirst())
                                    rows.getString(0) to rows.getString(1)
                                }
                        },
                        callback,
                    )
                }
            assertEquals("preserve me", row.first)
            assertEquals("local", row.second)
        } finally {
            await<Unit> { store.close(it) }
            root.deleteRecursively()
        }
    }

    @Test
    fun ownMediaRevisionKeepsOnlyAMatchingCachedBlob() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "media-revision-${UUID.randomUUID()}")
        val store = await<SQLiteStore> { SQLiteStore.open(root, callback = it) }
        val local = dev.kirill.kandinsky.chat.storage.SQLiteChatRepository(store, NetworkJournal(1))
        val cache = NetworkStore(local, 1)
        val client = UUID.randomUUID().toString()
        val oldBlob = "a".repeat(64)
        val newBlob = "b".repeat(64)
        fun page(blob: String, revision: Int): org.json.JSONObject {
            val a =
                org.json
                    .JSONObject()
                    .put("id", "wire-image")
                    .put("blobId", blob)
                    .put("mime", "image/jpeg")
                    .put("byteSize", 100)
                    .put("rawWidth", 120)
                    .put("rawHeight", 80)
                    .put("orientation", 1)
                    .put("durationMs", org.json.JSONObject.NULL)
                    .put("previewBlobId", org.json.JSONObject.NULL)
                    .put("thumbnailKey", blob)
            val row =
                org.json
                    .JSONObject()
                    .put("id", 501)
                    .put("chatId", 2)
                    .put("seq", 1)
                    .put("senderId", 1)
                    .put("clientId", client)
                    .put("kind", "photo")
                    .put("text", "")
                    .put("caption", "Real media revision")
                    .put("replyToId", org.json.JSONObject.NULL)
                    .put("savedAtMs", 1000)
                    .put("revision", revision)
                    .put("attachments", org.json.JSONArray().put(a))
            return org.json
                .JSONObject()
                .put("messages", org.json.JSONArray().put(row))
                .put("hasOlder", false)
        }
        try {
            val paths =
                await<Pair<String, String>> { callback ->
                    store.submit(
                        { db ->
                            NetworkSchema.install(db)
                            db.execSQL("INSERT OR IGNORE INTO chats(id,title) VALUES(2,'Генерации')")
                            db.execSQL(
                                "INSERT OR IGNORE INTO net_chats(chatID,title) VALUES(2,'Генерации')"
                            )
                            cache.history(db, 2, page(oldBlob, 1))
                            db.execSQL(
                                "UPDATE attachments SET relativePath='objects/own-original.jpg' WHERE id='net-501-0'"
                            )
                            fun path() =
                                db.rawQuery(
                                        "SELECT relativePath FROM attachments WHERE id='net-501-0'",
                                        null,
                                    )
                                    .use {
                                        it.moveToFirst()
                                        it.getString(0)
                                    }
                            cache.history(db, 2, page(oldBlob, 2))
                            val kept = path()
                            cache.history(db, 2, page(newBlob, 3))
                            kept to path()
                        },
                        callback,
                    )
                }
            assertEquals(
                "A matching upload should not be downloaded again",
                "objects/own-original.jpg",
                paths.first,
            )
            assertEquals(
                "A new server blob must replace the old local photo path",
                "remote/$newBlob.jpg",
                paths.second,
            )
        } finally {
            await<Unit> { store.close(it) }
            root.deleteRecursively()
        }
    }
}
