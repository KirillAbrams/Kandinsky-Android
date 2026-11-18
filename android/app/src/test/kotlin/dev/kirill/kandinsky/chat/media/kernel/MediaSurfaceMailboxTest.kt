package dev.kirill.kandinsky.chat.media.kernel

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class MediaSurfaceMailboxTest {
    private class Frame(val value: Int) : AutoCloseable {
        var closes = 0
        /**
         * Учитывает освобождение проверочного кадра и запрещает повторный вызов
         * По счётчику проверки обнаруживают двойное закрытие одного результата
         */
        override fun close() { closes++; check(closes == 1) }
    }
    /**
     * Передаёт тысячу кадров до обработки экрана и ожидает одно уведомление с последним кадром
     * Проверяет освобождение заменённых результатов и отклонение дубля или прежнего подключения
     * После закрытия ожидает снятый экран и запрет нового подключения
     */
    @Test fun coalescesFramesAndRejectsOldBindingAndSequence() {
        val tasks = ArrayDeque<() -> Unit>(); var shown = -1
        val box = MediaSurfaceMailbox<Frame>({ tasks.add(it) }) { shown = it?.value ?: -1 }
        val token = box.begin()!!; val frames = (1..1000).map { Frame(it) }
        frames.forEach { assertTrue(box.offer(token, it.value.toLong(), it)) }
        assertEquals(1, tasks.size); assertEquals(999, frames.count { it.closes == 1 })
        tasks.removeFirst()(); assertEquals(1000, shown)
        val duplicate = Frame(0); assertFalse(box.offer(token, 1000, duplicate)); assertEquals(1, duplicate.closes)
        val next = box.begin()!!; val late = Frame(7)
        assertFalse(box.offer(token, 1001, late)); assertEquals(1, late.closes)
        assertTrue(box.offer(next, 1, Frame(8))); tasks.removeFirst()()
        assertFalse(box.clear(token)); box.flush(); assertEquals(8, shown)
        assertEquals(8, shown); assertEquals(1, frames.last().closes)
        box.close(); box.flush(); assertEquals(-1, shown); assertNull(box.begin())
        while (tasks.isNotEmpty()) tasks.removeFirst()()
    }
    /**
     * Закрывает обмен после приёма нового кадра, но до его показа
     * Проверяет немедленное освобождение ожидающего и освобождение показанного после уведомления экрана
     * Поздний кадр не должен восстановить закрытое содержимое или вызвать двойное освобождение
     */
    @Test fun revokeBetweenOfferAndPublicationClearsWithoutResurrection() {
        val tasks = ArrayDeque<() -> Unit>(); var shown = -1
        val box = MediaSurfaceMailbox<Frame>({ tasks.add(it) }) { shown = it?.value ?: -1 }
        val token = box.begin()!!; val first = Frame(1)
        box.offer(token, 1, first); tasks.removeFirst()(); assertEquals(1, shown)
        val pending = Frame(2); box.offer(token, 2, pending)
        box.close(); assertEquals(1, pending.closes); assertEquals(0, first.closes)
        tasks.removeFirst()(); assertEquals(-1, shown); assertEquals(1, first.closes)
        val late = Frame(3); assertFalse(box.offer(token, 3, late)); assertEquals(1, late.closes)
        box.close(); box.flush(); assertEquals(1, first.closes)
    }
    /**
     * Тысячу раз открывает и очищает подключение с немедленным применением в потоке экрана
     * Проверяет, что при этом остаётся только одно ранее назначенное уведомление
     * После закрытия обрабатывает остаток уведомлений без новой работы
     */
    @Test fun immediateUiClearsDoNotAccumulateAlreadyQueuedNotices() {
        val tasks = ArrayDeque<() -> Unit>()
        val box = MediaSurfaceMailbox<Frame>({ tasks.add(it) }) {}
        repeat(1000) { box.begin(); box.flush(); box.clear(); box.flush() }
        assertEquals(1, tasks.size)
        box.close(); while (tasks.isNotEmpty()) tasks.removeFirst()()
    }
    /**
     * Запрещает показ кадра нового подключения до отдельного сигнала готовности
     * Проверяет снятие прежнего кадра и отсутствие новых уведомлений при повторной проверке ожидания
     * После разрешения ожидает нужный кадр и ровно одно его освобождение при закрытии
     */
    @Test fun futureFrameWaitsWithoutRetainingOldBindingOrQueuingTicks() {
        val tasks = ArrayDeque<() -> Unit>(); var due = true; var shown = -1
        val box = MediaSurfaceMailbox<Frame>({ tasks.add(it) }, { due }) { shown = it?.value ?: -1 }
        val first = box.begin()!!; box.offer(first, 1, Frame(1)); tasks.removeFirst()(); assertEquals(1, shown)
        due = false; val next = box.begin()!!; val pending = Frame(2)
        box.offer(next, 1, pending); tasks.removeFirst()(); assertEquals(-1, shown)
        repeat(1000) { box.flush() }; assertEquals(-1, shown); assertTrue(tasks.isEmpty()); assertEquals(0, pending.closes)
        due = true; box.flush(); assertEquals(2, shown)
        box.close(); box.flush(); while (tasks.isNotEmpty()) tasks.removeFirst()()
        assertEquals(1, pending.closes)
    }


 /**
  * Сто раз одновременно передаёт кадр из рабочего потока и закрывает обмен
  * Дожидается завершения потока и обработки оставшихся уведомлений
  * Проверяет, что единственный переданный кадр освобождён ровно один раз
  */
 @Test fun concurrentOfferAndCloseReleaseExactlyOnce(){
  repeat(100){
   val tasks=ArrayDeque<()->Unit>()
   val box=MediaSurfaceMailbox<dev.kirill.kandinsky.chat.support.FrameProbe>({tasks.add(it)}){}
   val token=box.begin()!!
   val frame=dev.kirill.kandinsky.chat.support.FrameProbe()
   val start=java.util.concurrent.CountDownLatch(1)
   val worker=Thread{start.await();box.offer(token,1,frame)}
   worker.start();start.countDown();box.close();box.flush()
   worker.join(2000);assertFalse(worker.isAlive)
   while(tasks.isNotEmpty())tasks.removeFirst()()
   assertEquals(1,frame.closes)
  }
 }
}
