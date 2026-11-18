package dev.kirill.kandinsky.chat.storage

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.Message
import dev.kirill.kandinsky.chat.domain.SendCommand

interface LocalChangeJournal {
    // Вызывается внутри той же операции базы, что и создание видимого сообщения, поэтому очередь и
    // история
    // не расходятся.
    fun appended(database: SQLiteDatabase, command: SendCommand, localID: Long)

    // Записываем команду удаления вместе с местным изменением. Тогда закрытие приложения не
    // потеряет запрос
    // к серверу.
    fun deleting(database: SQLiteDatabase, message: Message)

    fun read(database: SQLiteDatabase, chatID: Long)
}
