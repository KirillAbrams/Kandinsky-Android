package dev.kirill.kandinsky.chat.attachments

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import dev.kirill.kandinsky.chat.domain.*
import dev.kirill.kandinsky.chat.media.CaptureKind
import dev.kirill.kandinsky.chat.media.CaptureLease
import dev.kirill.kandinsky.chat.media.MediaAdmission
import dev.kirill.kandinsky.chat.media.MediaError
import dev.kirill.kandinsky.chat.media.MediaException
import dev.kirill.kandinsky.chat.media.MediaStore

class PhotoCaptureController(
    private val activity: Activity,
    private val media: MediaStore,
    private val admission: MediaAdmission,
    private val requestCameraPermission: ((Boolean) -> Unit) -> Unit,
    private val prepareStudioPhoto: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("Kandinsky-photo-camera").apply { start() }
    private val worker = Handler(thread.looper)
    private var lease: CaptureLease? = null
    private var request: Cancellation? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var cameraClosed = true
    private var sessionClosed = true
    private var opening = false
    private var creating = false
    @Volatile private var closing = false
    private var capturing = false
    private var result: Result<AttachmentDraft>? = null
    private var retiredTexture: SurfaceTexture? = null
    private var acknowledged = false
    private var shutter: Button? = null
    private var dialog: Dialog? = null
    private var texture: TextureView? = null
    private var callback: ((Result<AttachmentDraft>) -> Unit)? = null

    fun open(callback: (Result<AttachmentDraft>) -> Unit) {
        this.callback = callback
        requestCameraPermission { granted ->
            if (closing) return@requestCameraPermission
            if (!granted) {
                finish(Result.failure(MediaException(MediaError.PERMISSION_DENIED)))
                return@requestCameraPermission
            }
            request =
                admission.acquire(CaptureKind.PHOTO) { r ->
                    r.fold(
                        {
                            lease = it
                            show()
                        },
                        { finish(Result.failure(it)) },
                    )
                }
        }
    }

    private fun show() {
        val root = FrameLayout(activity)
        val preview = TextureView(activity)
        texture = preview
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val shutter =
            Button(activity).apply {
                isEnabled = false
                text = "●"
                textSize = 40f
                contentDescription = "Сделать снимок"
                setOnClickListener { capture() }
            }
        this.shutter = shutter
        root.addView(
            shutter,
            FrameLayout.LayoutParams(100, 100, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = 40
            },
        )
        val cancel =
            Button(activity).apply {
                text = "Отмена"
                contentDescription = "Отменить съёмку"
                setOnClickListener { cancel() }
            }
        root.addView(
            cancel,
            FrameLayout.LayoutParams(220, 90, Gravity.TOP or Gravity.START).apply { topMargin = 40 },
        )
        val d = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog = d
        d.setContentView(root)
        d.setCancelable(false)
        d.show()
        d.window?.setLayout(-1, -1)
        preview.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                    configure(t)
                }

                // Дополнительную перестройку камеры в этом обработчике не выполняет
                override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}

                // Камера ещё может писать в эту поверхность. Освобождаем её после подтверждения
                // закрытия.
                override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                    retiredTexture = t
                    cancel()
                    return false
                }

                // Обработчик не копирует кадр и не запускает отдельное обновление экрана
                override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
            }
    }

    // Полученное фото передаёт импорту, а поздний результат после закрытия удаляет как
    // неопубликованный.
    @Suppress("MissingPermission")
    private fun configure(texture: SurfaceTexture) {
        worker.post {
            try {
                if (closing) return@post
                val manager =
                    activity.getSystemService(android.content.Context.CAMERA_SERVICE)
                        as CameraManager
                val id =
                    manager.cameraIdList.firstOrNull {
                        manager
                            .getCameraCharacteristics(it)
                            .get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                    }
                        ?: manager.cameraIdList.firstOrNull()
                        ?: throw MediaException(MediaError.UNAVAILABLE)
                val sizes =
                    manager
                        .getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                        .getOutputSizes(ImageFormat.JPEG)
                val size =
                    sizes
                        .filter { it.width * it.height <= 2_100_000 }
                        .maxByOrNull { it.width * it.height }
                        ?: sizes.minBy { it.width * it.height }
                reader =
                    ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                        setOnImageAvailableListener(
                            { r ->
                                val image =
                                    r.acquireLatestImage() ?: return@setOnImageAvailableListener
                                val bytes =
                                    try {
                                        val b = image.planes[0].buffer
                                        ByteArray(b.remaining()).also { b.get(it) }
                                    } finally {
                                        image.close()
                                    }
                                if (!closing)
                                    main.post {
                                        if (closing) return@post
                                        val complete: (Result<AttachmentDraft>) -> Unit =
                                            { staged ->
                                                if (closing)
                                                    staged.getOrNull()?.let {
                                                        media.discardUnpublishedImport(it)
                                                    }
                                                else finish(staged)
                                            }
                                        if (prepareStudioPhoto)
                                            media.stageStudioPhoto(
                                                { bytes.inputStream() },
                                                complete,
                                            )
                                        else
                                            media.stage(
                                                { bytes.inputStream() },
                                                "image/jpeg",
                                                complete,
                                            )
                                    }
                            },
                            worker,
                        )
                    }
                texture.setDefaultBufferSize(640, 480)
                surface = Surface(texture)
                opening = true
                manager.openCamera(
                    id,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            opening = false
                            camera = device
                            cameraClosed = false
                            if (closing) {
                                device.close()
                                return
                            }
                            createSession(device)
                        }

                        override fun onDisconnected(device: CameraDevice) {
                            cameraFailed(device, MediaError.INTERRUPTED)
                        }

                        override fun onError(device: CameraDevice, error: Int) {
                            cameraFailed(device, MediaError.UNAVAILABLE)
                        }

                        override fun onClosed(device: CameraDevice) {
                            android.util.Log.d("KandinskyCamera", "device onClosed")
                            cameraClosed = true
                            camera = null
                            checkClosed()
                        }
                    },
                    worker,
                )
            } catch (error: Throwable) {
                opening = false
                main.post { finish(Result.failure(error)) }
            }
        }
    }

    private fun cameraFailed(device: CameraDevice, reason: MediaError) {
        opening = false
        camera = device
        cameraClosed = false
        if (closing) device.close()
        else main.post { finish(Result.failure(MediaException(reason))) }
    }

    // Сохраняет ожидание настройки, чтобы ресурсы не освободились раньше системного ответа.
    @Suppress("DEPRECATION")
    private fun createSession(device: CameraDevice) {
        creating = true
        sessionClosed = false
        try {
            device.createCaptureSession(
                listOf(surface!!, reader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        creating = false
                        session = s
                        if (closing) {
                            s.close()
                            return
                        }
                        try {
                            val request =
                                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surface!!)
                                    set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                    )
                                }
                            s.setRepeatingRequest(request.build(), null, worker)
                            main.post { shutter?.isEnabled = true }
                        } catch (t: Throwable) {
                            main.post { finish(Result.failure(t)) }
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        creating = false
                        session = s
                        if (closing) s.close()
                        else
                            main.post {
                                finish(Result.failure(MediaException(MediaError.UNAVAILABLE)))
                            }
                    }

                    override fun onClosed(s: CameraCaptureSession) {
                        android.util.Log.d("KandinskyCamera", "session onClosed")
                        sessionClosed = true
                        session = null
                        if (closing) camera?.close()
                        checkClosed()
                    }
                },
                worker,
            )
        } catch (error: Throwable) {
            creating = false
            sessionClosed = true
            main.post { finish(Result.failure(error)) }
        }
    }

    private fun capture() {
        worker.post {
            if (capturing || closing) return@post
            val device = camera ?: return@post
            val s = session ?: return@post
            capturing = true
            try {
                val request =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader!!.surface)
                        set(CaptureRequest.JPEG_ORIENTATION, 90)
                    }
                s.capture(
                    request.build(),
                    object : CameraCaptureSession.CaptureCallback() {
                        // Передаёт ошибку ввода и вывода в поток экрана для закрытия съёмки
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure,
                        ) {
                            main.post { finish(Result.failure(MediaException(MediaError.IO))) }
                        }
                    },
                    worker,
                )
            } catch (t: Throwable) {
                main.post { finish(Result.failure(t)) }
            }
        }
    }

    fun cancel() {
        finish(Result.failure(MediaException(MediaError.CANCELLED)))
    }

    private fun finish(value: Result<AttachmentDraft>) {
        if (closing) return
        android.util.Log.d("KandinskyCamera", "finish requested")
        closing = true
        result = value
        request?.cancel()
        worker.post {
            runCatching { session?.stopRepeating() }
            runCatching { session?.abortCaptures() }
            if (session != null) session?.close() else if (!creating) camera?.close()
            checkClosed()
        }
    }

    private fun checkClosed() {
        android.util.Log.d(
            "KandinskyCamera",
            "close gate opening=" +
                opening +
                " creating=" +
                creating +
                " sessionClosed=" +
                sessionClosed +
                " deviceClosed=" +
                cameraClosed,
        )
        if (acknowledged || !closing || opening || creating || !sessionClosed || !cameraClosed)
            return
        acknowledged = true
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        surface?.release()
        surface = null
        main.post {
            retiredTexture?.release()
            retiredTexture = null
            texture?.surfaceTextureListener = null
            dialog?.dismiss()
            dialog = null
            lease?.release()
            lease = null
            val done = callback
            callback = null
            done?.invoke(result ?: Result.failure(MediaException(MediaError.CANCELLED)))
            thread.quitSafely()
        }
    }
}
