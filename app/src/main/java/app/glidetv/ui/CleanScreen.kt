package app.glidetv.ui

import android.Manifest
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.glidetv.R
import app.glidetv.clean.JunkItem
import app.glidetv.clean.JunkKind
import app.glidetv.clean.LegacyCacheCleaner
import app.glidetv.engine.SelfAppOp
import app.glidetv.engine.ShellCmd
import app.glidetv.system.SpecialAccess
import app.glidetv.ui.widgets.CheckListAdapter
import app.glidetv.ui.widgets.CheckRow
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.StatusView
import app.glidetv.ui.widgets.Type
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.addSpaced
import app.glidetv.ui.widgets.card
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.iconView
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.screenHeader
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.tvListView
import app.glidetv.ui.widgets.vertical
import app.glidetv.util.Format
import app.glidetv.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Кеш приложений и мусорные файлы — две карточки с одной понятной кнопкой в каждой.
 * Ниже — список: самый большой кеш или найденный мусор (с галочками).
 */
class CleanScreen(activity: MainActivity) : Screen(activity) {
    private enum class Mode { CACHES, JUNK }

    private lateinit var status: StatusView
    private lateinit var clearCacheButton: TextView
    private lateinit var findJunkButton: TextView
    private lateinit var deleteButton: TextView
    private lateinit var cacheValue: TextView
    private lateinit var junkValue: TextView
    private lateinit var listTitle: TextView
    private lateinit var emptyText: TextView
    private val adapter = CheckListAdapter(activity) { renderDeleteButton() }
    private var junk: Map<String, JunkItem> = emptyMap()
    private var mode = Mode.CACHES
    private var busy = false
    /** Загрузка размеров кеша медленная (секунды на ТВ) — отменяем её, когда пользователь ищет мусор. */
    private var cacheJob: Job? = null

    override fun createView(): View = ctx.vertical {
        // Подзаголовка нет: карточки сами объясняют, что делает каждая кнопка, а месту под списком тесно
        screenHeader(string(R.string.clean_title))

        val cards = ctx.horizontal { gravity = android.view.Gravity.TOP }
        clearCacheButton = ctx.actionButton(string(R.string.clear_all_caches), primary = true, iconRes = R.drawable.ic_clean) { clearCaches() }
        cards.addSpaced(optionCard(R.drawable.ic_memory, R.string.cache_card_title, R.string.cache_card_text, clearCacheButton).also {
            cacheValue = it.tag as TextView
        }, 24, lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        findJunkButton = ctx.actionButton(string(R.string.find_junk), iconRes = R.drawable.ic_search) { scanJunk() }
        cards.addSpaced(optionCard(R.drawable.ic_folder, R.string.junk_card_title, R.string.junk_card_text, findJunkButton).also {
            junkValue = it.tag as TextView
        }, 0, lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addSpaced(cards, 20)

        status = StatusView(ctx).also { addSpaced(it, 16) }

        val listHeader = ctx.horizontal()
        listTitle = ctx.text(string(R.string.top_caches), Type.SECTION, bold = true)
        listHeader.addView(listTitle, lp(0, WRAP, 1f))
        deleteButton = ctx.actionButton(string(R.string.delete_selected_empty), primary = true, iconRes = R.drawable.ic_trash) { deleteSelected() }
        listHeader.addView(deleteButton, LinearLayout.LayoutParams(WRAP, WRAP))
        addSpaced(listHeader, 12)

        emptyText = ctx.text("", Type.SECONDARY, Palette.TEXT_DIM).apply { visibility = View.GONE }
        addSpaced(emptyText, 8)
        addView(ctx.tvListView(adapter), lp(height = 0, weight = 1f))
    }

    /** Карточка-вариант: заголовок и крупное значение в одной строке, пояснение, кнопка. Значение — в tag. */
    private fun optionCard(iconRes: Int, title: Int, body: Int, button: TextView): LinearLayout = ctx.card(paddingDp = 20) {
        val value = ctx.text("—", Type.SECTION, bold = true)
        addView(ctx.horizontal {
            addSpaced(ctx.iconView(iconRes, Palette.ACCENT), 12)
            addView(ctx.text(string(title), Type.BODY, bold = true), lp(0, WRAP, 1f))
            addView(value, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(12) })
        }, lp().apply { bottomMargin = dp(6) })
        addView(ctx.text(string(body), Type.CAPTION, Palette.TEXT_DIM), lp(weight = 1f).apply { bottomMargin = dp(16) })
        addView(button, LinearLayout.LayoutParams(WRAP, WRAP))
        tag = value
    }

    override fun defaultFocus(): View = clearCacheButton

    override fun onShow() {
        renderDeleteButton()
        if (mode == Mode.CACHES) loadCacheList()
    }

    private fun loadCacheList() {
        cacheJob?.cancel()
        cacheJob = scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !SpecialAccess.hasUsageAccess(ctx)) {
                // На ТВ экрана «Доступ к истории использования» часто нет — выдаём через «Турбо»
                graph.engines.current()?.run(ShellCmd.AllowSelfAppOp(SelfAppOp.USAGE_STATS))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !SpecialAccess.hasUsageAccess(ctx)) {
                if (mode == Mode.CACHES) {
                    status.warn(string(R.string.need_usage_access))
                    showRows(emptyList(), string(R.string.need_usage_access_short))
                }
                return@launch
            }
            if (mode == Mode.CACHES && adapter.count == 0) status.progress(string(R.string.loading))
            val apps = graph.apps.withSizes(withContext(Dispatchers.IO) { graph.apps.installed() })
                .filter { it.cacheBytes > 0 }
                .sortedByDescending { it.cacheBytes }
            val total = apps.sumOf { it.cacheBytes }
            cacheValue.text = Format.bytes(ctx, total)
            // Пока грузились размеры, пользователь мог перейти к мусору — тогда список не трогаем
            if (mode != Mode.CACHES) return@launch
            if (status.text == string(R.string.loading)) status.clear()
            listTitle.text = string(R.string.top_caches)
            showRows(apps.take(40).map {
                CheckRow(it.pkg, it.label, it.pkg, badge = Format.bytes(ctx, it.cacheBytes), checkable = false)
            }, string(R.string.cache_empty))
        }
    }

    private fun clearCaches() {
        if (busy) return
        val engine = graph.engines.current()
        if (engine == null && !LegacyCacheCleaner.isSupported) {
            dialog(string(R.string.need_turbo_title), string(R.string.need_turbo_caches),
                positive = R.string.open_turbo, negative = android.R.string.cancel) { activity.show("turbo") }
            return
        }
        setBusy(true)
        scope.launch {
            try {
                status.progress(string(R.string.step_caches))
                val before = graph.storage.internal().freeBytes
                val ok = engine?.run(ShellCmd.TrimCaches)?.ok ?: LegacyCacheCleaner.clearAll(ctx)
                val freed = (graph.storage.internal().freeBytes - before).coerceAtLeast(0)
                if (ok) status.success(string(R.string.caches_cleared, Format.bytes(ctx, freed)))
                else status.error(string(R.string.operation_failed))
                mode = Mode.CACHES
                loadCacheList()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun scanJunk() {
        if (busy) return
        if (!SpecialAccess.hasStorageAccess(ctx)) {
            requestStorageAccess()
            return
        }
        cacheJob?.cancel()
        mode = Mode.JUNK
        setBusy(true)
        scope.launch {
            try {
                status.progress(string(R.string.scanning))
                listTitle.text = string(R.string.junk_list_title)
                showRows(emptyList(), "")
                val items = graph.junkScanner.scan()
                Logx.i("Мусор: найдено ${items.size}, ${items.sumOf { it.sizeBytes }} Б")
                junk = items.associateBy { it.file.absolutePath }
                val total = Format.bytes(ctx, items.sumOf { it.sizeBytes })
                junkValue.text = if (items.isEmpty()) string(R.string.junk_value_clean) else total
                listTitle.text = string(R.string.junk_found, items.size, total)
                showRows(items.map { item ->
                    CheckRow(
                        key = item.file.absolutePath,
                        title = kindLabel(item.kind),
                        subtitle = item.file.absolutePath,
                        badge = Format.bytes(ctx, item.sizeBytes),
                        checked = item.selectedByDefault,
                    )
                }, string(R.string.junk_none))
                if (items.isEmpty()) status.success(string(R.string.junk_none)) else status.info(string(R.string.junk_hint))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Logx.w("Поиск мусора", t)
                status.error(string(R.string.junk_scan_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                setBusy(false)
                renderDeleteButton()
            }
        }
    }

    private fun deleteSelected() {
        val selected = adapter.checked().mapNotNull { junk[it.key] }
        if (selected.isEmpty() || busy) return
        val size = Format.bytes(ctx, selected.sumOf { it.sizeBytes })
        dialog(string(R.string.delete_confirm_title), string(R.string.delete_confirm_text, selected.size, size),
            positive = R.string.delete, negative = android.R.string.cancel) {
            setBusy(true)
            scope.launch {
                var freed = 0L
                try {
                    status.progress(string(R.string.deleting))
                    freed = graph.junkScanner.delete(selected)
                } finally {
                    setBusy(false)
                }
                scanJunk()
                // Сообщение об удалении важнее подсказки после повторного сканирования
                status.success(string(R.string.deleted, Format.bytes(ctx, freed)))
            }
        }
    }

    private fun showRows(rows: List<CheckRow>, emptyMessage: String) {
        adapter.rows = rows
        emptyText.text = emptyMessage
        emptyText.visibility = if (rows.isEmpty() && emptyMessage.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        clearCacheButton.isEnabled = !value
        findJunkButton.isEnabled = !value
        renderDeleteButton()
    }

    private fun renderDeleteButton() {
        val selected = adapter.checked().mapNotNull { junk[it.key] }
        deleteButton.visibility = if (mode == Mode.JUNK && junk.isNotEmpty()) View.VISIBLE else View.GONE
        deleteButton.text = if (selected.isEmpty()) string(R.string.delete_selected_empty)
        else string(R.string.delete_selected, selected.size, Format.bytes(ctx, selected.sumOf { it.sizeBytes }))
        deleteButton.isEnabled = selected.isNotEmpty() && !busy
    }

    private fun requestStorageAccess() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val engine = graph.engines.current()
                if (engine != null) {
                    status.progress(string(R.string.granting_storage))
                    scope.launch {
                        engine.run(ShellCmd.AllowSelfAppOp(SelfAppOp.ALL_FILES))
                        if (SpecialAccess.hasStorageAccess(ctx)) scanJunk()
                        else status.error(string(R.string.need_storage_access))
                    }
                } else if (!SpecialAccess.openAllFilesSettings(ctx)) {
                    status.warn(string(R.string.need_storage_access_turbo))
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    private fun kindLabel(kind: JunkKind) = string(
        when (kind) {
            JunkKind.APK -> R.string.junk_apk
            JunkKind.TEMP -> R.string.junk_temp
            JunkKind.THUMBNAILS -> R.string.junk_thumbs
            JunkKind.EXTERNAL_CACHE -> R.string.junk_ext_cache
            JunkKind.EMPTY_DIR -> R.string.junk_empty_dir
        },
    )

    companion object {
        const val REQ_STORAGE = 42
    }
}
