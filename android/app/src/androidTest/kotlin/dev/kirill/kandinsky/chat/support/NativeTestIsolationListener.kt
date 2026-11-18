package dev.kirill.kandinsky.chat.support
import androidx.test.uiautomator.Configurator
import org.junit.runner.Description
import org.junit.runner.notification.RunListener
/** Возвращает настройки управления устройством после отдельного проверочного сценария */
class NativeTestIsolationListener:RunListener(){
 private var previous=10000L
 private var autoRotate=false
 private var userRotation=0
 /**
  * Запоминает прежнюю настройку ожидания покоя и включает исходное ожидание для новой проверки
  * Сохраняет текущие пользовательский и автоматический режимы поворота
  * Закрепляет портретное положение на время отдельного сценария
  */
 override fun testStarted(description:Description){
  previous=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=10000
  val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();val resolver=i.targetContext.contentResolver
  autoRotate=android.provider.Settings.System.getInt(resolver,android.provider.Settings.System.ACCELEROMETER_ROTATION,0)==1
  userRotation=android.provider.Settings.System.getInt(resolver,android.provider.Settings.System.USER_ROTATION,0)
  check(i.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)){"Native test portrait control"}
 }
 /**
  * Возвращает сохранённый пользовательский поворот и при необходимости автоматическую ориентацию
  * Независимо от результата восстановления поворота возвращает прежнее ожидание покоя
  * Так следующий сценарий не наследует временные настройки предыдущего
  */
 override fun testFinished(description:Description){
  try{
   val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
   check(automation.setRotation(userRotation)){"Restore prior user rotation"}
   if(autoRotate)check(automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)){"Restore automatic rotation"}
  }finally{Configurator.getInstance().waitForIdleTimeout=previous}
 }
}
