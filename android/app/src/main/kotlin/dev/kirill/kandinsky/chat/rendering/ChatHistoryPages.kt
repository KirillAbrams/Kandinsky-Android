// Лента на андроиде читает порядок и размеры сообщений отдельно от их текста и файлов. Тяжёлое
// построение указателя проходит в фоне. На экране остаются только нужные порции, а новое
// изменение возвращает человека к тому же месту чтения.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.diagnostics.MetricsRuntime
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.storage.ChatRepository
import java.util.concurrent.*
import kotlin.math.*

// Снимаем ожидающий вызов перед передачей результата. Повторное уведомление не должно снова
// открыть уже показанный экран.
internal fun ChatScrollHost.finishOpening(result: Result<Unit>) {
    val callback = opening
    opening = null
    callback?.invoke(result)
}

// Принимаем ошибку только от текущей работы. При первом открытии возвращаем её вызывающему коду,
// а для уже видимого чата показываем через отдельный обработчик.
private fun ChatScrollHost.indexFailed(error: Throwable, ticket: Int) {
    if (!closed && ticket == generation) {
        indexRefreshing = false
        val wasOpening = opening != null
        finishOpening(Result.failure(error))
        if (!wasOpening) onError?.invoke(error)
    }
}

// Отменяем незавершённое открытие и запоминаем новый чат, хранилище и место чтения. Читаем
// сведения и строим указатель, принимая ответ только по текущему номеру.
internal fun ChatScrollHost.openHistory(
    chatID: ChatID,
    repository: ChatRepository,
    anchor: ReadingAnchor?,
    completion: (Result<Unit>) -> Unit,
) {
    if (closed) {
        completion(Result.failure(RepoException(RepoError.CANCELLED)))
        return
    }
    finishOpening(Result.failure(RepoException(RepoError.CANCELLED)))
    opening = completion
    this.chatID = chatID
    this.repository = repository
    pages = PageWindow(chatID, repository)
    pendingAnchor = anchor ?: ReadingAnchor()
    generation++
    val ticket = generation
    repository.index(chatID) { result ->
        if (!closed && ticket == generation)
            result.fold(
                { rows ->
                    buildIndex(rows, emptyMap(), ticket) { prepared ->
                        index = prepared
                        setGeometry(pendingAnchor ?: ReadingAnchor())
                        schedule()
                        finishOpening(Result.success(Unit))
                    }
                },
                { indexFailed(it, ticket) },
            )
    }
}

// Собираем указатель в фоне, сохраняя измеренные размеры строк той же редакции. Для новых оценок
// учитываем плотность экрана. Законченный указатель возвращаем потоку экрана, если чат ещё ждёт
// этот ответ.
private fun ChatScrollHost.buildIndex(
    rows: List<IndexedRow>,
    previous: Map<MessageID, IndexedRow>,
    ticket: Int,
    completion: (HistoryIndex) -> Unit,
) {
    try {
        buildPool.execute {
            try {
                val scaled =
                    rows.map { row ->
                        previous[row.messageID]?.takeIf { it.revision == row.revision }
                            ?: row.copy(estimatedHeight = row.estimatedHeight * d)
                    }
                val value = HistoryIndex(dayGap = 36.0 * d)
                value.replace(scaled, ReadingAnchor(), 0.0)
                main.post { if (!closed && ticket == generation) completion(value) }
            } catch (t: Exception) {
                main.post { indexFailed(t, ticket) }
            }
        }
    } catch (t: RejectedExecutionException) {
        indexFailed(t, ticket)
    }
}

// Сначала пробуем поправить карточки на месте. При остальных изменениях обновляем просмотр и
// заново собираем сведения, удерживая прежние строки по редакции и порядку. Отправленный с этого
// устройства запрос переводит ленту в конец.
internal fun ChatScrollHost.applyHistoryChange(delta: MessageDelta) {
    if (closed) return
    val repo = repository ?: return
    val id = chatID ?: return
    if (!indexRefreshing && patchStudioRows(delta)) return
    playback?.apply(delta)
    mediaGallery?.apply(delta)
    indexRefreshing = true
    val anchor =
        if (
            delta.reason == MessageChangeReason.LOCAL_SEND &&
                delta.inserted.any { it.direction == MessageDirection.OUTGOING }
        )
            ReadingAnchor(followsBottom = true)
        else
            ReadingAnchorResolver.afterRemoving(
                readingAnchor(),
                index.rows,
                delta.removedIDs.toSet(),
            )
    pendingAnchor = anchor
    generation++
    val ticket = generation
    repo.index(id) { result ->
        if (!closed && ticket == generation)
            result.fold(
                { rows ->
                    buildIndex(rows, index.rows.associateBy { it.messageID }, ticket) { prepared
                        ->
                        val retained =
                            loaded.values.filter { row ->
                                prepared
                                    .position(row.id)
                                    ?.let { prepared.rows[it] }
                                    ?.let {
                                        it.revision == row.revision && it.order == row.order
                                    } == true
                            }
                        pages?.close()
                        pages = PageWindow(id, repo).also { it.seedKnownRows(retained) }
                        pageLoading = false
                        loaded = retained.associateBy { it.id }
                        indexRefreshing = false
                        index = prepared
                        restoreCachedRowHeights(anchor)
                        setGeometry(anchor)
                        onCountChanged?.invoke(index.rows.size)
                        schedule()
                        finishOpening(Result.success(Unit))
                    }
                },
                { indexFailed(it, ticket) },
            )
    }
}

// Проверяем, что изменились только существующие карточки без смены места. Обновляем готовый
// рисунок и высоту, если она разошлась с прежней. Проигрыватель и галерею трогаем только при
// смене файлов.
private fun ChatScrollHost.patchStudioRows(delta: MessageDelta): Boolean {
    if (delta.inserted.isNotEmpty() || delta.removedIDs.isNotEmpty() || delta.updated.isEmpty())
        return false
    if (
        !delta.updated.all { row ->
            val entry = index.position(row.id)?.let { index.rows[it] }
            (row.studioGeneration != null || loaded[row.id]?.studioGeneration != null) &&
                entry != null &&
                entry.order == row.order &&
                entry.savedAtMs == row.savedAtMs
        }
    )
        return false
    val anchor = readingAnchor()
    var heightChanged = false
    var mediaChanged = false
    val changed = loaded.toMutableMap()
    for (row in delta.updated) {
        index.refreshRevision(row)
        pages?.refreshKnownRow(row)
        val old = changed[row.id] ?: continue
        if (old.revision >= row.revision) continue
        mediaChanged = mediaChanged || old.attachments != row.attachments
        changed[row.id] = row
        val resource = PreparedText(row, layoutKey(row))
        cache.insert(resource)
        val size = measuredRowHeight(resource)
        val previous = index.height(row.id)
        if (previous != null && kotlin.math.abs(previous - size) > 0.5) {
            index.updateHeight(row.id, size, anchor, height.toDouble())
            heightChanged = true
        }
    }
    loaded = changed
    if (mediaChanged) {
        playback?.apply(delta)
        mediaGallery?.apply(delta)
    }
    if (heightChanged) setGeometry(anchor)
    schedule()
    return true
}

// Держим один запрос порции за раз и отбрасываем ответы старого окна. Возвращаем измеренные
// высоты из памяти для уже подготовленных строк, иначе они могли бы остаться на примерных
// размерах.
internal fun ChatScrollHost.requestPage(center: OrderKey) {
    if (pageLoading) return
    val p = pages ?: return
    val id = chatID ?: return
    pageRequests++
    if (MetricsRuntime.enabled) MetricsRuntime.recorder.peak("page_requests", pageRequests)
    pageLoading = true
    p.request(PageRequest(id, center, PageDirection.AROUND)) { result ->
        if (closed || pages !== p) return@request
        pageLoading = false
        if (!closed)
            result.fold(
                {
                    val anchor = readingAnchor()
                    loaded = p.loadedRows.associateBy { it.id }
                    // Сохранённый фрагмент не проходит новую подготовку, поэтому его точную
                    // высоту восстанавливаем отдельно
                    if (restoreCachedRowHeights(anchor)) setGeometry(anchor)
                    schedule()
                },
                {
                    finishInitialFrame(Result.failure(it))
                    if (it !is RepoException || it.reason != RepoError.CANCELLED)
                        onError?.invoke(it)
                },
            )
    }
}
