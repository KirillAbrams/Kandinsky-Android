// Подтверждает серверу номер последнего сохранённого изменения. Пока ждёт ответ, держит одну
// отметку, чтобы подтверждения не меняли порядок.

package dev.kirill.kandinsky.chat.network

import org.json.JSONObject

internal class SyncCursorAcknowledgement(private val sync: NetworkSync) {
    private var lookingUp = false
    private var checkAgain = false
    private var inFlight: Long? = null

    // Смотрит в базу после возобновления связи и отправляет неподтверждённую отметку. Если пришёл
    // новый запрос на сверку, помнит об этом. Ответ от старого соединения после разрыва
    // отбрасывается.
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

    // Сверяет пришедший номер с ожидаемым и записывает его в базу. Только успешная запись
    // освобождает место для следующего подтверждения, иначе повторный запуск потерял бы отметку.
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

    // Очищает признаки поиска и ожидания старого ответа. После переподключения подтверждение
    // можно начать заново из базы, не удерживая состояние прежнего канала.
    fun reset() {
        checkAgain = false
        lookingUp = false
        inFlight = null
    }
}
