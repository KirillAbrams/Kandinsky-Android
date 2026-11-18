package dev.kirill.kandinsky.chat.support
import androidx.test.uiautomator.Configurator
import org.junit.*
import org.junit.Assert.*
import org.junit.runners.MethodSorters
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TestIsolationContractTest{
 /**
  * В первой упорядоченной проверке отключает ожидание покоя дерева элементов
  * Сверяет, что временная настройка действительно применена именно в этом вызове
  */
 @Test fun aChangesOnlyThisTestIdlePolicy(){Configurator.getInstance().waitForIdleTimeout=0;assertEquals(0L,Configurator.getInstance().waitForIdleTimeout)}
 /**
  * В следующей упорядоченной проверке читает исходное ожидание покоя
  * Предыдущий сценарий не должен передать ей своё временное отключение ожидания
  */
 @Test fun bNextTestReceivesOriginalNativeDefault(){assertEquals("A preceding scenario must not leak its zero idle timeout",10000L,Configurator.getInstance().waitForIdleTimeout)}
}
