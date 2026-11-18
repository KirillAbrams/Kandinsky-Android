// Общий признак видимости окна для ленты. Лента сверяется с владельцем экрана, прежде чем
// готовить или показывать движущиеся вложения. За закрытым окном им не нужно работать.

package dev.kirill.kandinsky.chat.rendering

interface ForegroundHost {
    val isForeground: Boolean
}
