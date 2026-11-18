package dev.kirill.kandinsky.chat.storage
import android.database.sqlite.*
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import dev.kirill.kandinsky.chat.domain.*
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class SQLiteStore private constructor(val root:File,val faults:RepositoryFaults) {
 private val main=Handler(Looper.getMainLooper())
 private val closed=AtomicBoolean(false)
 private val closeResult=CompletableFuture<Unit>()
 private val executor=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue(64),{r->Thread(r,"KandinskySQLite").apply{priority=Thread.NORM_PRIORITY-1}})
 private var database:SQLiteDatabase?=null
 companion object {
  /**
   * Открывает файл истории в папке выбранного профиля вне потока экрана
   * Включает проверку связей, журнал записи и надёжное подтверждение изменений, затем обновляет схему
   * Возвращает готовое хранилище только после этих действий, а при ошибке закрывает открытое соединение
   */
  fun open(root:File,faults:RepositoryFaults=RepositoryFaults(),callback:(Result<SQLiteStore>)->Unit):Cancellation {
   val store=SQLiteStore(root,faults);val cancel=AtomicBoolean(false)
   store.executor.execute {
    val result=runCatching {
     if(cancel.get())throw RepoException(RepoError.CANCELLED)
     if(!root.exists() && !root.mkdirs())throw RepoException(RepoError.IO)
     val db=SQLiteDatabase.openOrCreateDatabase(File(root,"chat.sqlite3"),null)
     try {
      db.setForeignKeyConstraintsEnabled(true);db.enableWriteAheadLogging()
      db.execSQL("PRAGMA synchronous=FULL")
      SchemaMigrations.apply(db);store.database=db;store
     }catch(t:Throwable){db.close();throw t}
    }.recoverCatching{throw map(it)}
    if(result.isFailure)store.executor.shutdown()
    store.main.post{callback(result)}
   }
   return Cancellation{cancel.set(true)}
  }
  /**
   * Переводит ошибки базы в состояния, которые умеют обрабатывать экран и очередь отправки
   * Отдельно различает заполненный диск, повреждённую базу, неверные данные и отмену
   * Остальные ошибки чтения или записи передаёт как ошибку хранилища
   */
  internal fun map(t:Throwable):Throwable=when(t) {
   is RepoException->t
   is SQLiteFullException->RepoException(RepoError.STORAGE_FULL)
   is SQLiteDatabaseCorruptException->RepoException(RepoError.CORRUPT)
   is SQLiteConstraintException->RepoException(RepoError.INVALID_INPUT)
   is OperationCanceledException->RepoException(RepoError.CANCELLED)
   else->RepoException(RepoError.IO)
  }
 }
 /**
  * Ставит действие в очередь единственного потока работы с базой
  * Перед выполнением проверяет отмену, а результат передаёт обратно в поток экрана
  * Если очередь заполнена или хранилище закрывается, сообщает об отказе вместо неограниченного накопления запросов
  */
 fun <T> submit(action:(SQLiteDatabase)->T,callback:(Result<T>)->Unit):Cancellation {
  val cancelled=AtomicBoolean(false)
  if(closed.get()){main.post{callback(Result.failure(RepoException(RepoError.CANCELLED)))};return Cancellation{}}
  try {
   executor.execute {
    val result=runCatching {
     if(cancelled.get())throw RepoException(RepoError.CANCELLED)
     val db=database ?: throw RepoException(RepoError.CANCELLED)
     action(db)
    }.recoverCatching{throw map(it)}
    main.post{callback(result)}
   }
  }catch(t:RejectedExecutionException){main.post{callback(Result.failure(RepoException(if(closed.get())RepoError.CANCELLED else RepoError.IO)))}}
  return Cancellation{cancelled.set(true)}
 }
 /**
  * Закрывает соединение в той же очереди, где выполняются запросы
  * Ранее принятые действия завершаются перед закрытием, поэтому соединение не исчезает посреди записи
  * После закрытия новые действия получают отказ вместо обращения к освобождённому соединению
  */
 fun close(callback:(Result<Unit>)->Unit):Cancellation {
  closeResult.whenComplete { _,error->main.post{callback(if(error==null)Result.success(Unit) else Result.failure(map(error)))} }
  if(closed.compareAndSet(false,true)){
   executor.shutdown()
   Thread({
    try{
     executor.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS)
     database?.close();database=null;closeResult.complete(Unit)
    }catch(t:Throwable){closeResult.completeExceptionally(map(t))}
   },"KandinskySQLiteClose").start()
  }
  return Cancellation{}
 }
}
