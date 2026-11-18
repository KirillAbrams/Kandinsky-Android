package dev.kirill.kandinsky.chat.attachments

import dev.kirill.kandinsky.chat.media.MediaError
import dev.kirill.kandinsky.chat.media.MediaException

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

class StudioPhotoEncoder {
 /**
  * Проверяет отмену перед записью и после системного кодирования
  * При незавершённой записи удаляет выходной файл, сохраняя исходную фотографию
  */
 fun jpeg(image:Bitmap,file:File,cancel:()->Boolean={false}){
  if(cancel())throw MediaException(MediaError.CANCELLED)
  var complete=false
  try{
   FileOutputStream(file).use{output->if(!image.compress(Bitmap.CompressFormat.JPEG,90,output))throw MediaException(MediaError.IO);output.fd.sync()}
   if(cancel())throw MediaException(MediaError.CANCELLED);complete=true
  }finally{if(!complete)file.delete()}
 }
}
