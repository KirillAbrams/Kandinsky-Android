// Проверяем возвращение к черновику после отмены съёмки.

package dev.kirill.kandinsky.chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.attachments.PhotoCaptureController
import dev.kirill.kandinsky.chat.domain.AttachmentDraft
import dev.kirill.kandinsky.chat.media.CaptureKind
import dev.kirill.kandinsky.chat.media.CaptureLease
import dev.kirill.kandinsky.chat.media.MediaAdmission
import dev.kirill.kandinsky.chat.media.MediaError
import dev.kirill.kandinsky.chat.media.MediaException
import dev.kirill.kandinsky.chat.media.MediaStore
import dev.kirill.kandinsky.chat.network.NetworkProfile
import dev.kirill.kandinsky.chat.support.AppFlowDriver
import dev.kirill.kandinsky.chat.support.RepositoryHarness
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PhotoCaptureFlowTest {
    @Test
    fun theOriginalSnapshotIsAvailableOnlyAfterTheCameraHasClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "dev.kirill.kandinsky.native.coverage.cleanup")
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val root = File(context.cacheDir, "original-camera-photo-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val saved = AtomicReference<Result<AttachmentDraft>>()
        val callback = CountDownLatch(1)
        val ui = AppFlowDriver()
        var media: MediaStore? = null
        var admission: MediaAdmission? = null
        var capture: PhotoCaptureController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ui.find(By.res("account.username"))
                scenario.onActivity { activity ->
                    val files = MediaStore(harness.store)
                    media = files
                    val owners = MediaAdmission()
                    admission = owners
                    val controller = PhotoCaptureController(activity, files, owners, { complete ->
                        complete(context.checkSelfPermission(Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED)
                    })
                    capture = controller
                    controller.open { outcome ->
                        assertEquals(0, owners.captureActiveCount)
                        assertNull(saved.getAndSet(outcome))
                        callback.countDown()
                    }
                }
                val shutter = By.desc("Сделать снимок").enabled(true)
                ui.click(shutter)
                assertTrue("Камера закрылась после записи снимка", callback.await(12, TimeUnit.SECONDS))
                assertTrue(ui.device.wait(Until.gone(shutter), 5000))
                val photo = checkNotNull(saved.get()).getOrThrow()
                val original = checkNotNull(media).fileURL(photo.relativePath)
                assertTrue(photo.relativePath.startsWith("objects/"))
                assertEquals("image/jpeg", photo.mime)
                assertEquals(photo.byteSize, original.length())
                val bitmap = checkNotNull(BitmapFactory.decodeFile(original.path))
                try {
                    assertEquals(photo.rawWidth, bitmap.width)
                    assertEquals(photo.rawHeight, bitmap.height)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                capture?.cancel()
                media?.close()
                admission?.close()
            }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectedCameraRequestDoesNotReleaseAnotherOwnersCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val root = File(context.cacheDir, "occupied-capture-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val ui = AppFlowDriver()
        var existing: CaptureLease? = null
        var media: MediaStore? = null
        var admission: MediaAdmission? = null
        var competing: MediaAdmission? = null
        var rejected: PhotoCaptureController? = null
        val result = AtomicReference<MediaError>()
        val done = CountDownLatch(1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ui.find(By.res("account.username"))
                scenario.onActivity { activity ->
                    val files = MediaStore(harness.store).also { media = it }
                    val owner = MediaAdmission().also { admission = it }
                    val other = MediaAdmission().also { competing = it }
                    owner.acquire(CaptureKind.PHOTO) { grant ->
                        existing = grant.getOrThrow()
                        val controller = PhotoCaptureController(activity, files, other, { it(true) })
                        rejected = controller
                        controller.open { reply ->
                            result.set((reply.exceptionOrNull() as? MediaException)?.reason)
                            done.countDown()
                        }
                    }
                }
                assertTrue(done.await(6, TimeUnit.SECONDS))
                assertEquals(MediaError.UNAVAILABLE, result.get())
                assertFalse(ui.device.hasObject(By.desc("Сделать снимок")))
                scenario.onActivity {
                    assertEquals(1, admission?.captureActiveCount)
                    assertEquals(0, competing?.captureActiveCount)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                rejected?.cancel()
                existing?.release()
                admission?.close()
                competing?.close()
                media?.close()
            }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun missingCameraClosesThePreviewAndReleasesItsLease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".cleanup"))
        val cameras = requireNotNull(context.getSystemService(CameraManager::class.java))
        assumeTrue("Для проверки нужен эмулятор без камер", cameras.cameraIdList.isEmpty())
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val root = File(context.cacheDir, "missing-camera-${UUID.randomUUID()}")
        val harness = RepositoryHarness.open(root)
        val ui = AppFlowDriver()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<MediaError>()
        var media: MediaStore? = null
        var admission: MediaAdmission? = null
        var capture: PhotoCaptureController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ui.find(By.res("account.username"))
                scenario.onActivity { activity ->
                    val files = MediaStore(harness.store).also { media = it }
                    val owner = MediaAdmission().also { admission = it }
                    capture = PhotoCaptureController(activity, files, owner, { complete ->
                        complete(context.checkSelfPermission(Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED)
                    })
                    requireNotNull(capture).open { outcome ->
                        failure.set((outcome.exceptionOrNull() as? MediaException)?.reason)
                        finished.countDown()
                    }
                }
                assertTrue("Проверка должна получить ответ камеры", finished.await(8, TimeUnit.SECONDS))
                assertEquals(MediaError.UNAVAILABLE, failure.get())
                assertTrue(ui.device.wait(Until.gone(By.desc("Сделать снимок")), 5000))
                ui.find(By.res("account.username"))
                scenario.onActivity { assertEquals(0, admission?.captureActiveCount) }
                assertTrue(File(root, "objects").list().orEmpty().isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync {
                capture?.cancel()
                admission?.close()
                media?.close()
            }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeCameraInterruptionClosesThePreviewAndReturnsItsLease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        assumeTrue(packageName == "dev.kirill.kandinsky.native.coverage.cleanup")
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeTrue(Build.PRODUCT.startsWith("sdk_gphone"))
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.CAMERA)
        val root = File(context.cacheDir, "interrupted-camera-" + UUID.randomUUID())
        val harness = RepositoryHarness.open(root)
        val reported = AtomicReference<MediaError>()
        val done = CountDownLatch(1)
        val ui = AppFlowDriver()
        var media: MediaStore? = null
        var admission: MediaAdmission? = null
        var capture: PhotoCaptureController? = null

        fun cameraCommand(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cmd media.camera $command"),
        ).bufferedReader().use { it.readText().trim() }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ui.find(By.res("account.username"))
                scenario.onActivity { activity ->
                    val files = MediaStore(harness.store)
                    media = files
                    val owner = MediaAdmission()
                    admission = owner
                    val controller = PhotoCaptureController(activity, files, owner, { complete ->
                        complete(context.checkSelfPermission(Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED)
                    })
                    capture = controller
                    controller.open { result ->
                        assertEquals(0, owner.captureActiveCount)
                        reported.set((result.exceptionOrNull() as? MediaException)?.reason)
                        done.countDown()
                    }
                }
                val shutter = By.desc("Сделать снимок").enabled(true)
                ui.find(shutter)
                assertEquals("active", cameraCommand("get-uid-state $packageName"))
                cameraCommand("set-uid-state $packageName idle")
                assertEquals("idle", cameraCommand("get-uid-state $packageName"))
                assertTrue("Система закрыла занятую камеру", done.await(12, TimeUnit.SECONDS))
                val reason = reported.get()
                assertTrue(
                    "После отключения пришла ошибка камеры: $reason",
                    reason == MediaError.INTERRUPTED || reason == MediaError.UNAVAILABLE,
                )
                assertTrue(ui.device.wait(Until.gone(shutter), 5000))
                ui.find(By.res("account.username"))
            }
        } finally {
            cameraCommand("reset-uid-state $packageName")
            instrumentation.runOnMainSync {
                capture?.cancel()
                media?.close()
                admission?.close()
            }
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun denyingCameraPermissionLeavesTheDraftAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        assumeTrue(context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        val editor = By.res("composer.input")
        val draft = ui.find(editor).text
        val plus = By.res("composer.plus")
        val photo = By.desc("Снять фото")
        ui.click(plus)
        ui.click(photo)
        ui.click(By.res(Pattern.compile(".+:id/permission_deny_button")))

        ui.find(By.res("studio.attachment.error").descContains("Проверьте разрешения"))
        assertEquals(draft, ui.find(editor).text)
        assertTrue(ui.device.wait(Until.gone(By.desc("Сделать снимок")), 5_000))
        ui.click(plus)
        ui.find(photo)
        ui.device.pressBack()
        assertEquals(draft, ui.find(editor).text)
    }

    // Открываем камеру из чата с текстом, отменяем съёмку и дожидаемся закрытия окна. Сверяем
    // прежний текст, чтобы случайная отмена не потеряла черновик.
    @Test
    fun cancellingCameraReturnsToSameDraft() {
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        val editor = By.res("composer.input")
        val draft = ui.find(editor).text
        ui.find(By.res("composer.plus")).click()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )
        ui.find(By.desc("Снять фото")).click()
        ui.find(By.desc("Сделать снимок").enabled(true))
        val cancel = By.desc("Отменить съёмку")
        val bounds = ui.find(cancel).visibleBounds
        ui.device.click(bounds.centerX(), bounds.centerY())
        org.junit.Assert.assertTrue(ui.device.wait(Until.gone(cancel), 10000))
        assertEquals(draft, ui.find(editor).text)
    }

    @Test
    fun capturedPhotoStaysAsAnAttachmentWithoutSendingTheDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".cleanup"))
        assumeTrue(URI(NetworkProfile.ORIGIN).host == "127.0.0.1")
        val ui = AppFlowDriver()
        ui.launch()
        ui.openChat("Длинная история")
        val editor = By.res("composer.input")
        val draft = ui.find(editor).text
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        ui.find(By.res("composer.plus")).click()
        ui.find(By.desc("Снять фото")).click()
        val shutter = By.desc("Сделать снимок").enabled(true)
        ui.find(shutter).click()
        assertTrue(ui.device.wait(Until.gone(shutter), 10_000))
        val reference = By.res("studio.reference.remove")
        ui.find(reference)
        assertEquals(draft, ui.find(editor).text)
        ui.find(reference).click()
        assertTrue(ui.device.wait(Until.gone(reference), 10_000))
        assertEquals(draft, ui.find(editor).text)
    }
}
