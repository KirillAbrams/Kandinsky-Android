package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import java.io.File
import java.util.UUID

internal class SyncAttachments(private val sync: NetworkSync) {
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

    // Для повторной загрузки берём тот же номер устройства и вложения. Сервер узнает уже принятый
    // файл.
    fun uploadStudioSource(
        draft: AttachmentDraft,
        file: File,
        transfer: dev.kirill.kandinsky.chat.media.MediaCancellation,
    ): Pair<String, String?> {
        val id =
            UUID.nameUUIDFromBytes((sync.profile.deviceID + ":" + draft.id).toByteArray())
                .toString()
        val blob =
            sync.api
                .upload(file, draft.mime, id, studioSource = true, transfer = transfer)
                .getString("blobId")
        val preview =
            if (draft.mime.startsWith("image/") && !transfer.isCancelled)
                NetworkPreview.upload(sync.store.root, draft, sync.api, sync.profile.deviceID)
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
