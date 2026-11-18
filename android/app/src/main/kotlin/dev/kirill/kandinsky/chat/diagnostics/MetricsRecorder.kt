// Хранит ограниченное число замеров Андроида, считает сводки и максимумы. Последние числа можно
// выгрузить без накопления всего сеанса.

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

    // Проверяет, что окно хранения содержит хотя бы один замер. Нулевой размер сделал бы замену
    // старых значений невозможной.
    init {
        require(capacity > 0)
    }

    // Возвращает время независимого счётчика. Изменение часового пояса и системной даты не меняет
    // длину измеряемой работы.
    fun begin() = System.nanoTime()

    // Переводит разницу между началом и концом в миллисекунды и записывает её. Сводки разных
    // операций получают одинаковую меру.
    fun end(name: String, token: Long) = record(name, (System.nanoTime() - token) / 1_000_000.0)

    // Отбрасывает неверные длительности и новые имена сверх предела. Внутри серии заменяет старые
    // замеры по кругу, сохраняя общий счёт и максимум.
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

    // Запоминает наибольшее значение и ограничивает число разных имён. Так динамический набор
    // показателей не займёт всю память.
    @Synchronized
    fun peak(name: String, value: Int) {
        if (peaks.containsKey(name) || peaks.size < 12) peaks[name] = maxOf(peaks[name] ?: 0, value)
    }

    // Сортирует последние значения и получает срединную и верхние точки. Общий счёт и
    // максимальное значение остаются накопленными за весь период.
    @Synchronized
    fun statistics(name: String): NumericStatistics? {
        val series = samples[name] ?: return null
        if (series.size == 0) return null
        val sortedValues = series.values.copyOf(series.size).sorted()
        // Находит первый элемент на заданной доле сортированного ряда. Округление вверх даёт
        // допустимую точку даже для короткого ряда.
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

    // Копирует сохранённые значения от самого старого к самому новому. Граница кольца не должна
    // развернуть хронологию отчёта.
    @Synchronized
    fun rawSamples() =
        samples.mapValues { (_, series) ->
            if (series.size < capacity) series.values.copyOf(series.size).toList()
            else
                (series.values.copyOfRange(series.next, capacity) +
                        series.values.copyOfRange(0, series.next))
                    .toList()
        }

    // Формирует отдельную сводку по каждому накопленному имени. Выгрузка получает готовые числа,
    // без доступа к рабочему буферу.
    @Synchronized fun snapshot() = samples.keys.associateWith { statistics(it)!! }

    // Возвращает копию максимумов, чтобы запись отчёта не удерживала и не меняла внутреннюю
    // таблицу.
    @Synchronized fun peakSnapshot() = peaks.toMap()
}
