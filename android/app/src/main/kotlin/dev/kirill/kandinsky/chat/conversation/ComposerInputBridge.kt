package dev.kirill.kandinsky.chat.conversation
import android.widget.EditText
import android.view.inputmethod.BaseInputConnection
object ComposerInputBridge {
 /**
  * Согласует содержимое поля ввода с сохранённым текстом только при фактическом отличии
  * Не вмешивается, пока клавиатура ещё составляет часть слова или знака
  * Это защищает незавершённый системный ввод от замены внешним обновлением черновика
  */
 fun synchronize(input:EditText,value:String){
  if(BaseInputConnection.getComposingSpanStart(input.text)>=0 || input.text.toString()==value)return
  input.setText(value);input.setSelection(input.length())
 }
 /**
  * Завершает составление текущего текста средствами системной клавиатуры
  * Используется перед отправкой, чтобы поле больше не держало незавершённый участок ввода
  */
 fun finish(input:EditText){input.clearComposingText()}
}
