package dev.kirill.kandinsky.chat.network

import android.os.Handler
import android.os.Looper
import dev.kirill.kandinsky.chat.domain.*

/**
 * Объединяет близкие уведомления для экрана после сохранения отдельных сетевых событий
 * Сделки базы остаются отдельными, меняется только частота передачи подготовленных изменений представлению
 */
class NetworkUIBatch(private val deliver:(List<MessageDelta>)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val pending=linkedMapOf<Long,MessageDelta>()
    private var scheduled=false
    private val flush=Runnable{scheduled=false;val deltas=pending.values.toList();pending.clear();if(deltas.isNotEmpty())deliver(deltas)}
    /**
     * Объединяет близкие изменения отдельно для каждого чата по постоянным номерам сообщений
     * Новые значения заменяют прежние, повторное появление снимает удаление, а последнее удаление убирает строку из обновлений
     * Назначает одну передачу накопленного экрану через шестнадцать миллисекунд вместо уведомления на каждое сетевое изменение
     */
    fun offer(deltas:List<MessageDelta>){
        check(Looper.myLooper()==Looper.getMainLooper())
        for(delta in deltas){
            val prior=pending[delta.chat.id]
            if(prior==null){pending[delta.chat.id]=delta;continue}
            val rows=linkedMapOf<Long,Message>();for(row in prior.inserted+prior.updated)rows[row.id]=row
            val removed=(prior.removedIDs+delta.removedIDs).toMutableSet()
            for(row in delta.inserted+delta.updated){removed.remove(row.id);rows[row.id]=row}
            for(id in delta.removedIDs)rows.remove(id)
            pending[delta.chat.id]=MessageDelta(updated=rows.values.toList(),removedIDs=removed.toList(),chat=delta.chat,reason=MessageChangeReason.SYNC)
        }
        if(!scheduled){scheduled=true;main.postDelayed(flush,16)}
    }
    /**
     * Снимает ожидаемую передачу изменений экрану и очищает накопленные данные чатов
     * После закрытия прежняя отложенная порция больше не должна обновлять представление
     */
    fun close(){main.removeCallbacks(flush);pending.clear();scheduled=false}
}
