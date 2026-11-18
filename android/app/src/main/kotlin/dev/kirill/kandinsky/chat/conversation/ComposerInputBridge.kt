package dev.kirill.kandinsky.chat.conversation

import android.view.inputmethod.BaseInputConnection
import android.widget.EditText

object ComposerInputBridge {
    fun synchronize(input: EditText, value: String) {
        if (
            BaseInputConnection.getComposingSpanStart(input.text) >= 0 ||
                input.text.toString() == value
        )
            return
        input.setText(value)
        input.setSelection(input.length())
    }

    // Используется перед отправкой, чтобы поле больше не держало незавершённый участок ввода.
    fun finish(input: EditText) {
        input.clearComposingText()
    }
}
