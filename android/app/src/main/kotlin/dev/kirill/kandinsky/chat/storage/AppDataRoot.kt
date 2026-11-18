package dev.kirill.kandinsky.chat.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files

object AppDataRoot {
    /**
     * Единственный узнаваемый прежний корень переносит целиком вместе с базой и скачанными файлами
     * При нескольких подходящих корнях останавливает переход вместо произвольного выбора
     * Повторный вызов возвращает уже подготовленную папку без повторного переноса
     */
    @Synchronized fun resolve(base: File): File {
        if (!base.isDirectory && !base.mkdirs()) throw IOException("Не удалось открыть папку приложения")
        val target = File(base, "Kandinsky")
        if (target.exists()) {
            if (!target.isDirectory || Files.isSymbolicLink(target.toPath())) {
                throw IOException("Неверный корень данных приложения")
            }
            return target
        }
        val candidates = (base.listFiles() ?: throw IOException("Не удалось прочитать папку приложения"))
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && containsSavedState(it) }
        if (candidates.size > 1) throw IOException("Найдено несколько прежних хранилищ")
        val previous = candidates.singleOrNull()
        if (previous != null) {
            if (!previous.renameTo(target)) throw IOException("Не удалось перенести сохранённую историю")
        } else if (!target.mkdirs()) {
            throw IOException("Не удалось создать папку приложения")
        }
        return target
    }

    /**
     * Узнаёт прежний корень по хранилищам поддержанного вида и незавершённым удалениям аккаунтов
     * Название прежнего приложения для этого не требуется
     * Символические ссылки исключены, чтобы перенос не выбирал чужое расположение
     */
    private fun containsSavedState(root: File): Boolean {
        if (root.listFiles()?.any { child ->
            child.name.matches(Regex("^(chat-v1|net-v1-u[0-9]+|test-[A-Fa-f0-9-]+)$")) &&
                child.isDirectory && !Files.isSymbolicLink(child.toPath()) &&
                File(child, "chat.sqlite3").let { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        } == true) return true
        val queue = File(root, "erased-accounts")
        if (!queue.isDirectory || Files.isSymbolicLink(queue.toPath())) return false
        return queue.listFiles()?.any { marker ->
            val id = marker.name.toLongOrNull()
            id != null && id > 0 && marker.name == id.toString() &&
                marker.isFile && !Files.isSymbolicLink(marker.toPath()) && marker.length() == 0L
        } == true
    }
}
