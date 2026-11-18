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
    fun queuedSlotClearsProgressWhileRunningSlotKeepsItsMeasurement() = withReceiptStore { h, _ ->
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
                receiptMessage(947, 1, 2)
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
    fun recentStudioThreadsComeFromTheSavedCatalogWhileOffline() = withReceiptStore { h, cache ->
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
    fun unconfirmedPromptsStayAtTailThroughReplayAndLegacyUpgrade() = withReceiptStore { h, cache ->
        val local = SQLiteChatRepository(h.store, NetworkJournal(1))
        val serverBase = 1_837_550_888_340_132_174L
        val first = SendCommand(UUID.randomUUID().toString(), 2, text = "First queued prompt")
        val second = SendCommand(UUID.randomUUID().toString(), 2, text = "Second queued prompt")
        fun history(vararg rows: JSONObject) =
            JSONObject().put("messages", JSONArray(rows.toList())).put("hasOlder", false)
        fun wire(id: Long, seq: Long, sender: Long, text: String) =
            receiptMessage(id, seq, sender).put("text", text).put("savedAtMs", 1_794_483_289_395L)
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

    // После закрытия синхронизации новое намерение другого сообщения приниматься не должно.
    @Test
    fun contentIntentPersistsOfflineAndClosedSyncIsQuiet() {
        withReceiptStore { h, cache ->
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.bootstrap(
                            db,
                            contentSnapshot(
                                listOf(contentMessage(7601, 1, 2), contentMessage(7602, 2, 2))
                            ),
                        )
                        Unit
                    },
                    cb,
                )
            }
            val rows = contentRows(h)
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
                sync.consume(rows[0].id)
                sync.consume(rows[0].id)
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (
                    System.nanoTime() < end &&
                        h.rawScalar("SELECT count(*) FROM net_pending_content") != 1
                ) android.os.SystemClock.sleep(10)
                assertEquals(1, h.rawScalar("SELECT count(*) FROM net_pending_content"))
                assertEquals(0, sync.contentBatchesSent)
                sync.close()
                sync.consume(rows[1].id)
                android.os.SystemClock.sleep(150)
                assertEquals(1, h.rawScalar("SELECT count(*) FROM net_pending_content"))
            } finally {
                sync.close()
            }
        }
    }

    @Test
    fun contentQueuePersistsOnlyMappedIncomingMedia() {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "content-queue-" + UUID.randomUUID(),
            )
        var h = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
        try {
            var cache = NetworkStore(h.repo, 1)
            val snapshot =
                contentSnapshot(
                    listOf(
                        contentMessage(7001, 1, 2),
                        contentMessage(7002, 2),
                        receiptMessage(7003, 3, 2),
                    )
                )
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        cache.bootstrap(db, snapshot)
                        Unit
                    },
                    cb,
                )
            }
            val before = contentRows(h)
            val delta =
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit({ db -> cache.queueContent(db, before[0].id) }, cb)
                }
            assertEquals(listOf(before[0].id), delta.flatMap { it.updated }.map { it.id })
            val duplicate =
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit(
                        { db ->
                            val result = cache.queueContent(db, before[0].id)
                            cache.queueContent(db, before[1].id)
                            cache.queueContent(db, before[2].id)
                            cache.queueContent(db, Long.MAX_VALUE)
                            result
                        },
                        cb,
                    )
                }
            assertTrue(duplicate.isEmpty())
            assertEquals(0, h.rawScalar("SELECT count(*) FROM net_content_receipts"))
            h.close()
            h = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
            cache = NetworkStore(h.repo, 1)
            val pending =
                await<List<ContentBatch>> { cb ->
                    h.store.submit({ db -> cache.pendingContent(db) }, cb)
                }
            assertEquals(listOf(ContentBatch(2, listOf(7001))), pending)
            val after = contentRows(h)
            assertEquals(listOf(true, false, false), after.map { it.contentRead })
            assertEquals(
                listOf(before[0].revision + 1, before[1].revision, before[2].revision),
                after.map { it.revision },
            )
            assertEquals(0, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
            assertEquals(3, h.rawScalar("PRAGMA user_version"))
        } finally {
            h.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun contentQueueBatchesByChatWithBoundedSelection() {
        withReceiptStore { h, cache ->
            val rows =
                (1..70).map { contentMessage(7100L + it, it.toLong(), 2) } +
                    (1..5).map { contentMessage(7200L + it, it.toLong(), 2).put("chatId", 3) }
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        db.execSQL("INSERT INTO chats(id,title) VALUES(3,'Second')")
                        db.execSQL("INSERT INTO net_chats(chatID,title) VALUES(3,'Second')")
                        cache.bootstrap(db, contentSnapshot(rows))
                        val locals =
                            db.rawQuery("SELECT localID FROM net_messages ORDER BY serverID", null)
                                .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
                        locals.forEach { cache.queueContent(db, it) }
                    },
                    cb,
                )
            }
            val batches =
                await<List<ContentBatch>> { cb ->
                    h.store.submit({ db -> cache.pendingContent(db) }, cb)
                }
            assertEquals(listOf(2L, 2L, 2L, 3L), batches.map { it.chatID })
            assertEquals(listOf(32, 32, 6, 5), batches.map { it.messageIDs.size })
            assertEquals(
                (7101L..7170L).toList() + (7201L..7205L).toList(),
                batches.flatMap { it.messageIDs },
            )
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        for (id in 8001..8200) db.execSQL(
                            "INSERT INTO net_pending_content(serverID,chatID) VALUES(?,4)",
                            arrayOf(id),
                        )
                        db.execSQL(
                            "UPDATE net_pending_content SET errorCode='forbidden' WHERE chatID IN (2,3)"
                        )
                    },
                    cb,
                )
            }
            val bounded =
                await<List<ContentBatch>> { cb ->
                    h.store.submit({ db -> cache.pendingContent(db) }, cb)
                }
            assertEquals(4, bounded.size)
            assertEquals((8001L..8128L).toList(), bounded.flatMap { it.messageIDs })
        }
    }

    @Test
    fun contentQueueWritesFlagAndIntentAtomically() {
        for (table in listOf("net_pending_content", "message_content")) {
            withReceiptStore { h, cache ->
                await<Unit> { cb ->
                    h.store.submit(
                        { db ->
                            cache.bootstrap(db, contentSnapshot(listOf(contentMessage(7301, 1, 2))))
                            db.execSQL(
                                "CREATE TRIGGER content_queue_fault AFTER INSERT ON $table " +
                                    "BEGIN SELECT RAISE(ABORT,'content queue fault'); END"
                            )
                        },
                        cb,
                    )
                }
                val before = contentRows(h)[0]
                val result = runCatching {
                    await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                        h.store.submit({ db -> cache.queueContent(db, before.id) }, cb)
                    }
                }
                assertTrue("The real SQLite fault must reach the caller", result.isFailure)
                assertEquals(0, h.rawScalar("SELECT count(*) FROM net_pending_content"))
                assertEquals(0, h.rawScalar("SELECT count(*) FROM message_content"))
                val after = contentRows(h)[0]
                assertFalse(after.contentRead)
                assertEquals(before.revision, after.revision)
            }
        }
    }

    // Неверный пакет не должен менять очередь или оставлять подтверждения.
    @Test
    fun contentQueueAcknowledgementRequiresExactRequestChatAndIDs() {
        withReceiptStore { h, cache ->
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.bootstrap(
                            db,
                            contentSnapshot(
                                (1..3).map { contentMessage(7400L + it, it.toLong(), 2) }
                            ),
                        )
                        for (id in 7401..7403) db.execSQL(
                            "INSERT INTO net_pending_content(serverID,chatID) VALUES(?,2)",
                            arrayOf(id),
                        )
                    },
                    cb,
                )
            }
            val request = UUID.randomUUID().toString()
            val batch = ContentBatch(2, listOf(7401, 7402))
            val valid =
                org.json
                    .JSONObject()
                    .put("type", "content_applied")
                    .put("requestId", request)
                    .put("chatId", 2)
                    .put("messageIds", org.json.JSONArray(listOf(7402, 7401)))
            val invalid =
                listOf(
                    "type" to "accepted",
                    "requestId" to UUID.randomUUID().toString(),
                    "chatId" to 3,
                    "messageIds" to org.json.JSONArray(listOf(7401)),
                    "messageIds" to org.json.JSONArray(listOf(7401, 7401)),
                    "messageIds" to org.json.JSONArray(listOf(7401, 7403)),
                    "messageIds" to org.json.JSONArray(listOf(true, 7402)),
                    "messageIds" to org.json.JSONArray(listOf(7401.5, 7402)),
                    "messageIds" to org.json.JSONArray(listOf("7401", 7402)),
                )
            for ((key, value) in invalid) {
                val packet = org.json.JSONObject(valid.toString()).put(key, value)
                val result = runCatching {
                    await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                        h.store.submit(
                            { db -> cache.confirmContent(db, packet, request, batch) },
                            cb,
                        )
                    }
                }
                assertTrue(result.isFailure)
                assertEquals(3, h.rawScalar("SELECT count(*) FROM net_pending_content"))
                assertEquals(0, h.rawScalar("SELECT count(*) FROM net_content_receipts"))
            }
            val delta =
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit(
                        { db ->
                            cache.confirmContent(
                                db,
                                valid,
                                request.uppercase(java.util.Locale.ROOT),
                                batch,
                            )
                        },
                        cb,
                    )
                }
            assertEquals(2, delta.flatMap { it.updated }.size)
            assertEquals(1, h.rawScalar("SELECT count(*) FROM net_pending_content"))
            assertEquals(2, h.rawScalar("SELECT count(*) FROM net_content_receipts WHERE userID=1"))
            val duplicate =
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit({ db -> cache.confirmContent(db, valid, request, batch) }, cb)
                }
            assertTrue(duplicate.isEmpty())
            assertEquals(0, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
            assertEquals(
                0,
                h.rawScalar(
                    "SELECT COALESCE((SELECT value FROM net_state WHERE key='ownRead:2'),0)"
                ),
            )
        }
    }

    // Чужая отметка не должна подтверждать наше намерение.
    @Test
    fun contentQueueOnlyOwnReceiptOrDeletionSettlesPending() {
        withReceiptStore { h, cache ->
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.bootstrap(
                            db,
                            contentSnapshot(
                                (1..2).map { contentMessage(7500L + it, it.toLong(), 2) }
                            ),
                        )
                        for (id in 7501..7502) db.execSQL(
                            "INSERT INTO net_pending_content(serverID,chatID) VALUES(?,2)",
                            arrayOf(id),
                        )
                        cache.events(
                            db,
                            org.json.JSONObject(
                                """
                                {
                                  "cursor": 1,
                                  "events": [
                                    {
                                      "seq": 1, "kind": "read", "chatId": 2,
                                      "userId": 3, "through": 0, "contentMessageIds": [7501]
                                    }
                                  ]
                                }
                                """
                            ),
                        )
                        Unit
                    },
                    cb,
                )
            }
            assertEquals(2, h.rawScalar("SELECT count(*) FROM net_pending_content"))
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.events(
                            db,
                            org.json.JSONObject(
                                """
                                {
                                  "cursor": 2,
                                  "events": [
                                    {
                                      "seq": 2, "kind": "read", "chatId": 2,
                                      "userId": 1, "through": 0, "contentMessageIds": [7501]
                                    }
                                  ]
                                }
                                """
                            ),
                        )
                        Unit
                    },
                    cb,
                )
            }
            assertEquals(1, h.rawScalar("SELECT count(*) FROM net_pending_content"))
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.events(
                            db,
                            org.json.JSONObject(
                                """{"cursor":3,"events":[{"seq":3,"kind":"deleted","chatId":2,"messageId":7502}]}"""
                            ),
                        )
                        Unit
                    },
                    cb,
                )
            }
            assertEquals(0, h.rawScalar("SELECT count(*) FROM net_pending_content"))
        }
    }

    @Test
    fun contentReceiptRejectsLoadedTextAndCrossChatAtomically() {
        for (isText in listOf(false, true)) {
            withReceiptStore { h, cache ->
                val message = if (isText) receiptMessage(931, 1, 2) else contentMessage(931, 1, 2)
                val chat = if (isText) 2 else 3
                await<Unit> { cb ->
                    h.store.submit(
                        { db ->
                            cache.bootstrap(db, contentSnapshot(listOf(message)))
                            db.execSQL(
                                "INSERT OR IGNORE INTO chats(id,title) VALUES(3,'Other chat')"
                            )
                            db.execSQL(
                                "INSERT OR IGNORE INTO net_chats(chatID,title) VALUES(3,'Other chat')"
                            )
                        },
                        cb,
                    )
                }
                val packet =
                    org.json
                        .JSONObject()
                        .put("cursor", 1)
                        .put(
                            "events",
                            org.json
                                .JSONArray()
                                .put(
                                    org.json
                                        .JSONObject()
                                        .put("seq", 1)
                                        .put("kind", "read")
                                        .put("chatId", chat)
                                        .put("userId", 1)
                                        .put("through", 0)
                                        .put("contentMessageIds", org.json.JSONArray().put(931))
                                ),
                        )
                assertTrue(
                    "Only loaded media in the matching chat may be consumed",
                    runCatching {
                            await<Unit> { cb ->
                                h.store.submit(
                                    {
                                        cache.events(it, packet)
                                        Unit
                                    },
                                    cb,
                                )
                            }
                        }
                        .isFailure,
                )
                assertEquals(0, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
                assertEquals(0, h.rawScalar("SELECT count(*) FROM net_content_receipts"))
            }
        }
    }

    private fun contentMessage(id: Long, seq: Long, sender: Long = 1) =
        receiptMessage(id, seq, sender)
            .put("kind", "voice")
            .put("text", "")
            .put(
                "attachments",
                org.json
                    .JSONArray()
                    .put(
                        org.json
                            .JSONObject()
                            .put("blobId", "a".repeat(64))
                            .put("mime", "audio/mp4")
                            .put("byteSize", 100)
                            .put("rawWidth", 0)
                            .put("rawHeight", 0)
                            .put("orientation", 1)
                            .put("durationMs", 7000)
                    ),
            )

    private fun contentSnapshot(
        rows: List<org.json.JSONObject>,
        receipts: List<Pair<Long, Long>> = emptyList(),
    ): org.json.JSONObject =
        receiptChats(0)
            .put("version", 1)
            .put("userId", 1)
            .put("cursor", 0)
            .put("messages", org.json.JSONArray(rows))
            .put(
                "contentReceipts",
                org.json.JSONArray(
                    receipts.map {
                        org.json.JSONObject().put("messageId", it.first).put("userId", it.second)
                    }
                ),
            )

    private fun consumed(row: dev.kirill.kandinsky.chat.domain.Message): Boolean =
        runCatching {
                row.javaClass
                    .getDeclaredField("contentRead")
                    .apply { isAccessible = true }
                    .getBoolean(row)
            }
            .getOrDefault(false)

    private fun contentRows(h: dev.kirill.kandinsky.chat.support.RepositoryHarness) =
        await<dev.kirill.kandinsky.chat.domain.HistoryPage> {
                h.repo.history(dev.kirill.kandinsky.chat.domain.PageRequest(2), it)
            }
            .rows

    @Test
    fun contentSnapshotIsActorScopedAndSurvivesReopen() {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "content-snapshot-" + UUID.randomUUID(),
            )
        var h = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
        try {
            var cache = NetworkStore(h.repo, 1)
            val data =
                contentSnapshot(
                    listOf(
                        contentMessage(901, 1),
                        contentMessage(902, 2, 2),
                        contentMessage(903, 3, 2),
                        contentMessage(904, 4),
                    ),
                    listOf(901L to 2L, 902L to 1L, 903L to 3L, 904L to 1L),
                )
            val delta =
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit(
                        { db ->
                            NetworkSchema.install(db)
                            cache.bootstrap(db, data)
                        },
                        cb,
                    )
                }
            assertTrue(
                "Consumed state must reach actual native delta",
                delta.flatMap { it.updated }.any { it.order.sortKey == 1L && consumed(it) },
            )
            h = h.reopen()
            cache = NetworkStore(h.repo, 1)
            assertEquals(listOf(true, true, false, false), contentRows(h).map { consumed(it) })
            assertEquals(
                "Additive flags keep v3 rollback compatibility",
                3,
                h.rawScalar("PRAGMA user_version"),
            )
            assertEquals(0, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
        } finally {
            h.close()
            root.deleteRecursively()
        }
    }

    // Повтор тех же событий и страницы не должен снова увеличивать версии строк.
    @Test
    fun contentEventsBeforeHistoryPersistWithoutWrongActorOrDuplicateRevision() {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "content-replay-" + UUID.randomUUID(),
            )
        var h = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
        try {
            var cache = NetworkStore(h.repo, 1)
            val empty = contentSnapshot(emptyList())
            val events =
                org.json.JSONObject(
                    """
                    {
                      "cursor": 3,
                      "events": [
                        {"seq": 1, "kind": "read", "chatId": 2, "userId": 2, "through": 0, "contentMessageIds": [911]},
                        {"seq": 2, "kind": "read", "chatId": 2, "userId": 3, "through": 0, "contentMessageIds": [912]},
                        {"seq": 3, "kind": "read", "chatId": 2, "userId": 1,
                          "through": 0, "contentMessageIds": [913, 914]}
                      ]
                    }
                    """
                )
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        cache.bootstrap(db, empty)
                        cache.events(db, events)
                        Unit
                    },
                    cb,
                )
            }
            h = h.reopen()
            cache = NetworkStore(h.repo, 1)
            val page =
                org.json
                    .JSONObject()
                    .put(
                        "messages",
                        org.json.JSONArray(
                            listOf(
                                contentMessage(911, 1),
                                contentMessage(912, 2, 2),
                                contentMessage(913, 3, 2),
                                contentMessage(914, 4),
                            )
                        ),
                    )
                    .put("hasOlder", false)
            await<Unit> { cb ->
                h.store.submit(
                    {
                        cache.history(it, 2, page)
                        Unit
                    },
                    cb,
                )
            }
            val first = contentRows(h)
            assertEquals(listOf(true, false, true, false), first.map { consumed(it) })
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.events(db, events)
                        cache.history(db, 2, page)
                        Unit
                    },
                    cb,
                )
            }
            val again = contentRows(h)
            assertEquals(first.map { it.revision }, again.map { it.revision })
            assertEquals(listOf(true, false, true, false), again.map { consumed(it) })
            assertEquals(3, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
        } finally {
            h.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun historyContentSnapshotNeverRegressesConsumedState() {
        withReceiptStore { h, cache ->
            val empty = contentSnapshot(emptyList())
            val page =
                org.json
                    .JSONObject()
                    .put(
                        "messages",
                        org.json.JSONArray(
                            listOf(contentMessage(921, 1), contentMessage(922, 2, 2))
                        ),
                    )
                    .put("hasOlder", false)
                    .put(
                        "contentReceipts",
                        org.json
                            .JSONArray()
                            .put(org.json.JSONObject().put("messageId", 921).put("userId", 2))
                            .put(org.json.JSONObject().put("messageId", 922).put("userId", 1)),
                    )
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.bootstrap(db, empty)
                        cache.history(db, 2, page)
                        Unit
                    },
                    cb,
                )
            }
            val rows = contentRows(h)
            assertEquals(listOf(true, true), rows.map { consumed(it) })
            page.put("contentReceipts", org.json.JSONArray())
            await<Unit> { cb ->
                h.store.submit(
                    {
                        cache.history(it, 2, page)
                        Unit
                    },
                    cb,
                )
            }
            val again = contentRows(h)
            assertEquals(listOf(true, true), again.map { consumed(it) })
            assertEquals(rows.map { it.revision }, again.map { it.revision })
        }
    }

    @Test
    fun malformedContentReceiptRollsBackTheWholeEventBatch() {
        for ((actor, ids) in
            listOf(
                2L to listOf(-1),
                2L to listOf(901, 901),
                2L to (901..933).toList(),
                0L to listOf(901),
            )) {
            withReceiptStore { h, cache ->
                await<Unit> { cb ->
                    h.store.submit(
                        {
                            cache.bootstrap(it, contentSnapshot(emptyList()))
                            Unit
                        },
                        cb,
                    )
                }
                val data =
                    org.json
                        .JSONObject()
                        .put("cursor", 1)
                        .put(
                            "events",
                            org.json
                                .JSONArray()
                                .put(
                                    org.json
                                        .JSONObject()
                                        .put("seq", 1)
                                        .put("kind", "read")
                                        .put("chatId", 2)
                                        .put("userId", actor)
                                        .put("through", 9)
                                        .put("contentMessageIds", org.json.JSONArray(ids))
                                ),
                        )
                val rejected =
                    runCatching {
                            await<Unit> { cb ->
                                h.store.submit(
                                    {
                                        cache.events(it, data)
                                        Unit
                                    },
                                    cb,
                                )
                            }
                        }
                        .isFailure
                assertTrue("Malformed content must reject entire event", rejected)
                assertEquals(0, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
                assertEquals(
                    0,
                    h.rawScalar(
                        "SELECT COALESCE((SELECT value FROM net_state WHERE key='peerRead:2'),0)"
                    ),
                )
            }
        }
    }

    // Входящая строка не должна получать исходящее состояние доставки.
    @Test
    fun bootstrapRestoresPeerReadWithoutOldEventReplay() {
        withReceiptStore { h, cache ->
            val own = receiptMessage(501, 7)
            val packet =
                receiptChats(7, own)
                    .put("version", 1)
                    .put("userId", 1)
                    .put("cursor", 40)
                    .put("messages", org.json.JSONArray().put(own).put(receiptMessage(502, 8, 2)))
            val changes =
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit({ cache.bootstrap(it, packet) }, cb)
                }
            assertEquals("read", receiptState(h, 7))
            assertEquals("local", receiptState(h, 8))
            assertTrue(
                changes
                    .flatMap { it.updated }
                    .any {
                        it.order.sortKey == 7L &&
                            it.delivery == dev.kirill.kandinsky.chat.domain.MessageDelivery.READ
                    }
            )
            assertEquals(40, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
        }
    }

    // Более старая сводка не должна уменьшить эту границу для следующей страницы истории.
    @Test
    fun peerReadSurvivesReopenAndLaterHistoryWithoutRegression() {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "peer-read-reopen-${UUID.randomUUID()}",
            )
        var h = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
        try {
            var cache = NetworkStore(h.repo, 1)
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        cache.mergeChats(db, receiptChats(7))
                        Unit
                    },
                    cb,
                )
            }
            h = h.reopen()
            cache = NetworkStore(h.repo, 1)
            val history =
                org.json
                    .JSONObject()
                    .put(
                        "messages",
                        org.json
                            .JSONArray()
                            .put(receiptMessage(511, 6))
                            .put(receiptMessage(512, 8))
                            .put(receiptMessage(513, 5, 2)),
                    )
                    .put("hasOlder", false)
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.mergeChats(db, receiptChats(3))
                        cache.history(db, 2, history)
                        Unit
                    },
                    cb,
                )
            }
            assertEquals("read", receiptState(h, 6))
            assertEquals("accepted", receiptState(h, 8))
            assertEquals("local", receiptState(h, 5))
            assertEquals(3, h.rawScalar("SELECT count(*) FROM messages"))
            assertEquals(
                "Metadata cannot skip replay",
                0,
                h.rawScalar("SELECT count(*) FROM net_state WHERE key='cursor'"),
            )
        } finally {
            h.close()
            root.deleteRecursively()
        }
    }

    // Собственная граница чтения не должна подтверждать новые исходящие сообщения.
    @Test
    fun peerReadBeforeAcceptedWaitsForMappingAndIsActorScoped() {
        withReceiptStore { h, cache ->
            val local =
                dev.kirill.kandinsky.chat.storage.SQLiteChatRepository(h.store, NetworkJournal(1))
            val command =
                dev.kirill.kandinsky.chat.domain.SendCommand(
                    UUID.randomUUID().toString(),
                    2,
                    text = "Wait for server identity",
                )
            val pending =
                await<dev.kirill.kandinsky.chat.domain.MessageDelta> { local.send(command, it) }
                    .inserted
                    .single()
            val events =
                org.json.JSONObject(
                    """
                    {
                      "type": "events",
                      "cursor": 2,
                      "events": [
                        {"seq": 1, "kind": "read", "chatId": 2, "userId": 2, "through": 9},
                        {"seq": 2, "kind": "read", "chatId": 2, "userId": 1, "through": 99}
                      ]
                    }
                    """
                )
            await<Unit> { cb ->
                h.store.submit(
                    {
                        cache.events(it, events)
                        Unit
                    },
                    cb,
                )
            }
            assertEquals("pending", receiptState(h, id = pending.id))
            val accepted =
                org.json
                    .JSONObject()
                    .put(
                        "messages",
                        org.json
                            .JSONArray()
                            .put(
                                org.json
                                    .JSONObject()
                                    .put("clientId", command.operationID.lowercase())
                                    .put("id", 521)
                                    .put("seq", 9)
                                    .put("savedAtMs", 2000)
                            ),
                    )
            val changes =
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit({ cache.accepted(it, accepted) }, cb)
                }
            assertEquals(
                dev.kirill.kandinsky.chat.domain.MessageDelivery.READ,
                changes.flatMap { it.updated }.first { it.id == pending.id }.delivery,
            )
            val history =
                org.json
                    .JSONObject()
                    .put("messages", org.json.JSONArray().put(receiptMessage(522, 10)))
                    .put("hasOlder", false)
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        cache.events(db, events)
                        cache.history(db, 2, history)
                        Unit
                    },
                    cb,
                )
            }
            assertEquals(
                "Our own read cannot acknowledge our sends",
                "accepted",
                receiptState(h, 10),
            )
            assertEquals("read", receiptState(h, id = pending.id))
            assertEquals(0, h.rawScalar("SELECT count(*) FROM net_outbox"))
        }
    }

    @Test
    fun negativePeerReadRollsBackWithoutAdvancingCursor() {
        withReceiptStore { h, cache ->
            val packet =
                org.json.JSONObject(
                    """
                    {
                      "type": "events",
                      "cursor": 1,
                      "events": [
                        {
                          "seq": 1, "kind": "read", "chatId": 2,
                          "userId": 2, "through": -1
                        }
                      ]
                    }
                    """
                )
            assertTrue(
                "Negative watermark must be rejected",
                runCatching {
                        await<Unit> { cb ->
                            h.store.submit(
                                {
                                    cache.events(it, packet)
                                    Unit
                                },
                                cb,
                            )
                        }
                    }
                    .isFailure,
            )
            assertEquals(0, await<Long> { cb -> h.store.submit({ cache.cursor(it) }, cb) }.toInt())
        }
    }

    private fun withReceiptStore(
        action: (dev.kirill.kandinsky.chat.support.RepositoryHarness, NetworkStore) -> Unit
    ) {
        val root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "peer-read-${UUID.randomUUID()}",
            )
        val h = dev.kirill.kandinsky.chat.support.RepositoryHarness.open(root)
        try {
            await<Unit> { cb ->
                h.store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        db.execSQL("INSERT INTO net_chats(chatID,title) VALUES(2,'Peer')")
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

    private fun receiptState(
        h: dev.kirill.kandinsky.chat.support.RepositoryHarness,
        seq: Long = 0,
        id: Long? = null,
    ): String = await { cb ->
        h.store.submit(
            { db ->
                db.rawQuery(
                        "SELECT deliveryState FROM messages WHERE ${if (id != null) "id" else "sortKey"}=?",
                        arrayOf((id ?: seq).toString()),
                    )
                    .use {
                        check(it.moveToFirst())
                        it.getString(0)
                    }
            },
            cb,
        )
    }

    private fun ownReadChats(read: Long, unread: Long, last: Long = 1000) =
        org.json
            .JSONObject()
            .put(
                "chats",
                org.json
                    .JSONArray()
                    .put(
                        org.json
                            .JSONObject()
                            .put("id", 2)
                            .put("title", "Partial cached tail")
                            .put("pinned", false)
                            .put("unreadCount", unread)
                            .put("lastSeq", last)
                            .put("readSeq", read)
                            .put("peerUserId", 2)
                            .put("peerReadSeq", 0)
                            .put("lastMessage", org.json.JSONObject.NULL)
                    ),
            )

    private fun seedOwnRead(
        h: dev.kirill.kandinsky.chat.support.RepositoryHarness,
        cache: NetworkStore,
        read: Long = 0,
        unread: Long = 1000,
    ) {
        val packet =
            ownReadChats(read, unread)
                .put("version", 1)
                .put("userId", 1)
                .put("cursor", 0)
                .put("messages", org.json.JSONArray().put(receiptMessage(6000, 1000, 2)))
        await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
            h.store.submit({ cache.bootstrap(it, packet) }, cb)
        }
    }

    @Test
    fun ownPartialReadPreservesAuthoritativeCountUntilFreshSummary() {
        withReceiptStore { h, cache ->
            seedOwnRead(h, cache)
            val event =
                org.json.JSONObject(
                    """{"cursor":1,"events":[{"seq":1,"kind":"read","chatId":2,"userId":1,"through":500}]}"""
                )
            await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                h.store.submit({ cache.events(it, event) }, cb)
            }
            assertEquals(
                "One cached tail row cannot replace full-history unread",
                1000,
                h.rawScalar("SELECT unreadCount FROM chats WHERE id=2"),
            )
            assertEquals(
                1,
                h.rawScalar(
                    "SELECT COALESCE((SELECT value FROM net_state WHERE key='readRefresh:2'),0)"
                ),
            )
            await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                h.store.submit({ cache.mergeChats(it, ownReadChats(500, 500)) }, cb)
            }
            assertEquals(500, h.rawScalar("SELECT unreadCount FROM chats WHERE id=2"))
            assertEquals(
                0,
                h.rawScalar(
                    "SELECT COALESCE((SELECT value FROM net_state WHERE key='readRefresh:2'),0)"
                ),
            )
            assertEquals(1, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
        }
    }

    @Test
    fun staleOwnReadSnapshotsAndEventsCannotRegressAfterReopen() {
        withReceiptStore { initial, _ ->
            val cache = NetworkStore(initial.repo, 1)
            seedOwnRead(initial, cache, 500, 500)
            val h = initial.reopen()
            try {
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit(
                        { NetworkStore(h.repo, 1).mergeChats(it, ownReadChats(100, 900)) },
                        cb,
                    )
                }
                assertEquals(500, h.rawScalar("SELECT unreadCount FROM chats WHERE id=2"))
                val event =
                    org.json.JSONObject(
                        """{"cursor":1,"events":[{"seq":1,"kind":"read","chatId":2,"userId":1,"through":100}]}"""
                    )
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit({ NetworkStore(h.repo, 1).events(it, event) }, cb)
                }
                assertEquals(500, h.rawScalar("SELECT unreadCount FROM chats WHERE id=2"))
                assertEquals(
                    500,
                    h.rawScalar(
                        "SELECT COALESCE((SELECT value FROM net_state WHERE key='ownRead:2'),0)"
                    ),
                )
            } finally {
                h.close()
            }
        }
    }

    // Отметка просмотра вложения с той же границей чтения не должна пересчитывать неполный хвост
    // или
    // требовать новую сводку.
    @Test
    fun overlappingSummaryKeepsNewIncomingAndNoOpContentReadDoesNotRecountTail() {
        withReceiptStore { h, cache ->
            seedOwnRead(h, cache, 500, 500)
            val incoming =
                org.json
                    .JSONObject()
                    .put("cursor", 1)
                    .put(
                        "events",
                        org.json
                            .JSONArray()
                            .put(
                                org.json
                                    .JSONObject()
                                    .put("seq", 1)
                                    .put("kind", "message")
                                    .put("chatId", 2)
                                    .put("message", contentMessage(6001, 1001, 2))
                            ),
                    )
            await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                h.store.submit({ cache.events(it, incoming) }, cb)
            }
            assertEquals(501, h.rawScalar("SELECT unreadCount FROM chats WHERE id=2"))
            await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                h.store.submit({ cache.mergeChats(it, ownReadChats(500, 500)) }, cb)
            }
            assertEquals(
                "Snapshot must retain later incoming rows",
                501,
                h.rawScalar("SELECT unreadCount FROM chats WHERE id=2"),
            )
            val consumed =
                org.json.JSONObject(
                    """
                    {
                      "cursor": 2,
                      "events": [
                        {
                          "seq": 2, "kind": "read", "chatId": 2,
                          "userId": 1, "through": 500, "contentMessageIds": [6001]
                        }
                      ]
                    }
                    """
                )
            await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                h.store.submit({ cache.events(it, consumed) }, cb)
            }
            assertEquals(501, h.rawScalar("SELECT unreadCount FROM chats WHERE id=2"))
            assertEquals(
                0,
                h.rawScalar(
                    "SELECT COALESCE((SELECT value FROM net_state WHERE key='readRefresh:2'),0)"
                ),
            )
        }
    }

    @Test
    fun negativeOwnReadRollsBackItsEventCursor() {
        withReceiptStore { h, cache ->
            seedOwnRead(h, cache)
            val invalid =
                org.json.JSONObject(
                    """{"cursor":1,"events":[{"seq":1,"kind":"read","chatId":2,"userId":1,"through":-1}]}"""
                )
            try {
                await<List<dev.kirill.kandinsky.chat.domain.MessageDelta>> { cb ->
                    h.store.submit({ cache.events(it, invalid) }, cb)
                }
                fail("Reject negative own read atomically")
            } catch (_: Exception) {}
            assertEquals(0, h.rawScalar("SELECT value FROM net_state WHERE key='cursor'"))
            assertEquals(1000, h.rawScalar("SELECT unreadCount FROM chats WHERE id=2"))
        }
    }

    private fun receiptMessage(id: Long, seq: Long, sender: Long = 1) =
        org.json
            .JSONObject()
            .put("id", id)
            .put("chatId", 2)
            .put("seq", seq)
            .put("senderId", sender)
            .put("clientId", UUID.randomUUID().toString())
            .put("kind", "text")
            .put("text", "Read restoration fixture")
            .put("caption", "")
            .put("replyToId", org.json.JSONObject.NULL)
            .put("savedAtMs", 1000)
            .put("revision", 1)
            .put("attachments", org.json.JSONArray())

    private fun receiptChats(through: Long, last: org.json.JSONObject? = null) =
        org.json
            .JSONObject()
            .put(
                "chats",
                org.json
                    .JSONArray()
                    .put(
                        org.json
                            .JSONObject()
                            .put("id", 2)
                            .put("title", "Peer")
                            .put("pinned", false)
                            .put("unreadCount", 0)
                            .put("lastSeq", 20)
                            .put("peerUserId", 2)
                            .put("peerReadSeq", through)
                            .put("lastMessage", last ?: org.json.JSONObject.NULL)
                    ),
            )

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
                    .put("senderId", 1001)
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
                                    .put("title", "New peer")
                                    .put("pinned", false)
                                    .put("unreadCount", 1)
                                    .put("lastSeq", 1)
                                    .put("peerUserId", 1001)
                                    .put("lastMessage", message)
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
                        db.execSQL("INSERT INTO chats(id,title) VALUES(2,'Peer')")
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
                db.execSQL("INSERT INTO chats(id,title) VALUES(2,'Peer')")
                db.execSQL("INSERT INTO net_chats(chatID,title) VALUES(2,'Peer')")
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
                    assertEquals(1, it.getInt(0))
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

    // Даже высокая граница прочтения другой стороной не должна подтверждать такую строку.
    @Test
    fun peerReadCannotAcknowledgeAnUnsentLocalMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "network-read-${UUID.randomUUID()}")
        val store = await<SQLiteStore> { SQLiteStore.open(root, callback = it) }
        try {
            await<Unit> {
                store.submit(
                    { db ->
                        NetworkSchema.install(db)
                        db.execSQL("INSERT INTO chats(id,title) VALUES(2,'Peer')")
                        db.execSQL("INSERT INTO net_chats(chatID,title) VALUES(2,'Peer')")
                    },
                    it,
                )
            }
            val local =
                dev.kirill.kandinsky.chat.storage.SQLiteChatRepository(store, NetworkJournal(1))
            val pending =
                await<dev.kirill.kandinsky.chat.domain.MessageDelta> {
                        local.send(
                            dev.kirill.kandinsky.chat.domain.SendCommand(
                                UUID.randomUUID().toString(),
                                2,
                                text = "Not on server yet",
                            ),
                            it,
                        )
                    }
                    .inserted
                    .single()
            val frame =
                org.json.JSONObject(
                    """
                    {
                      "type": "events",
                      "cursor": 1,
                      "events": [
                        {
                          "seq": 1, "kind": "read", "chatId": 2,
                          "userId": 2, "through": 999
                        }
                      ]
                    }
                    """
                )
            await<Unit> { callback ->
                store.submit(
                    {
                        NetworkStore(local, 1).events(it, frame)
                        Unit
                    },
                    callback,
                )
            }
            val state =
                await<String> { callback ->
                    store.submit(
                        { db ->
                            db.rawQuery(
                                    "SELECT deliveryState FROM messages WHERE id=?",
                                    arrayOf(pending.id.toString()),
                                )
                                .use {
                                    it.moveToFirst()
                                    it.getString(0)
                                }
                        },
                        callback,
                    )
                }
            assertEquals(
                "Peer watermark applies only to accepted server sequences",
                "pending",
                state,
            )
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
                        db.execSQL("INSERT INTO chats(id,title) VALUES(2,'Peer')")
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
                            db.execSQL("INSERT OR IGNORE INTO chats(id,title) VALUES(2,'Peer')")
                            db.execSQL(
                                "INSERT OR IGNORE INTO net_chats(chatID,title) VALUES(2,'Peer')"
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
