// Проверяет и кладёт в базу маленькие изображения и точки формы звука. Эти данные нужны для
// быстрого показа вложения до открытия исходного файла.

package dev.kirill.kandinsky.chat.storage

import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray

object MediaPreviewStorage {
    // Проверяет начало строки, длину и размер после преобразования в байты. Слишком тяжёлая или битая
    // копия не попадёт в местную таблицу.
    fun tiny(value: String?): String? =
        value?.takeIf {
            it.length <= 4096 &&
                it.startsWith("/9j/") &&
                runCatching { Base64.decode(it, Base64.NO_WRAP).size <= 3072 }.getOrDefault(false)
        }

    // Оставляет только короткий непустой ряд значений от нуля до двухсот пятидесяти пяти. Вне
    // этих границ форма звука не подходит для вывода.
    fun waveform(value: List<Int>?): List<Int>? =
        value?.takeIf { it.isNotEmpty() && it.size <= 128 && it.all { v -> v in 0..255 } }

    // Читает короткий массив из строки базы и проверяет длину до разбора точек. Неподходящая
    // запись пропускается, чтобы вся история открылась.
    fun readWave(value: String?): List<Int>? =
        value
            ?.takeIf { it.length <= 1024 }
            ?.let {
                runCatching {
                        val a = JSONArray(it)
                        require(a.length() <= 128)
                        waveform((0 until a.length()).map { n -> a.getInt(n) })
                    }
                    .getOrNull()
            }

    // Обновляет обе копии по номеру вложения. Перед записью проверяет каждую и оставляет пустое
    // поле, когда содержимое нельзя использовать.
    fun save(db: SQLiteDatabase, id: String, tiny: String?, waveform: List<Int>?) {
        db.execSQL(
            "INSERT OR REPLACE INTO media_previews(attachmentID,tinyPreview,waveform) VALUES(?,?,?)",
            arrayOf(id, tiny(tiny), waveform(waveform)?.let { JSONArray(it).toString() }),
        )
    }
}
