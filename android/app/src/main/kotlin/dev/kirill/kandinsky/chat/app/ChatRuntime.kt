// Собирает базу, историю и службы вложений на Андроиде. При наличии сетевого входа добавляет
// загрузку оригиналов и обмен, а местные проверки могут работать со своей базой.

package dev.kirill.kandinsky.chat.app

import android.content.Context
import android.os.Build
import dev.kirill.kandinsky.chat.media.MediaAdmission
import dev.kirill.kandinsky.chat.media.MediaCoordinator
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.network.LocalAccountErasure
import dev.kirill.kandinsky.chat.network.NetworkAPI
import dev.kirill.kandinsky.chat.network.NetworkChatRepository
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.network.NetworkJournal
import dev.kirill.kandinsky.chat.network.NetworkMedia
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.network.NetworkSchema
import dev.kirill.kandinsky.chat.network.NetworkSync
import dev.kirill.kandinsky.chat.storage.ChatRepository
import dev.kirill.kandinsky.chat.storage.SQLiteChatRepository
import dev.kirill.kandinsky.chat.storage.SQLiteStore
import org.json.JSONObject

internal class ChatRuntime(
    val store: SQLiteStore,
    val network: NetworkSync? = null,
) {
    val repository: ChatRepository =
        network?.let { NetworkChatRepository(it.local, it) }
            ?: SQLiteChatRepository(store)
    val media = MediaStore(store, studioOriginals = network != null)

    // Подключает загрузку, проигрывание и поиск уже скачанных файлов к местному хранилищу. Без
    // этой связи сетевой чат не сможет открыть сохранённый оригинал так же, как местный.
    init {
        network?.let { sync ->
            val remote = NetworkMedia(store, sync.api, cacheLimitBytes = 256L * 1024 * 1024)
            sync.studioMedia = remote
            sync.onStudioMediaEvicted = media::releaseIdleImages
            media.remoteFile = remote::resolve
            media.remoteCached = remote::cached
            media.remotePlayback = remote::playback
            media.pendingReferences = NetworkMedia::pendingReferences
        }
    }

    val admission = MediaAdmission()
    val playback = MediaCoordinator(admission, media)

    companion object {
        // Доводит до конца прежнюю очистку, затем читает сохранённый вход. Если его нет,
        // проверяет приглашение, запрашивает профиль и кладёт его в защищённое хранилище.
        fun restoreProfile(context: Context, invitation: () -> String?): NetworkProfile? {
            val credentials = NetworkCredentials(context)
            LocalAccountErasure.finish(context, credentials)
            credentials.load()?.let { return it }
            val code = invitation() ?: return null
            require(code.matches(Regex("[a-f0-9]{64}")))
            val api = NetworkAPI(null)
            try {
                api.initialize()
                return NetworkProfile.decode(
                        api.json(
                            "/v1/devices/enroll",
                            JSONObject().put("invitation", code).put("deviceName", Build.MODEL),
                        )
                    )
                    .also(credentials::save)
            } finally {
                api.close()
            }
        }

        // Ставит сетевые таблицы на очереди готовой базы и только при успехе соединяет историю с
        // журналом и обменом. Вызывающий экран получает ошибку вместо наполовину готового
        // аккаунта.
        fun openAccount(
            store: SQLiteStore,
            profile: NetworkProfile,
            complete: (Result<ChatRuntime>) -> Unit,
        ) {
            store.submit({ db -> NetworkSchema.install(db) }) { installed ->
                complete(
                    installed.map {
                        val local = SQLiteChatRepository(store, NetworkJournal(profile.userID))
                        ChatRuntime(store, NetworkSync(profile, store, local))
                    }
                )
            }
        }
    }

    // Останавливает обмен, проигрывание, хранилище файлов и очередь выдачи ресурсов, затем
    // закрывает базу. Иначе фоновые службы могли бы пережить выход из аккаунта.
    fun close() {
        network?.close()
        playback.close()
        media.close()
        admission.close()
        store.close {}
    }
}
