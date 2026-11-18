// Делит поиск, скачивание и чтение одного изображения между его получателями. Загрузка видимого
// изображения может приостановить менее срочную.

package dev.kirill.kandinsky.chat.media

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.Cancellation
import java.util.UUID

internal typealias ThumbnailStart =
    (Int, () -> Boolean, (Double) -> Unit, (Result<PreparedImage>) -> Unit) -> Cancellation

// Один запрос обслуживает несколько владельцев кадра, в том числе при его появлении на экране.
internal class MediaThumbnailRequests {
    private class Subscriber(
        val priority: Int,
        val progress: (Double) -> Unit,
        val localMiss: () -> Unit,
        val completion: (Result<PreparedImage>) -> Unit,
    ) {
        val token = MediaCancellation()
    }

    private class Group(val key: String, val start: ThumbnailStart) {
        val owners = MediaRequestOwners()
        val subscribers = linkedMapOf<UUID, Subscriber>()
        var work: Cancellation? = null
        var generation = 0
        var running = false
        var remote = false
        val priority
            // Берёт наибольшую срочность среди получателей общей работы. Появление кадра на
            // экране меняет порядок его допуска.
            get() = subscribers.values.maxOfOrNull { it.priority } ?: 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val groups = linkedMapOf<String, Group>()
    var onRemoteActivity: ((Boolean) -> Unit)? = null

    // Создаёт одинаковую ошибку отмены для ожидавших и уже начатых работ. Экран отличает закрытие
    // запроса от сбоя файла.
    private fun cancelled() = MediaException(MediaError.CANCELLED)

    // Подключает получателя к открытой группе или добавляет новую с ограничением общего числа.
    // Отмена снимает только свою подписку, пока ещё есть другие получатели.
    fun submit(
        key: String,
        priority: Int,
        progress: (Double) -> Unit,
        start: ThumbnailStart,
        completion: (Result<PreparedImage>) -> Unit,
        localMiss: () -> Unit = {},
    ): Cancellation {
        check(Looper.myLooper() == Looper.getMainLooper())
        val subscriber = Subscriber(priority, progress, localMiss, completion)
        if (key !in groups && groups.size >= 32) {
            main.post {
                subscriber.token.finish()
                completion(Result.failure(MediaException(MediaError.UNAVAILABLE)))
            }
            return subscriber.token
        }
        val id = UUID.randomUUID()
        var group = groups[key] ?: Group(key, start)
        while (!group.owners.insert(id)) {
            retire(group)
            group = groups[key] ?: Group(key, start)
        }
        groups[key] = group
        val ownedGroup = group
        group.subscribers[id] = subscriber
        subscriber.token.onCancel {
            ownedGroup.owners.remove(id)
            val cancel = {
                if (ownedGroup.subscribers.remove(id) != null) {
                    completion(Result.failure(cancelled()))
                    if (ownedGroup.subscribers.isEmpty()) remove(ownedGroup)
                    resumeWaiting()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cancel() else main.post { cancel() }
        }
        if (!group.running) {
            launch(group)
        } else if (group.remote) {
            val generation = group.generation
            main.post {
                if (ownedGroup.remote && ownedGroup.generation == generation && !subscriber.token.isCancelled) {
                    subscriber.localMiss()
                }
            }
        }
        return subscriber.token
    }

    // Начинает поиск и даёт пройти к сети в порядке срочности. По ответу закрывает устаревшее или
    // готовит удержания всех получателей заранее, чтобы очистка из первого ответа не обесценила
    // остальные.
    private fun launch(group: Group) {
        if (group.owners.isClosed) return
        group.running = true
        group.remote = false
        val generation = ++group.generation
        group.work =
            group.start(
                group.priority,
                {
                    if (groups[group.key] !== group || group.generation != generation) false
                    else if (
                        group.priority <= 0 && groups.values.any { it !== group && it.remote }
                    ) {
                        pause(group)
                        false
                    } else {
                        if (group.priority > 0)
                            groups.values
                                .toList()
                                .filter { it !== group && it.remote && it.priority <= 0 }
                                .forEach(::pause)
                        group.remote = true
                        group.subscribers.values.toList().forEach {
                            if (!it.token.isCancelled) it.localMiss()
                        }
                        onRemoteActivity?.invoke(true)
                        true
                    }
                },
                { value ->
                    if (group.generation == generation)
                        group.subscribers.values
                            .toList()
                            .filter { !it.token.isCancelled }
                            .forEach { it.progress(value) }
                },
            ) { result ->
                if (groups[group.key] !== group || group.generation != generation) {
                    result.getOrNull()?.close()
                } else {
                    // Получатели могут очистить кэш. Все удержания создаём перед первым вызовом.
                    val deliveries =
                        group.subscribers.values.map { subscriber ->
                            subscriber to
                                result.fold(
                                    { frame ->
                                        frame.retained()?.let { Result.success(it) }
                                            ?: Result.failure(cancelled())
                                    },
                                    { Result.failure(it) },
                                )
                        }
                    result.getOrNull()?.close()
                    remove(group)
                    for ((subscriber, delivery) in deliveries) {
                        if (subscriber.token.finish()) subscriber.completion(delivery)
                        else {
                            delivery.getOrNull()?.close()
                            subscriber.completion(Result.failure(cancelled()))
                        }
                    }
                    resumeWaiting()
                }
            }
        group.work?.let { work -> group.owners.cancellation.onCancel { work.cancel() } }
    }

    // Отменяет текущую работу и меняет номер попытки, сохраняя получателей. Поздний ответ
    // отменённого запроса не должен попасть в возобновлённый.
    private fun pause(group: Group) {
        group.generation++
        group.running = false
        group.remote = false
        group.work?.cancel()
        group.work = null
    }

    // Убирает группу с её ключом и освобождает работу и получателей. Повторный запрос с тем же
    // ключом сможет начать новую подготовку.
    private fun remove(group: Group) {
        groups.remove(group.key)
        pause(group)
        group.owners.close()
        group.subscribers.clear()
    }

    // Снимает группу и завершает всех ожидавших с отменой. После очистки ни один получатель не
    // остаётся ждать.
    private fun retire(group: Group) {
        val subscribers = group.subscribers.values.toList()
        remove(group)
        for (subscriber in subscribers) {
            subscriber.token.finish()
            subscriber.completion(Result.failure(cancelled()))
        }
    }

    // Продолжает срочные группы, а без сетевой работы начинает одну предварительную. Передаёт
    // состояние сети общей очереди подготовки.
    private fun resumeWaiting() {
        val waiting = groups.values.filter { !it.running }
        waiting.filter { it.priority > 0 }.forEach(::launch)
        if (groups.values.none { it.remote })
            waiting.firstOrNull { it.priority <= 0 && !it.running }?.let(::launch)
        onRemoteActivity?.invoke(groups.values.any { it.remote })
    }

    // Отменяет все группы и снимает признак сетевой работы. При смене экрана очередь освобождает
    // получателей и файлы.
    fun clear() {
        groups.values.toList().forEach(::retire)
        onRemoteActivity?.invoke(false)
    }
}
