package dev.kirill.kandinsky.chat.storage

import android.content.res.AssetManager
import dev.kirill.kandinsky.chat.domain.RepoError
import dev.kirill.kandinsky.chat.domain.RepoException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

object SeedMediaInstaller {
    private data class Record(val name: String, val size: Long, val sha: String)

    private fun corrupt(): Nothing = throw RepoException(RepoError.CORRUPT)

    private fun records(assets: AssetManager): List<Record> {
        val text =
            assets.open("real-media/manifest.json").use { input ->
                val bytes = input.readBytes()
                if (bytes.size > 262144) corrupt()
                bytes.toString(Charsets.UTF_8)
            }
        val document = JSONObject(text)
        val files = document.getJSONArray("files")
        if (
            document.getInt("version") != 1 ||
                document.getLong("chatId") != 1352L ||
                files.length() !in 1..32
        )
            corrupt()
        val seen = HashSet<String>()
        var total = 0L
        return (0 until files.length()).map { index ->
            val f = files.getJSONObject(index)
            val id = f.getString("id")
            val mime = f.getString("mime")
            val name = f.getString("fileName")
            val path = f.getString("relativePath")
            val sha = f.getString("sha256")
            val size = f.getLong("byteSize")
            val width = f.getInt("width")
            val height = f.getInt("height")
            val orientation = f.getInt("orientation")
            val suffix =
                mapOf("image/jpeg" to ".jpg", "video/mp4" to ".mp4", "image/gif" to ".gif")[mime]
                    ?: corrupt()
            if (
                !id.matches(Regex("backend-[0-9a-f]{32}")) ||
                    name != id + suffix ||
                    path != "seed/$name" ||
                    !sha.matches(Regex("[0-9a-f]{64}")) ||
                    size !in 1..32L * 1024 * 1024 ||
                    width !in 1..16384 ||
                    height !in 1..16384 ||
                    orientation !in 1..8 ||
                    !seen.add(id)
            )
                corrupt()
            total += size
            if (total > 32L * 1024 * 1024) corrupt()
            Record(name, size, sha)
        }
    }

    private fun matches(file: File, record: Record, buffer: ByteArray): Boolean {
        if (file.length() != record.size) return false
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                if (size > record.size) return false
                hash.update(buffer, 0, n)
            }
        }
        return size == record.size &&
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == record.sha
    }

    fun install(root: File, assets: AssetManager) {
        val values = records(assets)
        val directory = File(root.canonicalFile, "media/seed")
        if (!directory.exists() && !directory.mkdirs()) throw RepoException(RepoError.IO)
        if (directory.canonicalFile != directory.absoluteFile) corrupt()
        val buffer = ByteArray(65536)
        for (record in values) {
            val destination = File(directory, record.name)
            if (destination.canonicalFile != destination.absoluteFile) corrupt()
            if (destination.exists()) {
                if (!matches(destination, record, buffer)) corrupt()
                continue
            }
            val temporary = File(directory, "." + record.name + "-" + UUID.randomUUID() + ".tmp")
            try {
                assets.open("real-media/" + record.name).use { input ->
                    FileOutputStream(temporary).use { output ->
                        var size = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n
                            if (size > record.size) corrupt()
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                }
                if (!matches(temporary, record, buffer)) corrupt()
                if (!temporary.renameTo(destination)) throw RepoException(RepoError.IO)
            } finally {
                temporary.delete()
            }
        }
    }
}
