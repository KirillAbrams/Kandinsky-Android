package dev.kirill.kandinsky.chat.network

import dev.kirill.kandinsky.chat.domain.*
import org.json.JSONObject

internal class SyncModelCatalog(private val sync: NetworkSync) {
    fun studioModels(completion: (Result<List<StudioModel>>) -> Unit) {
        sync.worker.post {
            if (sync.closed) return@post
            sync.http({
                val values = sync.api.json("/v1/kandinsky/models").getJSONArray("models")
                List(values.length()) { index ->
                    val value = values.getJSONObject(index)
                    val id = value.getString("id")
                    StudioModel(
                        id,
                        value.getString("name"),
                        value.getString("description"),
                        if (id == "video_sr") null
                        else studioCapabilities(value.getJSONObject("capabilities")),
                        value.optJSONObject("reference_capabilities")?.let(::studioCapabilities),
                    )
                }
            }) { result ->
                sync.main.post { completion(result) }
            }
        }
    }

    private fun studioCapabilities(value: JSONObject): StudioCapabilities {
        fun choices(key: String): List<StudioChoice> {
            val array = value.getJSONArray(key)
            return List(array.length()) { index ->
                val choice = array.getJSONObject(index)
                val sizes = choice.optJSONObject("sizes")
                StudioChoice(
                    choice.getString("id"),
                    choice.getString("name"),
                    sizes?.let { entries ->
                        entries.keys().asSequence().associateWith { aspect ->
                            val dimensions = entries.getJSONArray(aspect)
                            List(dimensions.length()) { dimensions.getInt(it) }
                        }
                    } ?: emptyMap(),
                )
            }
        }
        val aspects = value.getJSONArray("aspects")
        val durations = value.getJSONArray("duration_seconds")
        val guidance =
            value.optJSONObject("guidance")?.let {
                StudioGuidance(it.getDouble("min"), it.getDouble("max"), it.getDouble("default"))
            }
        return StudioCapabilities(
            choices("quality"),
            choices("resolutions"),
            List(aspects.length()) { aspects.getString(it) },
            List(durations.length()) { durations.getInt(it) },
            value.getInt("max_count"),
            value.getBoolean("negative_prompt"),
            guidance,
        )
    }
}
