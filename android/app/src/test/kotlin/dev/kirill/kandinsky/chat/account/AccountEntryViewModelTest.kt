package dev.kirill.kandinsky.chat.account

import dev.kirill.kandinsky.chat.network.NetworkProfile
import org.junit.Assert.*
import org.junit.Test

class AccountEntryViewModelTest {
    private val calls = mutableListOf<String>()
    private val profile = NetworkProfile(1, "phone", "reader", "token", 0)
    private val queued = ArrayDeque<() -> Unit>()
    private val model = AccountEntryViewModel(
        requestCode = { _, recovery -> calls += if (recovery) "recover" else "register" },
        resetPassword = { _, code, _ -> calls += "reset $code" },
        signIn = { _, _, code -> calls += code ?: "login"; profile },
        readProfiles = { emptyList() },
        saveProfile = { calls += "saved" },
        closeService = {},
        background = { queued.addLast(it) },
        foreground = { it() },
        selected = { calls += "selected" },
    )

    private fun submit(code: String = "", resend: Boolean = false) {
        model.submit(" reader@example.org ", "example password", code, resend)
        while (queued.isNotEmpty()) queued.removeFirst()()
    }

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

    @Test fun duplicateSubmissionAndClosedScreenDoNotSaveProfile() {
        repeat(2) { model.submit("reader@example.org", "example password", "") }
        assertEquals(1, queued.size)
        model.close()
        while (queued.isNotEmpty()) queued.removeFirst()()
        assertFalse("saved" in calls)
        assertFalse("selected" in calls)
    }
}
