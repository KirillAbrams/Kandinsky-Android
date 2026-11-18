package dev.kirill.kandinsky.chat.storage
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
object MediaPreviewStorage {
 /**
  * Проверяет ограниченное текстовое представление самой маленькой копии изображения и ожидаемое начало его кодирования
  * Принимает только декодируемую запись в пределах размеров текста и содержимого, иначе возвращает отсутствие пригодной копии
  */
 fun tiny(value:String?):String?=value?.takeIf{it.length<=4096 && it.startsWith("/9j/") && runCatching{Base64.decode(it,Base64.NO_WRAP).size<=3072}.getOrDefault(false)}
 /**
  * Принимает непустой ограниченный набор значений рисунка звуковой дорожки
  * Возвращает те же значения при допустимом размере, позволяя отдельному чтению и сохранению использовать общую проверку
  */
 fun waveform(value:List<Int>?):List<Int>?=value?.takeIf{it.isNotEmpty() && it.size<=128 && it.all{v->v in 0..255}}
 /**
  * Читает ограниченную текстовую запись рисунка дорожки и разбирает её числовые значения
  * При повреждённом содержимом возвращает отсутствие рисунка, а подходящий набор дополнительно проверяет общей процедурой
  */
 fun readWave(value:String?):List<Int>?=value?.takeIf{it.length<=1024}?.let{runCatching{val a=JSONArray(it);require(a.length()<=128);waveform((0 until a.length()).map{n->a.getInt(n)})}.getOrNull()}
 /**
  * Проверяет маленькую копию и рисунок дорожки перед записью дополнительных сведений вложения
  * Сохраняет их под постоянным номером вложения, заменяя непригодные необязательные данные отсутствующим значением
  */
 fun save(db:SQLiteDatabase,id:String,tiny:String?,waveform:List<Int>?){db.execSQL("INSERT OR REPLACE INTO media_previews(attachmentID,tinyPreview,waveform) VALUES(?,?,?)",arrayOf(id,tiny(tiny),waveform(waveform)?.let{JSONArray(it).toString()}))}
}
