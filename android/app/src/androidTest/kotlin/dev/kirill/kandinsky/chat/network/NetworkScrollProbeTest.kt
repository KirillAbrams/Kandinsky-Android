package dev.kirill.kandinsky.chat.network

import android.content.Intent
import android.os.*
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.kirill.kandinsky.chat.app.MainActivity
import dev.kirill.kandinsky.chat.rendering.ChatScrollHost
import org.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Измеряет рабочую сборку системными жестами, отчётами кадров и ограниченными наблюдениями ресурсов */
class NetworkScrollProbeTest {
    /**
     * Открывает заданный настоящий серверный чат и устанавливает отдельные наблюдения системных кадров и касаний
     * По параметрам готовит начальную позицию, вид жеста и ограниченный контроль размытия
     * Во время прокрутки сохраняет события, показатели процесса и видимые позиции, затем измеряет покой и обработку нехватки памяти
     * В завершении снимает только свои наблюдения, возвращает эффекты и настройки и записывает общий отчёт
     */
    @Test fun pageThroughRealServerHistory(){
        val i=InstrumentationRegistry.getInstrumentation();val chatID=(InstrumentationRegistry.getArguments().getString("chat-id") ?: "4").toLong();val device=UiDevice.getInstance(i)
        val previousIdle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
        device.wakeUp()
        val activity=i.startActivitySync(checkNotNull(i.targetContext.packageManager.getLaunchIntentForPackage(i.targetContext.packageName)).apply{
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }) as MainActivity
        val output=File(i.targetContext.getExternalFilesDir(null),"kandinsky-network-probe").apply{mkdirs()}
        val phase=AtomicReference("setup");val lock=Any();val frames=ArrayList<JSONArray>();val samples=JSONArray();val phases=JSONArray();val lost=AtomicLong()
        val observer=HandlerThread("KandinskyNetworkFrameObserver").apply{start()}
        var host:ChatScrollHost?=null
        val restoredEffects=mutableListOf<Pair<android.graphics.RenderNode,Float>>()
        val gestureMode=InstrumentationRegistry.getArguments().getString("gestures") ?: "UiAutomator.swipe"
        val inputLedger=java.util.concurrent.ConcurrentLinkedQueue<JSONArray>()
        val ownDown=AtomicLong(-1);val receivedUp=AtomicLong(-1);val foreignTouches=AtomicLong()
        lateinit var preceding:Window.Callback
        lateinit var owned:Window.Callback
        i.runOnMainSync{
            preceding=checkNotNull(activity.window.callback)
            owned=object:Window.Callback by preceding{
                /**
                 * Во время участка прокрутки добавляет ограниченную запись действительного события касания
                 * Для управляемой системной цепочки считает посторонние касания и отмечает получение ожидаемого отпускания
                 * Затем передаёт событие прежнему обработчику окна без подмены его результата
                 */
                override fun dispatchTouchEvent(event:MotionEvent):Boolean{
                    if(phase.get()=="scroll"){
                        if(inputLedger.size<20000)inputLedger.add(JSONArray().put(event.actionMasked).put(event.downTime).put(event.eventTime).put(System.nanoTime()))
                        if(gestureMode=="os_fling" && event.downTime!=ownDown.get())foreignTouches.incrementAndGet()
                        if(event.actionMasked==MotionEvent.ACTION_UP && event.downTime==ownDown.get())receivedUp.set(event.downTime)
                    };return preceding.dispatchTouchEvent(event)
                }
            };activity.window.callback=owned
        }
        val listener=Window.OnFrameMetricsAvailableListener{_,metrics,dropped->
            lost.addAndGet(dropped.toLong())
            val row=JSONArray().put(phase.get()).put(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
                .put(metrics.getMetric(FrameMetrics.TOTAL_DURATION)).put(metrics.getMetric(FrameMetrics.DEADLINE))
                .put(metrics.getMetric(FrameMetrics.GPU_DURATION)).put(metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME))
                .put(if(Build.VERSION.SDK_INT>=36)metrics.getMetric(FrameMetrics.FRAME_TIMELINE_VSYNC_ID)else -1L)
            synchronized(lock){if(frames.size<50000)frames.add(row)}
        }
        i.runOnMainSync{activity.window.addOnFrameMetricsAvailableListener(listener,Handler(observer.looper))}
        /**
         * Рекурсивно ищет область прокручиваемой истории в дереве представлений
         * Возвращает найденный объект для чтения положения и ограниченных внутренних показателей
         */
        fun locate(view:View):ChatScrollHost?{
            if(view is ChatScrollHost)return view
            if(view is ViewGroup)for(n in 0 until view.childCount)locate(view.getChildAt(n))?.let{return it}
            return null
        }
        /**
         * Читает время процесса, память и тепловое состояние, затем на основном потоке получает положение и владельцев истории
         * Добавляет наблюдение к общей серии и сохраняет последнее состояние отдельным файлом
         * Запрошенная частота экрана здесь является отдельным полем и сама не означает частоту показанных кадров
         */
        fun sample(){
            val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
            val value=JSONObject().put("phase",phase.get()).put("ns",System.nanoTime()).put("cpuMs",android.os.Process.getElapsedCpuTime())
                .put("pssBytes",memory.totalPss*1024L).put("javaUsed",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                .put("nativeAllocated",Debug.getNativeHeapAllocatedSize()).put("memoryStats",JSONObject(memory.memoryStats))
                .put("thermalStatus",(activity.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).currentThermalStatus)
            i.runOnMainSync{
                value.put("anchor",host?.readingAnchor()?.messageID)
                host?.let{h->val index=ChatScrollHost::class.java.getDeclaredField("index").apply{isAccessible=true}.get(h) as dev.kirill.kandinsky.chat.rendering.HistoryIndex;value.put("anchorOrdinal",h.readingAnchor().messageID?.let{index.position(it)})}
                value.put("activeGifClocks",host?.metrics?.activeFrameClocks ?: 0).put("liveFragments",host?.metrics?.liveFragments ?: 0).put("displayHz",activity.display?.refreshRate).put("rows",host?.messageCount ?: 0).put("loadedRows",host?.loadedCount ?: 0)
                val field=MainActivity::class.java.getDeclaredField("runtime").apply{isAccessible=true}
                val runtime=field.get(activity) as? dev.kirill.kandinsky.chat.media.LocalRuntime
                value.put("ownedImageBytes",runtime?.media?.bytesUsed ?: 0).put("activePlayers",runtime?.playback?.activePlayerCount ?: 0)
            }
            samples.put(value);File(output,"progress.json").writeText(value.toString())
        }
        /**
         * Переключает имя текущего участка и каждую секунду сохраняет одно наблюдение ресурсов
         * После указанного срока записывает действительные временные границы участка покоя
         */
        fun idle(name:String,seconds:Int){phase.set(name);val start=System.nanoTime();repeat(seconds){sample();SystemClock.sleep(1000)};phases.put(JSONObject().put("name",name).put("startNs",start).put("endNs",System.nanoTime()))}
        try{
            val row=device.wait(Until.findObject(By.res("chat.row.$chatID")),20000) ?: error("Missing server heavy chat")
            idle("idle_before",3);row.click()
            assertNotNull(device.wait(Until.findObject(By.res("composer.input").enabled(true)),15000))
            i.runOnMainSync{host=checkNotNull(locate(activity.window.decorView))}
            val startSeq=InstrumentationRegistry.getArguments().getString("start-seq")?.toLong()
            if(startSeq!=null){
                require(startSeq>0)
                lateinit var runtime:dev.kirill.kandinsky.chat.media.LocalRuntime
                i.runOnMainSync{
                    runtime=MainActivity::class.java.getDeclaredField("runtime").apply{isAccessible=true}.get(activity) as dev.kirill.kandinsky.chat.media.LocalRuntime
                }
                val expectedUser=checkNotNull(InstrumentationRegistry.getArguments().getString("expected-user-id")).toLong()
                assertEquals("Do not measure in an unexpected saved profile",expectedUser,checkNotNull(runtime.network).profile.userID)
                val localID=android.database.sqlite.SQLiteDatabase.openDatabase(File(runtime.store.root,"chat.sqlite3").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use{db->
                    db.rawQuery("SELECT n.localID FROM net_messages n JOIN messages m ON m.id=n.localID WHERE m.chatId=? AND n.serverSeq=?",arrayOf(chatID.toString(),startSeq.toString())).use{c->assertTrue("The controlled server sequence must already be cached",c.moveToFirst());c.getLong(0)}
                }
                i.runOnMainSync{checkNotNull(host).goTo(localID)}
            }else if(InstrumentationRegistry.getArguments().getString("start")=="middle"){
                val ready=SystemClock.uptimeMillis()+10000;var installed=false
                while(!installed && SystemClock.uptimeMillis()<ready){i.runOnMainSync{installed=checkNotNull(host).messageCount>=10000};if(!installed)SystemClock.sleep(20)}
                assertTrue("Full heavy-history index before positioning",installed)
                i.runOnMainSync{val h=checkNotNull(host);val index=ChatScrollHost::class.java.getDeclaredField("index").apply{isAccessible=true}.get(h) as dev.kirill.kandinsky.chat.rendering.HistoryIndex;h.goTo(index.rows[index.rows.size/2].messageID)}
            }
            phase.set("warmup");SystemClock.sleep(3000)
            if(InstrumentationRegistry.getArguments().getString("blur")=="off")i.runOnMainSync{
                val chrome=checkNotNull(host).parent
                for(name in listOf("headerGlass","composerGlass")){
                    val material=chrome.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(chrome)
                    val gpu=material.javaClass.getDeclaredField("gpu").apply{isAccessible=true}.get(material) ?: error("No live GPU glass")
                    val node=gpu.javaClass.getDeclaredField("node").apply{isAccessible=true}.get(gpu) as android.graphics.RenderNode
                    val radius=gpu.javaClass.getDeclaredField("radius").apply{isAccessible=true}.getFloat(gpu)
                    node.setRenderEffect(null);restoredEffects.add(node to radius)
                };(chrome as View).invalidate()
            }
            val seconds=(InstrumentationRegistry.getArguments().getString("seconds") ?: "90").toInt();require(seconds in 10..180)
            phase.set("scroll");val start=System.nanoTime();var nextSample=start;var gestures=0
            Trace.beginAsyncSection("Kandinsky.NetworkScroll",101)
            try{
                while(System.nanoTime()-start<seconds*1_000_000_000L){
                    var bounds=android.graphics.RectF();val location=IntArray(2)
                    i.runOnMainSync{val h=checkNotNull(host);assertTrue(h.hasWindowFocus() && activity.isForeground);bounds=h.gestureViewport;h.getLocationOnScreen(location)}
                    val reverse=gestures%24 in 20..23
                    val from=location[1]+bounds.top+bounds.height()*(if(reverse).8f else .2f)
                    val to=location[1]+bounds.top+bounds.height()*(if(reverse).2f else .8f)
                    val x=location[0]+bounds.centerX()
                    if(gestureMode=="os_fling"){
                        var down=0L
                        for(n in 0..12){
                            if(n>0){val wait=down+n*8-SystemClock.uptimeMillis();if(wait>0)SystemClock.sleep(wait)}
                            val now=SystemClock.uptimeMillis();if(n==0){down=now;ownDown.set(down)}
                            val action=if(n==0)MotionEvent.ACTION_DOWN else if(n==12)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                            val event=MotionEvent.obtain(down,now,action,x,from+(to-from)*n/12f,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
                            try{assertTrue(i.uiAutomation.injectInputEvent(event,n==0 || n==12))}finally{event.recycle()}
                        }
                        val receiptDeadline=SystemClock.uptimeMillis()+2000
                        while(receivedUp.get()!=down && SystemClock.uptimeMillis()<receiptDeadline)SystemClock.sleep(2)
                        assertEquals("Own OS UP must reach the native window",down,receivedUp.get())
                        assertEquals("No operator input during the measured phase",0L,foreignTouches.get())
                        SystemClock.sleep(800)
                    }else{
                        assertTrue(device.swipe(x.toInt(),from.toInt(),x.toInt(),to.toInt(),14));SystemClock.sleep(280)
                    }
                    gestures++
                    if(System.nanoTime()>=nextSample){sample();nextSample=System.nanoTime()+1_000_000_000L}
                }
            }finally{Trace.endAsyncSection("Kandinsky.NetworkScroll",101)}
            val end=System.nanoTime();phase.set("post_scroll")
            phases.put(JSONObject().put("name","scroll").put("startNs",start).put("endNs",end).put("gestures",gestures))
            device.takeScreenshot(File(output,"heavy-scroll.png"))
            device.wait(Until.findObject(By.res("chat.back")),10000)!!.click();host=null
            assertTrue(device.wait(Until.hasObject(By.res("chat.row.$chatID")),10000));idle("idle_after",(InstrumentationRegistry.getArguments().getString("close-idle") ?: "3").toInt().coerceIn(3,45))
            val osTrim=InstrumentationRegistry.getArguments().getString("trim-mode")=="os"
            if(osTrim){
                // Системная команда доставляет сигнал процессу и его отрисовке
                // Одного вызова обработчика экрана для этого отдельного режима недостаточно
                android.os.ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am send-trim-memory "+i.targetContext.packageName+" RUNNING_LOW")).use{stream->
                    val response=stream.bufferedReader().readText();check(response.isBlank()){response}
                }
            }else i.runOnMainSync{activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)}
            idle(if(osTrim)"after_os_trim" else "after_simulated_trim",(InstrumentationRegistry.getArguments().getString("trim-idle") ?: "2").toInt().coerceIn(2,45))
        }finally{
            phase.set("teardown");i.runOnMainSync{activity.window.removeOnFrameMetricsAvailableListener(listener)
                if(activity.window.callback===owned)activity.window.callback=preceding
                for((node,radius) in restoredEffects)node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius*.25f,radius*.25f,android.graphics.Shader.TileMode.CLAMP))
            }
            observer.quitSafely();observer.join(5000)
            val result=JSONObject().put("pid",android.os.Process.myPid()).put("package",i.targetContext.packageName)
                .put("columns",JSONArray(listOf("phase","intendedVsyncNs","totalNs","deadlineNs","gpuNs","firstDraw","vsyncID")))
                .put("frames",synchronized(lock){JSONArray(frames)}).put("lostReports",lost.get()).put("phases",phases).put("samples",samples)
                .put("gestures",gestureMode).put("inputEvents",JSONArray(inputLedger.toList())).put("foreignTouches",foreignTouches.get()).put("viewRateIntervention",false).put("blurControl",InstrumentationRegistry.getArguments().getString("blur") ?: "normal")
                .put("startServerSeq",InstrumentationRegistry.getArguments().getString("start-seq"))
            File(output,"network-scroll.json").writeText(result.toString())
            Configurator.getInstance().waitForIdleTimeout=previousIdle
        }
    }
}
