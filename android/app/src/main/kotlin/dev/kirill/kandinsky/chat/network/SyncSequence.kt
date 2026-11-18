// Проверяет порядок событий перед записью в базу телефона. Повтор уже принятой части допустим, но
// пропуск нельзя подтверждать.

package dev.kirill.kandinsky.chat.network

// Уже применённые повторы пропускаются, а пропуск нового события запрещает продвижение курсора
internal object SyncSequence {
    // Начинает с последнего применённого номера, пропускает повторы и требует следующий номер без
    // разрыва. При пропуске останавливает запись, чтобы сеть могла повторить чтение с прежнего
    // места.
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
