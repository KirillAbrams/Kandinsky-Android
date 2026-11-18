package dev.kirill.kandinsky.chat.diagnostics

import kotlin.math.ceil

data class NumericStatistics(
    val count: Long,
    val retained: Int,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val maximum: Double,
)

class MetricsRecorder(private val capacity: Int = 8192) {
    private class Samples(capacity: Int) {
        val values = DoubleArray(capacity)
        var size = 0
        var next = 0
        var count = 0L
        var maximum = 0.0
    }

    private val samples = linkedMapOf<String, Samples>()
    private val peaks = linkedMapOf<String, Int>()

    init {
        require(capacity > 0)
    }

    // Монотонные часы продолжают отсчёт, даже если пользователь поменял время на телефоне.
    fun begin() = System.nanoTime()

    fun end(name: String, token: Long) = record(name, (System.nanoTime() - token) / 1_000_000.0)

    // Храним только последние замеры. Общее число и максимум считаем с начала работы.
    @Synchronized
    fun record(name: String, milliseconds: Double) {
        if (
            !milliseconds.isFinite() ||
                milliseconds < 0 ||
                !samples.containsKey(name) && samples.size >= 8
        )
            return
        val series = samples.getOrPut(name) { Samples(capacity) }
        series.count++
        series.maximum = maxOf(series.maximum, milliseconds)
        series.values[series.next] = milliseconds
        series.next = (series.next + 1) % capacity
        series.size = minOf(capacity, series.size + 1)
    }

    // Ограничиваем число имён показателей, чтобы диагностика не расходовала всё больше памяти.
    @Synchronized
    fun peak(name: String, value: Int) {
        if (peaks.containsKey(name) || peaks.size < 12) peaks[name] = maxOf(peaks[name] ?: 0, value)
    }

    // Медиану и процентили считаем по последним замерам, максимум берём за всё время.
    @Synchronized
    fun statistics(name: String): NumericStatistics? {
        val series = samples[name] ?: return null
        if (series.size == 0) return null
        val sortedValues = series.values.copyOf(series.size).sorted()
        fun percentile(fraction: Double) =
            sortedValues[maxOf(0, ceil(sortedValues.size * fraction).toInt() - 1)]
        return NumericStatistics(
            series.count,
            series.size,
            percentile(0.5),
            percentile(0.95),
            percentile(0.99),
            series.maximum,
        )
    }

    @Synchronized
    fun rawSamples() =
        samples.mapValues { (_, series) ->
            if (series.size < capacity) series.values.copyOf(series.size).toList()
            else
                (series.values.copyOfRange(series.next, capacity) +
                        series.values.copyOfRange(0, series.next))
                    .toList()
        }

    @Synchronized fun snapshot() = samples.keys.associateWith { statistics(it)!! }

    @Synchronized fun peakSnapshot() = peaks.toMap()
}
