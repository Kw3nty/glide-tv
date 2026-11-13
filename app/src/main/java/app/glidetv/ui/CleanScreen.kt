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
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.addSpaced
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.tvListView
import app.glidetv.ui.widgets.vertical
import app.glidetv.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Кеш приложений и мусорные файлы. */
class CleanScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var status: TextView
    private lateinit var clearCacheButton: TextView
    private lateinit var deleteButton: TextView
    private lateinit var listTitle: TextView
    private val adapter = CheckListAdapter(activity) { renderDeleteButton() }
    private var junk: Map<String, JunkItem> = emptyMap()
    private var busy = false

    override fun createView(): View = ctx.vertical {
        addSpaced(ctx.text(string(R.string.clean_title), 30f, bold = true), 16)
        val actions = ctx.horizontal()
        clearCacheButton = ctx.actionButton(string(R.string.clear_all_caches), primary = true) { clearCaches() }
        actions.addSpaced(clearCacheButton, 16, LinearLayout.LayoutParams(WRAP, WRAP))
        actions.addSpaced(ctx.actionButton(string(R.string.find_junk)) { scanJunk() }, 16, LinearLayout.LayoutParams(WRAP, WRAP))
        deleteButton = ctx.actionButton(string(R.string.delete_selected_empty)) { deleteSelected() }
        actions.addSpaced(deleteButton, 0, LinearLayout.LayoutParams(WRAP, WRAP))
        addSpaced(actions, 12)
        status = ctx.text("", 15f, Palette.TEXT_DIM).also { addSpaced(it, 12) }
        listTitle = ctx.text(string(R.string.top_caches), 18f, bold = true).also { addSpaced(it, 8) }
        addView(ctx.tvListView(adapter), lp(height = 0, weight = 1f))
    }

    override fun defaultFocus(): View = clearCacheButton

    override fun onShow() {
        renderDeleteButton()
        if (junk.isEmpty()) loadCacheList()
    }

    private fun loadCacheList() {
        listTitle.text = string(R.string.top_caches)
        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !SpecialAccess.hasUsageAccess(ctx)) {
                // На ТВ экрана «Доступ к истории использования» часто нет — выдаём через «Турбо»
                graph.engines.current()?.let {
                    it.run(ShellCmd.AllowSelfAppOp(SelfAppOp.USAGE_STATS))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !SpecialAccess.hasUsageAccess(ctx)) {
                status.text = string(R.string.need_usage_access)
                adapter.rows = emptyList()
                return@launch
            }
            status.text = string(R.string.loading)
            val apps = graph.apps.withSizes(withContext(Dispatchers.IO) { graph.apps.installed() })
                .filter { it.cacheBytes > 0 }
                .sortedByDescending { it.cacheBytes }
                .take(40)
            val total = apps.sumOf { it.cacheBytes }
            status.text = string(R.string.cache_total, Format.bytes(ctx, total))
            adapter.rows = apps.map {
                CheckRow(it.pkg, it.label, it.pkg, badge = Format.bytes(ctx, it.cacheBytes), checkable = false)
            }
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
        busy = true
        scope.launch {
            try {
                status.text = string(R.string.step_caches)
                val before = graph.storage.internal().freeBytes
                val ok = engine?.run(ShellCmd.TrimCaches)?.ok ?: LegacyCacheCleaner.clearAll(ctx)
                val freed = (graph.storage.internal().freeBytes - before).coerceAtLeast(0)
                status.text = if (ok) string(R.string.caches_cleared, Format.bytes(ctx, freed))
                else string(R.string.operation_failed)
                if (junk.isEmpty()) loadCacheList()
            } finally {
                busy = false
            }
        }
    }

    private fun scanJunk() {
        if (busy) return
        if (!SpecialAccess.hasStorageAccess(ctx)) {
            requestStorageAccess()
            return
        }
        busy = true
        scope.launch {
            try {
                status.text = string(R.string.scanning)
                val items = graph.junkScanner.scan()
                junk = items.associateBy { it.file.absolutePath }
                listTitle.text = string(R.string.junk_found, items.size, Format.bytes(ctx, items.sumOf { it.sizeBytes }))
                adapter.rows = items.map { item ->
                    CheckRow(
                        key = item.file.absolutePath,
                        title = kindLabel(item.kind),
                        subtitle = item.file.absolutePath,
                        badge = Format.bytes(ctx, item.sizeBytes),
                        checked = item.selectedByDefault,
                    )
                }
                status.text = if (items.isEmpty()) string(R.string.junk_none) else string(R.string.junk_hint)
                renderDeleteButton()
            } finally {
                busy = false
            }
        }
    }

    private fun deleteSelected() {
        val selected = adapter.checked().mapNotNull { junk[it.key] }
        if (selected.isEmpty() || busy) return
        val size = Format.bytes(ctx, selected.sumOf { it.sizeBytes })
        dialog(string(R.string.delete_confirm_title), string(R.string.delete_confirm_text, selected.size, size),
            positive = R.string.delete, negative = android.R.string.cancel) {
            busy = true
            scope.launch {
                try {
                    val freed = graph.junkScanner.delete(selected)
                    status.text = string(R.string.deleted, Format.bytes(ctx, freed))
                } finally {
                    busy = false
                }
                scanJunk()
            }
        }
    }

    private fun renderDeleteButton() {
        val selected = adapter.checked().mapNotNull { junk[it.key] }
        deleteButton.text = if (selected.isEmpty()) string(R.string.delete_selected_empty)
        else string(R.string.delete_selected, selected.size, Format.bytes(ctx, selected.sumOf { it.sizeBytes }))
        deleteButton.alpha = if (selected.isEmpty()) 0.5f else 1f
    }

    private fun requestStorageAccess() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val engine = graph.engines.current()
                if (engine != null) {
                    scope.launch {
                        engine.run(ShellCmd.AllowSelfAppOp(SelfAppOp.ALL_FILES))
                        if (SpecialAccess.hasStorageAccess(ctx)) scanJunk()
                        else status.text = string(R.string.need_storage_access)
                    }
                } else if (!SpecialAccess.openAllFilesSettings(ctx)) {
                    status.text = string(R.string.need_storage_access_turbo)
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
