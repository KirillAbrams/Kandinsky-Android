package dev.kirill.kandinsky.chat.storage

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.Message
import dev.kirill.kandinsky.chat.domain.SendCommand

/**
 * Записывает намерения через последовательную очередь базы
 * Сохраняет их в той же сделке, которая меняет видимую историю или счётчик чата
 */
interface LocalChangeJournal {
    /**
     * Записывает намерение передать сохранённое сообщение серверу
     * Вызывается внутри той же операции базы, что и создание видимого сообщения, поэтому очередь и история не расходятся
     */
    fun appended(database: SQLiteDatabase, command: SendCommand, localID: Long)
    /**
     * Записывает намерение удалить сообщение на сервере
     * Сохранение намерения выполняется вместе с местным удалением, чтобы оно не потерялось после закрытия приложения
     */
    fun deleting(database: SQLiteDatabase, message: Message)
    /**
     * Запоминает границу прочтения чата для последующей передачи серверу
     * Использует уже открытое соединение и сохраняется вместе с местным изменением счётчика
     */
    fun read(database: SQLiteDatabase, chatID: Long)
}
