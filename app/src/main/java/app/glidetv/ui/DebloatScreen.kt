package app.glidetv.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.glidetv.R
import app.glidetv.debloat.Candidate
import app.glidetv.debloat.DebloatPlan
import app.glidetv.debloat.Risk
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
import kotlinx.coroutines.launch

/**
 * «Лишние приложения»: список с галочками. Галочка = «должно быть отключено».
 * Отмечены по умолчанию только фоновые пакеты, проверенные на этой модели или массово на других ТВ.
 */
class DebloatScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var header: TextView
    private lateinit var status: TextView
    private lateinit var applyButton: TextView
    private lateinit var restoreButton: TextView
    private lateinit var turboButton: TextView
    private val adapter = CheckListAdapter(activity) { renderButtons() }
    private var plan: DebloatPlan? = null
    private var candidates: Map<String, Candidate> = emptyMap()
    private var busy = false

    override fun createView(): View = ctx.vertical {
        addSpaced(ctx.text(string(R.string.apps_title), 30f, bold = true), 6)
        header = ctx.text("", 15f, Palette.TEXT_DIM).also { addSpaced(it, 14) }
        val actions = ctx.horizontal()
        applyButton = ctx.actionButton(string(R.string.apply_selection), primary = true) { apply() }
        actions.addSpaced(applyButton, 16, LinearLayout.LayoutParams(WRAP, WRAP))
        restoreButton = ctx.actionButton(string(R.string.restore_all)) { restoreAll() }
        actions.addSpaced(restoreButton, 16, LinearLayout.LayoutParams(WRAP, WRAP))
        turboButton = ctx.actionButton(string(R.string.open_turbo)) { activity.show("turbo") }
        actions.addSpaced(turboButton, 0, LinearLayout.LayoutParams(WRAP, WRAP))
        addSpaced(actions, 10)
        status = ctx.text("", 15f, Palette.TEXT_DIM).also { addSpaced(it, 10) }
        addView(ctx.tvListView(adapter), lp(height = 0, weight = 1f))
    }

    override fun defaultFocus(): View = if (graph.engines.isPrivileged) applyButton else turboButton

    override fun onShow() {
        val privileged = graph.engines.isPrivileged
        turboButton.visibility = if (privileged) View.GONE else View.VISIBLE
        applyButton.visibility = if (privileged) View.VISIBLE else View.GONE
        restoreButton.visibility = if (privileged) View.VISIBLE else View.GONE
        when {
            !graph.platform.canDisablePackages -> {
                header.text = string(R.string.apps_fireos5)
                adapter.rows = emptyList()
                applyButton.visibility = View.GONE
            }
            else -> load()
        }
        if (!privileged) status.text = string(R.string.apps_need_turbo)
    }

    private fun load() {
        scope.launch {
            status.text = string(R.string.loading)
            val p = graph.debloatPlanner.plan(graph.debloatJournal)
            plan = p
            candidates = p.candidates.associateBy { it.pkg }
            header.text = p.profile?.let { string(R.string.apps_profile, it.title) }
                ?: string(R.string.apps_no_profile, graph.debloatDb.profiles.size)
            adapter.rows = p.candidates.map { it.toRow() }
            if (graph.engines.isPrivileged) status.text = string(R.string.apps_hint)
            renderButtons()
        }
    }

    private fun Candidate.toRow(): CheckRow {
        val badge = when {
            disabledByGlide -> string(R.string.badge_disabled_by_glide)
            risk != null -> string(R.string.badge_caution)
            userVisible -> string(R.string.badge_app)
            recommended -> string(R.string.badge_recommended)
            else -> ""
        }
        val color = when {
            disabledByGlide -> Palette.ACCENT
            risk != null -> Palette.WARN
            recommended -> Palette.OK
            else -> Palette.TEXT_DIM
        }
        val noteText = listOfNotNull(
            risk?.let { riskText(it) },
            note.takeIf { it.isNotBlank() },
            if (disabledByOthers > 0) string(R.string.disabled_by_others, disabledByOthers) else null,
        ).joinToString(" · ")
        return CheckRow(
            key = pkg,
            title = label,
            subtitle = pkg,
            note = noteText,
            badge = badge,
            badgeColor = color,
            checked = disabledByGlide || recommended,
            checkable = graph.engines.isPrivileged,
        )
    }

    private fun changes(): Pair<List<String>, List<String>> {
        val rows = adapter.rows
        val toDisable = rows.filter { it.checked && candidates[it.key]?.enabledNow == true }.map { it.key }
        val toEnable = rows.filter { !it.checked && candidates[it.key]?.disabledByGlide == true }.map { it.key }
        return toDisable to toEnable
    }

    private fun renderButtons() {
        val (dis, en) = changes()
        applyButton.text = string(R.string.apply_selection_n, dis.size, en.size)
        applyButton.alpha = if (dis.isEmpty() && en.isEmpty()) 0.5f else 1f
        val journaled = graph.debloatJournal.entries().size
        restoreButton.text = string(R.string.restore_all_n, journaled)
        restoreButton.alpha = if (journaled == 0) 0.5f else 1f
    }

    private fun apply() {
        if (busy) return
        val (toDisable, toEnable) = changes()
        if (toDisable.isEmpty() && toEnable.isEmpty()) return
        val risky = toDisable.mapNotNull { candidates[it] }.filter { it.risk != null || it.userVisible }
        val message = buildString {
            append(string(R.string.apply_confirm, toDisable.size, toEnable.size))
            if (risky.isNotEmpty()) {
                append("\n\n").append(string(R.string.apply_confirm_risky)).append('\n')
                risky.take(8).forEach { append("• ").append(it.label).append('\n') }
            }
            append("\n").append(string(R.string.apply_confirm_reversible))
        }
        dialog(string(R.string.apply_confirm_title), message, positive = R.string.apply, negative = android.R.string.cancel) {
            busy = true
            scope.launch {
                try {
                    val disabled = graph.debloatExecutor.disable(toDisable) { i, n ->
                        status.post { status.text = string(R.string.progress_disable, i, n) }
                    }
                    val enabled = graph.debloatExecutor.enable(toEnable) { i, n ->
                        status.post { status.text = string(R.string.progress_enable, i, n) }
                    }
                    val failed = (disabled + enabled).filter { !it.ok }
                    status.text = string(R.string.apply_done, disabled.count { it.ok }, enabled.count { it.ok }) +
                        if (failed.isNotEmpty()) " " + string(R.string.apply_failed_n, failed.size) else ""
                    if (failed.isNotEmpty()) {
                        dialog(string(R.string.apply_failed_title),
                            failed.joinToString("\n") { "• ${candidates[it.pkg]?.label ?: it.pkg}: ${it.message}" })
                    }
                } finally {
                    busy = false
                    load()
                }
            }
        }
    }

    private fun restoreAll() {
        if (busy || graph.debloatJournal.entries().isEmpty()) return
        dialog(string(R.string.restore_confirm_title), string(R.string.restore_confirm_text),
            positive = R.string.restore, negative = android.R.string.cancel) {
            busy = true
            scope.launch {
                try {
                    val r = graph.debloatExecutor.restoreAll { i, n ->
                        status.post { status.text = string(R.string.progress_enable, i, n) }
                    }
                    status.text = string(R.string.restore_done, r.count { it.ok })
                } finally {
                    busy = false
                    load()
                }
            }
        }
    }

    private fun riskText(risk: Risk) = string(
        when (risk) {
            Risk.VOICE -> R.string.risk_voice
            Risk.CAST -> R.string.risk_cast
            Risk.REMOTE_APPS -> R.string.risk_remote_apps
            Risk.SCREENSAVER -> R.string.risk_screensaver
            Risk.ACCESSIBILITY -> R.string.risk_accessibility
            Risk.LIVE_TV -> R.string.risk_live_tv
            Risk.INPUT_KEY -> R.string.risk_input_key
        },
    )
}
