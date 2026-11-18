// Читает предыдущие страницы по мере прокрутки и сверяет свежие строки видимого чата. У каждого
// чата свой признак загрузки, поэтому несколько событий экрана не запускают одинаковое чтение.

package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.*

internal class SyncHistory(private val sync: NetworkSync) {
    private var currentChat: Long? = null
    private val loadingHistory = mutableSetOf<Long>()
    private val nextHistoryRetry = mutableMapOf<Long, Long>()
    private val visibleHistoryRefreshes = linkedMapOf<Long, Long>()

    // Проверяет местное число строк перед опорой и наличие более старых сообщений. Если пора
    // подгружать, запрашивает порцию и сверяет номер соединения перед записью. После отказа ждёт
    // паузу, прежде чем повторить.
    fun viewport(chat: Long, anchor: ReadingAnchor) {
        sync.worker.post {
            currentChat = chat
            val localID = anchor.messageID ?: return@post
            if (
                chat in loadingHistory || System.currentTimeMillis() < (nextHistoryRetry[chat] ?: 0)
            )
                return@post
            loadingHistory.add(chat)
            sync.database({ db ->
                val before =
                    db.rawQuery(
                            "SELECT MIN(serverSeq) " +
                                "FROM net_messages n " +
                                "JOIN messages m ON m.id=n.localID " +
                                "WHERE m.chatId=? AND serverSeq>0",
                            arrayOf(chat.toString()),
                        )
                        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
                val count =
                    db.rawQuery(
                            "SELECT count(*) " +
                                "FROM messages " +
                                "WHERE chatId=? AND sortKey<(SELECT sortKey " +
                                "FROM messages " +
                                "WHERE id=?)",
                            arrayOf(chat.toString(), localID.toString()),
                        )
                        .use {
                            it.moveToFirst()
                            it.getInt(0)
                        }
                val older =
                    db.rawQuery(
                            "SELECT hasOlder FROM net_chats WHERE chatID=?",
                            arrayOf(chat.toString()),
                        )
                        .use { it.moveToFirst() && it.getInt(0) != 0 }
                if (older && count < 32) before else null
            }) { result ->
                val before = result.getOrNull()
                if (before == null || !sync.active) {
                    loadingHistory.remove(chat)
                    return@database
                }
                val connectionID = sync.connectionEpoch
                sync.http({ sync.api.json("/v1/chats/$chat/history?limit=128&before=$before") }) {
                    page ->
                    if (connectionID != sync.connectionEpoch) {
                        loadingHistory.remove(chat)
                        return@http
                    }
                    page.fold(
                        { json ->
                            sync.database({ sync.cache.history(it, chat, json) }) { applied ->
                                loadingHistory.remove(chat)
                                applied.onSuccess { sync.emit(it) }
                            }
                        },
                        {
                            loadingHistory.remove(chat)
                            nextHistoryRetry[chat] = System.currentTimeMillis() + 5000
                        },
                    )
                }
            }
        }
    }

    // Меняет номер читаемого чата в рабочем потоке и обновляет историю при переходе. Так частые
    // события для одного открытого чата не дублируют сетевой запрос.
    fun visibleChat(chat: Long?) {
        sync.worker.post {
            val changed = currentChat != chat
            currentChat = chat
            if (changed && chat != null) refreshVisibleHistory(chat)
        }
    }

    // Читает последнюю порцию, записывает изменения и сообщает экрану. Запоминает успешное время
    // сверки, чтобы недавний чат не скачивался повторно, а отказ показывает только для текущего
    // чата.
    private fun refreshVisibleHistory(chat: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (sync.resumed && visibleHistoryRefreshes[chat]?.let { now - it < 90_000 } == true) return
        if (!loadingHistory.add(chat)) return
        sync.http({ sync.api.json("/v1/chats/$chat/history?limit=128") }) { page ->
            page.fold(
                { json ->
                    sync.database({ sync.cache.history(it, chat, json) }) { result ->
                        loadingHistory.remove(chat)
                        result.onSuccess {
                            visibleHistoryRefreshes.remove(chat)
                            visibleHistoryRefreshes[chat] = android.os.SystemClock.elapsedRealtime()
                            if (visibleHistoryRefreshes.size > 32)
                                visibleHistoryRefreshes.remove(visibleHistoryRefreshes.keys.first())
                            sync.emit(it)
                        }
                    }
                },
                {
                    loadingHistory.remove(chat)
                    if (currentChat == chat) sync.publishStatus("История временно недоступна")
                },
            )
        }
    }

    // Повторяет сверку для чата, который всё ещё открыт. Без выбранного номера не отправляет
    // запрос, поэтому переподключение на списке чатов не обновляет случайную историю.
    fun refreshCurrentChat() {
        currentChat?.let(::refreshVisibleHistory)
    }
}
