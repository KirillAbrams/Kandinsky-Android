// Обновляет строки генерации и учёт повторов в местной базе. Ответ, пришедший после обрыва связи,
// можно сопоставить с исходным номером команды.

package dev.kirill.kandinsky.chat.network

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*
import org.json.JSONObject

// Записывает предложенный номер только при отсутствии прежнего, затем читает сохранённый. Даже
// после перезапуска приложения одна и та же команда не превращается в новый запрос.
// При потере ответа телефон повторно отправит уже сохранённый номер.
internal fun NetworkStore.loadOrCreateRetryID(db: SQLiteDatabase, key: String, proposed: java.util.UUID?): String {
    val candidate = (proposed ?: java.util.UUID.randomUUID()).toString()
    db.execSQL(
        "INSERT OR IGNORE INTO net_state(key,value) VALUES(?,?)",
        arrayOf(key, candidate),
    )
    return db.rawQuery("SELECT value FROM net_state WHERE key=?", arrayOf(key)).use { row ->
        check(row.moveToFirst())
        row.getString(0)
    }
}

// Проверяет чат всех возвращённых строк и объединяет их одним изменением базы. Отметка повтора
// снимается в той же записи и только по совпавшему номеру, чтобы не подтвердить чужую попытку.
internal fun NetworkStore.saveGenerationResponse(
    db: SQLiteDatabase,
    chat: Long,
    json: JSONObject,
    retryKey: String? = null,
    operation: String? = null,
): List<MessageDelta> =
    NetworkStore.transaction(db) {
        val messages = json.getJSONArray("messages")
        val touched = linkedSetOf<Long>()
        for (i in 0 until messages.length()) {
            val wire = messages.getJSONObject(i)
            require(wire.getLong("chatId") == chat)
            merge(db, wire)?.let(touched::add)
        }
        if (retryKey != null && operation != null) {
            db.delete("net_state", "key=? AND value=?", arrayOf(retryKey, operation))
        }
        deltas(db, mapOf(chat to touched), emptyMap(), MessageChangeReason.SYNC)
    }
