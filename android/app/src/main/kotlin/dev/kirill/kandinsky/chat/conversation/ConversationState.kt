// Управляет черновиком, положением чтения и отправкой одного чата на Андроиде. Сводит запись на
// телефоне и возвращение результата на экран.

package dev.kirill.kandinsky.chat.conversation

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.storage.ChatRepository
import java.util.UUID

class ConversationState(private val repository: ChatRepository) {
    private val pendingRetrySlots = mutableSetOf<String>()

    // Разрешает только один ожидающий повтор для результата без файла. Номер вычисляется из места
    // и запуска, поэтому после потери ответа остаётся тем же.
    fun beginSlotRetry(slot: StudioGeneration.Slot): UUID? {
        if (!slot.canRetry) return null
        val id = slot.id
        val run = slot.runId
        if (!pendingRetrySlots.add(id)) return null
        val key = "kandinsky/retry/" + id.lowercase() + "/" + (run?.lowercase() ?: "initial")
        val bytes =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return java.util.UUID(buffer.long, buffer.long)
    }

    // Убирает отметку ожидающего повтора. По завершении прежняя отметка не должна блокировать
    // допустимое действие.
    fun finishSlotRetry(id: String) {
        pendingRetrySlots.remove(id)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    var chatID: ChatID? = null
        private set

    var draft = Draft()
        private set

    var anchor: ReadingAnchor? = null
        // При изменении точки чтения снимает прежнюю ожидающую запись и ставит новую. Положению
        // не надо ждать следующего изменения текста.
        set(value) {
            if (field == value) return
            field = value
            if (!closed && isReady && chatID != null) {
                handler.removeCallbacks(positionWrite)
                handler.post(positionWrite)
            }
        }

    var lastError: Throwable? = null
        private set

    var isSending = false
        private set

    var isReady = false
        private set

    var onDelta: ((MessageDelta) -> Unit)? = null
    var onChange: (() -> Unit)? = null
    var persistStudioDraft: ((ChatID?, Draft, (Result<Unit>) -> Unit) -> Unit)? = null
    // Место чтения сохраняем отдельно от черновика, чтобы остановка процесса не вернула чат в
    // конец.
    private val positionWrite = Runnable {
        val id = chatID
        val position = anchor
        if (!closed && isReady && id != null && position != null) {
            repository.savePosition(id, position) { result ->
                if (!closed) {
                    result.exceptionOrNull()?.let { error ->
                        lastError = error
                        onChange?.invoke()
                    }
                }
            }
        }
    }
    private val debounce = Runnable {
        flush {
            it.exceptionOrNull()?.let { error ->
                lastError = error
                onChange?.invoke()
            }
        }
    }

    // Читает черновик и опору прокрутки, затем отмечает прочтение. Состояние становится готовым
    // только после успешного чтения и пока экран открыт.
    fun open(chatID: ChatID, callback: (Result<Unit>) -> Unit) {
        closed = false
        isReady = false
        this.chatID = chatID
        repository.draft(chatID) { result ->
            result.fold(
                { value ->
                    draft = value
                    repository.position(chatID) { p ->
                        p.fold(
                            {
                                anchor = it
                                repository.markRead(chatID) { read ->
                                    if (closed)
                                        callback(Result.failure(RepoException(RepoError.CANCELLED)))
                                    else {
                                        if (read.isSuccess) {
                                            isReady = true
                                            onChange?.invoke()
                                        }
                                        callback(read)
                                    }
                                }
                            },
                            { callback(Result.failure(it)) },
                        )
                    }
                },
                { callback(Result.failure(it)) },
            )
        }
    }

    // Восстанавливает заготовку без номера чата и даёт редактировать её. Серверная история может
    // появиться позже при отправке или выборе файла.
    fun openStudioDraft(restored: Draft? = null) {
        if (closed || chatID != null) return
        if (restored != null) draft = restored
        isReady = true
        onChange?.invoke()
    }

    // Запоминает полученный номер только для готовой заготовки без прежней привязки. Поздний
    // ответ не заменит номер уже открытого чата.
    fun bindStudioDraft(id: ChatID) {
        if (closed || !isReady || chatID != null) return
        chatID = id
    }

    // Обновляет ввод и его версию, сохраняя номер незавершённой отправки и очередь файлов.
    // Короткая задержка объединяет ввод нескольких символов в одну запись.
    fun editDraft(value: Draft) {
        if (closed || !isReady) return
        draft =
            value.copy(
                revision = draft.revision + 1,
                operationID = draft.operationID,
                pendingSend = draft.pendingSend,
                pendingRevision = draft.pendingRevision,
                pendingMediaBatch = draft.pendingMediaBatch,
            )
        lastError = null
        handler.removeCallbacks(debounce)
        handler.postDelayed(debounce, 300)
        onChange?.invoke()
    }

    // Сохраняет изменившиеся настройки в черновике и откладывает запись до паузы. Повтор того же
    // выбора не запускает лишнюю работу.
    fun updateGenerationOptions(value: KandinskyOptions) {
        if (closed || !isReady || draft.generationOptions == value) return
        draft = draft.copy(generationOptions = value)
        lastError = null
        handler.removeCallbacks(debounce)
        handler.postDelayed(debounce, 300)
        onChange?.invoke()
    }

    // Снимает отложенные записи и сохраняет текущий ввод. Вслед за ним пишет более свежую точку
    // чтения, если пользователь прокрутил чат во время ожидания.
    fun flush(callback: (Result<Unit>) -> Unit) {
        handler.removeCallbacks(debounce)
        handler.removeCallbacks(positionWrite)
        if (!isReady) return callback(Result.success(Unit))
        val value = draft
        val id = chatID
        if (id == null) {
            persistStudioDraft?.invoke(null, value, callback) ?: callback(Result.success(Unit))
            return
        }
        repository.saveDraft(id, value) { result ->
            if (result.isFailure) callback(result)
            else {
                // После записи черновика и заготовки читает нынешнюю точку и просит сохранить её.
                // При сбое возвращает ошибку записи вместо перезаписи места.
                fun saved(result: Result<Unit>) {
                    // Пока записывался черновик, пользователь мог прокрутить дальше.
                    val position = anchor
                    if (result.isFailure || position == null) callback(result)
                    else repository.savePosition(id, position, callback)
                }
                persistStudioDraft?.invoke(id, value, ::saved) ?: saved(Result.success(Unit))
            }
        }
    }

    // Передаёт отправку общему методу без дополнительных настроек. Обычный ввод использует те же
    // проверки и сохранение номера.
    fun send(callback: (Result<Unit>) -> Unit = {}) = send(null, callback)

    // Сохраняет команду перед отправкой и использует ту же команду при повторе. После ответа
    // очищает только отправленный ввод, оставляя текст, появившийся позже.
    fun send(generationOptions: KandinskyOptions?, callback: (Result<Unit>) -> Unit = {}) {
        if (draft.pendingMediaBatch != null) return sendMediaBatch(callback = callback)
        val id = chatID
        if (!isReady || isSending || closed || id == null)
            return callback(Result.failure(RepoException(RepoError.CANCELLED)))
        if (draft.pendingSend == null && draft.text.isBlank() && draft.attachmentDrafts.isEmpty())
            return callback(Result.success(Unit))
        isSending = true
        lastError = null
        handler.removeCallbacks(debounce)
        if (draft.pendingSend == null) {
            val token = UUID.randomUUID().toString()
            val kind = kind(draft.attachmentDrafts.firstOrNull())
            draft =
                draft.copy(
                    operationID = token,
                    pendingRevision = draft.revision,
                    pendingSend =
                        SendCommand(
                            token,
                            id,
                            kind,
                            if (kind == MessageKind.TEXT) draft.text else "",
                            if (kind == MessageKind.TEXT) "" else draft.text,
                            draft.replyToID,
                            draft.attachmentDrafts,
                            generationOptions,
                        ),
                )
        }
        val command = draft.pendingSend!!
        val revision = draft.pendingRevision ?: draft.revision
        onChange?.invoke()
        // Снимает отметку отправки и передаёт итоговую ошибку или успех. Экран снова может
        // принять действие после завершения.
        fun finish(result: Result<Unit>) {
            lastError = result.exceptionOrNull()
            isSending = false
            onChange?.invoke()
            callback(result)
        }
        flush { saved ->
            if (saved.isFailure) finish(saved)
            else
                repository.send(command) { sent ->
                    sent.fold(
                        { delta ->
                            draft =
                                if (draft.revision == revision)
                                    Draft(
                                        revision = draft.revision + 1,
                                        generationOptions = draft.generationOptions,
                                    )
                                else
                                    draft.copy(
                                        operationID = null,
                                        pendingSend = null,
                                        pendingRevision = null,
                                        revision = draft.revision + 1,
                                    )
                            flush { result ->
                                if (result.isSuccess) onDelta?.invoke(delta)
                                finish(result)
                            }
                        },
                        { finish(Result.failure(it)) },
                    )
                }
        }
    }

    // Проверяет состав группы и сохраняет её с постоянными номерами. Начинает следующую команду
    // лишь после записи остатка, чтобы можно было продолжить после закрытия.
    fun sendMediaBatch(commands: List<SendCommand>? = null, callback: (Result<Unit>) -> Unit = {}) {
        val id = chatID
        if (!isReady || isSending || closed || id == null || draft.pendingSend != null)
            return callback(Result.failure(RepoException(RepoError.CANCELLED)))
        if (commands != null) {
            val valid =
                commands.isNotEmpty() &&
                    commands.size <= 32 &&
                    commands.all {
                        it.chatID == id &&
                            runCatching { UUID.fromString(it.operationID) }.isSuccess &&
                            it.attachments.size <= 10
                    } &&
                    commands.map { it.operationID }.toSet().size == commands.size &&
                    commands.sumOf { it.attachments.size } <= 32 &&
                    (draft.pendingMediaBatch?.let { commands.takeLast(it.size) == it } ?: true)
            if (!valid) {
                lastError = RepoException(RepoError.INVALID_INPUT)
                onChange?.invoke()
                return callback(Result.failure(lastError!!))
            }
            if (draft.pendingMediaBatch == null)
                draft =
                    draft.copy(
                        pendingMediaBatch =
                            commands.map { it.copy(attachments = it.attachments.toList()) },
                        revision = draft.revision + 1,
                    )
        }
        if (draft.pendingMediaBatch.isNullOrEmpty()) return callback(Result.success(Unit))
        isSending = true
        lastError = null
        handler.removeCallbacks(debounce)
        onChange?.invoke()
        // Завершает передачу группы с учётом результата. Не оставляет экран в режиме ожидания,
        // когда очередь закончилась или возник сбой.
        fun finish(result: Result<Unit>) {
            isSending = false
            lastError = result.exceptionOrNull()
            onChange?.invoke()
            callback(result)
        }
        // Берёт первое ожидающее действие и после успеха сохраняет остаток. Если запись остатка
        // упала, возвращает исходную очередь для безопасного повтора.
        fun drain() {
            if (closed) return finish(Result.failure(RepoException(RepoError.CANCELLED)))
            val queue = draft.pendingMediaBatch ?: return finish(Result.success(Unit))
            val command = queue.first()
            repository.send(command) { sent ->
                sent.fold(
                    { delta ->
                        if (closed) finish(Result.failure(RepoException(RepoError.CANCELLED)))
                        else {
                            onDelta?.invoke(delta)
                            draft =
                                draft.copy(
                                    pendingMediaBatch = queue.drop(1).takeIf { it.isNotEmpty() },
                                    revision = draft.revision + 1,
                                )
                            flush { saved ->
                                if (saved.isSuccess) drain()
                                else {
                                    draft =
                                        draft.copy(
                                            pendingMediaBatch = queue,
                                            revision = draft.revision + 1,
                                        )
                                    finish(saved)
                                }
                            }
                        }
                    },
                    { finish(Result.failure(it)) },
                )
            }
        }
        flush { if (it.isSuccess) drain() else finish(it) }
    }

    // Закрывает новые действия, просит сохранить накопленные данные и отсоединяет уведомления.
    // Уход со страницы не должен терять последний ввод.
    fun close() {
        closed = true
        handler.removeCallbacks(debounce)
        flush {}
        isReady = false
        onChange = null
        onDelta = null
    }

    // Определяет вид сообщения по файлу и его метке. По этому виду экран позже решит, как
    // показать строку.
    private fun kind(a: AttachmentDraft?): MessageKind =
        when {
            a == null -> MessageKind.TEXT
            a.thumbnailKey == "sticker" -> MessageKind.STICKER
            a.mime == "image/gif" -> MessageKind.GIF
            a.mime.startsWith("image/") -> MessageKind.PHOTO
            a.mime.startsWith("audio/") -> MessageKind.VOICE
            a.thumbnailKey == "round" -> MessageKind.ROUND
            else -> MessageKind.VIDEO
        }
}
