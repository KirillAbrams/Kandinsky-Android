// Запоминает действия, которые телефон выполнил в своей базе и должен передать серверу. Хранилище
// вызывает эти методы внутри той же операции записи.

package dev.kirill.kandinsky.chat.storage

import android.database.sqlite.SQLiteDatabase
import dev.kirill.kandinsky.chat.domain.Message
import dev.kirill.kandinsky.chat.domain.SendCommand

interface LocalChangeJournal {
    // Сохраняет команду отправки и номер появившегося сообщения. Если очередь не запишется,
    // операция откатится целиком и не оставит сообщение только на телефоне.
    fun appended(database: SQLiteDatabase, command: SendCommand, localID: Long)

    // Добавляет запрос к серверу вместе с местным удалением. Закрытие приложения после удаления
    // не приведёт к тому, что сервер оставит сообщение навсегда.
    fun deleting(database: SQLiteDatabase, message: Message)

    // Добавляет команду прочтения для указанного чата. Без неё непрочитанные сообщения на других
    // устройствах остались бы с прежней отметкой.
    fun read(database: SQLiteDatabase, chatID: Long)
}
