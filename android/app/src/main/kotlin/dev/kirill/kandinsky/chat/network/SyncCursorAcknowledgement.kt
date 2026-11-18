package dev.kirill.kandinsky.chat.network

import org.json.JSONObject

internal class SyncCursorAcknowledgement(private val sync: NetworkSync) {
    private var lookingUp = false
    private var checkAgain = false
    private var inFlight: Long? = null

    fun acknowledgeSavedCursor() {
        if (!sync.resumed || inFlight != null) return
        if (lookingUp) {
            checkAgain = true
            return
        }
        lookingUp = true
        val epoch = sync.connectionEpoch
        sync.database({ db -> sync.cache.cursorAwaitingConfirmation(db) }) { result ->
            if (sync.connectionEpoch != epoch) return@database
            lookingUp = false
            val requestedDuringLookup = checkAgain
            checkAgain = false
            result.onSuccess { cursor ->
                if (cursor != null && sync.resumed) {
                    inFlight = cursor
                    sync.send(JSONObject().put("type", "applied").put("cursor", cursor))
                } else if (requestedDuringLookup) {
                    acknowledgeSavedCursor()
                }
            }.onFailure { sync.reconnectAfterFailure(epoch) }
        }
    }

    fun confirmSavedCursor(frame: JSONObject) {
        val cursor = frame.getLong("cursor")
        if (inFlight != cursor) return
        val epoch = sync.connectionEpoch
        sync.database({ db -> sync.cache.state(db, "ackedCursor", cursor.toString()) }) { result ->
            if (sync.connectionEpoch != epoch) return@database
            result.onSuccess {
                inFlight = null
                acknowledgeSavedCursor()
            }.onFailure { sync.reconnectAfterFailure(epoch) }
        }
    }

    fun reset() {
        checkAgain = false
        lookingUp = false
        inFlight = null
    }
}
