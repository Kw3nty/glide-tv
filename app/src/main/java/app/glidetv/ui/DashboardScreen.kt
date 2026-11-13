package app.glidetv.ui

import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.glidetv.R
import app.glidetv.clean.OptimizeReport
import app.glidetv.clean.OptimizeStep
import app.glidetv.clean.StepStatus
import app.glidetv.engine.EngineKind
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.RingGauge
import app.glidetv.ui.widgets.StatusView
import app.glidetv.ui.widgets.Tone
import app.glidetv.ui.widgets.Type
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.addButtons
import app.glidetv.ui.widgets.addSpaced
import app.glidetv.ui.widgets.card
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.icon
import app.glidetv.ui.widgets.iconView
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.screenHeader
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.vertical
import app.glidetv.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Главная: состояние памяти, статус «Турбо» и одна главная кнопка «Оптимизировать».
 * Результат показывается прямо на экране (а не во всплывающем окне), чтобы его было видно после закрытия.
 */
class DashboardScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var ramGauge: RingGauge
    private lateinit var ramText: TextView
    private lateinit var storageGauge: RingGauge
    private lateinit var storageText: TextView
    private lateinit var turboCard: LinearLayout
    private lateinit var turboChip: TextView
    private lateinit var turboText: TextView
    private lateinit var turboAction: TextView
    private lateinit var optimizeButton: TextView
    private lateinit var killToggle: TextView
    private lateinit var status: StatusView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultLines: LinearLayout
    private var running = false

    override fun createView(): View = ScrollView(ctx).apply {
        isFillViewport = true
        addView(ctx.vertical {
            screenHeader(string(R.string.dashboard_title), graph.platform.displayName)

            val row = ctx.horizontal { gravity = android.view.Gravity.TOP }
            row.addSpaced(metricCard(R.drawable.ic_memory, R.string.ram).also {
                ramGauge = it.getChildAt(0) as RingGauge
                ramText = (it.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            }, 24, lp(0, WRAP, 1f))
            row.addSpaced(metricCard(R.drawable.ic_storage, R.string.storage).also {
                storageGauge = it.getChildAt(0) as RingGauge
                storageText = (it.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            }, 0, lp(0, WRAP, 1f))
            addSpaced(row, 24)

            // Баннер виден только без «Турбо»: когда оно включено, статус уже показан в меню слева
            turboCard = ctx.card(focusable = true, paddingDp = 20) {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addSpaced(ctx.iconView(R.drawable.ic_bolt, Palette.WARN, 32), 20)
                addView(ctx.vertical {
                    turboChip = ctx.text("", Type.BODY, Palette.WARN, bold = true).also { addView(it) }
                    turboText = ctx.text("", Type.CAPTION, Palette.TEXT_DIM).apply { maxLines = 2 }.also { addView(it) }
                }, lp(0, WRAP, 1f))
                turboAction = ctx.text("", Type.SECONDARY, Palette.ACCENT, bold = true).apply {
                    setCompoundDrawables(null, null,
                        ctx.icon(R.drawable.ic_chevron, ColorStateList.valueOf(Palette.ACCENT)).apply { setBounds(0, 0, dp(20), dp(20)) },
                        null)
                    compoundDrawablePadding = dp(4)
                }
                addView(turboAction, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(20) })
                setOnClickListener { activity.show("turbo") }
            }
            addSpaced(turboCard, 24)

            optimizeButton = ctx.actionButton(string(R.string.optimize), primary = true, iconRes = R.drawable.ic_clean) { optimize() }
            killToggle = ctx.actionButton("", iconRes = R.drawable.ic_memory) {
                graph.prefs.optimizeKillBackground = !graph.prefs.optimizeKillBackground
                renderToggle()
            }
            addSpaced(ctx.horizontal { addButtons(optimizeButton, killToggle) }, 16)
            status = StatusView(ctx).also { addSpaced(it, 16) }

            resultCard = ctx.card {
                visibility = View.GONE
                addView(ctx.horizontal {
                    addSpaced(ctx.iconView(R.drawable.ic_check, Palette.OK, 28), 12)
                    addView(ctx.text(string(R.string.report_title), Type.SECTION, bold = true))
                }, lp().apply { bottomMargin = dp(12) })
                resultLines = ctx.vertical().also { addView(it) }
            }
            addSpaced(resultCard, 24)

            addSpaced(ctx.text(string(R.string.optimize_honest_note), Type.CAPTION, Palette.TEXT_DIM), 0)
        })
    }

    /** Карточка метрики: кольцо слева, справа название и «свободно X из Y». */
    private fun metricCard(iconRes: Int, title: Int): LinearLayout = ctx.card(paddingDp = 20) {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(RingGauge(ctx, sizeDp = 104).apply { caption = string(R.string.used) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(20) })
        addView(ctx.vertical {
            addView(ctx.horizontal {
                addSpaced(ctx.iconView(iconRes, Palette.ACCENT, 20), 8)
                addView(ctx.text(string(title), Type.SECONDARY, bold = true), lp(0, WRAP, 1f))
            }, lp().apply { bottomMargin = dp(8) })
            addView(ctx.text("", Type.SECONDARY, Palette.TEXT_DIM))
        }, lp(0, WRAP, 1f))
    }

    override fun defaultFocus(): View = optimizeButton

    override fun onShow() {
        renderToggle()
        scope.launch {
            while (isActive) {
                refresh()
                delay(3_000)
            }
        }
        scope.launch {
            graph.engines.state.collect { renderTurbo(it.kind) }
        }
    }

    private suspend fun refresh() {
        val (ram, st) = withContext(Dispatchers.IO) { graph.memory.snapshot() to graph.storage.internal() }
        ramGauge.percent = Format.percent(ram.usedBytes, ram.totalBytes)
        ramText.text = string(R.string.free_of, Format.bytes(ctx, ram.availBytes), Format.bytes(ctx, ram.totalBytes))
        storageGauge.percent = Format.percent(st.usedBytes, st.totalBytes)
        storageText.text = string(R.string.free_of, Format.bytes(ctx, st.freeBytes), Format.bytes(ctx, st.totalBytes))
    }

    private fun renderTurbo(kind: EngineKind) {
        turboCard.visibility = if (kind == EngineKind.NONE) View.VISIBLE else View.GONE
        turboChip.text = string(R.string.turbo_card_off_title)
        turboText.text = string(R.string.turbo_card_off_text)
        turboAction.text = string(R.string.open_turbo)
    }

    private fun renderToggle() {
        killToggle.text = string(
            if (graph.prefs.optimizeKillBackground) R.string.toggle_free_ram_on else R.string.toggle_free_ram_off,
        )
    }

    private fun optimize() {
        if (running) return
        running = true
        optimizeButton.isEnabled = false
        optimizeButton.text = string(R.string.optimizing)
        resultCard.visibility = View.GONE
        status.progress(string(R.string.step_caches))
        scope.launch {
            try {
                val report = graph.optimizer.run(graph.prefs.optimizeKillBackground) { step ->
                    status.post {
                        status.progress(string(if (step == OptimizeStep.CACHES) R.string.step_caches else R.string.step_ram))
                    }
                }
                status.clear()
                refresh()
                showReport(report)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                status.error(string(R.string.operation_failed))
            } finally {
                running = false
                optimizeButton.isEnabled = true
                optimizeButton.text = string(R.string.optimize)
            }
        }
    }

    private fun showReport(report: OptimizeReport) {
        resultLines.removeAllViews()
        fun line(text: String, tone: Tone? = null) {
            resultLines.addView(ctx.horizontal {
                if (tone != null) addSpaced(ctx.iconView(tone.icon, tone.color, 20), 10)
                addView(ctx.text(text, Type.BODY, if (tone == null) Palette.TEXT else tone.color))
            }, lp().apply { bottomMargin = ctx.dp(6) })
        }
        line(string(R.string.report_storage, Format.bytes(ctx, report.storageFreedBytes)))
        line(string(R.string.report_ram, Format.bytes(ctx, report.ramFreedBytes)))
        report.steps.filter { it.status == StepStatus.FAILED }.forEach {
            line(string(R.string.report_step_failed, stepName(it.step)), Tone.ERROR)
        }
        if (report.needsTurbo) line(string(R.string.report_needs_turbo), Tone.WARN)
        resultCard.visibility = View.VISIBLE
        // Карточка без фокуса: пультом до неё не прокрутить, поэтому показываем её сами
        resultCard.post { resultCard.requestRectangleOnScreen(Rect(0, 0, resultCard.width, resultCard.height)) }
    }

    private fun stepName(step: OptimizeStep) =
        string(if (step == OptimizeStep.CACHES) R.string.step_caches_name else R.string.step_ram_name)
}
