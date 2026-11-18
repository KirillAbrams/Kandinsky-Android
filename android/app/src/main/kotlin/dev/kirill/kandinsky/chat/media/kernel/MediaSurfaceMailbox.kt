package dev.kirill.kandinsky.chat.media.kernel

/**
 * Обмен удерживает один показанный и один ожидающий кадр, а экрану отправляет только уведомление
 * Представление вызывается под защитой состояния в потоке экрана и обязано снять прежнюю ссылку до возврата
 * Освобождение кадра выполняется после выхода из защиты, а уведомление назначается только асинхронно
 */
internal class MediaSurfaceMailbox<T : AutoCloseable>(
    private val schedule: (() -> Unit) -> Unit, private val mayPublish: ((T) -> Boolean)? = null, render: (T?) -> Unit,
) : AutoCloseable {
    private val gate = Any()
    private var binding = 0L
    private var sequence = 0L
    private var active = false
    private var closed = false
    private var pending: T? = null
    private var displayed: T? = null
    private var dirty = false
    private var clearDisplay = false
    private var scheduled = false
    private var render: ((T?) -> Unit)? = render
    /**
     * Требует активное подключение и отсутствие окончательного закрытия
     * По этому ответу источник может отказаться от уже устаревшей работы
     */
    fun isCurrent(token: Long): Boolean = synchronized(gate) { active && !closed && binding == token }
    /**
     * Открывает новое поколение подключения кадров и сбрасывает порядок их приёма
     * Освобождает прежний ожидающий кадр, при необходимости оставляя показанный до замены
     * Назначает одно уведомление экрану и возвращает обозначение для новых кадров, если обмен ещё не закрыт
     */
    fun begin(preservingDisplay: Boolean = false): Long? {
        var old: T?; val token: Long?; val notify: Boolean
        synchronized(gate) {
            if (closed) return null
            if (binding == Long.MAX_VALUE) { closed = true; active = false; token = null }
            else { binding++; sequence = 0; active = true; token = binding }
            old = pending; pending = null; clearDisplay = !preservingDisplay || displayed == null; dirty = true
            notify = markNotice()
        }
        old?.close(); if (notify) enqueueNotice()
        return token
    }
    /**
     * Принимает только более новый кадр действующего подключения
     * Хранит последний ожидающий кадр вместо накапливания очереди и назначает одно уведомление экрану
     * Заменённый или отклонённый кадр закрывает самостоятельно, поэтому владение передаётся даже при отказе
     */
    fun offer(token: Long, serial: Long, frame: T): Boolean {
        val old: T?; val accepted: Boolean; val notify: Boolean
        synchronized(gate) {
            accepted = active && !closed && token == binding && serial > sequence
            if (accepted) {
                sequence = serial; old = pending; pending = frame; dirty = true; notify = markNotice()
            } else { old = frame; notify = false }
        }
        old?.close(); if (notify) enqueueNotice()
        return accepted
    }
    /**
     * Завершает текущее подключение и запрашивает очистку показанного содержимого
     * Ожидающий кадр освобождает сразу, а показанный снимается при обработке уведомления экраном
     * Сам обмен остаётся доступным для следующего подключения
     */
    fun clear() { invalidate(false, null) }
    /**
     * Запрашивает очистку только при совпадении переданного обозначения с действующим подключением
     * Не позволяет поздней очистке прежнего источника убрать кадр нового содержимого
     * Возвращает признак того, была ли команда принята
     */
    fun clear(binding: Long): Boolean = invalidate(false, binding)
    /**
     * Окончательно прекращает приём новых кадров и запрашивает очистку представления
     * Ожидающий кадр освобождает сразу, а показанный после его снятия экраном
     * После такого завершения новое подключение уже не открывается
     */
    override fun close() { invalidate(true, null) }
    /**
     * Под защитой завершает подключение и делает прежние обозначения недействительными
     * Помечает показанный кадр для снятия, извлекает ожидающий и назначает уведомление
     * Освобождение извлечённого кадра выполняет вне защиты состояния, а при окончательном закрытии запрещает новые подключения
     */
    private fun invalidate(terminal: Boolean, expected: Long?): Boolean {
        val old: T?; val notify: Boolean
        synchronized(gate) {
            if (closed || (expected != null && (!active || expected != binding))) return false
            closed = terminal || binding == Long.MAX_VALUE
            if (binding != Long.MAX_VALUE) binding++
            active = false; old = pending; pending = null; clearDisplay = true; dirty = true
            notify = markNotice()
        }
        old?.close(); if (notify) enqueueNotice()
        return true
    }
    /**
     * Разрешает назначить уведомление экрану только при отсутствии уже ожидающего
     * Сохраняет признак назначенной работы для следующих изменений
     * Вызывается внутри общей защиты состояния обмена
     */
    private fun markNotice(): Boolean {
        if (scheduled) return false
        scheduled = true; return true
    }
    /**
     * Передаёт короткую задачу назначенному асинхронному планировщику экрана
     * Перед обработкой снимает признак ожидающего уведомления
     * Затем применяет последнее накопленное состояние, не передавая кадры в отдельной очереди уведомлений
     */
    private fun enqueueNotice() = schedule {
        synchronized(gate) { scheduled = false }
        flush()
    }
    /**
     * Передаёт последний допустимый кадр представлению в потоке экрана
     * Если кадр пока нельзя показать, оставляет его ожидающим и при необходимости снимает прежнее изображение
     * Под защитой отсоединяет старую ссылку представления, а освобождает прежний кадр после выхода из защиты
     * При окончательном закрытии также убирает сохранённый обработчик рисования
     */
    fun flush() {
        var old: T? = null
        try {
            synchronized(gate) {
                if (!dirty) return
                val next = pending
                if (next != null && active && !closed && mayPublish?.invoke(next) == false) {
                    if (clearDisplay) { old = displayed; displayed = null; clearDisplay = false; render?.invoke(null) }
                    return
                }
                dirty = false
                if (clearDisplay || pending != null) {
                    old = displayed; displayed = if (active && !closed) pending else null; pending = null
                }
                clearDisplay = false
                render?.invoke(displayed)
                if (closed) render = null
            }
        } finally { old?.close() }
    }
}
