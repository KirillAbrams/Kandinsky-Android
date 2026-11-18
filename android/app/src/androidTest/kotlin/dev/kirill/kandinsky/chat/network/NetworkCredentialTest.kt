package dev.kirill.kandinsky.chat.network
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import org.json.JSONArray
import org.json.JSONObject
class NetworkCredentialTest {
 /**
  * Создаёт прежние записи независимым системным шифрованием и проверяет перенос без выхода из аккаунта
  * Архив начинается только с резервного файла, чтобы повторить незавершённую замену записи
  * Новые имена должны содержать те же зашифрованные байты и читаться повторным открытием
  */
 @Test fun previousEncryptedRecordsMoveWithoutChangingAccountOrKey(){
  val base=InstrumentationRegistry.getInstrumentation().targetContext
  val root=File(base.cacheDir,"credentials-upgrade-"+UUID.randomUUID()).apply{mkdirs()}
  val namespace=base.packageName+".qa."+UUID.randomUUID()
  val alias=namespace+".previous.session.v1"
  val context=object:ContextWrapper(base){
   /**
    * Отделяет файлы проверочного переноса от сохранённого входа приложения
    * Все записи этой проверки находятся в одной временной папке
    */
   override fun getNoBackupFilesDir()=root
   /**
    * Отделяет системный ключ этой проверки от ключей других запусков
    * Возвращаемое пространство существует только до завершения проверки
    */
   override fun getPackageName()=namespace
  }
  try{
   val key=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
    init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
     .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
   }.generateKey()
   val session=JSONObject().put("userId",42).put("deviceId","previous-device").put("name","Saved account")
    .put("token","d".repeat(64)).put("expiresAtMs",123456789L).put("username","saved")
   val second=JSONObject(session.toString()).put("userId",43).put("username","second")
   /**
    * Получает зашифрованную запись старого формата без вызова сохраняющей части приложения
    * Благодаря отдельному способу подготовки ошибка нового сохранения не меняет ожидаемые байты
    */
   fun encrypted(text:String):ByteArray{
    val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key)}
    return byteArrayOf(1)+cipher.iv+cipher.doFinal(text.toByteArray(Charsets.UTF_8))
   }
   val activeBytes=encrypted(session.toString())
   val accountBytes=encrypted(JSONArray().put(session).put(second).toString())
   File(root,"previous-session-v1").writeBytes(activeBytes)
   File(root,"previous-accounts-v1.bak").writeBytes(accountBytes)
   val upgraded=NetworkCredentials(context)
   assertEquals(42L,upgraded.load()!!.userID)
   assertEquals("saved",upgraded.load()!!.username)
   assertEquals(listOf(42L,43L),upgraded.profiles().map{it.userID})
   assertArrayEquals(activeBytes,File(root,"kandinsky-session-v1").readBytes())
   assertArrayEquals(accountBytes,File(root,"kandinsky-accounts-v1").readBytes())
   assertEquals(42L,NetworkCredentials(context).load()!!.userID)
   val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
   assertTrue(store.containsAlias(alias))
   assertFalse(store.containsAlias(namespace+".kandinsky.session.v1"))
   upgraded.removeActive()
   assertNull(NetworkCredentials(context).load())
   assertEquals(listOf(43L),NetworkCredentials(context).profiles().map{it.userID})
  }finally{
   root.deleteRecursively()
   KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
  }
 }
 /**
  * Создаёт отдельное пространство защищённых профилей и проверяет переключение старого и нового форматов
  * Число запомненных учётных записей должно быть ограничено, а сохранённые файлы не содержать открытый ключ доступа
  * Повреждение архива должно обнаруживаться без потери отдельного активного профиля
  * После восстановления байтов архив снова должен читаться, созданный системный ключ удаляется в завершении
  */
 @Test fun switchingRetainsOldSessionEncryptsAndBoundsAccounts(){
  val base=InstrumentationRegistry.getInstrumentation().targetContext
  val root=File(base.cacheDir,"credentials-"+UUID.randomUUID()).apply{mkdirs()};val namespace=base.packageName+".qa."+UUID.randomUUID()
  val context=object:ContextWrapper(base){
   /**
    * Возвращает отдельную папку этой проверки вместо обычного хранилища профиля
    * Так сохранённые файлы не пересекаются с данными приложения
    */
   override fun getNoBackupFilesDir()=root;
   /**
    * Возвращает отдельное случайное пространство имён текущей проверки
    * Оно отделяет создаваемый ключ шифрования от рабочего системного ключа приложения
    */
   override fun getPackageName()=namespace}
  try{
   val credentials=NetworkCredentials(context)
   val legacy=NetworkProfile(1,"legacy","Old invitation","a".repeat(64),123)
   credentials.save(legacy);credentials.save(NetworkProfile(1000,"new","Demo","b".repeat(64),456,"demo"))
   assertEquals(listOf(1000L,1L),credentials.profiles().map{it.userID});assertEquals(1000L,NetworkCredentials(context).load()!!.userID)
   credentials.save(legacy);assertNull(credentials.load()!!.username)
   for(id in 1001L..1010L)credentials.save(NetworkProfile(id,"qa","Demo","c".repeat(64),456))
   assertEquals(8,credentials.profiles().size)
   for(file in root.listFiles()!!)assertFalse(String(file.readBytes(),Charsets.ISO_8859_1).contains("c".repeat(64)))
   val archive=File(root,"kandinsky-accounts-v1");val valid=archive.readBytes();val changed=valid.copyOf();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte();archive.writeBytes(changed)
   assertTrue(runCatching{credentials.profiles()}.isFailure);assertEquals(1010L,credentials.load()!!.userID)
   archive.writeBytes(valid);assertEquals(8,credentials.profiles().size)
  }finally{root.deleteRecursively();KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(namespace+".kandinsky.session.v1")}}
 }
}
