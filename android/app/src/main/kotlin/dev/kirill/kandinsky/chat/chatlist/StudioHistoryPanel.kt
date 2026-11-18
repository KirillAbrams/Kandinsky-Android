package dev.kirill.kandinsky.chat.chatlist

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import dev.kirill.kandinsky.chat.R
import dev.kirill.kandinsky.chat.domain.Cancellation
import dev.kirill.kandinsky.chat.network.NetworkSync
import dev.kirill.kandinsky.chat.network.StudioRecentPage
import dev.kirill.kandinsky.chat.network.StudioThread
import dev.kirill.kandinsky.chat.rendering.KandinskyInsetHost
import dev.kirill.kandinsky.chat.rendering.KandinskyPalette
import org.json.JSONObject

class StudioHistoryPanel(
    context: Context,
    private val network: NetworkSync,
    private val selected: Long?,
    private val closeCurrent: (() -> Unit)?,
    private val open: (Long) -> Unit,
    private val compose: () -> Unit,
    private val logout: () -> Unit,
    private val eraseAccount: () -> Unit,
    private val deleted: (Long) -> Unit,
) : LinearLayout(context), KandinskyInsetHost {
    private sealed interface Item {
        data class Heading(val title: String) : Item

        data class Thread(val value: StudioThread) : Item

        data class Footer(val title: String, val canLoad: Boolean) : Item
    }

    private val main = Handler(Looper.getMainLooper())
    private val requestSearch = Runnable { loadPage() }
    private val threads = mutableListOf<StudioThread>()
    private var localRecentIDs = emptySet<Long>()
    private var items = emptyList<Item>()
    private var archive = false
    private var pageOffset = 0
    private val pageSize = 30
    private var more = true
    private var loading = false
    private var readingRecent = false
    private var recentRead: Cancellation? = null
    private var failed = false
    private var mutating = false
    private var revision = 0
    private var top = 0
    private var bottom = 0
    private var pendingDialog: AlertDialog? = null

    private val search = EditText(context)
    private val archiveTile = label(16f)
    private val history = ListView(context)
    private val adapter =
        object : BaseAdapter() {
            override fun getCount() = items.size

            override fun getItem(position: Int) = items[position]

            override fun getItemId(position: Int) =
                (items[position] as? Item.Thread)?.value?.id ?: (-position - 1L)

            override fun getViewTypeCount() = 3

            override fun getItemViewType(position: Int) =
                when (items[position]) {
                    is Item.Heading -> 0
                    is Item.Thread -> 1
                    is Item.Footer -> 2
                }

            override fun isEnabled(position: Int) =
                items[position] is Item.Thread || (items[position] as? Item.Footer)?.canLoad == true

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                when (val item = items[position]) {
                    is Item.Thread ->
                        ((convertView as? ThreadCell) ?: ThreadCell()).apply { bind(item.value) }
                    is Item.Heading ->
                        ((convertView as? TextView) ?: label(15f)).apply {
                            text = item.title
                            setTextColor(KandinskyPalette.studioMuted)
                            setPadding(dp(24), dp(8), dp(16), dp(6))
                            layoutParams = AbsListView.LayoutParams(-1, dp(36))
                        }
                    is Item.Footer ->
                        ((convertView as? TextView) ?: label(15f)).apply {
                            text = item.title
                            setTextColor(KandinskyPalette.studioMuted)
                            setPadding(dp(24), 0, dp(16), 0)
                            layoutParams = AbsListView.LayoutParams(-1, dp(48))
                            setOnClickListener(
                                if (item.canLoad) OnClickListener { loadPage() } else null
                            )
                            isClickable = item.canLoad
                            identity(this, "chats.empty")
                        }
                }
        }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        isFocusableInTouchMode = true
        identity(this, "chats.library")
        val brand =
            object : View(context) {
                override fun onDraw(canvas: Canvas) {
                    val density = resources.displayMetrics.density
                    KandinskyPalette.studioBrand(
                        canvas,
                        24 * density,
                        26.5f * density,
                        density,
                        centered = false,
                    )
                }
            }
        brand.contentDescription = "Kandinsky Studio"
        addView(brand, LayoutParams(-1, dp(77)))

        val create =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                background = rounded(KandinskyPalette.studioInk, 14)
                addView(
                    android.widget.ImageView(context).apply {
                        setImageDrawable(icon(R.drawable.kandinsky_drawer_add, 24, Color.WHITE))
                    },
                    LayoutParams(dp(24), dp(24)),
                )
                addView(
                    label(14f).apply {
                        text = "Новый чат"
                        setTextColor(Color.WHITE)
                        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
                        setPadding(dp(8), 0, 0, 0)
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                )
                contentDescription = "Новый чат"
                identity(this, "chats.compose")
                setOnClickListener {
                    hideKeyboard()
                    compose()
                }
            }
        addView(create, LayoutParams(-1, dp(52)).apply { setMargins(dp(16), 0, dp(16), 0) })

        search.hint = "Поиск по чатам"
        search.textSize = 16f
        search.setTextColor(KandinskyPalette.studioInk)
        search.setHintTextColor(KandinskyPalette.studioMuted)
        search.setSingleLine()
        search.filters = arrayOf(InputFilter.LengthFilter(100))
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.background = rounded(KandinskyPalette.studioSurface, 14)
        search.setPadding(dp(16), 0, dp(16), 0)
        search.setCompoundDrawablesRelative(
            icon(R.drawable.kandinsky_drawer_search, 24),
            null,
            null,
            null,
        )
        search.compoundDrawablePadding = dp(14)
        identity(search, "chats.search.field")
        search.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                // Ждём короткую паузу в наборе, чтобы не отправлять отдельный поиск на каждую
                // букву.
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    reload(true)
                }

                // Дополнительный запрос не создаёт, поскольку он уже назначен при изменении текста.
                override fun afterTextChanged(s: Editable?) = Unit
            }
        )
        addView(
            search,
            LayoutParams(-1, dp(56)).apply { setMargins(dp(16), dp(16), dp(16), dp(4)) },
        )

        archiveTile.setPadding(dp(16), 0, dp(16), 0)
        identity(archiveTile, "chats.archive")
        archiveTile.setOnClickListener {
            archive = !archive
            updateArchive()
            reload()
        }
        updateArchive()
        addView(archiveTile, LayoutParams(-1, dp(48)))

        history.adapter = adapter
        history.divider = null
        history.isVerticalScrollBarEnabled = false
        history.setOnItemClickListener { _, _, position, _ ->
            when (val item = items.getOrNull(position)) {
                is Item.Thread -> select(item.value)
                is Item.Footer -> if (item.canLoad) loadPage()
                else -> Unit
            }
        }
        identity(history, "chats.rows")
        addView(history, LayoutParams(-1, 0, 1f))
        addView(
            View(context).apply { setBackgroundColor(KandinskyPalette.studioBorder) },
            LayoutParams(-1, dp(1)),
        )
        val accountRow = FrameLayout(context)
        accountRow.addView(
            label(12f).apply {
                text = network.profile.name
                setTextColor(KandinskyPalette.studioMuted)
                setPadding(dp(24), 0, dp(24), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            FrameLayout.LayoutParams(-1, -1).apply { marginEnd = dp(40) },
        )
        accountRow.addView(
            label(18f).apply {
                text = "⋯"
                gravity = Gravity.CENTER
                contentDescription = "Управление аккаунтом"
                identity(this, "chats.manageAccount")
                setOnClickListener {
                    hideKeyboard()
                    PopupMenu(context, this).apply {
                        menu.add("Удалить аккаунт").setOnMenuItemClickListener {
                            eraseAccount()
                            true
                        }
                        show()
                    }
                }
            },
            FrameLayout.LayoutParams(dp(44), -1, Gravity.END).apply { marginEnd = dp(12) },
        )
        addView(accountRow, LayoutParams(-1, dp(32)))
        val exit =
            label(16f).apply {
                text = "Выйти"
                contentDescription = "Выйти"
                setCompoundDrawablesRelative(
                    icon(R.drawable.kandinsky_drawer_logout, 20),
                    null,
                    null,
                    null,
                )
                compoundDrawablePadding = dp(20)
                setPadding(dp(16), 0, dp(16), 0)
                identity(this, "chats.account")
                setOnClickListener {
                    hideKeyboard()
                    logout()
                }
            }
        addView(exit, LayoutParams(-1, dp(56)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(size: Float) =
        TextView(context).apply {
            textSize = size
            setTextColor(KandinskyPalette.studioInk)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun icon(resource: Int, size: Int, color: Int = KandinskyPalette.studioInk) =
        context.getDrawable(resource)?.mutate()?.apply {
            setBounds(0, 0, dp(size), dp(size))
            setTint(color)
        }

    private fun identity(view: View, id: String) {
        view.accessibilityDelegate =
            object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.viewIdResourceName = id
                    if (host.isClickable) info.className = "android.widget.Button"
                }
            }
    }

    override fun updateInsets(top: Int, bottom: Int) {
        if (top != this.top || bottom != this.bottom) {
            this.top = top
            this.bottom = bottom
            setPadding(0, top, 0, bottom)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        network.onStudioRecentChanged = {
            if (
                showingRecent() &&
                    pageOffset <= StudioRecentPage.CATALOG_LIMIT &&
                    (!loading || readingRecent)
            )
                loadRecent()
        }
        reload()
    }

    override fun onDetachedFromWindow() {
        revision++
        network.onStudioRecentChanged = null
        recentRead?.cancel()
        recentRead = null
        main.removeCallbacks(requestSearch)
        pendingDialog?.dismiss()
        pendingDialog = null
        super.onDetachedFromWindow()
    }

    fun hideKeyboard() {
        search.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    private fun updateArchive() {
        archiveTile.text = if (archive) "Все чаты" else "Архив"
        archiveTile.setCompoundDrawablesRelative(
            icon(
                if (archive) R.drawable.kandinsky_drawer_back
                else R.drawable.kandinsky_drawer_archive,
                24,
            ),
            null,
            null,
            null,
        )
        archiveTile.compoundDrawablePadding = dp(16)
        archiveTile.contentDescription = if (archive) "Все чаты" else "Архив"
    }

    private fun query() = search.text.toString().trim()

    private fun showingRecent() = !archive && query().isEmpty()

    // При смене поиска отменяем прежнюю загрузку, чтобы старые результаты не заменили новые.
    private fun reload(debounce: Boolean = false) {
        main.removeCallbacks(requestSearch)
        recentRead?.cancel()
        recentRead = null
        revision++
        threads.clear()
        localRecentIDs = emptySet()
        pageOffset = 0
        more = true
        loading = false
        readingRecent = false
        failed = false
        history.setSelection(0)
        if (showingRecent()) {
            loadRecent()
            return
        }
        refreshRows()
        if (debounce) main.postDelayed(requestSearch, 240) else loadPage()
    }

    // Читает сохранённый каталог недавних чатов вместо повторного запроса первых страниц при
    // открытии
    // панели.
    private fun loadRecent() {
        recentRead?.cancel()
        val ticket = ++revision
        readingRecent = true
        loading = true
        failed = false
        refreshRows()
        recentRead =
            network.cachedStudioRecent { result ->
                if (ticket != revision || !isAttachedToWindow) return@cachedStudioRecent
                recentRead = null
                readingRecent = false
                loading = false
                result
                    .onSuccess { page ->
                        threads.clear()
                        threads.addAll(page.threads)
                        localRecentIDs = page.threads.mapTo(mutableSetOf()) { it.id }
                        pageOffset = page.nextOffset ?: 0
                        more = page.nextOffset != null
                    }
                    .onFailure { failed = true }
                refreshRows()
            }
    }

    private fun loadPage() {
        if (loading || !more && !failed || !isAttachedToWindow) return
        if (showingRecent() && pageOffset == 0) {
            loadRecent()
            return
        }
        loading = true
        failed = false
        refreshRows()
        val ticket = revision
        val query = query()
        network.studioThreads(
            if (archive) "archive" else if (query.isEmpty()) "recent" else "all",
            query,
            pageOffset,
            pageSize,
        ) { result ->
            if (ticket == revision && isAttachedToWindow) {
                loading = false
                result
                    .onSuccess { page ->
                        val ids = threads.mapTo(mutableSetOf()) { it.id }
                        threads += page.filter { ids.add(it.id) }
                        pageOffset += page.size
                        more = page.size == pageSize
                    }
                    .onFailure { failed = true }
                refreshRows()
            }
        }
    }

    private fun refreshRows() {
        val next = buildList {
            if (archive) add(Item.Heading("Архив"))
            if (!archive && query().isEmpty()) {
                val pinned = threads.filter { it.pinned }
                val recent = threads.filterNot { it.pinned }
                if (pinned.isNotEmpty()) {
                    add(Item.Heading("Закреплённые"))
                    addAll(pinned.map { Item.Thread(it) })
                }
                if (recent.isNotEmpty()) {
                    add(Item.Heading("Недавние"))
                    addAll(recent.map { Item.Thread(it) })
                }
            } else addAll(threads.map { Item.Thread(it) })
            when {
                readingRecent -> Unit
                loading -> add(Item.Footer("Загрузка…", false))
                failed -> add(Item.Footer("Повторить загрузку", true))
                more -> add(Item.Footer("Показать ещё", true))
                threads.isEmpty() ->
                    add(
                        Item.Footer(
                            if (query().isNotEmpty()) "Чаты не найдены"
                            else if (archive) "Архив пуст" else "Чатов пока нет",
                            false,
                        )
                    )
            }
        }
        if (items != next) {
            items = next
            adapter.notifyDataSetChanged()
        }
    }

    private fun select(row: StudioThread) {
        if (mutating) return
        hideKeyboard()
        if (row.id == selected && closeCurrent != null) {
            closeCurrent.invoke()
            return
        }
        if (row.id in localRecentIDs) {
            open(row.id)
            return
        }
        mutating = true
        network.admitStudioThread(row) { result ->
            mutating = false
            if (!isAttachedToWindow) return@admitStudioThread
            result.onSuccess { open(row.id) }.onFailure { showError() }
        }
    }

    private fun menu(anchor: View, row: StudioThread) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, "Переименовать")
            if (!row.archived) menu.add(0, 2, 1, if (row.pinned) "Открепить" else "Закрепить")
            menu.add(0, 3, 2, if (row.archived) "Вернуть из архива" else "В архив")
            menu.add(0, 4, 3, "Удалить")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> rename(row)
                    2 -> change(row, JSONObject().put("pinned", !row.pinned))
                    3 -> change(row, JSONObject().put("archived", !row.archived))
                    4 -> confirmDelete(row)
                }
                true
            }
            show()
        }
    }

    private fun rename(row: StudioThread) {
        val edit =
            EditText(context).apply {
                setText(row.title)
                selectAll()
                setSingleLine()
                filters = arrayOf(InputFilter.LengthFilter(100))
                identity(this, "chats.rename.input")
            }
        pendingDialog =
            AlertDialog.Builder(context)
                .setTitle("Переименовать чат")
                .setView(edit)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить") { _, _ ->
                    val title = edit.text.toString().trim()
                    if (title.isNotEmpty()) change(row, JSONObject().put("title", title))
                }
                .show()
    }

    private fun change(row: StudioThread, change: JSONObject) {
        if (mutating) return
        mutating = true
        network.changeStudioThread(row.id, change) { result ->
            mutating = false
            if (isAttachedToWindow) result.onSuccess { reload() }.onFailure { showError() }
        }
    }

    private fun confirmDelete(row: StudioThread) {
        pendingDialog =
            AlertDialog.Builder(context)
                .setTitle("Удалить чат?")
                .setMessage("Запросы и результаты этого чата будут удалены.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ ->
                    if (mutating) return@setPositiveButton
                    mutating = true
                    network.deleteStudioThread(row.id) { result ->
                        mutating = false
                        if (isAttachedToWindow)
                            result
                                .onSuccess { if (row.id == selected) deleted(row.id) else reload() }
                                .onFailure { showError() }
                    }
                }
                .show()
    }

    private fun showError() {
        Toast.makeText(
                context,
                "Не удалось выполнить действие. Проверьте соединение.",
                Toast.LENGTH_LONG,
            )
            .show()
    }

    private inner class ThreadCell : LinearLayout(context) {
        private val mark = label(19f)
        private val title = label(16f)
        private val actions = android.widget.ImageView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = AbsListView.LayoutParams(-1, dp(56))
            setPadding(dp(16), 0, dp(12), 0)
            mark.gravity = Gravity.CENTER_VERTICAL
            addView(mark, LayoutParams(dp(40), -1))
            title.maxLines = 2
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            addView(title, LayoutParams(0, -1, 1f))
            actions.setImageDrawable(icon(R.drawable.kandinsky_drawer_menu, 24))
            actions.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            actions.contentDescription = "Действия с чатом"
            addView(actions, LayoutParams(dp(44), -1))
        }

        // Привязывает доступные действия к этим данным, чтобы повторное использование строки не
        // обращалось
        // к прежнему чату.
        fun bind(row: StudioThread) {
            val marker =
                when {
                    row.archived -> icon(R.drawable.kandinsky_drawer_archive, 19)
                    row.pinned -> icon(R.drawable.kandinsky_drawer_pin, 19)
                    else -> null
                }
            mark.setCompoundDrawablesRelative(marker, null, null, null)
            mark.visibility = if (row.archived || row.pinned) View.VISIBLE else View.GONE
            title.text = row.title
            title.isSelected = row.id == selected
            identity(title, "chat.row.${row.id}")
            identity(actions, "chats.actions.${row.id}")
            setBackgroundColor(
                if (row.id == selected) Color.argb(20, 56, 227, 178) else Color.WHITE
            )
            title.setOnClickListener { select(row) }
            actions.setOnClickListener { menu(actions, row) }
        }
    }
}
