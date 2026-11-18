// Подготовка первого кадра на андроиде задерживает открытие истории до готовности видимых строк и
// сцены. Медиа получают короткое время на подготовку, чтобы длинная загрузка не удерживала
// пользователя на пустом экране.

package dev.kirill.kandinsky.chat.rendering

import android.graphics.*
import android.view.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.*
import kotlin.math.*

// Отменяем прежнее ожидание, а закрытой истории сразу возвращаем отмену. Для новой сохраняем
// получателя результата и назначаем повторную проверку после срока медиа.
internal fun ChatScrollHost.awaitInitialFrame(completion: (Result<Unit>) -> Unit) {
    finishInitialFrame(Result.failure(RepoException(RepoError.CANCELLED)))
    if (closed) {
        completion(Result.failure(RepoException(RepoError.CANCELLED)))
        return
    }
    initialFrame = completion
    initialMediaDeadline = android.os.SystemClock.uptimeMillis() + 250
    main.postDelayed(initialTimeout, 250)
    schedule()
}

// Снимаем отложенный вызов и передаём результат одному ожидающему. Очистка ссылки до передачи
// защищает от второго завершения.
internal fun ChatScrollHost.finishInitialFrame(result: Result<Unit>) {
    val callback = initialFrame
    initialFrame = null
    main.removeCallbacks(initialTimeout)
    callback?.invoke(result)
}

// Проверяем наличие подготовленных строк и видимых фрагментов сцены. После срока медиа могут
// догрузиться позже, но неполный текст по-прежнему задерживает открытие.
internal fun ChatScrollHost.publishInitialFrame(fragments: List<SceneFragment>) {
    if (initialFrame == null) return
    val visible =
        index
            .visible(
                max(0.0, offset + overlayTop),
                max(0.0, height - overlayTop - overlayBottom),
                overscan = 0,
            )
            .map { index.rows[it] }
    if (visible.any { meta -> loaded[meta.messageID]?.let { cache.value(layoutKey(it)) } == null })
        return
    val ids =
        fragments.filter { RectF.intersects(it.frame, scene.viewport) }.map { it.id }.toSet()
    if (visible.isNotEmpty() && ids.isEmpty()) return
    if (
        scene.presentationReady(
            ids,
            android.os.SystemClock.uptimeMillis() < initialMediaDeadline,
        )
    )
        finishInitialFrame(Result.success(Unit))
}
