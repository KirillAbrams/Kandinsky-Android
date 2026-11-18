package dev.kirill.kandinsky.chat.rendering

import kotlin.math.abs

internal class MediaPreparationWindow {
    var before = 500.0
        private set

    var after = 320.0
        private set

    private var offset: Double? = null
    private var timestamp: Double? = null

    fun observe(next: Double, now: Double, viewport: Double, density: Double) {
        if (!next.isFinite() || !now.isFinite() || !viewport.isFinite() || viewport <= 0) return
        val ceiling = minOf(4096 * density, viewport * 3)
        before = minOf(before, ceiling)
        after = minOf(after, ceiling)
        val previous = offset
        val at = timestamp
        if (previous == null || at == null) {
            offset = next
            timestamp = now
            before = minOf(500 * density, ceiling)
            after = minOf(320 * density, viewport * 0.4)
            return
        }
        val elapsed = now - at
        if (elapsed < 1.0 / 240) return
        offset = next
        timestamp = now
        if (elapsed > 0.25) {
            before = minOf(500 * density, ceiling)
            after = minOf(320 * density, viewport * 0.4)
            return
        }
        val delta = next - previous
        if (abs(delta) <= 0.01) return
        // Даём сети время до появления строки, не заглядывая дальше трёх экранов.
        val lead =
            minOf(
                ceiling,
                500 * density + abs(delta) / elapsed * 0.35,
            )
        val trail = minOf(320 * density, viewport * 0.4)
        before = if (delta < 0) lead else trail
        after = if (delta < 0) trail else lead
    }

    fun rebase(delta: Double) {
        if (delta.isFinite()) offset = offset?.plus(delta)
    }
}
