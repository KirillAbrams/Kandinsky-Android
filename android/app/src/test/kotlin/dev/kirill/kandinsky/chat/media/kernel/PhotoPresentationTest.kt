package dev.kirill.kandinsky.chat.media.kernel
import org.junit.Test
import org.junit.Assert.*
internal class PhotoPresentationTest{
 private val expected=arrayOf(arrayOf(doubleArrayOf(16.0,8.0),doubleArrayOf(48.0,8.0),doubleArrayOf(16.0,24.0),doubleArrayOf(48.0,24.0)),arrayOf(doubleArrayOf(48.0,8.0),doubleArrayOf(16.0,8.0),doubleArrayOf(48.0,24.0),doubleArrayOf(16.0,24.0)),arrayOf(doubleArrayOf(48.0,24.0),doubleArrayOf(16.0,24.0),doubleArrayOf(48.0,8.0),doubleArrayOf(16.0,8.0)),arrayOf(doubleArrayOf(16.0,24.0),doubleArrayOf(48.0,24.0),doubleArrayOf(16.0,8.0),doubleArrayOf(48.0,8.0)),arrayOf(doubleArrayOf(8.0,16.0),doubleArrayOf(8.0,48.0),doubleArrayOf(24.0,16.0),doubleArrayOf(24.0,48.0)),arrayOf(doubleArrayOf(24.0,16.0),doubleArrayOf(24.0,48.0),doubleArrayOf(8.0,16.0),doubleArrayOf(8.0,48.0)),arrayOf(doubleArrayOf(24.0,48.0),doubleArrayOf(24.0,16.0),doubleArrayOf(8.0,48.0),doubleArrayOf(8.0,16.0)),arrayOf(doubleArrayOf(8.0,48.0),doubleArrayOf(8.0,16.0),doubleArrayOf(24.0,48.0),doubleArrayOf(24.0,16.0)))
 /**
  * Сопоставляет преобразование четырёх контрольных точек с независимой таблицей ожиданий
  * Проверяет заданную ориентацию и подходящие ей размеры области с малой числовой погрешностью
  */
 private fun orientation(o:Int){
  val p=PhotoPresentation.make(8,4,o,if(o>=5)32.0 else 64.0,if(o>=5)64.0 else 32.0,PhotoSurfaceStyle.FILL)
  assertNotNull("PHOTO_PRESENTATION_VALID_"+o,p)
  val points=arrayOf(2.0 to 1.0,6.0 to 1.0,2.0 to 3.0,6.0 to 3.0)
  points.forEachIndexed{i,q->val v=p!!.point(q.first,q.second);assertEquals(expected[o-1][i][0],v.first,1e-8);assertEquals(expected[o-1][i][1],v.second,1e-8)}
 }
 /**
  * Запускает проверку контрольных точек для ориентации 1
  * Ожидаемые координаты берутся из общей независимой таблицы, а не из проверяемого преобразования
  */
 @Test fun orientation1(){orientation(1)}
 /**
  * Запускает проверку контрольных точек для ориентации 2
  * Ожидаемые координаты берутся из общей независимой таблицы, а не из проверяемого преобразования
  */
 @Test fun orientation2(){orientation(2)}
 /**
  * Запускает проверку контрольных точек для ориентации 3
  * Ожидаемые координаты берутся из общей независимой таблицы, а не из проверяемого преобразования
  */
 @Test fun orientation3(){orientation(3)}
 /**
  * Запускает проверку контрольных точек для ориентации 4
  * Ожидаемые координаты берутся из общей независимой таблицы, а не из проверяемого преобразования
  */
 @Test fun orientation4(){orientation(4)}
 /**
  * Запускает проверку контрольных точек для ориентации 5
  * Ожидаемые координаты берутся из общей независимой таблицы, а не из проверяемого преобразования
  */
 @Test fun orientation5(){orientation(5)}
 /**
  * Запускает проверку контрольных точек для ориентации 6
  * Ожидаемые координаты берутся из общей независимой таблицы, а не из проверяемого преобразования
  */
 @Test fun orientation6(){orientation(6)}
 /**
  * Запускает проверку контрольных точек для ориентации 7
  * Ожидаемые координаты берутся из общей независимой таблицы, а не из проверяемого преобразования
  */
 @Test fun orientation7(){orientation(7)}
 /**
  * Запускает проверку контрольных точек для ориентации 8
  * Ожидаемые координаты берутся из общей независимой таблицы, а не из проверяемого преобразования
  */
 @Test fun orientation8(){orientation(8)}
 /**
  * Проверяет вписывание прямоугольного изображения целиком в квадратную область
  * Ожидает единый масштаб по обеим осям и вертикальное центрирование без горизонтального сдвига
  */
 @Test fun fit(){val p=PhotoPresentation.make(8,4,1,80.0,80.0,PhotoSurfaceStyle.FIT)!!;assertEquals(10.0,p.a,0.0);assertEquals(10.0,p.d,0.0);assertEquals(0.0,p.tx,0.0);assertEquals(20.0,p.ty,0.0)}
 /**
  * Проверяет заполнение квадратной области прямоугольным изображением
  * Ожидает увеличение с горизонтальной обрезкой и сохранением пропорций
  */
 @Test fun fill(){val p=PhotoPresentation.make(8,4,1,80.0,80.0,PhotoSurfaceStyle.FILL)!!;assertEquals(20.0,p.a,0.0);assertEquals(20.0,p.d,0.0);assertEquals(-40.0,p.tx,0.0);assertEquals(0.0,p.ty,0.0)}
 /**
  * Проверяет преобразование исходника с длинной стороной две тысячи сорок восемь пикселей
  * Сравнивает границы вписанного изображения на вертикальном экране в обычной и повёрнутой ориентации
  */
 @Test fun original2048KeepsOrientedGeometry(){
  val p=PhotoPresentation.make(2048,1536,1,390.0,844.0,PhotoSurfaceStyle.FIT)
  assertNotNull("An original decode must reach native presentation",p)
  assertEquals(0.0,p!!.point(0.0,0.0).first,0.001);assertEquals(275.75,p.point(0.0,0.0).second,0.001)
  assertEquals(390.0,p.point(2048.0,1536.0).first,0.001);assertEquals(568.25,p.point(2048.0,1536.0).second,0.001)
  val rotated=PhotoPresentation.make(2048,1536,6,390.0,844.0,PhotoSurfaceStyle.FIT)!!
  assertEquals(390.0,rotated.point(0.0,0.0).first,0.001);assertEquals(162.0,rotated.point(0.0,0.0).second,0.001)
  assertEquals(0.0,rotated.point(2048.0,1536.0).first,0.001);assertEquals(682.0,rotated.point(2048.0,1536.0).second,0.001)
 }
 /**
  * Передаёт нулевые, превышающие предел размеры и неподдерживаемую ориентацию
  * Для каждого набора ожидает отказ создания преобразования
  */
 @Test fun invalidSource(){for(q in arrayOf(Triple(0,4,1),Triple(8,0,1),Triple(2049,4,1),Triple(8,4,0),Triple(8,4,9)))assertNull(PhotoPresentation.make(q.first,q.second,q.third,80.0,80.0,PhotoSurfaceStyle.FILL))}
 /**
  * Передаёт нулевую, отрицательную, бесконечную и превышающую предел область показа
  * Проверяет отказ вместо создания недопустимой геометрии
  */
 @Test fun invalidViewport(){for(q in arrayOf(0.0 to 80.0,80.0 to -1.0,Double.NaN to 80.0,80.0 to Double.POSITIVE_INFINITY,8193.0 to 80.0))assertNull(PhotoPresentation.make(8,4,1,q.first,q.second,PhotoSurfaceStyle.FILL))}
 /**
  * Проверяет требование квадратной области для режима аватара
  * Прямоугольная область должна отклоняться, а допустимый повёрнутый источник в квадрате приниматься
  */
 @Test fun avatar(){assertNull(PhotoPresentation.make(8,4,1,80.0,40.0,PhotoSurfaceStyle.AVATAR));assertNotNull(PhotoPresentation.make(8,4,8,44.0,44.0,PhotoSurfaceStyle.AVATAR))}
 /**
  * Тысячу раз повторяет расчёт одних и тех же параметров изображения
  * Каждый результат должен совпадать с первым допустимым преобразованием
  */
 @Test fun stable(){val p=PhotoPresentation.make(8,4,1,80.0,80.0,PhotoSurfaceStyle.FIT);assertNotNull(p);repeat(1000){assertEquals(p,PhotoPresentation.make(8,4,1,80.0,80.0,PhotoSurfaceStyle.FIT))}}

 /**
  * Проверяет шестую ориентацию прямоугольного изображения при вписывании в квадрат
  * Сравнивает положение исходного угла с заранее заданными координатами
  */
 @Test fun rotationSixFitsSquare(){
  val p=PhotoPresentation.make(100,50,6,200.0,200.0,PhotoSurfaceStyle.FIT)!!
  assertEquals(150.0,p.point(0.0,0.0).first,0.0)
  assertEquals(0.0,p.point(0.0,0.0).second,0.0)
 }
}
