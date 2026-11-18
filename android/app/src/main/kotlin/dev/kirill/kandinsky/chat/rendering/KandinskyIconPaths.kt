package dev.kirill.kandinsky.chat.rendering
import android.graphics.*
enum class IconID { CHECK, PLUS, MICROPHONE, ROUND, GIF, SEND, STOP, BACK, SEARCH, EDIT, BOOKMARK, PIN, TASKS, SETTINGS, MORE, CHATS, MENU, COMPOSE, TUNE, CHEVRON_DOWN }
object KandinskyIconPaths {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    private val paths=IconID.entries.associateWith { icon ->
        Path().apply {
            /**
             * Добавляет к подготавливаемому значку отдельную ломаную из заранее заданных пар координат
             * Первая пара задаёт начало, остальные последовательно продолжают контур без его автоматического замыкания
             */
            fun line(vararg v:Float) { moveTo(v[0],v[1]);for(i in 2 until v.size step 2) lineTo(v[i],v[i+1]) }
            when(icon) {
                IconID.MENU->{for(y in listOf(6f,11f,16f))addRect(3f,y,21f,y+2f,Path.Direction.CW)}
                IconID.COMPOSE->{
                    line(14f,3f,5f,3f);quadTo(3f,3f,3f,5f);lineTo(3f,21f)
                    quadTo(3f,23f,5f,23f);lineTo(21f,23f);quadTo(23f,23f,23f,21f);lineTo(23f,13f)
                    line(11f,13f,20f,4f);quadTo(21f,3f,22f,4f);lineTo(24f,6f)
                    quadTo(25f,7f,24f,8f);lineTo(15f,17f);lineTo(10f,18f);close()
                }
                IconID.CHEVRON_DOWN->line(6f,9f,12f,15f,18f,9f)
                IconID.TUNE->{
                    line(3f,6f,8f,6f);line(12f,6f,21f,6f);line(8f,4f,8f,8f)
                    line(3f,12f,15f,12f);line(19f,12f,21f,12f);line(15f,10f,15f,14f)
                    line(3f,18f,8f,18f);line(12f,18f,21f,18f);line(8f,16f,8f,20f)
                }
                IconID.PLUS->{line(19f,13f,13f,13f,13f,19f,11f,19f,11f,13f,5f,13f,5f,11f,11f,11f,11f,5f,13f,5f,13f,11f,19f,11f);close()}
                IconID.MICROPHONE->{addRoundRect(8f,2f,16f,15f,4f,4f,Path.Direction.CW);moveTo(5f,10f);cubicTo(5f,22f,19f,22f,19f,10f);line(12f,19f,12f,22f);line(8f,22f,16f,22f)}
                IconID.ROUND->{addCircle(12f,12f,10f,Path.Direction.CW);addRoundRect(6f,8f,14f,16f,2f,2f,Path.Direction.CW);line(14f,10f,18f,8f,18f,16f,14f,14f)}
                IconID.GIF->{addRoundRect(2f,3f,22f,21f,5f,5f,Path.Direction.CW)}
                IconID.SEND->{line(4f,12f,5.41f,13.41f,11f,7.83f,11f,20f,13f,20f,13f,7.83f,18.59f,13.41f,20f,12f,12f,4f);close()}
                IconID.STOP->{addRoundRect(5f,5f,19f,19f,2f,2f,Path.Direction.CW)}
                IconID.BACK->{line(14f,3f,6f,12f,14f,21f);line(6f,12f,22f,12f)}
                IconID.SEARCH->{addCircle(10f,9f,7f,Path.Direction.CW);line(15f,15f,22f,22f)}
                IconID.CHECK->line(4f,12f,9f,17f,20f,6f)
                IconID.EDIT->{line(4f,16f,15f,5f,19f,9f,8f,20f,3f,21f,4f,16f);line(14f,6f,17f,3f,21f,7f,18f,10f)}
                IconID.BOOKMARK->line(5f,3f,19f,3f,19f,22f,12f,17f,5f,22f,5f,3f)
                IconID.PIN->{
                    moveTo(14.5f,1f)
                    cubicTo(13.5f,0f,12f,2f,12.5f,3f)
                    lineTo(14.5f,5f);lineTo(10.2f,9f)
                    cubicTo(7f,7f,4f,8f,1f,11f)
                    lineTo(7.8f,17.3f);lineTo(.9f,22.8f);lineTo(1.6f,23.4f)
                    lineTo(8.5f,18f);lineTo(13.2f,23.2f)
                    cubicTo(16.4f,20.8f,15.5f,17f,14.7f,14.5f)
                    lineTo(19f,10.1f);lineTo(20.8f,12f)
                    cubicTo(21.8f,12.8f,24.5f,10f,23f,9f)
                    close()
                }
                IconID.TASKS->{line(3f,6f,5f,8f,8f,3f);line(11f,6f,22f,6f);line(3f,14f,5f,16f,8f,11f);line(11f,14f,22f,14f);line(3f,22f,5f,23f,8f,19f);line(11f,22f,22f,22f)}
                IconID.SETTINGS->{addCircle(12f,12f,9f,Path.Direction.CW);addCircle(12f,12f,4f,Path.Direction.CW);repeat(8){val a=it*Math.PI/4;line((12+kotlin.math.cos(a)*9).toFloat(),(12+kotlin.math.sin(a)*9).toFloat(),(12+kotlin.math.cos(a)*12).toFloat(),(12+kotlin.math.sin(a)*12).toFloat())}}
                IconID.CHATS->{addRoundRect(1f,2f,18f,18f,8f,8f,Path.Direction.CW);line(4f,15f,3f,22f,10f,18f);addRoundRect(9f,10f,24f,23f,7f,7f,Path.Direction.CW);line(18f,20f,23f,24f,22f,18f)}
                IconID.MORE->{for(x in listOf(4f,12f,20f)) addCircle(x,12f,1f,Path.Direction.CW)}
            }
        }
    }
    /**
     * Масштабирует подготовленный контур выбранного значка к переданной области и задаёт цвет и толщину
     * Для заполненных значков выбирает заливку, для остальных обводку и после вывода возвращает прежние координаты холста
     */
    fun draw(c:Canvas,icon:IconID,rect:RectF,color:Int,width:Float=1.8f) {
        val side=if(icon==IconID.COMPOSE)26f else 24f
        c.save();c.translate(rect.left,rect.top);c.scale(rect.width()/side,rect.height()/side)
        paint.color=color;paint.strokeWidth=width
        paint.style=when(icon){IconID.CHATS,IconID.PIN,IconID.MENU,IconID.PLUS,IconID.SEND,IconID.STOP->Paint.Style.FILL;else->Paint.Style.STROKE}
        c.drawPath(paths.getValue(icon),paint);c.restore()
    }
}
