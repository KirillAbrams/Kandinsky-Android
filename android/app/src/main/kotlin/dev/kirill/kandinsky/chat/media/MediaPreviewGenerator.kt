package dev.kirill.kandinsky.chat.media
import android.graphics.*
import android.media.ThumbnailUtils
import android.util.Base64
import android.util.Size
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object MediaPreviewGenerator {
 /**
  * Получает постоянное имя локальной уменьшенной копии из относительного пути исходника
  * Использует криптографический отпечаток пути, чтобы не переносить его знаки в имя файла
  * Один и тот же путь даёт одинаковое имя для повторного использования подготовленной копии
  */
 fun derivedName(relative:String)="local-"+MessageDigest.getInstance("SHA-256").digest(relative.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}+".jpg"
 /**
  * Возвращает уже сохранённую уменьшенную копию или создаёт её из фотографии либо кадра видео
  * Сохраняет ориентацию и ограничивает длинную сторону тремястами двадцатью пикселями
  * Публикует готовый файл отдельно от исходника и сокращает число таких производных копий до установленного предела
  */
 fun normalFile(root:File,relative:String,file:File,mime:String):File {
  val directory=File(root.canonicalFile,"media/previews");require(directory.canonicalFile==directory.absoluteFile);check(directory.isDirectory || directory.mkdirs())
  val target=File(directory,derivedName(relative));if(target.isFile)return target
  val bitmap=if(mime.startsWith("video/"))ThumbnailUtils.createVideoThumbnail(file,Size(320,320),null) else ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)){decoder,info,_->
   val scale=minOf(1.0,320.0/maxOf(info.size.width,info.size.height));decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
  }
  val scale=minOf(1.0,320.0/maxOf(bitmap.width,bitmap.height));val image=if(scale<1)Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*scale).toInt()),maxOf(1,(bitmap.height*scale).toInt()),true) else bitmap
  val temp=File(directory,"encode-${UUID.randomUUID()}.part")
  try{temp.outputStream().use{check(image.compress(Bitmap.CompressFormat.JPEG,78,it));it.fd.sync()};require(temp.length()<=256*1024);check(temp.renameTo(target))}
  finally{temp.delete();if(image!==bitmap)image.recycle();bitmap.recycle()}
  val files=directory.listFiles()?.filter{it.name.startsWith("local-")} ?: emptyList();files.filter{it!=target}.sortedBy{it.lastModified()}.take(maxOf(0,files.size-128)).forEach{it.delete()}
  return target
 }
 /**
  * Готовит очень маленькое размытое изображение для немедленного показа до полной фотографии
  * Ограничивает сторону сорока восемью пикселями и кодирует результат с небольшим качеством
  * Возвращает строковое представление байтов только при размере не больше трёх килобайт
  */
 fun tiny(normal:File):String {
  val source=BitmapFactory.decodeFile(normal.path) ?: throw MediaException(MediaError.CORRUPT)
  val ratio=minOf(1.0,48.0/maxOf(source.width,source.height));val image=Bitmap.createScaledBitmap(source,maxOf(1,(source.width*ratio).toInt()),maxOf(1,(source.height*ratio).toInt()),true)
  try{
   val w=image.width;val h=image.height;val pixels=IntArray(w*h);image.getPixels(pixels,0,w,0,0,w,h);val blurred=IntArray(pixels.size)
   for(y in 0 until h)for(x in 0 until w){var r=0;var g=0;var b=0;var n=0;for(dy in -2..2)for(dx in -2..2){val c=pixels[(y+dy).coerceIn(0,h-1)*w+(x+dx).coerceIn(0,w-1)];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);n++};blurred[y*w+x]=Color.rgb(r/n,g/n,b/n)}
   val output=Bitmap.createBitmap(blurred,w,h,Bitmap.Config.ARGB_8888)
   try{val bytes=java.io.ByteArrayOutputStream();check(output.compress(Bitmap.CompressFormat.JPEG,40,bytes));require(bytes.size()<=3072);return Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)}finally{output.recycle()}
  }finally{if(image!==source)image.recycle();source.recycle()}
 }
}
