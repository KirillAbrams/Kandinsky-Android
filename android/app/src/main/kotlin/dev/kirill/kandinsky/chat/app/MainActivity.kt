package dev.kirill.kandinsky.chat.app
import dev.kirill.kandinsky.chat.diagnostics.*
import dev.kirill.kandinsky.chat.BuildConfig
import android.app.Activity
import dev.kirill.kandinsky.chat.media.*
import dev.kirill.kandinsky.chat.network.*
import dev.kirill.kandinsky.chat.account.AccountEntryView
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView
import android.widget.FrameLayout
import android.view.Gravity
import dev.kirill.kandinsky.chat.chatlist.StudioHistoryPanel
import dev.kirill.kandinsky.chat.conversation.*
import dev.kirill.kandinsky.chat.rendering.KandinskyInsetHost
import dev.kirill.kandinsky.chat.storage.*
import dev.kirill.kandinsky.chat.domain.*
import java.io.File
import java.util.UUID
class MainActivity:Activity() {
 private lateinit var coordinator:AppCoordinator
 private var store:SQLiteStore?=null
 private var runtime:LocalRuntime?=null
 var documentResult:((android.content.Intent?)->Unit)?=null
 private var cameraPermission:((Boolean)->Unit)?=null
 private var repository:ChatRepository?=null
 private var state:ConversationState?=null
 private var chatView:ConversationChrome?=null
 private var rootName="chat-v1"
 private val appDataRoot by lazy { dev.kirill.kandinsky.chat.storage.AppDataRoot.resolve(filesDir) }
 private var pendingContainer:android.widget.FrameLayout?=null
 private var baseInsetTop=0;private var baseInsetBottom=0

 private var frameProbe:WindowFrameProbe?=null
 var isForeground=false;private set
 private var generation=0
 private var studioOptions=KandinskyOptions()
 private var entry:AccountEntryView?=null
 private var historyDrawer:FrameLayout?=null
 private var drawerClosing=false
 /**
  * Поднимает окружение приложения, восстанавливает вход и открывает начальный экран
  * Открывает личные данные только после восстановления сохранённого входа
  * При восстановленной учётной записи подготавливает местную схему, медиа и синхронизацию до открытия истории
  * При пересоздании окна принимает уже существующее окружение вместо повторного создания его владельцев
  */
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState);coordinator=AppCoordinator{show(it)}
  val loading=TextView(this).apply{text="Кандинский · открываем историю";gravity=android.view.Gravity.CENTER;setBackgroundColor(Color.WHITE)}
  setContentView(loading);configureWindow()
  val applicationRoot=try{appDataRoot}catch(error:Exception){loading.text="Не удалось открыть сохранённые данные приложения";return}
  val metrics=intent.getBooleanExtra("metrics",false)
  MetricsRuntime.enabled=metrics;MetricsRuntime.output=File(applicationRoot,"diagnostics/latest.json")
  if(metrics)frameProbe=WindowFrameProbe(window)
  val restoredID=savedInstanceState?.getLong("chatID",-1) ?: -1
  /**
   * Связывает восстановленное окружение аккаунта с текущим окном и изменениями истории
   * Сначала выбирает сохранённый доступный чат, затем недавний чат или новый запрос
   * Позднее сетевое обновление не заменяет новый запрос, который пользователь уже начал вводить
   */
  fun activate(local:LocalRuntime){
   runtime=local;store=local.store;repository=local.repository;rootName=local.store.root.name
   val sync=local.network ?: run{showAccountEntry();return}
   sync.onDeltas={deltas->
    for(delta in deltas){
     local.playback.apply(delta)
     if(coordinator.currentChatID==delta.chat.id){
      if(delta.chat.hidden)coordinator.showChats() else chatView?.apply(delta)
     }
    }
    if(coordinator.currentChatID==null)local.repository.chats(""){result->result.onSuccess{rows->coordinator.setAllowedChats(rows.map{it.id}.toSet())}}
   }
   sync.onStatus={text->chatView?.setNetworkStatus(if(text=="В сети")"подключено" else text)}
   sync.onAccountErased={eraseAccountLocally(sync)}
   sync.onSessionExpired={
    if(!isDestroyed && runtime?.network===sync){
     try {
      NetworkCredentials(this).forget(sync.profile.userID)
      switchAccount()
      android.widget.Toast.makeText(this,"Сеанс истёк. Войдите снова.",android.widget.Toast.LENGTH_LONG).show()
     } catch (_: Exception) {
      chatView?.setNetworkStatus("Не удалось закрыть истёкший сеанс.")
     }
    }
   }
   sync.foreground(isForeground)
   val ticket=generation
   local.repository.chats(""){result->
    if(isDestroyed || generation!=ticket)return@chats
    val rows=result.getOrDefault(emptyList())
    val lastChat = restoredID.takeIf { it > 0 } ?: NetworkCredentials(this).selectedChat(sync.profile.userID)
    val selected = rows.firstOrNull { it.id == lastChat && !it.archived } ?: rows.firstOrNull { !it.archived }
    if(selected!=null){coordinator.admitChat(selected.id);coordinator.openChat(selected.id)}
    else{
     coordinator.showChats()
     val draft=chatView ?: return@chats
     val draftTicket=generation
     sync.studioThreads("recent","",0,1){remote->
      val first=remote.getOrNull()?.firstOrNull() ?: return@studioThreads
      if(isDestroyed || generation!=draftTicket || draft.studioDraftTouched || historyDrawer!=null)return@studioThreads
      sync.admitStudioThread(first){saved->
       if(saved.isSuccess && !isDestroyed && generation==draftTicket && !draft.studioDraftTouched && historyDrawer==null){
        coordinator.admitChat(first.id);coordinator.openChat(first.id)
       }
      }
     }
    }
   }
  }
  val retained=lastNonConfigurationInstance as? LocalRuntime
  if(retained!=null)activate(retained)else{
   Thread({
    val session=runCatching{
     val credentials=NetworkCredentials(applicationContext)
     LocalAccountErasure.finish(applicationContext,credentials)
     credentials.load() ?: (intent.getStringExtra("network-invitation") ?: intent.data?.takeIf{it.scheme=="kandinsky-native" && it.host=="join"}?.getQueryParameter("invitation"))?.let{invitation->
      require(invitation.matches(Regex("[a-f0-9]{64}")))
      val api=NetworkAPI(null)
      try{api.initialize();NetworkProfile.decode(api.json("/v1/devices/enroll",org.json.JSONObject().put("invitation",invitation).put("deviceName",Build.MODEL))).also(credentials::save)}finally{api.close()}
     }
    }
    runOnUiThread{
     if(isDestroyed)return@runOnUiThread
     if(session.isFailure){loading.text="Не удалось открыть сохранённый вход. Попробуйте ещё раз.";return@runOnUiThread}
     val profile=session.getOrNull()
     if(profile==null){showAccountEntry();return@runOnUiThread}
     val name="net-v1-u"+profile.userID
     SQLiteStore.open(File(applicationRoot,name)){result->result.fold({opened->
      if(isDestroyed){opened.close{};return@fold};store=opened
      opened.submit({db->NetworkSchema.install(db)}){installed->installed.fold({
       val localRepo=SQLiteChatRepository(opened,NetworkJournal(profile.userID))
       val sync=NetworkSync(profile,opened,localRepo)
       val local=LocalRuntime(this,opened,sync)
       local.media.recover{recovered->if(isDestroyed)local.close()else recovered.fold({activate(local)},{loading.text="Не удалось восстановить медиа."})}
      },{loading.text="Не удалось открыть сетевую историю."})}
     },{loading.text="Не удалось открыть историю."})}
    }
   },"KandinskySession").start()
  }
  if(Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){leaveStudio()}
 }
 /**
  * Настраивает светлые системные панели и размещение содержимого под их областями
  * Выбирает подходящий способ управления краями для установленной версии системы
  * Просит систему уменьшать доступную область при появлении клавиатуры
  */
 @Suppress("DEPRECATION")
 private fun configureWindow(){
  window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.WHITE
  if(Build.VERSION.SDK_INT>=30){window.setDecorFitsSystemWindows(false);window.insetsController?.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)}
  else window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
  window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
 }
 /**
  * Передаёт выбранный чат или отсутствие выбора общей смене экрана
  * Не создаёт отдельное состояние перехода поверх основного механизма
  * Используется координатором как единая точка отображения выбора
  */
 private fun show(id:Long?)=showSurface(id)
 /**
  * Сохраняет параметры генерации и место чтения прежнего чата, затем создаёт нужное состояние и оформление
  * Для существующего чата ожидает историю, для нового открывает пустой черновик без преждевременного создания на сервере
  * По поколению перехода отбрасывает поздние ответы и передаёт новому экрану системные отступы
  */
 @Suppress("DEPRECATION")
 private fun showSurface(id:Long?){
  if (id != null) runtime?.network?.let { NetworkCredentials(this).selectChat(it.profile.userID, id) }
  if(historyDrawer!=null)closeStudioDrawer(animated=false)
  generation++;val ticket=generation
  if(runtime?.network!=null)chatView?.let{studioOptions=it.generationOptions}
  chatView?.prepareForNavigation();state?.close();state=null
  val root=findViewById<android.view.ViewGroup>(android.R.id.content)
  pendingContainer?.let{pending->runtime?.media?.withNavigationCache{root.removeView(pending)} ?: root.removeView(pending)}
  val container=android.widget.FrameLayout(this).apply{alpha=0f;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
  pendingContainer=container
  val inputBlocker=View(this).apply{isClickable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
  /**
   * Показывает подготовленный контейнер только для ещё действующего перехода
   * Убирает прежние представления с временным сохранением подходящих медиа в хранилище перехода
   * Снимает блокировку ввода и системного чтения, затем обновляет общий проигрыватель
   */
  fun reveal(){
   if(isDestroyed || generation!=ticket || pendingContainer!==container)return
   val retire={for(n in root.childCount-1 downTo 0){val child=root.getChildAt(n);if(child!==container)root.removeView(child)}}
   runtime?.media?.withNavigationCache(retire) ?: retire()
   container.removeView(inputBlocker);container.alpha=1f;container.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;pendingContainer=null
  }
  runtime?.network?.visibleChat(id)
  chatView=null
  val openToken=MetricsRuntime.recorder.begin();val repo=repository
  val surface:View
  if(id==null){
   val sync=runtime?.network
   if(sync!=null && repo!=null){
    val model=ConversationState(repo);state=model
    val chat=ConversationChrome(this,0L,model,runtime){openStudioDrawer()}
    val identity=UUID.randomUUID()
    chat.restoreGenerationOptions(studioOptions)
    chat.onNewChat={composeNetworkChat()}
    chat.onFirstSend={options->submitFirstPrompt(chat,model,sync,identity,options,ticket)}
    chat.openStudioDraft()
    chatView=chat;surface=chat
    container.post{reveal()}
   }else{
    showAccountEntry();return
   }
  }else{
   val model=repo?.let{ConversationState(it)};state=model
   val chat=ConversationChrome(this,id,model,runtime){openStudioDrawer()};surface=chat;chatView=chat
   runtime?.network?.let{
    chat.restoreGenerationOptions(studioOptions)
    chat.onNewChat={composeNetworkChat()}
   }
   if(repo!=null && model!=null)model.open(id){result->if(generation==ticket)result.fold({
    chat.openHistory(repo,model.anchor){opened->if(generation==ticket){if(MetricsRuntime.enabled)MetricsRuntime.recorder.end("chat_metadata_open_ms",openToken);opened.exceptionOrNull()?.let{chat.showError(it)};reveal()}}
    model.onDelta={delta->if(generation==ticket)chat.apply(delta)}
   },{chat.showError(it);reveal()})}else container.post{reveal()}
  }
  surface.setOnApplyWindowInsetsListener{_,insets->
   val top:Int;val bottom:Int
   if(Build.VERSION.SDK_INT>=30){val bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=maxOf(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom)}
   else{top=insets.systemWindowInsetTop;bottom=insets.systemWindowInsetBottom}
   baseInsetTop=top;baseInsetBottom=bottom;(surface as? KandinskyInsetHost)?.updateInsets(top,bottom);insets
  }
  container.addView(surface,android.widget.FrameLayout.LayoutParams(-1,-1));container.addView(inputBlocker,android.widget.FrameLayout.LayoutParams(-1,-1))
  root.addView(container,android.widget.FrameLayout.LayoutParams(-1,-1));surface.requestApplyInsets()
 }
 /**
  * Передаёт текущий чат для выделения в списке
  * Действия боковой панели выполняет после её закрытия, а удаление согласует с координатором
  */
 private fun makeStudioHistory(sync:NetworkSync,drawer:Boolean):StudioHistoryPanel{
  /**
   * Передаёт выбранное действие после закрытия боковой истории
   * Для отдельного представления без выдвижной панели выполняет его сразу
   * Это общее продолжение используется действиями созданной панели
   */
  fun leave(action:()->Unit){if(drawer)closeStudioDrawer(then=action)else action()}
  return StudioHistoryPanel(this,sync,coordinator.currentChatID,
   closeCurrent=if(drawer){{closeStudioDrawer()}}else null,
   open={id->leave{coordinator.admitChat(id);coordinator.openChat(id)}},
   compose={leave{composeNetworkChat()}},
   logout={leave{logoutStudio(sync)}},
   eraseAccount={leave{confirmAccountErasure(sync)}},
   deleted={id->leave{coordinator.forgetChat(id);if(coordinator.currentChatID==id)coordinator.showChats()}})
 }
 /**
  * Открывает боковую историю поверх текущего чата, если переход или создание чата не выполняются
  * Подготавливает ограниченную по ширине панель, затемнение и отступы системных областей
  * На время показа скрывает нижележащий чат от системного чтения и приостанавливает движение медиа
  * Проявляет панель и затемнение согласованным плавным переходом
  */
 private fun openStudioDrawer(){
  val sync=runtime?.network ?: return coordinator.showChats()
  val chat=chatView ?: return
  if(historyDrawer!=null || pendingContainer!=null || chat.isStartingThread)return
  val root=findViewById<android.view.ViewGroup>(android.R.id.content)
  val overlay=FrameLayout(this)
  val shade=View(this).apply{
   setBackgroundColor(Color.argb(138,0,0,0));alpha=0f
   contentDescription="Закрыть историю"
   setOnClickListener{closeStudioDrawer()}
  }
  val panel=makeStudioHistory(sync,drawer=true)
  panel.contentDescription="История запросов"
  val width=minOf(root.width,(304*resources.displayMetrics.density).toInt())
  if(width<=0)return
  val radius=16*resources.displayMetrics.density
  panel.background=android.graphics.drawable.GradientDrawable().apply{
   setColor(Color.WHITE);cornerRadii=floatArrayOf(0f,0f,radius,radius,radius,radius,0f,0f)
  }
  panel.clipToOutline=true
  panel.translationX=-width.toFloat()
  panel.updateInsets(baseInsetTop,baseInsetBottom)
  overlay.addView(shade,FrameLayout.LayoutParams(-1,-1))
  overlay.addView(panel,FrameLayout.LayoutParams(width,-1,Gravity.LEFT))
  overlay.setOnApplyWindowInsetsListener{_,insets->
   if(Build.VERSION.SDK_INT>=30){
    val bars=insets.getInsets(WindowInsets.Type.systemBars())
    panel.updateInsets(bars.top,maxOf(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom))
   }else @Suppress("DEPRECATION") panel.updateInsets(insets.systemWindowInsetTop,insets.systemWindowInsetBottom)
   insets
  }
  chat.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  chat.setHistoryDrawerVisible(true)
  historyDrawer=overlay;root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
  overlay.requestApplyInsets()
  panel.animate().translationX(0f).setDuration(246).start()
  shade.animate().alpha(1f).setDuration(246).start()
 }
 /**
  * Убирает клавиатуру панели и предотвращает повторный одновременный переход
  * После завершения возвращает активность чату и выполняет назначенное продолжение
  */
 private fun closeStudioDrawer(animated:Boolean=true,then:(()->Unit)?=null){
  val drawer=historyDrawer ?: return
  if(drawerClosing && animated)return
  drawerClosing=true
  val panel=drawer.getChildAt(1) as StudioHistoryPanel
  panel.hideKeyboard()
  /**
   * Завершает закрытие только той панели, для которой начат текущий переход
   * Убирает её из окна и возвращает чату системное чтение и движение медиа
   * Затем вызывает действие, которое ожидало скрытия панели
   */
  fun finish(){
   if(historyDrawer!==drawer)return
   historyDrawer=null;drawerClosing=false
   (drawer.parent as? android.view.ViewGroup)?.removeView(drawer)
   chatView?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
   chatView?.setHistoryDrawerVisible(false)
   then?.invoke()
  }
  panel.animate().cancel()
  if(!animated){finish();return}
  drawer.getChildAt(0).animate().alpha(0f).setDuration(220).start()
  panel.animate().translationX(-panel.width.toFloat()).setDuration(220).withEndAction{finish()}.start()
 }
 /**
  * Отдельно пытается завершить серверную сессию, закрывая временное соединение после запроса
  * Если местный выход не удался, сообщает об ошибке вместо подтверждения перехода
  */
 private fun logoutStudio(sync:NetworkSync){
  try{
   NetworkCredentials(this).removeActive()
   switchAccount()
   Thread({
    val api=NetworkAPI(sync.profile)
    try{api.json("/v1/kandinsky/logout",org.json.JSONObject())}catch(_:Exception){}finally{api.close()}
   },"KandinskyLogout").start()
  }catch(_:Exception){
   android.widget.Toast.makeText(this,"Не удалось выйти",android.widget.Toast.LENGTH_LONG).show()
  }
 }
 /**
  * Запрашивает пароль перед окончательным удалением текущей учётной записи
  * Проверяет длину ввода и на время запроса запрещает повторное подтверждение
  * При успехе начинает местную очистку, при отказе возвращает ввод и показывает подходящую ошибку
  * Ответ другой или уже закрытой сессии не применяет к текущему окну
  */
 private fun confirmAccountErasure(sync: NetworkSync) {
  if (runtime?.network !== sync || isDestroyed) return
  val password = android.widget.EditText(this).apply {
   hint = "Пароль"
   isSingleLine = true
   inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
   filters = arrayOf(android.text.InputFilter.LengthFilter(256))
   accessibilityDelegate = object : View.AccessibilityDelegate() {
    /**
     * Добавляет полю подтверждения удаления постоянное системное обозначение
     * Сохраняет стандартные сведения о защищённом вводе пароля
     * Позволяет однозначно находить это поле при системной навигации и проверке экрана
     */
    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
     super.onInitializeAccessibilityNodeInfo(host, info)
     info.viewIdResourceName = "account.erasure.password"
    }
   }
  }
  val padding = (resources.displayMetrics.density * 24).toInt()
  val feedback = TextView(this).apply {
   visibility = View.GONE
   setTextColor(Color.rgb(167, 31, 44))
   accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
  }
  val field = android.widget.LinearLayout(this).apply {
   orientation = android.widget.LinearLayout.VERTICAL
   setPadding(padding, 0, padding, 0)
   addView(password, android.widget.LinearLayout.LayoutParams(-1, -2))
   addView(feedback, android.widget.LinearLayout.LayoutParams(-1, -2))
  }
  val dialog = android.app.AlertDialog.Builder(this)
   .setTitle("Удалить аккаунт?")
   .setMessage("История, запросы и медиа будут удалены. Для подтверждения введите пароль.")
   .setView(field)
   .setNegativeButton("Отмена", null)
   .setPositiveButton("Удалить навсегда", null)
   .create()
  dialog.show()
  val confirm = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
  confirm.setOnClickListener {
   val value = password.text.toString()
   if (value.codePointCount(0, value.length) !in 12..128) {
    feedback.text = "Проверьте пароль."
    feedback.visibility = View.VISIBLE
    return@setOnClickListener
   }
   feedback.visibility = View.GONE
   confirm.isEnabled = false
   password.isEnabled = false
   sync.eraseStudioAccount(value) { result ->
    if (runtime?.network !== sync || isDestroyed) {
     dialog.dismiss()
     return@eraseStudioAccount
    }
    result.fold({
     dialog.dismiss()
     eraseAccountLocally(sync)
    }, { error ->
     confirm.isEnabled = true
     password.isEnabled = true
     feedback.text = if ((error as? NetworkFailure)?.status == 403) "Неверный пароль."
      else "Проверьте соединение и попробуйте снова."
     feedback.visibility = View.VISIBLE
    })
   }
  }
 }
 /**
  * Проверяет принадлежность подтверждённого удаления текущей сетевой сессии
  * Сохраняет намерение местной очистки и закрывает боковую историю
  * Передаёт смене учётной записи обозначение пользователя, чьи данные нужно убрать
  */
 private fun eraseAccountLocally(sync: NetworkSync) {
  if (runtime?.network !== sync || isDestroyed) return
  val id = sync.profile.userID
  runCatching { LocalAccountErasure.stage(applicationContext, id) }
  closeStudioDrawer(animated = false)
  switchAccount(erasing = id)
 }
 /**
  * В отдельном потоке завершает сохранённую очистку данных удалённой учётной записи
  * После успеха открывает вход, если окно ещё существует
  * При ошибке показывает обязательный повтор очистки вместо возврата к удалённым данным
  */
 private fun finishErasure(userID: Long) {
  val context = applicationContext
  Thread({
   val result = runCatching {
    LocalAccountErasure.stage(context, userID)
    LocalAccountErasure.finish(context, NetworkCredentials(context))
   }
   runOnUiThread {
    if (!isDestroyed) result.fold({ showAccountEntry() }, {
     android.app.AlertDialog.Builder(this)
      .setTitle("Не удалось очистить данные")
      .setMessage("Повторите очистку данных этого аккаунта на устройстве.")
      .setCancelable(false)
      .setPositiveButton("Повторить") { _, _ -> finishErasure(userID) }
      .show()
    })
   }
  }, "KandinskyAccountErasure").start()
 }
 /**
  * После успешного входа начинает обычный запуск приложения с очищенным стеком окон
  * Получение пользовательской истории выполняется уже в восстановленном окружении нового запуска
  */
 private fun showAccountEntry(){
  pendingContainer=null
  entry?.close();val screen=AccountEntryView(this, NetworkAPI(null), {entry?.close();entry=null
   startActivity(android.content.Intent(this,MainActivity::class.java).apply{flags=android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK});finish()
  },null);entry=screen
  screen.setOnApplyWindowInsetsListener{_,insets->if(Build.VERSION.SDK_INT>=30){val bars=insets.getInsets(WindowInsets.Type.systemBars());screen.updateInsets(bars.top,maxOf(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom))}else screen.updateInsets(insets.systemWindowInsetTop,insets.systemWindowInsetBottom);insets}
  setContentView(screen);screen.requestApplyInsets()
 }
 /**
  * Завершает состояние прежнего чата и отсоединяет его окружение от текущего окна
  * Снимает общий проигрыватель и ждёт подтверждения остановки прежде, чем закрыть прежних владельцев
  * Для обычного выхода открывает вход, а для удаления сначала завершает очистку местной базы
  */
 private fun switchAccount(erasing: Long? = null){
  generation++;pendingContainer=null;chatView?.captureAnchor();chatView=null;state?.close();state=null;studioOptions=KandinskyOptions()
  val old=runtime;runtime=null;repository=null;store=null
  coordinator.close()
  if (erasing != null) setContentView(FrameLayout(this))
  /**
   * Продолжает смену учётной записи после освобождения прежнего окружения
   * При удалении запускает завершение местной очистки пользователя
   * При обычном выходе показывает экран входа только для ещё существующего окна
   */
  fun finish() {
   if (erasing != null) finishErasure(erasing)
   else if (!isDestroyed) showAccountEntry()
  }
  if(old==null)finish()else{
   old.network?.close()
   old.playback.stop {
    old.close()
    if (erasing == null) finish() else old.store.close { finish() }
   }
  }
 }
 /**
  * Не выполняет переход во время уже начатого создания чата
  * Сам серверный чат здесь ещё не создаётся
  */
 private fun composeNetworkChat(){
  if(runtime?.network!=null && chatView?.isStartingThread!=true)coordinator.showChats()
 }
 /**
  * Связывает первый введённый запрос с созданием серверного чата
  * Повторно использует постоянное обозначение создания и уже назначенный номер чата, если он появился ранее
  * При успехе отправки допускает чат в координатор и открывает его историю
  * На каждом позднем шаге проверяет поколение экрана, а при ошибке сохраняет возможность повторить действие
  */
 private fun submitFirstPrompt(
  chat:ConversationChrome,model:ConversationState,sync:NetworkSync,identity:UUID,
  options:KandinskyOptions,ticket:Int
 ){
  if(isDestroyed || generation!=ticket || chat.isStartingThread)return
  chat.setStartingThread(true)
  /**
   * Привязывает текущий первый черновик к полученному номеру чата и отправляет его с выбранными параметрами
   * После ответа снимает признак создания только у того же действующего экрана
   * Успех переводит к истории этого чата, а ошибка остаётся доступной пользователю для повтора
   */
  fun send(id:Long){
   if(isDestroyed || generation!=ticket)return
   model.bindStudioDraft(id)
   model.send(options) sent@{result->
    if(isDestroyed || generation!=ticket)return@sent
    chat.setStartingThread(false)
    result.fold({
     coordinator.admitChat(id);coordinator.openChat(id)
    },{chat.showError(it)})
   }
  }
  val bound=model.chatID
  if(bound!=null){send(bound);return}
  sync.openDirect("",identity){result->
   if(isDestroyed || generation!=ticket)return@openDirect
   result.fold(::send,{
    chat.setStartingThread(false)
    android.app.AlertDialog.Builder(this).setTitle("Не удалось начать историю")
     .setMessage("Проверьте соединение.").setPositiveButton("Понятно",null).show()
   })
  }
 }
 /**
  * Обрабатывает общий возврат с учётом создания чата и открытой боковой истории
  * Сначала закрывает боковую панель, в местном режиме возвращает из разговора к списку
  * В остальных случаях завершает окно обычным системным путём
  */
 private fun leaveStudio(){
  if(chatView?.isStartingThread==true)return
  if(historyDrawer!=null)closeStudioDrawer()
  else if(runtime?.network==null && coordinator.currentChatID!=null)coordinator.showChats()
  else finish()
 }

 /**
  * Передаёт прежний системный способ возврата общей обработке выхода из студии
  * Поддерживает версии системы без нового способа уведомления о возврате
  * Самостоятельно не выбирает другой чат
  */
 @Deprecated("Compatibility path below API33")
 override fun onBackPressed(){leaveStudio()}
 /**
  * Отмечает возвращение окна на передний план после стандартного системного запуска
  * Возобновляет сетевую синхронизацию и разрешает показ медиа текущего чата
  * Фактический набор видимых медиа согласует сама сцена истории
  */
 override fun onStart(){super.onStart();isForeground=true;runtime?.network?.foreground(true);chatView?.setMediaVisible(true)}
 /**
  * Прекращает показ медиа, основное воспроизведение и захват, затем сохраняет место чтения и черновик
  * После этого передаёт остановку обычному системному механизму окна
  */
 override fun onStop(){MetricsRuntime.export();isForeground=false;runtime?.network?.foreground(false);chatView?.setMediaVisible(false);runtime?.playback?.stop();chatView?.stopCapture();chatView?.captureAnchor();state?.flush{};super.onStop()}
 /**
  * Сохраняет место чтения и запрашивает запись черновика перед системным сохранением окна
  * В состояние восстановления кладёт выбранный чат и имя местного хранилища
  * Передаёт остальные сведения родительскому механизму системы
  */
 override fun onSaveInstanceState(outState:Bundle){chatView?.captureAnchor();state?.flush{};outState.putLong("chatID",coordinator.currentChatID ?: -1);outState.putString("rootName",rootName);super.onSaveInstanceState(outState)}
 /**
  * Передаёт уже открытое окружение следующему экземпляру окна при изменении конфигурации
  * Возвращает его только после появления рабочего хранилища
  * Это позволяет сохранить владельцев базы и медиа между пересозданиями окна
  */
 @Deprecated("Native SDK-only retention across Activity recreation")
 override fun onRetainNonConfigurationInstance():Any?=if(repository!=null)runtime else null
 /**
  * При достаточном уровне нехватки памяти просит медиа и текущий чат освободить восстанавливаемые данные
  * До собственного действия передаёт уведомление системному владельцу окна
  * Постоянную историю и пользовательские файлы здесь не удаляет
  */
 override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW){runtime?.media?.trimMemory();chatView?.trimMemory()}}
 /**
  * Отменяет действительность поздних результатов и снимает обработчики выбора файлов и разрешений
  * Окружение закрывает только при окончательном завершении либо отсутствии рабочего хранилища
  * При пересоздании сохраняет передаваемое окружение для следующего окна
  */
 override fun onDestroy(){entry?.close();entry=null;frameProbe?.close();frameProbe=null;chatView?.captureAnchor();generation++;state?.close();documentResult=null;cameraPermission=null;if(!isChangingConfigurations || repository==null){runtime?.close() ?: store?.close{}};coordinator.close();super.onDestroy()}
 /**
  * Проверяет уже выданный доступ к камере перед началом захвата
  * При отсутствии разрешения сохраняет продолжение и просит систему показать запрос
  * Полученный ответ будет передан отдельным системным обработчиком
  */
 fun requestCameraPermission(callback:(Boolean)->Unit){
  if(checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)callback(true)
  else{cameraPermission=callback;requestPermissions(arrayOf(android.Manifest.permission.CAMERA),7402)}
 }
 /**
  * Принимает результат предусмотренного системного выбора исходного файла
  * Передаёт данные текущему ожидающему экрану только для нужного номера запроса
  * При отмене передаёт отсутствие результата вместо прежнего файла
  */
 @Deprecated("Native SDK-only scoped picker result")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:android.content.Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==7401)documentResult?.invoke(if(resultCode==RESULT_OK)data else null)}
 /**
  * Разбирает ответы на запросы микрофона и камеры по их отдельным номерам
  * Забирает и очищает соответствующее ожидающее продолжение перед вызовом
  * Возвращает разрешение только при положительном первом результате системы
  */
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==7402){val callback=cameraPermission;cameraPermission=null;callback?.invoke(grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED)}}

}
