package dev.kirill.kandinsky.chat.attachments

import android.content.Intent

interface AttachmentPicker {
    fun requestCameraPermission(callback: (Boolean) -> Unit)

    fun launchDocumentPicker(request: Intent, callback: (Intent?) -> Unit)

    fun clearDocumentResult()
}
