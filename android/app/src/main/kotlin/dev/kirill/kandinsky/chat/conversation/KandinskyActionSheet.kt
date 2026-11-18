package dev.kirill.kandinsky.chat.conversation

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.*
import android.widget.*
import dev.kirill.kandinsky.chat.rendering.NativeGlassSurface

data class KandinskySheetAction(val title: String, val symbol: String, val destructive: Boolean = false, val action: () -> Unit)

/** Системные элементы действий размещены над размытым участком текущего содержимого окна */
class KandinskyActionSheet(activity: Activity, title: String?, items: List<KandinskySheetAction>): Dialog(activity) {
    private val d = activity.resources.displayMetrics.density
    private val glass = NativeGlassSurface(activity)
    private val panel = object: LinearLayout(activity) {
        private val position = IntArray(2)
        private val origin = IntArray(2)
        /**
         * Сопоставляет положения окна и панели на экране, чтобы за размытием оставался нужный участок
         * Ограничивает обработку областью панели и её скруглением
         */
        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(position); activity.window.decorView.getLocationOnScreen(origin)
            glass.draw(canvas,RectF(0f,0f,width.toFloat(),height.toFloat()),24*d,d) { source ->
                source.save();source.translate((origin[0]-position[0]).toFloat(),(origin[1]-position[1]).toFloat())
                activity.window.decorView.draw(source);source.restore()
            }
        }
    }
    init {
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        panel.orientation = LinearLayout.VERTICAL;panel.setWillNotDraw(false)
        panel.setPadding((4*d).toInt(),(8*d).toInt(),(4*d).toInt(),(8*d).toInt())
        panel.clipToOutline=true;panel.outlineProvider=object:ViewOutlineProvider(){
         /**
          * Радиус учитывает плотность экрана
          * Система использует контур для обрезки содержимого по углам
          */
         override fun getOutline(view:View,outline:Outline){outline.setRoundRect(0,0,view.width,view.height,24*d)}}
        if(title!=null)panel.addView(TextView(activity).apply {
            text=title;setTextColor(Color.BLACK);textSize=18f;gravity=Gravity.CENTER;setTypeface(typeface,Typeface.BOLD)
            setPadding((14*d).toInt(),(14*d).toInt(),(14*d).toInt(),(14*d).toInt())
        })
        for((index,item) in (items+KandinskySheetAction("Отмена","cancel"){}).withIndex()) {
            val color=if(item.destructive)Color.rgb(235,56,57)else Color.rgb(22,25,27)
            panel.addView(TextView(activity).apply {
                text=item.title;textSize=17f;setTextColor(color);gravity=Gravity.CENTER_VERTICAL
                minHeight=(48*d).toInt();setPadding((16*d).toInt(),(12*d).toInt(),(16*d).toInt(),(12*d).toInt())
                compoundDrawablePadding=(14*d).toInt();setCompoundDrawables(ActionGlyph(item.symbol,color,d),null,null,null)
                isFocusable=true;isClickable=true;setOnClickListener{dismiss();item.action()}
                accessibilityDelegate=object:View.AccessibilityDelegate(){
                 /**
                  * Дополняет системное описание пункта панели ролью кнопки и постоянным обозначением
                  * Сохраняет стандартные сведения о текстовом элементе
                  * Это позволяет находить конкретный пункт по его месту в списке действий
                  */
                 override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(host,info);info.className="android.widget.Button";info.viewIdResourceName="actions.$index"}}
            },LinearLayout.LayoutParams(-1,-2))
        }
        val scroll=ScrollView(activity).apply {isFillViewport=false;addView(panel)}
        setContentView(scroll)
        setOnDismissListener{glass.close()}
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.18f)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes=attributes.apply{width=minOf((360*d).toInt(),activity.resources.displayMetrics.widthPixels-(24*d).toInt());height=WindowManager.LayoutParams.WRAP_CONTENT;y=(16*d).toInt()}
        }
    }
    /**
     * После открытия подгоняет окно панели под доступные размеры экрана
     * Ограничивает ширину общим пределом и высоту большей частью экрана
     * Если содержимое ещё не измерено, использует оценку по числу пунктов
     */
    override fun onStart(){super.onStart();window?.setLayout(minOf((360*d).toInt(),context.resources.displayMetrics.widthPixels-(24*d).toInt()),minOf(panel.measuredHeight.takeIf{it>0} ?: ((panel.childCount*52+16)*d).toInt(),(context.resources.displayMetrics.heightPixels*.82f).toInt()))}
}

private class ActionGlyph(private val symbol:String,private val color:Int,d:Float):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    init{setBounds(0,0,(23*d).toInt(),(23*d).toInt())}
    /**
     * Подбирает контур ответа, пересылки, удаления и других предусмотренных действий
     * Масштабирует рисунок под фактическую область значка и после вывода восстанавливает координаты
     */
    override fun draw(c:Canvas){
        val save=c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f);paint.color=color
        /**
         * Рисует ломаную линию по переданным парам координат
         * Первую точку использует как начало, остальные последовательно добавляет в контур
         * Применяет общую краску значка, чтобы толщина и цвет всех его частей совпадали
         */
        fun line(vararg p:Float){val path=Path();path.moveTo(p[0],p[1]);for(i in 2 until p.size step 2)path.lineTo(p[i],p[i+1]);c.drawPath(path,paint)}
        when(symbol){
            "reply"->line(10f,5f,3f,11f,10f,17f,10f,13f,15f,13f,21f,19f,20f,11f,16f,8f,10f,8f,10f,5f)
            "forward"->line(14f,5f,21f,11f,14f,17f,14f,13f,9f,13f,3f,19f,4f,11f,8f,8f,14f,8f,14f,5f)
            "copy"->{c.drawRoundRect(3f,3f,16f,17f,3f,3f,paint);c.drawRoundRect(8f,8f,21f,22f,3f,3f,paint)}
            "edit"->line(4f,16f,16f,4f,20f,8f,8f,20f,3f,21f,4f,16f)
            "save"->line(6f,3f,18f,3f,18f,21f,12f,16f,6f,21f,6f,3f)
            "pin"->line(9f,3f,19f,13f,16f,13f,12f,17f,7f,12f,11f,8f,9f,3f,7f,5f,17f,15f,19f,13f)
            "report"->{line(5f,21f,5f,4f,11f,4f,14f,7f,20f,7f,20f,15f,14f,15f,11f,12f,5f,12f)}
            "trash"->{c.drawRoundRect(6f,6f,18f,22f,2f,2f,paint);line(3f,6f,21f,6f);line(9f,3f,15f,3f);line(10f,10f,10f,18f);line(14f,10f,14f,18f)}
            "search"->{c.drawCircle(10f,10f,7f,paint);line(15f,15f,22f,22f)}
            "cancel"->{line(5f,5f,19f,19f);line(19f,5f,5f,19f)}
            "archive"->{c.drawRoundRect(4f,6f,20f,21f,2f,2f,paint);line(3f,3f,21f,3f,21f,7f,3f,7f,3f,3f);line(9f,12f,15f,12f)}
            else->{c.drawCircle(12f,12f,9f,paint);line(7f,12f,11f,16f,17f,8f)}
        };c.restoreToCount(save)
    }
    /**
     * Следующий вывод использует обновлённое значение
     * Сам контур и размеры рисунка при этом не меняются
     */
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    /**
     * Назначает переданный цветовой фильтр краске значка
     * Значение без фильтра возвращает обычный способ вывода цвета
     * Геометрия рисунка остаётся прежней
     */
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    /**
     * Значок занимает только часть выделенной области
     * Поэтому его нельзя считать сплошным непрозрачным прямоугольником
     */
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
