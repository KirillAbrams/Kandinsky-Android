package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.io.File
import java.util.UUID
import org.json.JSONObject

internal class SyncAttachments(private val sync: NetworkSync) {
    fun savedStudioDraft(completion: (Result<StudioDraftRecord?>) -> Unit) =
        sync.store.submit(StudioDraftStorage::load, completion)

    fun beginStudioDraft(
        record: StudioDraftRecord,
        completion: (Result<StudioDraftRecord?>) -> Unit,
    ) =
        sync.store.submit(
            { db ->
                NetworkStore.transaction(db) {
                    val previous = StudioDraftStorage.load(db)
                    StudioDraftStorage.write(db, record)
                    previous
                }
            },
            completion,
        )

    fun saveStudioDraft(draft: Draft, identity: UUID, completion: (Result<Unit>) -> Unit) =
        sync.store.submit(
            { db ->
                NetworkStore.transaction(db) {
                    if (StudioDraftStorage.load(db)?.identity == identity)
                        StudioDraftStorage.write(db, StudioDraftRecord(identity, draft))
                }
            },
            completion,
        )

    fun finishStudioDraft(identity: UUID, completion: (Result<Unit>) -> Unit) =
        sync.store.submit(
            { db ->
                NetworkStore.transaction(db) {
                    if (StudioDraftStorage.load(db)?.identity == identity)
                        db.execSQL(
                            "DELETE FROM net_state WHERE key=?",
                            arrayOf(StudioDraftStorage.KEY),
                        )
                }
            },
            completion,
        )

    fun acceptStudioTransfers(value: JSONObject) {
        val connection = sync.connectionEpoch
        val rows = value.getJSONArray("uploads")
        require(rows.length() <= 8)
        val transfers =
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                StudioTransfer(
                    row.getString("id"),
                    row.getString("deviceId"),
                    row.getLong("received"),
                    row.getLong("total"),
                    row.getString("mime"),
                )
            }
        require(
            transfers.all { it.isValid } && transfers.map { it.id }.toSet().size == transfers.size
        )
        sync.main.post {
            if (!sync.closed && connection == sync.connectionEpoch) {
                sync.studioTransfers = transfers
                sync.onStudioTransfersChanged?.invoke()
            }
        }
    }

    fun recentStudioUploads(completion: (Result<List<StudioUpload>>) -> Unit) {
        sync.worker.post {
            if (sync.closed) return@post
            sync.http({
                val rows = sync.api.json("/v1/kandinsky/uploads/recent").getJSONArray("uploads")
                List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    StudioUpload(
                        row.getString("blobId"),
                        row.getString("mime"),
                        row.getLong("byteSize"),
                    )
                }
            }) { result ->
                sync.main.post { completion(result) }
            }
        }
    }

    fun rememberStudioUpload(
        attachmentID: String,
        blobID: String,
        previewID: String? = null,
        completion: (Result<Unit>) -> Unit,
    ) {
        sync.worker.post {
            sync.database({ db ->
                db.execSQL(
                    "INSERT OR REPLACE INTO net_uploads(attachmentID,blobID,previewBlobID) VALUES(?,?,?)",
                    arrayOf(attachmentID, blobID, previewID),
                )
            }) { result ->
                sync.main.post { completion(result) }
            }
        }
    }

    // Номер вложения сохраняется и после нового входа, поэтому сервер узнает повторную загрузку.
    fun uploadStudioSource(
        draft: AttachmentDraft,
        file: File,
        transfer: dev.kirill.kandinsky.chat.media.MediaCancellation,
        chat: Long,
    ): Pair<String, String?> {
        val id = draft.id.lowercase()
        val blob =
            sync.api
                .upload(file, draft.mime, id, studioSource = true, transfer = transfer, chat = chat)
                .getString("blobId")
        val preview =
            if (draft.mime.startsWith("image/") && !transfer.isCancelled)
                NetworkPreview.upload(sync.store.root, draft, sync.api, parent = blob)
            else null
        return blob to preview
    }

    private var removingMedia = false

    fun drainRemovedStudioMedia() {
        val media = sync.studioMedia ?: return
        if (sync.closed || removingMedia) return
        removingMedia = true
        sync.database({ db ->
            db.rawQuery(
                    "SELECT key,value FROM net_state WHERE key LIKE 'studioRemoved:%' LIMIT 16",
                    null,
                )
                .use { rows ->
                    buildList {
                        while (rows.moveToNext()) StudioMediaRemoval.parse(
                                rows.getString(0).removePrefix(StudioMediaRemoval.PREFIX),
                                rows.getString(1),
                            )
                            ?.let { add(it) }
                    }
                }
        }) { result ->
            val batch = result.getOrNull()
            if (batch.isNullOrEmpty()) {
                removingMedia = false
                return@database
            }
            sync.http({ for (item in batch) media.removeCached(item) }) { removed ->
                if (removed.isFailure) {
                    removingMedia = false
                    return@http
                }
                sync.main.post { sync.onStudioMediaEvicted?.invoke() }
                sync.database({ db ->
                    for (item in batch) db.delete(
                        "net_state",
                        "key=? AND value=?",
                        arrayOf(item.key, item.preview),
                    )
                }) { saved ->
                    removingMedia = false
                    if (saved.isSuccess) drainRemovedStudioMedia()
                }
            }
        }
    }
}

internal object StudioDraftStorage {
    const val KEY = "studio.newDraft"

    fun load(db: android.database.sqlite.SQLiteDatabase): StudioDraftRecord? =
        db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf(KEY)).use { rows ->
            if (!rows.moveToFirst()) null
            else {
                val raw = rows.getString(0)
                require(raw.toByteArray().size <= 262144)
                val row = JSONObject(raw)
                StudioDraftRecord(
                    UUID.fromString(row.getString("identity")),
                    ChatStorageCodec.decodeDraft(row.getJSONObject("draft").toString(), ""),
                )
            }
        }

    fun write(db: android.database.sqlite.SQLiteDatabase, record: StudioDraftRecord) {
        val raw =
            JSONObject()
                .put("identity", record.identity.toString())
                .put("draft", JSONObject(ChatStorageCodec.draft(record.draft)))
                .toString()
        require(raw.toByteArray().size <= 262144)
        db.execSQL("INSERT OR REPLACE INTO net_state(key,value) VALUES(?,?)", arrayOf(KEY, raw))
    }
}
