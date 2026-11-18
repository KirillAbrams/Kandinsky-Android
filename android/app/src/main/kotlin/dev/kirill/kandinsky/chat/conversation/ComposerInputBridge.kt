// Согласует текст черновика с полем Андроида. Обновление не сбрасывает слово, которое клавиатура
// ещё готовит.

package dev.kirill.kandinsky.chat.conversation

import android.view.inputmethod.BaseInputConnection
import android.widget.EditText

object ComposerInputBridge {
    // Сравнивает текст и пропускает замену во время составления слова. При настоящей замене
    // ставит курсор в конец восстановленного текста.
    fun synchronize(input: EditText, value: String) {
        if (
            BaseInputConnection.getComposingSpanStart(input.text) >= 0 ||
                input.text.toString() == value
        )
            return
        input.setText(value)
        input.setSelection(input.length())
    }

    // Снимает отметку незаконченного ввода перед отправкой. Клавиатура больше не считает часть
    // отправляемого текста временной.
    fun finish(input: EditText) {
        input.clearComposingText()
    }
}
