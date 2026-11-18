// Проверяет модель входа Андроида без экрана и сети. Управляет очередью вручную, чтобы
// воспроизвести повтор и закрытие во время запроса.

package dev.kirill.kandinsky.chat.account

import dev.kirill.kandinsky.chat.network.NetworkFailure
import dev.kirill.kandinsky.chat.network.NetworkProfile
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AccountEntryViewModelTest {
    private val calls = mutableListOf<String>()
    private val profile = NetworkProfile(1, "phone", "reader", "token", 0)
    private var signInError: IOException? = null
    private var readError: IOException? = null
    private var saveError: IOException? = null
    private val queued = ArrayDeque<() -> Unit>()
    private val model = AccountEntryViewModel(
        requestCode = { _, recovery -> calls += if (recovery) "recover" else "register" },
        resetPassword = { _, code, _ -> calls += "reset $code" },
        signIn = { _, _, code ->
            calls += code ?: "login"
            signInError?.let { throw it }
            profile
        },
        readProfiles = {
            readError?.let { throw it }
            listOf(profile)
        },
        saveProfile = {
            saveError?.let { throw it }
            calls += "saved"
        },
        closeService = { calls += "closed" },
        background = { queued.addLast(it) },
        foreground = { it() },
        selected = { calls += "selected" },
    )

    // Передаёт заранее заданный адрес и пароль, затем исполняет очередную работу. Это позволяет
    // проверить весь переход с правильным порядком ответа.
    private fun submit(code: String = "", resend: Boolean = false) {
        model.submit(" reader@example.org ", "example password", code, resend)
        completeScheduledWork()
    }

    // Выполняет накопленные задачи в порядке добавления. Проверка может сначала закрыть форму или
    // изменить условие, а затем вернуть ответ.
    private fun completeScheduledWork() {
        while (queued.isNotEmpty()) queued.removeFirst()()
    }

    // Проверяет запрос двух разных кодов, неверный ввод и успешные переходы. Сверяет, что
    // сохранение и выбор аккаунта произошли только после входа или регистрации.
    @Test fun registrationAndRecoveryKeepDifferentPurposes() {
        model.startRecovery()
        submit()
        assertEquals(AccountEntryStep.VERIFY_RECOVERY, model.step)
        submit(resend = true)
        submit("123x5678")
        assertEquals(listOf("recover", "recover"), calls)
        submit("01234567")
        assertEquals(AccountEntryStep.LOGIN, model.step)
        submit()
        model.toggleRegistration()
        submit()
        assertEquals(AccountEntryStep.VERIFY_REGISTRATION, model.step)
        submit("87654321")
        assertEquals(listOf("recover", "recover", "reset 01234567", "login", "saved", "selected",
            "register", "87654321", "saved", "selected"), calls)
    }

    // Дважды отправляет форму и убеждается, что в очередь попала одна работа. После закрытия не
    // допускает сохранения полученного профиля и выбора аккаунта.
    @Test fun duplicateSubmissionAndClosedScreenDoNotSaveProfile() {
        repeat(2) { model.submit("reader@example.org", "example password", "") }
        assertEquals(1, queued.size)
        model.close()
        while (queued.isNotEmpty()) queued.removeFirst()()
        assertFalse("saved" in calls)
        assertFalse("selected" in calls)
    }

    // Проверяет подсказки для неверной почты и короткого пароля. Модель должна остаться на входе
    // с пустой очередью и свободной кнопкой.
    @Test fun invalidInputKeepsTheLoginFormAndNeverUsesTheNetwork() {
        model.submit("reader", "valid-password", "")
        assertEquals("Введите электронную почту.", model.message)
        model.submit("reader@example.org", "short", "")
        assertEquals("Введите пароль длиной от 12 до 128 символов.", model.message)
        assertFalse(model.isBusy)
        assertTrue(queued.isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals(AccountEntryStep.LOGIN, model.step)
    }

    // Возвращает разные отказы и обрыв и сверяет подсказки. Ошибка должна освободить форму, не
    // добавляя сохранения профиля.
    @Test fun failedLoginShowsActionableMessageAndNeverSavesSession() {
        val failures = mapOf(
            400 to "Код неверный или больше не действует.",
            401 to "Неверная почта или пароль.",
            409 to "Аккаунт уже существует. Войдите с паролем.",
            422 to "Выберите другой пароль.",
            429 to "Слишком много попыток. Попробуйте через минуту.",
            503 to "Служба временно недоступна. Попробуйте ещё раз.",
            500 to "Проверьте соединение и попробуйте снова.",
        )
        for ((status, message) in failures) {
            signInError = NetworkFailure(status, "Выберите другой пароль.")
            submit()
            assertEquals("HTTP $status", message, model.message)
            assertEquals(AccountEntryStep.LOGIN, model.step)
            assertFalse(model.isBusy)
        }
        signInError = IOException("offline")
        submit()
        assertEquals("Проверьте соединение и попробуйте снова.", model.message)
        assertEquals(List(failures.size + 1) { "login" }, calls)
    }

    // Прерывает чтение и запись профиля, затем повторяет с исправным хранилищем. Проверяет
    // порядок сохранения и выбора, а также одно закрытие при повторной команде.
    @Test fun unreadableOrUnsavableProfileNeverReplacesTheCurrentAccount() {
        val displayed = mutableListOf<NetworkProfile>()
        readError = IOException("profile storage unavailable")
        model.loadSavedProfiles(displayed::addAll)
        completeScheduledWork()
        assertTrue(displayed.isEmpty())
        assertEquals(
            "Не удалось прочитать сохранённые аккаунты. Данные остаются на устройстве.",
            model.message,
        )
        readError = null
        model.loadSavedProfiles(displayed::addAll)
        completeScheduledWork()
        assertEquals(listOf(profile), displayed)

        saveError = IOException("profile storage read only")
        model.choose(profile)
        completeScheduledWork()
        assertTrue(calls.isEmpty())
        saveError = null
        model.choose(profile)
        completeScheduledWork()
        assertEquals(listOf("saved", "selected"), calls)

        model.close()
        model.close()
        model.choose(profile)
        completeScheduledWork()
        assertEquals(listOf("saved", "selected", "closed"), calls)
    }
}
