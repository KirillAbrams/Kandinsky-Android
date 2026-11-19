// Открывает местную базу на рабочем потоке и принимает ограниченное число команд. Результаты
// возвращаются в поток экрана, где их можно безопасно показать.

package dev.kirill.kandinsky.chat.storage

import android.database.sqlite.*
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import dev.kirill.kandinsky.chat.domain.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

// Запоминает папку, настройки проверочных сбоев и создаёт очередь на один рабочий поток.
// Соединение появится только после открытия файла, поэтому создание оболочки не читает базу.
class SQLiteStore private constructor(val root: File, val faults: RepositoryFaults) {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val closeResult = CompletableFuture<Unit>()
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(64),
            { r -> Thread(r, "KandinskySQLite").apply { priority = Thread.NORM_PRIORITY - 1 } },
        )
    private var database: SQLiteDatabase? = null

    companion object {
        // Открывает файл базы, включает проверку связей и обновляет таблицы. Возвращает готовое
        // соединение, а при сбое закрывает файл и прекращает работу очереди.
        fun open(
            root: File,
            faults: RepositoryFaults = RepositoryFaults(),
            callback: (Result<SQLiteStore>) -> Unit,
        ): Cancellation {
            val store = SQLiteStore(root, faults)
            val cancel = AtomicBoolean(false)
            store.executor.execute {
                val result =
                    runCatching {
                            if (cancel.get()) throw RepoException(RepoError.CANCELLED)
                            if (!root.exists() && !root.mkdirs()) throw RepoException(RepoError.IO)
                            val db =
                                SQLiteDatabase.openOrCreateDatabase(
                                    File(root, "chat.sqlite3").path,
                                    null,
                                ) { damaged ->
                                    // Сохраняем файл и журнал для восстановления. Системный
                                    // обработчик по умолчанию удаляет повреждённую историю.
                                    if (damaged.isOpen) damaged.close()
                                }
                            try {
                                db.setForeignKeyConstraintsEnabled(true)
                                db.enableWriteAheadLogging()
                                db.execSQL("PRAGMA synchronous=FULL")
                                SchemaMigrations.apply(db)
                                store.database = db
                                store
                            } catch (t: Throwable) {
                                if (db.isOpen) db.close()
                                throw t
                            }
                        }
                        .recoverCatching { throw map(it) }
                if (result.isFailure) store.executor.shutdown()
                store.main.post { callback(result) }
            }
            return Cancellation { cancel.set(true) }
        }

        // Сводит ошибки базы, нехватку места и отмену к ошибкам приложения. Без такого перевода
        // экран не сможет отличить неверные данные от обычного сбоя чтения.
        internal fun map(t: Throwable): Throwable =
            when (t) {
                is RepoException -> t
                is SQLiteFullException -> RepoException(RepoError.STORAGE_FULL)
                is SQLiteDatabaseCorruptException -> RepoException(RepoError.CORRUPT)
                is SQLiteConstraintException -> RepoException(RepoError.INVALID_INPUT)
                is OperationCanceledException -> RepoException(RepoError.CANCELLED)
                else -> RepoException(RepoError.IO)
            }
    }

    // Ставит работу в ограниченную очередь и проверяет отмену перед обращением к базе. При
    // заполненной или закрытой очереди тоже возвращает ответ, иначе экран мог бы ждать
    // бесконечно.
    fun <T> submit(action: (SQLiteDatabase) -> T, callback: (Result<T>) -> Unit): Cancellation {
        val cancelled = AtomicBoolean(false)
        if (closed.get()) {
            main.post { callback(Result.failure(RepoException(RepoError.CANCELLED))) }
            return Cancellation {}
        }
        try {
            executor.execute {
                val result =
                    runCatching {
                            if (cancelled.get()) throw RepoException(RepoError.CANCELLED)
                            val db = database ?: throw RepoException(RepoError.CANCELLED)
                            action(db)
                        }
                        .recoverCatching { throw map(it) }
                main.post { callback(result) }
            }
        } catch (t: RejectedExecutionException) {
            main.post {
                callback(
                    Result.failure(
                        RepoException(if (closed.get()) RepoError.CANCELLED else RepoError.IO)
                    )
                )
            }
        }
        return Cancellation { cancelled.set(true) }
    }

    // Останавливает приём новых команд, ждёт завершения уже принятых и закрывает файл. Повторные
    // вызовы ждут тот же результат, поэтому запись не прерывается посередине.
    fun close(callback: (Result<Unit>) -> Unit): Cancellation {
        closeResult.whenComplete { _, error ->
            main.post {
                callback(if (error == null) Result.success(Unit) else Result.failure(map(error)))
            }
        }
        if (closed.compareAndSet(false, true)) {
            executor.shutdown()
            Thread(
                    {
                        try {
                            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
                            database?.close()
                            database = null
                            closeResult.complete(Unit)
                        } catch (t: Throwable) {
                            closeResult.completeExceptionally(map(t))
                        }
                    },
                    "KandinskySQLiteClose",
                )
                .start()
        }
        return Cancellation {}
    }
}
