// Подготовка кадра ленты на андроиде читает нужные строки, собирает их рисунки и задаёт сцене,
// какие файлы видны. Измерения и изменение позиции применяются вместе, чтобы текст не сдвигался
// между касанием и кадром.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.diagnostics.MetricsRuntime
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import kotlin.math.*

internal data class PreparedChatRowWindow(
    val range: IntRange,
    val geometryRevision: Int,
    val layoutRevision: Int,
    val rows: List<Row>,
) {
    data class Row(val message: Message, val layout: PreparedText, val x: Float, val top: Double)
}

// Учитываем редакцию, шрифт, плотность и свободную ширину, а для вложения ещё пропорции с
// поворотом. Готовый рисунок применяем только к совпавшим условиям.
internal fun ChatScrollHost.layoutKey(row: Message): TextLayoutKey {
    val viewport = if (layoutWidth > 0) layoutWidth else width
    val visual =
        row.attachments.isNotEmpty() &&
            row.kind in
                listOf(
                    MessageKind.PHOTO,
                    MessageKind.VIDEO,
                    MessageKind.ROUND,
                    MessageKind.GIF,
                    MessageKind.STICKER,
                )
    val a = row.attachments.firstOrNull()
    val rw = if (a != null && a.orientation >= 5) a.rawHeight else a?.rawWidth
    val rh = if (a != null && a.orientation >= 5) a.rawWidth else a?.rawHeight
    val result =
        row.direction == MessageDirection.INCOMING &&
            row.attachments.size == 1 &&
            (row.kind == MessageKind.PHOTO || row.kind == MessageKind.VIDEO)
    val studio = row.studioGeneration
    val bound =
        when {
            studio != null && (studio.isActive || studio.slots.isNotEmpty()) ->
                minOf(
                    304 * d,
                    viewport - 20 * d,
                    if (studio.ratio >= 1) 304 * d else viewport * .7f + 8 * d,
                )
            studio != null -> minOf(316 * d, viewport - 24 * d)
            result ->
                minOf(
                    304 * d,
                    viewport - 20 * d,
                    if ((rw ?: 1) >= (rh ?: 1)) 304 * d else viewport * .7f + 8 * d,
                )
            row.kind == MessageKind.ROUND -> viewport * .6
            visual -> if ((rw ?: 1) >= (rh ?: 1)) viewport - 60 * d else viewport * .7 + 4 * d
            else -> minOf(viewport * .76, 280.0 * d)
        }
    val allocation =
        if (row.kind == MessageKind.ROUND) minOf(viewport - 12.0 * d, bound.toDouble() + 36 * d)
        else bound
    return TextLayoutKey(
        row.id,
        row.revision,
        max(60, allocation.toInt()),
        layoutFontSize,
        1,
        d,
        roundDiameter = if (row.kind == MessageKind.ROUND) bound.toInt() else null,
    )
}

// Решаем, от какой стороны вести строку, и оставляем её поля по виду сообщения. Карточки и кнопки
// результата выравниваются одинаково с остальной лентой.
internal fun ChatScrollHost.rowLeft(row: Message, prepared: PreparedText): Float {
    prepared.generationCard?.let {
        return (if (it.isBatch || it.generation.isActive) 10 else 6) * d
    }
    if (prepared.mediaActions.isNotEmpty()) return 10 * d
    val media = prepared.mediaRect != null
    return if (row.direction == MessageDirection.OUTGOING)
        width - prepared.width - (if (media) 6 else 12) * d
    else (if (media) 6 else 18) * d
}

// Повторно используем полное окно, пока совпадает диапазон и версии размеров. Запрашиваем
// ближайшие недостающие сообщения и ставим видимый расчёт первым. Дальние расчёты ограничиваем,
// чтобы не задержать следующий жест.
private fun ChatScrollHost.preparedRows(
    window: IntRange,
    current: IntRange,
): List<PreparedChatRowWindow.Row> {
    preparedRowWindow?.let { prepared ->
        if (prepared.range == window &&
            prepared.geometryRevision == index.geometryRevision &&
            prepared.layoutRevision == cache.revision
        ) return prepared.rows
    }
    preparedRowWindow = null
    val ready = ArrayList<PreparedChatRowWindow.Row>(max(0, window.last - window.first + 1))
    val pivot = current.first + (current.last - current.first + 1) / 2
    data class MissingLayout(val row: Message, val key: TextLayoutKey, val distance: Double)
    val missingLayouts = mutableListOf<MissingLayout>()
    val wanted = mutableSetOf<TextLayoutKey>()
    val visibleLayouts = mutableSetOf<TextLayoutKey>()
    var missing: IndexedRow? = null
    var missingDistance = Int.MAX_VALUE
    for (position in window) {
        val meta = index.rows[position]
        val row = loaded[meta.messageID]
        if (row == null) {
            val distance = abs(position - pivot)
            if (distance < missingDistance) {
                missing = meta
                missingDistance = distance
            }
            continue
        }
        val key = layoutKey(row)
        wanted += key
        val top = index.top(row.id) ?: continue
        val layout = cache.value(key)
        if (layout == null) {
            if (position in current) visibleLayouts += key
            if (key !in inFlight) {
                val distance = maxOf(0.0, offset - (top + meta.estimatedHeight), top - (offset + height))
                missingLayouts += MissingLayout(row, key, distance)
            }
        } else {
            ready += PreparedChatRowWindow.Row(row, layout, rowLeft(row, layout), top)
        }
    }
    missing?.let { requestPage(it.order) }
    for ((key, token) in inFlight) if (key !in wanted) token.cancel()
    worker.prioritizeVisible(visibleLayouts)
    missingLayouts.sortBy { it.distance }
    var offscreenRequests = 0
    for ((row, key) in missingLayouts) {
        if (key !in visibleLayouts) {
            if (offscreenRequests >= 8) continue
            offscreenRequests++
        }
        prepareRowLayout(row, key)
    }
    // Пока окно готово, прокрутка меняет только положение строк на экране.
    if (ready.size == window.last - window.first + 1) {
        preparedRowWindow = PreparedChatRowWindow(
            window, index.geometryRevision, cache.revision, ready,
        )
    }
    return ready
}

// Передаём строку фоновому счётчику размеров только один раз на ключ. Принимаем кадр по текущему
// номеру сцены и откладываем его высоту до согласования ленты.
private fun ChatScrollHost.prepareRowLayout(row: Message, key: TextLayoutKey) {
    if (key in inFlight) return
    val ticket = scene.ticket
    inFlight[key] = worker.prepare(row, key, ticket) { t, value ->
        inFlight.remove(key)
        if (value != null && scene.accept(value, t)) {
            cache.insert(value)
            pendingHeights[row.id] = value.key to measuredRowHeight(value)
        }
        schedule()
    }
}

// Применяем накопленные измерения только для того же сообщения и ключа. Пересчитываем точку один
// раз. Во время прокрутки оставляем строку под пальцем вместо перехода в конец.
private fun ChatScrollHost.applyPreparedHeights() {
    if (pendingHeights.isEmpty()) return
    var anchor = readingAnchor()
    if (dragging || !scroller.isFinished) anchor = anchor.copy(followsBottom = false)
    var changed = false
    for ((id, update) in pendingHeights) {
        val (key, rowHeight) = update
        val row = loaded[id] ?: continue
        if (layoutKey(row) != key || index.height(id) == null || index.height(id) == rowHeight) continue
        index.updateHeight(id, rowHeight, anchor, height.toDouble())
        changed = true
    }
    pendingHeights.clear()
    // Сдвигаем позицию один раз перед рисованием, сохраняя строку под пальцем.
    if (changed) setGeometry(anchor)
}

// Сначала поправляем размеры, затем собираем видимую сцену и при включённой диагностике
// записываем время и объём. После этого запрашиваем рисунок и сообщаем открытию, готов ли первый
// кадр.
internal fun ChatScrollHost.renderVisibleRows() {
    if (width <= 0 || height <= 0 || closed) return
    applyPreparedHeights()
    val renderToken = MetricsRuntime.recorder.begin()
    val tracing = MetricsRuntime.enabled
    val fragments = if (tracing) {
        android.os.Trace.beginSection("Kandinsky.Scene")
        try {
            applyVisibleScene()
        } finally {
            android.os.Trace.endSection()
        }
    } else {
        applyVisibleScene()
    }
    if (tracing) {
        android.os.Trace.setCounter("Kandinsky.RenderedOffsetMilliPx", (offset * 1000).toLong())
        MetricsRuntime.recorder.end("scene_cpu_ms", renderToken)
        MetricsRuntime.recorder.peak("loaded_messages", loaded.size)
        MetricsRuntime.recorder.peak("history_rows", index.rows.size)
        MetricsRuntime.recorder.peak("scene_fragments", scene.metrics.liveFragments)
        MetricsRuntime.recorder.peak("active_gif_owners", scene.metrics.activeFrameClocks)
        MetricsRuntime.recorder.peak("text_cache_bytes", cache.bytes)
        MetricsRuntime.recorder.peak("image_owned_bytes", viewerMedia?.bytesUsed ?: 0)
    }
    updateDisplayHint()
    invalidate()
    publishInitialFrame(fragments)
}

// Выбираем видимые и соседние строки по текущему смещению и скорости. Передаём фрагменты сцене,
// совмещаем проигрыватель и обновляем список озвучиваемых действий. Частые уведомления
// доступности объединяем, чтобы прокрутка не отправляла одно событие на каждый кадр.
private fun ChatScrollHost.applyVisibleScene(): List<SceneFragment> {
    mediaPreparation.observe(offset, android.os.SystemClock.uptimeMillis() / 1000.0,
        height.toDouble(), d.toDouble())
    val before = minOf(mediaPreparation.before, height * 3.0)
    val after = minOf(mediaPreparation.after, height * 3.0)
    val current = index.visible(max(0.0, offset), height.toDouble(), overscan = 0)
    val runwayTop = max(0.0, offset - before)
    val runway = index.visible(runwayTop, offset + height + after - runwayTop, overscan = 0)
    val start = maxOf(runway.first, current.last - 127)
    val window = start..maxOf(current.last, minOf(runway.last, start + 127))
    val rows = preparedRows(window, current)
    val fragments = mutableListOf<SceneFragment>()
    val accessibility =
        days
            .apply(
                index.days(offset, height.toDouble()),
                offset,
                width,
                d,
                studio = onStudioGenerationAction != null,
            )
            .toMutableList()
    for ((row, resource, x, top) in rows) {
        val rowY = (top - offset).toFloat()
        for (part in resource.blocks.indices) {
            val b = resource.blocks[part]
            val y = rowY + b.offsetY
            if (y + b.height >= -before && y <= height + after && fragments.size < 128)
                fragments +=
                    SceneFragment(resource, part, RectF(x, y, x + resource.width, y + b.height))
        }
        if (rowY + resource.height <= 0 || rowY >= height) continue
        appendRowAccessibility(row, resource, x, rowY, accessibility)
    }
    scene.viewport =
        RectF(
            0f,
            overlayTop.toFloat(),
            width.toFloat(),
            maxOf(overlayTop, height - overlayBottom).toFloat(),
        )
    scene.imageViewport = RectF(0f, 0f, width.toFloat(), height.toFloat())
    scene.imagePreparationViewport =
        RectF(0f, -before.toFloat(), width.toFloat(), (height + after).toFloat())
    scene.apply(SceneSnapshot(scene.ticket, fragments))
    for ((id, itemIndex, rect, label) in scene.loadActions()) if (
        rect.bottom > 0 && rect.top < height
    )
        accessibility +=
            NativeAction(
                1000 + accessibility.size,
                "media.load.$id" + (if (itemIndex == 0) "" else ".$itemIndex"),
                label,
                rect,
                action = { scene.performLoadAction(rect.centerX(), rect.centerY()) },
            )
    updateInlinePlayer()
    nodes = accessibility
    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastAxNotice >= 150) {
        lastAxNotice = now
        ax.notifyChanged()
    } else if (!axNoticePending) {
        axNoticePending = true
        postDelayed(
            {
                axNoticePending = false
                if (!closed) {
                    lastAxNotice = android.os.SystemClock.uptimeMillis()
                    ax.notifyChanged()
                }
            },
            150,
        )
    }
    return fragments
}
