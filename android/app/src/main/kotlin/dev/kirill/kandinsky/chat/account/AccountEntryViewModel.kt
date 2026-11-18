package dev.kirill.kandinsky.chat.account

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.network.NetworkAPI
import dev.kirill.kandinsky.chat.network.NetworkCredentials
import dev.kirill.kandinsky.chat.network.NetworkFailure
import dev.kirill.kandinsky.chat.network.NetworkProfile

internal enum class AccountEntryStep {
    LOGIN, REGISTRATION, RECOVERY, VERIFY_REGISTRATION, VERIFY_RECOVERY;

    val needsCode get() = this == VERIFY_REGISTRATION || this == VERIFY_RECOVERY
    val needsPassword get() = this == LOGIN || needsCode
}

internal class AccountEntryViewModel(
    private val requestCode: (String, Boolean) -> Unit,
    private val resetPassword: (String, String, String) -> Unit,
    private val signIn: (String, String, String?) -> NetworkProfile,
    private val readProfiles: () -> List<NetworkProfile>,
    private val saveProfile: (NetworkProfile) -> Unit,
    private val closeService: () -> Unit,
    private val background: (() -> Unit) -> Unit,
    private val foreground: (() -> Unit) -> Unit,
    private val selected: (NetworkProfile) -> Unit,
) {
    companion object {
        fun forDevice(context: Context, api: NetworkAPI, selected: (NetworkProfile) -> Unit): AccountEntryViewModel {
            val credentials = NetworkCredentials(context)
            val main = Handler(Looper.getMainLooper())
            return AccountEntryViewModel(
                api::requestEmailCode, api::resetPassword, api::signIn,
                credentials::profiles, credentials::save, api::close,
                background = { work -> Thread(work, "KandinskyEntry").start() },
                foreground = { work -> main.post { work() } },
                selected = selected,
            )
        }
    }

    private val profileLock = Any()
    @Volatile private var closed = false
    var step = AccountEntryStep.LOGIN
        private set
    var message = ""
        private set
    var isBusy = false
        private set
    var onChange: (() -> Unit)? = null

    fun loadSavedProfiles(display: (List<NetworkProfile>) -> Unit) {
        background {
            val profiles = runCatching(readProfiles)
            foreground {
                if (!closed) profiles.fold(display) {
                    show("Не удалось прочитать сохранённые аккаунты. Данные остаются на устройстве.")
                }
            }
        }
    }

    fun choose(profile: NetworkProfile) {
        if (!closed && !isBusy) runRequest({ remember(profile) }, selected)
    }

    private fun remember(profile: NetworkProfile): NetworkProfile {
        synchronized(profileLock) {
            check(!closed)
            saveProfile(profile)
        }
        return profile
    }

    fun toggleRegistration() {
        if (!closed && !isBusy) changeStep(if (step == AccountEntryStep.LOGIN) AccountEntryStep.REGISTRATION else AccountEntryStep.LOGIN)
    }

    fun startRecovery() {
        if (!closed && !isBusy) changeStep(AccountEntryStep.RECOVERY)
    }

    private fun changeStep(next: AccountEntryStep, notice: String = "") {
        step = next
        show(notice)
    }

    private fun show(notice: String) {
        message = notice
        onChange?.invoke()
    }

    fun submit(email: String, password: String, code: String, resending: Boolean = false) {
        if (closed || isBusy) return
        val address = email.trim()
        if (!address.contains('@')) return show("Введите электронную почту.")
        if (!resending && step.needsCode && !code.matches(Regex("[0-9]{8}"))) {
            return show("Введите восемь цифр из письма.")
        }
        if (!resending && step.needsPassword && password.length !in 12..128) {
            return show("Введите пароль длиной от 12 до 128 символов.")
        }
        if (resending || !step.needsPassword) {
            val recovery = step == AccountEntryStep.RECOVERY || step == AccountEntryStep.VERIFY_RECOVERY
            runRequest({ requestCode(address, recovery) }) {
                val notice = if (recovery) "Если аккаунт есть, код придёт на почту." else "Если адрес свободен, код придёт на почту."
                if (resending) show(notice)
                else changeStep(if (recovery) AccountEntryStep.VERIFY_RECOVERY else AccountEntryStep.VERIFY_REGISTRATION, notice)
            }
        } else if (step == AccountEntryStep.VERIFY_RECOVERY) {
            runRequest({ resetPassword(address, code, password) }) {
                changeStep(AccountEntryStep.LOGIN, "Пароль изменён. Войдите с новым паролем.")
            }
        } else {
            val registrationCode = if (step == AccountEntryStep.VERIFY_REGISTRATION) code else null
            runRequest({ remember(signIn(address, password, registrationCode)) }, selected)
        }
    }

    private fun <T> runRequest(action: () -> T, success: (T) -> Unit) {
        isBusy = true
        show("")
        background {
            val result = runCatching(action)
            foreground {
                if (!closed) {
                    isBusy = false
                    result.fold(success) { show(errorMessage(it)) }
                    onChange?.invoke()
                }
            }
        }
    }

    private fun errorMessage(error: Throwable): String {
        val failure = error as? NetworkFailure
        return when (failure?.status) {
            400 -> "Код неверный или больше не действует."
            401 -> "Неверная почта или пароль."
            409 -> "Аккаунт уже существует. Войдите с паролем."
            422 -> failure.code
            429 -> "Слишком много попыток. Попробуйте через минуту."
            503 -> "Служба временно недоступна. Попробуйте ещё раз."
            else -> "Проверьте соединение и попробуйте снова."
        }
    }

    fun close() {
        synchronized(profileLock) {
            if (closed) return
            closed = true
        }
        onChange = null
        background(closeService)
    }
}
