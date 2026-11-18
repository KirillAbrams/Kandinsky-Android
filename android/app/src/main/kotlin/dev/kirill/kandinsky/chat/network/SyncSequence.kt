package dev.kirill.kandinsky.chat.network

// Уже применённые повторы пропускаются, а пропуск нового события запрещает продвижение курсора
internal object SyncSequence {
    /**
     * Проверяет ограниченную порцию номеров событий перед сохранением её данных и нового курсора
     * Пропускает уже применённые повторы, но требует непрерывное увеличение каждого нового номера ровно на единицу
     * Возвращает последнюю допустимую границу либо прерывает применение при пропуске или неверном номере
     */
    fun next(current: Long, sequences: List<Long>): Long {
        require(current >= 0 && sequences.size <= 128)
        var applied = current
        for (sequence in sequences) {
            require(sequence > 0)
            if (sequence <= applied) continue
            require(sequence == applied + 1) { "Missing durable update" }
            applied = sequence
        }
        return applied
    }
}
