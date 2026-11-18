// Описывает действия с системной камерой и выбором файлов. Код вложений вызывает их через
// владельца окна, которое получает ответы системы.

package dev.kirill.kandinsky.chat.attachments

import android.content.Intent

interface AttachmentPicker {
    // Просит разрешить камеру и сообщает решение ожидающему вызову. Без подтверждения съёмка не
    // начинается.
    fun requestCameraPermission(callback: (Boolean) -> Unit)

    // Открывает системный выбор документа по переданному запросу. Результат относится к одному
    // выбору и может отсутствовать при отмене.
    fun launchDocumentPicker(request: Intent, callback: (Intent?) -> Unit)

    // Снимает ожидание результата выбора. Поздний ответ системы не уйдёт в уже закрытый экран.
    fun clearDocumentResult()
}
