// Проверяет путь от запуска приложения Андроида до двух исходных чатов с помощью элементов
// экрана. Для этого нужен уже сохранённый вход.

package dev.kirill.kandinsky.chat.network

import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.allViews
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.domain.StudioDraftRecord
import dev.kirill.kandinsky.chat.storage.AppDataRoot
import dev.kirill.kandinsky.chat.storage.SQLiteStore
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import dev.kirill.kandinsky.chat.support.awaitResult
import java.io.File
import java.net.URI
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class AccountLiveFlowTest {
    @Test
    fun aFailedDraftWriteLeavesTheComposerUsableAndRetriesTheNextEdit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "dev.kirill.kandinsky.native.coverage.cleanup")
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val profile = NetworkCredentials(context).load()
        assumeNotNull(profile)
        val userID = checkNotNull(profile).userID
        val root = File(AppDataRoot.resolve(context.filesDir), "net-v1-u$userID")
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Плотная медиалента")
        val storage = awaitResult<SQLiteStore> { SQLiteStore.open(root, callback = it) }
        val original = awaitResult<StudioDraftRecord?> { callback ->
            storage.submit(StudioDraftStorage::load, callback)
        }
        try {
            awaitResult<Unit> { callback ->
                storage.submit({ db ->
                    db.execSQL(
                        "CREATE TRIGGER coverage_draft_write_refused BEFORE INSERT ON net_state " +
                            "WHEN NEW.key='studio.newDraft' " +
                            "BEGIN SELECT RAISE(ABORT,'coverage draft write refused'); END",
                    )
                }, callback)
            }
            ui.click(By.desc("История запросов"))
            ui.click(By.res("chats.compose"))
            ui.find(By.res("chat.welcome"))
            val input = ui.find(By.res("composer.input"))
            assertTrue(input.isEnabled)
            val afterFailure = awaitResult<StudioDraftRecord?> { callback ->
                storage.submit(StudioDraftStorage::load, callback)
            }
            assertEquals(original?.identity, afterFailure?.identity)

            awaitResult<Unit> { callback ->
                storage.submit({ db -> db.execSQL("DROP TRIGGER coverage_draft_write_refused") }, callback)
            }
            val text = "Черновик после восстановления записи"
            input.text = text
            val deadline = SystemClock.uptimeMillis() + 6000
            var restored: StudioDraftRecord?
            do {
                restored = awaitResult<StudioDraftRecord?> { callback ->
                    storage.submit(StudioDraftStorage::load, callback)
                }
                if (restored?.draft?.text == text) break
                SystemClock.sleep(25)
            } while (SystemClock.uptimeMillis() < deadline)
            assertEquals(text, restored?.draft?.text)
            assertFalse(restored?.identity == original?.identity)
        } finally {
            awaitResult<Unit> { callback ->
                storage.submit({ db ->
                    db.execSQL("DROP TRIGGER IF EXISTS coverage_draft_write_refused")
                    if (original != null) StudioDraftStorage.write(db, original)
                    else db.execSQL("DELETE FROM net_state WHERE key=?", arrayOf(StudioDraftStorage.KEY))
                }, callback)
            }
            awaitResult<Unit> { storage.close(it) }
        }
    }

    @Test
    fun corruptedChatDatabaseStopsStartupWithoutReplacingTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "dev.kirill.kandinsky.native.coverage.cleanup")
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val credentials = NetworkCredentials(context)
        assertNull(credentials.load())
        val userID = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
        val root = File(AppDataRoot.resolve(context.filesDir), "net-v1-u$userID")
        check(userID > 0 && !root.exists())
        val previousHistory = RepositoryHarness.open(root)
        previousHistory.close()
        val database = File(root, "chat.sqlite3")
        val previousBytes = database.readBytes()
        val damaged = previousBytes.copyOf()
        damaged.fill(0x5a, fromIndex = 0, toIndex = 16)
        val ui = AppFlowDriver()
        try {
            database.writeBytes(damaged)
            credentials.save(NetworkProfile(
                userID, UUID.randomUUID().toString(), "Проверочный вход",
                "a".repeat(64), Long.MAX_VALUE,
            ))
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.find(By.text("Не удалось открыть историю."))
                assertFalse(ui.device.hasObject(By.res("composer.input")))
                assertArrayEquals(damaged, database.readBytes())
            }
            database.writeBytes(previousBytes)
            val recovered = RepositoryHarness.open(root)
            try {
                assertEquals(3, recovered.rawScalar("SELECT unreadCount FROM chats WHERE id=2"))
            } finally {
                recovered.close()
            }
        } finally {
            credentials.removeActive()
            root.deleteRecursively()
        }
    }

    @Test
    fun startupReportsAnUnreadableMediaDirectoryAndPreservesTheHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val credentials = NetworkCredentials(context)
        assertNull(credentials.load())
        val userID = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
        val root = File(AppDataRoot.resolve(context.filesDir), "net-v1-u$userID")
        check(userID > 0 && !root.exists())
        val original = RepositoryHarness.open(root)
        val unread = original.rawScalar("SELECT unreadCount FROM chats WHERE id=2")
        original.close()
        val path = File(root, "media/objects")
        val content = "Файл на месте папки вложений"
        val ui = AppFlowDriver()
        try {
            check(path.parentFile?.mkdirs() == true || path.parentFile?.isDirectory == true)
            check(!path.exists())
            path.writeText(content)
            credentials.save(NetworkProfile(
                userID, UUID.randomUUID().toString(), "Временная проверка хранилища",
                "a".repeat(64), Long.MAX_VALUE,
            ))
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.find(By.text("Не удалось восстановить медиа."))
                assertFalse(ui.device.hasObject(By.res("composer.input")))
                assertEquals(content, path.readText())
            }
            check(path.delete())
            val preserved = RepositoryHarness.open(root)
            try {
                assertEquals(unread, preserved.rawScalar("SELECT unreadCount FROM chats WHERE id=2"))
            } finally {
                preserved.close()
            }
        } finally {
            credentials.removeActive()
            root.deleteRecursively()
        }
    }

    @Test
    fun aFileAtTheDataRootStopsStartupAndTheOriginalDirectoryCanBeRestored() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "dev.kirill.kandinsky.native.coverage.cleanup")
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assertNull(NetworkCredentials(context).load())
        val root = AppDataRoot.resolve(context.filesDir)
        val saved = File(context.filesDir, "coverage-data-root-" + UUID.randomUUID())
        assertTrue(root.renameTo(saved))
        val contents = "Файл вместо папки данных"
        val ui = AppFlowDriver()
        try {
            root.writeText(contents)
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.find(By.text("Не удалось открыть сохранённые данные приложения"))
                assertFalse(ui.device.hasObject(By.res("account.username")))
                assertEquals(contents, root.readText())
            }
        } finally {
            if (root.isFile) assertTrue(root.delete())
            assertTrue(saved.renameTo(root))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            ui.find(By.res("account.username"))
        }
    }

    @Test
    fun aNewerLocalSchemaLeavesTheSavedHistoryIntactAndStopsOpeningTheAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "dev.kirill.kandinsky.native.coverage.cleanup")
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val credentials = NetworkCredentials(context)
        assertNull(credentials.load())
        val userId = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE
        val root = File(AppDataRoot.resolve(context.filesDir), "net-v1-u$userId")
        check(!root.exists() && userId > 0)
        val history = RepositoryHarness.open(root)
        try {
            awaitResult<Unit> { callback ->
                history.store.submit({ db ->
                    db.execSQL("CREATE TABLE net_state(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO net_state VALUES('schema','99')")
                    db.execSQL("UPDATE chats SET title='Сохранённая история' WHERE id=2")
                }, callback)
            }
        } finally {
            history.close()
        }
        try {
            credentials.save(NetworkProfile(
                userId, UUID.randomUUID().toString(), "Проверочный вход",
                "a".repeat(64), Long.MAX_VALUE,
            ))
            val ui = AppFlowDriver()
            ActivityScenario.launch(MainActivity::class.java).use {
                ui.find(By.text("Не удалось открыть сетевую историю."))
                assertFalse(ui.device.hasObject(By.res("composer.input")))
            }
            val reopened = RepositoryHarness.open(root)
            try {
                assertEquals(99, reopened.rawScalar("SELECT CAST(value AS INTEGER) FROM net_state"))
                assertEquals(0, reopened.rawScalar("SELECT count(*) FROM sqlite_master WHERE name='net_messages'"))
                assertEquals(1, reopened.rawScalar("SELECT count(*) FROM chats WHERE title='Сохранённая история'"))
                assertEquals(userId, credentials.load()?.userID)
            } finally {
                reopened.close()
            }
        } finally {
            credentials.forget(userId)
            root.deleteRecursively()
        }
    }

    @Test
    fun refusedStartupInvitationLeavesSignInAvailableOnNextLaunch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val credentials = NetworkCredentials(context)
        assertNull(credentials.load())
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(Intent(intent).putExtra("network-invitation", "0".repeat(64)))
        val ui = AppFlowDriver()
        ui.find(By.text("Не удалось открыть сохранённый вход. Попробуйте ещё раз."))
        assertNull(credentials.load())
        context.startActivity(intent)
        ui.find(By.res("account.username"))
        assertNull(credentials.load())
    }

    @Test
    fun erasingTheAccountFromAnotherSessionRemovesItsLocalDraftAndOpensSignIn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeTrue(InstrumentationRegistry.getArguments().getString("coverage_mailbox") == "1")
        val credentials = NetworkCredentials(context)
        assertNull("Проверка начинается без сохранённого входа", credentials.load())
        val nonce = UUID.randomUUID().toString().take(8)
        val mail = "native-coverage-flow-android-$nonce@example.com"
        val password = "OtherSession-$nonce!"
        val api = NetworkAPI(null)
        val ui = AppFlowDriver()
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val username = By.res("account.username")
        val editor = By.res("composer.input")
        try {
            api.requestEmailCode(mail, recovery = false)
            val code = api.json("/v1/__coverage/mail/registration?email=$mail").getString("code")
            assertEquals(8, code.length)
            api.signIn(mail, password, registrationCode = code)
            context.startActivity(launcher)
            ui.find(username).text = mail
            ui.find(By.res("account.name")).text = password
            ui.click(By.res("account.submit"))
            ui.find(editor).text = "Черновик удаляемого аккаунта"
            ui.find(By.res("composer.input").text("Черновик удаляемого аккаунта"))
            val first = checkNotNull(credentials.load())
            val local = File(AppDataRoot.resolve(context.filesDir), "net-v1-u${first.userID}")
            assertTrue(local.isDirectory)
            val second = api.signIn(mail, password)
            assertEquals(first.userID, second.userID)
            val other = NetworkAPI(second)
            try {
                other.json("/v1/kandinsky/account", JSONObject().put("password", password), "DELETE")
            } finally {
                other.close()
            }
            ui.find(username)
            assertNull(credentials.load())
            assertFalse(local.exists())
            assertFalse(ui.device.hasObject(editor))
            context.startActivity(launcher)
            ui.find(username)
            assertFalse(ui.device.hasObject(editor))
        } finally {
            api.close()
            credentials.removeActive()
        }
    }

    // Запускает приложение, открывает боковую панель и нажимает на оба названия по очереди.
    // Появление поля ввода подтверждает переход в чат.
    @Test
    fun existingAccountCanOpenBothOriginalHistories() {
        val ui = AppFlowDriver()
        ui.launch()
        for (title in listOf("Плотная медиалента", "Длинная история")) {
            ui.openChat(title)
        }
    }

    @Test
    fun localSignInSurvivesRestartAndSignOutClearsTheSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val arguments = InstrumentationRegistry.getArguments()
        val email = arguments.getString("coverage_email")
        val password = arguments.getString("coverage_password")
        assumeNotNull(email, password)
        val credentials = NetworkCredentials(context)
        assertNull("Для проверки входа нужна пустая проверочная установка", credentials.load())
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val ui = AppFlowDriver()
        try {
            context.startActivity(
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            val username = By.res("account.username")
            ui.find(username).text = checkNotNull(email)
            ui.find(By.clazz("android.widget.EditText").text("Пароль")).text = checkNotNull(password)
            ui.click(username)
            instrumentation.runOnMainSync {
                val address = WindowInspector.getGlobalWindowViews().asSequence()
                    .flatMap { it.allViews }.filterIsInstance<EditText>()
                    .single { it.isShown && it.createAccessibilityNodeInfo().viewIdResourceName == "account.username" }
                assertTrue(address.hasFocus())
                address.onEditorAction(EditorInfo.IME_ACTION_DONE)
            }
            ui.find(By.res("composer.input"))
            val profile = checkNotNull(credentials.load())
            assertEquals(email, profile.name)

            ui.launch()
            ui.openChat("Плотная медиалента")
            assertEquals(profile.userID, credentials.load()?.userID)
            ui.click(By.desc("История запросов"))
            ui.click(By.res("chats.account"))
            ui.find(username)
            assertNull(credentials.load())
        } finally {
            credentials.removeActive()
        }
    }


    @Test
    fun localAccountRemovalRejectsWrongPasswordAndClearsAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val arguments = InstrumentationRegistry.getArguments()
        val email = arguments.getString("coverage_erasure_email")
        val password = arguments.getString("coverage_erasure_password")
        assumeTrue(email?.startsWith("native-coverage-delete-android-") == true)
        assumeTrue(checkNotNull(email).endsWith("@example.com"))
        assumeNotNull(password)
        val credentials = NetworkCredentials(context)
        assertNull("Для удаления нужна пустая проверочная установка", credentials.load())
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val ui = AppFlowDriver()
        val username = By.res("account.username")
        val root = AppDataRoot.resolve(context.filesDir)
        val queue = File(root, "erased-accounts")
        val backup = File(root, "erased-accounts-coverage-" + UUID.randomUUID())
        var blocked = false
        var moved = false

        fun restoreErasureQueue() {
            if (blocked) {
                check(queue.isFile && queue.delete())
                blocked = false
            }
            if (moved) {
                check(backup.renameTo(queue))
                moved = false
            }
        }

        fun submitLogin() {
            ui.find(username).text = email
            ui.find(By.clazz("android.widget.EditText").text("Пароль")).text = checkNotNull(password)
            ui.click(By.res("account.submit"))
        }

        try {
            context.startActivity(launch)
            submitLogin()
            ui.find(By.res("composer.input"))
            val owner = checkNotNull(credentials.load())
            assertEquals(email, owner.name)
            ui.click(By.desc("История запросов"))
            ui.click(By.res("chats.manageAccount"))
            ui.click(By.text("Удалить аккаунт"))

            val secret = By.res("account.erasure.password")
            val confirm = By.res("android:id/button1")
            ui.find(secret).text = "short"
            ui.click(confirm)
            ui.find(By.text("Проверьте пароль."))
            ui.find(secret).text = "WrongPassword2026"
            ui.click(confirm)
            ui.find(By.text("Неверный пароль."))
            assertEquals(owner.userID, credentials.load()?.userID)

            // Заменяем пустую папку отметок файлом, чтобы первая запись на устройстве не прошла.
            if (queue.exists()) {
                check(queue.isDirectory && queue.list()?.isEmpty() == true)
                check(queue.renameTo(backup))
                moved = true
            }
            check(queue.createNewFile())
            blocked = true
            ui.find(secret).text = checkNotNull(password)
            ui.click(confirm)
            ui.find(By.text("Не удалось очистить данные"))
            assertTrue(File(root, "net-v1-u${owner.userID}").isDirectory)

            restoreErasureQueue()
            ui.click(confirm)
            ui.find(username)
            assertFalse(File(root, "net-v1-u${owner.userID}").exists())
            assertNull(credentials.load())
            context.startActivity(launch)
            submitLogin()
            ui.find(By.res("account.error"))
            assertNull(credentials.load())
        } finally {
            restoreErasureQueue()
            credentials.removeActive()
        }
    }


    @Test
    fun localRegistrationAndRecoveryUseCodesFromSeparateLetters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeTrue(InstrumentationRegistry.getArguments().getString("coverage_mailbox") == "1")
        val credentials = NetworkCredentials(context)
        assertNull("Для регистрации нужна пустая проверочная установка", credentials.load())
        val mail = "native-coverage-flow-android-${java.util.UUID.randomUUID().toString().take(8)}@example.com"
        val originalPassword = "FirstLocalPassword2026!"
        val recoveredPassword = "RecoveredLocalPassword2026!"
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val ui = AppFlowDriver()
        val email = By.res("account.username")
        val code = By.res("account.code")
        val password = By.res("account.name")
        val submit = By.res("account.submit")
        val composer = By.res("composer.input")
        val api = NetworkAPI(null)
        fun localMailCode(kind: String): String {
            val value = api.json("/v1/__coverage/mail/$kind?email=$mail").getString("code")
            assertEquals(8, value.length)
            return value
        }

        try {
            context.startActivity(launch)
            ui.click(By.res("account.toggle"))
            ui.find(email).text = mail
            assertEquals("Получить код", ui.find(submit).text)
            ui.click(submit)
            ui.find(code).text = localMailCode("registration")
            ui.find(password).text = originalPassword
            assertEquals("Зарегистрироваться", ui.find(submit).text)
            ui.click(submit)
            ui.find(composer)
            assertEquals(mail, credentials.load()?.name)

            ui.click(By.desc("История запросов"))
            ui.click(By.res("chats.account"))
            ui.find(email)
            ui.click(By.res("account.forgot"))
            ui.find(email).text = mail
            assertEquals("Получить код", ui.find(submit).text)
            ui.click(submit)
            ui.find(code).text = localMailCode("recovery")
            ui.find(password).text = recoveredPassword
            assertEquals("Сохранить пароль", ui.find(submit).text)
            ui.click(submit)
            ui.find(By.text("Пароль изменён. Войдите с новым паролем."))
            ui.find(password).text = originalPassword
            ui.click(submit)
            ui.find(By.text("Неверная почта или пароль."))
            assertNull(credentials.load())
            ui.find(password).text = recoveredPassword
            ui.click(submit)
            ui.find(composer)
            assertEquals(mail, credentials.load()?.name)
            ui.click(By.desc("История запросов"))
            ui.click(By.res("chats.account"))
            ui.find(email)
            assertNull(credentials.load())
        } finally {
            api.close()
            credentials.removeActive()
        }
    }


    @Test
    fun savedAccountRemainsAvailableAfterSigningOutOfTheOtherAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeTrue(InstrumentationRegistry.getArguments().getString("coverage_mailbox") == "1")
        val credentials = NetworkCredentials(context)
        val primary = checkNotNull(credentials.load())
        assumeTrue(primary.name == "native-coverage@example.com")
        val marker = UUID.randomUUID().toString().take(8)
        val address = "native-coverage-flow-android-$marker@example.com"
        val password = "LocalSavedAccount-$marker!"
        val temporaryTitle = "История другого входа $marker"
        val entry = NetworkAPI(null)
        try {
            entry.json("/v1/demo/register/request", JSONObject().put("email", address))
            val mail = entry.json("/v1/__coverage/mail/registration?email=$address")
            val secondary = entry.signIn(address, password, mail.getString("code"))
            try {
                assertTrue(primary.userID != secondary.userID)
                val api = NetworkAPI(secondary)
                try {
                    val chat = api.json("/v1/demo/chats", JSONObject().put("id", UUID.randomUUID().toString()))
                        .getJSONObject("chat").getLong("id")
                    api.json("/v1/kandinsky/threads/$chat", JSONObject().put("title", temporaryTitle), "PATCH")
                } finally {
                    api.close()
                }
                credentials.save(secondary)
                val ui = AppFlowDriver()
                ui.launch()
                ui.openChat(temporaryTitle)
                ui.click(By.desc("История запросов"))
                ui.click(By.res("chats.account"))
                ui.find(By.res("account.username"))
                assertNull(credentials.load())
                assertFalse(ui.device.hasObject(By.res("account.saved.${secondary.userID}")))

                ui.click(By.res("account.saved.${primary.userID}"))
                ui.find(By.res("composer.input"))
                assertEquals(primary.userID, credentials.load()?.userID)
                assertEquals(primary.deviceID, credentials.load()?.deviceID)
                ui.openChat("Длинная история")
                ui.openChat("Плотная медиалента")
                ui.click(By.desc("История запросов"))
                assertFalse(ui.device.hasObject(By.text(temporaryTitle)))
            } finally {
                credentials.forget(secondary.userID)
                credentials.save(primary)
                val cleanup = NetworkAPI(entry.signIn(address, password))
                try {
                    cleanup.json("/v1/kandinsky/account", JSONObject().put("password", password), "DELETE")
                } finally {
                    cleanup.close()
                }
            }
        } finally {
            entry.close()
        }
    }

    @Test
    fun aRevokedSessionKeepsTheDraftForTheNextValidSignIn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val arguments = InstrumentationRegistry.getArguments()
        val email = arguments.getString("coverage_session_email")
        val password = arguments.getString("coverage_session_password")
        assumeTrue(email?.startsWith("native-coverage-session-android-") == true)
        assumeTrue(checkNotNull(email).endsWith("@example.com"))
        assumeNotNull(password)
        val credentials = NetworkCredentials(context)
        assertNull("Проверка начинается без сохранённого входа", credentials.load())
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val ui = AppFlowDriver()
        val username = By.res("account.username")
        val input = By.res("composer.input")
        val draft = "Черновик до отзыва сеанса"

        fun enter(): NetworkProfile {
            ui.find(username).text = email
            ui.find(By.res("account.name")).text = checkNotNull(password)
            ui.click(By.res("account.submit"))
            ui.find(input)
            return checkNotNull(credentials.load())
        }

        try {
            context.startActivity(launcher)
            val original = enter()
            val api = NetworkAPI(original)
            val title = "Проверка отзыва " + java.util.UUID.randomUUID().toString().take(8)
            val thread: Long
            try {
                val body = org.json.JSONObject().put("id", java.util.UUID.randomUUID().toString())
                thread = api.json("/v1/demo/chats", body).getJSONObject("chat").getLong("id")
                api.json("/v1/kandinsky/threads/$thread", org.json.JSONObject().put("title", title), "PATCH")
                ui.launch()
                ui.openChat(title)
                ui.find(input).text = draft
                ui.find(By.res("composer.input").text(draft))

                api.json("/v1/kandinsky/logout", method = "POST")
                val denied = runCatching { api.json("/v1/chats") }.exceptionOrNull() as? NetworkFailure
                assertEquals(401, denied?.status)
            } finally {
                api.close()
            }

            context.startActivity(launcher)
            ui.find(username)
            assertNull(credentials.load())
            val accepted = enter()
            assertEquals(original.userID, accepted.userID)
            ui.find(By.res("composer.input").text(draft))

            val restored = NetworkAPI(accepted)
            try {
                restored.json("/v1/kandinsky/threads/$thread", method = "DELETE")
                restored.json("/v1/kandinsky/logout", method = "POST")
            } finally {
                restored.close()
            }
        } finally {
            credentials.removeActive()
        }
    }
}
