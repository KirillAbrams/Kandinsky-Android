package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.diagnostics.MetricsRuntime
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import kotlin.math.*

// Учитывает шрифт и масштаб, чтобы сохранённая разметка использовалась только при совпадении
// условий
// показа.
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

internal fun ChatScrollHost.renderVisibleRows() {
    if (width <= 0 || height <= 0 || closed) return
    val renderToken = MetricsRuntime.recorder.begin()
    if (MetricsRuntime.enabled) android.os.Trace.beginSection("Kandinsky.Scene")
    lastPreparedOffset?.let { if (offset != it) preparingOlder = offset < it }
    lastPreparedOffset = offset
    val leading = minOf(600.0 * d, height * 0.9)
    val trailing = minOf(320.0 * d, height * 0.4)
    val before = if (preparingOlder) leading else trailing
    val after = if (preparingOlder) trailing else leading
    val current = index.visible(max(0.0, offset), height.toDouble(), overscan = 0)
    val runwayTop = max(0.0, offset - before)
    val runway = index.visible(runwayTop, offset + height + after - runwayTop, overscan = 0)
    val start = maxOf(runway.first, current.last - 127)
    val window = start..maxOf(current.last, minOf(runway.last, start + 127))
    val visible = window.map { index.rows[it] }
    val pivot = current.first + (current.last - current.first + 1) / 2
    window
        .filter { index.rows[it].messageID !in loaded }
        .minByOrNull { kotlin.math.abs(it - pivot) }
        ?.let { requestPage(index.rows[it].order) }
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
    for (meta in visible) {
        val row = loaded[meta.messageID] ?: continue
        val top = index.top(row.id) ?: continue
        val k = layoutKey(row)
        val resource = cache.value(k)
        if (resource == null) {
            if (!inFlight.containsKey(k)) {
                val ticket = scene.ticket
                inFlight[k] =
                    worker.prepare(row, k, ticket) { t, value ->
                        inFlight.remove(k)
                        if (value != null && scene.accept(value, t)) {
                            val anchor = readingAnchor()
                            cache.insert(value)
                            if (
                                loaded[row.id]?.let { layoutKey(it) == value.key } == true &&
                                    index.position(row.id) != null
                            ) {
                                index.updateHeight(
                                    row.id,
                                    measuredRowHeight(value),
                                    anchor,
                                    height.toDouble(),
                                )
                                setGeometry(anchor)
                            }
                            schedule()
                        }
                    }
            }
            continue
        }
        val x = rowLeft(row, resource)
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
    if (MetricsRuntime.enabled) {
        android.os.Trace.endSection()
        MetricsRuntime.recorder.end("scene_cpu_ms", renderToken)
        MetricsRuntime.recorder.peak("loaded_messages", loaded.size)
        MetricsRuntime.recorder.peak("history_rows", index.rows.size)
        MetricsRuntime.recorder.peak("scene_fragments", scene.metrics.liveFragments)
        MetricsRuntime.recorder.peak("active_gif_owners", scene.metrics.activeFrameClocks)
        MetricsRuntime.recorder.peak("text_cache_bytes", cache.bytes)
        MetricsRuntime.recorder.peak("image_owned_bytes", viewerMedia?.bytesUsed ?: 0)
    }
    updateDisplayHint()
    onSceneChanged?.invoke()
    invalidate()
    publishInitialFrame(fragments)
}
