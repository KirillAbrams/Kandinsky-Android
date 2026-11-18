// Добавляет уменьшенные изображения и точки формы звука для проверочного набора. Экран использует
// их до чтения полных файлов.

package dev.kirill.kandinsky.chat.storage

import android.content.res.AssetManager
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

object SeedMediaPreviews {
    // Проверяет размер описания, связывает копии с именами файлов и номерами вложений и ставит
    // отметку в той же записи базы. После сбоя установку можно повторить целиком.
    fun install(db: SQLiteDatabase, assets: AssetManager) {
        if (
            db.rawQuery("SELECT 1 FROM metadata WHERE key='media-preview-catalog-v1'", null).use {
                it.moveToFirst()
            }
        )
            return
        val raw = assets.open("media-previews-v1.json").use { it.readBytes() }
        require(raw.size <= 2 * 1024 * 1024)
        val entries = JSONObject(String(raw, Charsets.UTF_8))
        require(entries.length() <= 512)
        db.beginTransaction()
        try {
            for (key in entries.keys()) {
                val entry = entries.getJSONObject(key)
                val tiny = MediaPreviewStorage.tiny(entry.optString("tinyPreview"))
                val wave = MediaPreviewStorage.readWave(entry.optJSONArray("waveform")?.toString())
                for (id in listOf(entry.getString("relativePath"), entry.getString("blobId"))) db
                    .execSQL(
                        "INSERT OR REPLACE INTO media_preview_catalog(key,tinyPreview,waveform) VALUES(?,?,?)",
                        arrayOf(id, tiny, wave?.let { org.json.JSONArray(it).toString() }),
                    )
            }
            db.execSQL(
                "INSERT OR REPLACE INTO metadata(key,value) VALUES('media-preview-catalog-v1','1')"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
