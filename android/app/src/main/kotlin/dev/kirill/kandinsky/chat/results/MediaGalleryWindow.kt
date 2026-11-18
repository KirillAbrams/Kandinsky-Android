// Хранит текущий и ближайшие файлы одного чата для перелистывания. Сохраняет выбор при
// добавлении страниц и ограничивает общий размер окна.

package dev.kirill.kandinsky.chat.results

import dev.kirill.kandinsky.chat.domain.*

data class MediaGalleryEntry(
    val messageID: MessageID,
    val chatID: ChatID,
    val order: OrderKey,
    val kind: MessageKind,
    val caption: String,
    val savedAtMs: Long,
    val attachmentIndex: Int,
    val attachment: Attachment,
    val isIncoming: Boolean,
) {
    val id
        // Собирает постоянный номер из сообщения и результата либо позиции в альбоме. С ним новый
        // снимок страницы может заменить тот же файл без дубля.
        get() =
            "$messageID/" +
                if (attachment.id.startsWith("net-") && attachment.id.contains("-result-"))
                    attachment.id
                else attachmentIndex.toString()

    val isVideo
        // Определяет видео по типу вложения. Это помогает выбрать просмотр ролика вместо
        // отдельного показа снимка.
        get() = attachment.mime.startsWith("video/")

    // Сверяет свойства местного файла, важные для вывода. Если эти поля совпадают повторная
    // подготовка не нужна.
    fun hasSameMedia(other: MediaGalleryEntry) =
        attachment.relativePath == other.attachment.relativePath &&
            attachment.mime == other.attachment.mime &&
            attachment.byteSize == other.attachment.byteSize &&
            attachment.rawWidth == other.attachment.rawWidth &&
            attachment.rawHeight == other.attachment.rawHeight &&
            attachment.orientation == other.attachment.orientation

    companion object {
        // Собирает фото и видео из сообщений подходящих видов в порядке вложений. Другие строки
        // не занимают места в галерее.
        fun entries(row: Message): List<MediaGalleryEntry> {
            if (row.kind !in listOf(MessageKind.PHOTO, MessageKind.VIDEO, MessageKind.GIF))
                return emptyList()
            return row.attachments.mapIndexedNotNull { index, a ->
                if (a.mime.startsWith("image/") || a.mime.startsWith("video/"))
                    MediaGalleryEntry(
                        row.id,
                        row.chatID,
                        row.order,
                        row.kind,
                        row.caption,
                        row.savedAtMs,
                        index,
                        a,
                        row.direction == MessageDirection.INCOMING,
                    )
                else null
            }
        }
    }
}

class MediaGalleryWindow(seed: Message, attachmentIndex: Int = 0) {
    companion object {
        const val MAXIMUM_ENTRIES = 96
        private val order = compareBy<MediaGalleryEntry>({ it.order }, { it.attachmentIndex })
    }

    val chatID = seed.chatID
    var entries = MediaGalleryEntry.entries(seed)
        private set

    private var selectedID =
        entries.firstOrNull { it.attachmentIndex == attachmentIndex }?.id
            ?: entries.firstOrNull()?.id
    val selectedIndex
        // Находит номер позиции по постоянному имени выбранного файла. При вставке более старых
        // кадров прежний выбор не сдвинется на другое изображение.
        get() = entries.indexOfFirst { it.id == selectedID }.takeIf { it >= 0 }

    val selected
        // Возвращает выбранный файл, если его позиция ещё есть. Пустое окно не возвращает
        // произвольный элемент.
        get() = selectedIndex?.let { entries[it] }

    var hasOlder = true
        private set

    var hasNewer = true
        private set

    val neighbours
        // Даёт выбранный файл и по одному соседу с двух сторон. Просмотр готовит только ближайшие
        // страницы.
        get() =
            selectedIndex?.let { entries.subList(maxOf(0, it - 1), minOf(entries.size, it + 2)) }
                ?: emptyList()

    val needsPage
        // Просит следующую порцию при приближении к краю, если за ним есть данные. При
        // достигнутом конце лишняя загрузка не начинается.
        get() =
            selectedIndex?.let { it < 4 && hasOlder || entries.size - 1 - it < 4 && hasNewer }
                ?: false

    // Обрезает исходный набор до рабочего размера сразу при открытии. Даже большой альбом не
    // раздувает начальное окно.
    init {
        trim()
    }

    // Принимает новый выбор только из известных файлов. Неудачный переход сохраняет текущий кадр.
    fun select(id: String): Boolean {
        if (entries.none { it.id == id }) return false
        selectedID = id
        return true
    }

    // Проверяет принадлежность чату, соединяет старые и новые файлы по номерам и обновляет
    // признаки продолжения. Одна страница не приносит дубли и чужие записи.
    fun merge(page: HistoryPage): Boolean {
        if (page.rows.any { it.chatID != chatID }) return false
        val incoming = page.rows.flatMap { MediaGalleryEntry.entries(it) }.sortedWith(order)
        if (incoming.isEmpty()) {
            hasOlder = false
            hasNewer = false
            return true
        }
        if (entries.isEmpty() || incoming.first().order <= entries.first().order)
            hasOlder = page.before != null
        if (entries.isEmpty() || incoming.last().order >= entries.last().order)
            hasNewer = page.after != null
        val byID = entries.associateBy { it.id }.toMutableMap()
        for (entry in incoming) byID[entry.id] = entry
        entries = byID.values.sortedWith(order)
        trim()
        return true
    }

    // Удаляет исчезнувшие файлы, заменяет изменённые строки и при необходимости выбирает соседний
    // кадр. Галерея остаётся согласованной с историей чата.
    fun apply(delta: MessageDelta) {
        if (delta.chat.id != chatID) return
        val previous = selected
        val changed = (delta.updated + delta.inserted).filter { it.chatID == chatID }
        val removed = (delta.removedIDs + changed.map { it.id }).toSet()
        val byID = entries.filter { it.messageID !in removed }.associateBy { it.id }.toMutableMap()
        for (row in changed) for (entry in MediaGalleryEntry.entries(row)) byID[entry.id] = entry
        entries = byID.values.sortedWith(order)
        if (selectedIndex == null)
            selectedID =
                entries.firstOrNull { previous == null || order.compare(it, previous) >= 0 }?.id
                    ?: entries.lastOrNull()?.id
        trim()
    }

    // Оставляет часть истории вокруг выбора и отмечает обе обрезанные стороны. Позже пользователь
    // может вернуться к этим файлам через подгрузку.
    private fun trim() {
        if (entries.size <= MAXIMUM_ENTRIES) return
        val lower =
            ((selectedIndex ?: 0) - MAXIMUM_ENTRIES / 2).coerceIn(0, entries.size - MAXIMUM_ENTRIES)
        val upper = minOf(entries.size, lower + MAXIMUM_ENTRIES)
        if (lower > 0) hasOlder = true
        if (upper < entries.size) hasNewer = true
        entries = entries.subList(lower, upper).toList()
    }
}
