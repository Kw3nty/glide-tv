package app.glidetv.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.glidetv.R
import app.glidetv.clean.OptimizeStep
import app.glidetv.clean.StepStatus
import app.glidetv.engine.EngineKind
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.RingGauge
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.addSpaced
import app.glidetv.ui.widgets.card
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.vertical
import app.glidetv.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardScreen(activity: MainActivity) : Screen(activity) {
    private lateinit var ramGauge: RingGauge
    private lateinit var ramText: TextView
    private lateinit var storageGauge: RingGauge
    private lateinit var storageText: TextView
    private lateinit var turboTitle: TextView
    private lateinit var turboText: TextView
    private lateinit var optimizeButton: TextView
    private lateinit var status: TextView
    private lateinit var killToggle: TextView
    private var running = false

    override fun createView(): View = ScrollView(ctx).apply {
        isFillViewport = true
        addView(ctx.vertical {
            addSpaced(ctx.text(string(R.string.dashboard_title), 34f, bold = true), 4)
            addSpaced(ctx.text(graph.platform.displayName, 16f, Palette.TEXT_DIM), 24)

            val row = ctx.horizontal()
            row.addSpaced(ctx.card {
                ramGauge = RingGauge(ctx).also { addView(it, LinearLayout.LayoutParams(WRAP, WRAP)) }
                addView(ctx.text(string(R.string.ram), 20f, bold = true))
                ramText = ctx.text("", 15f, Palette.TEXT_DIM).also { addView(it) }
            }, 20, lp(0, WRAP, 1f))
            row.addSpaced(ctx.card {
                storageGauge = RingGauge(ctx).also { addView(it, LinearLayout.LayoutParams(WRAP, WRAP)) }
                addView(ctx.text(string(R.string.storage), 20f, bold = true))
                storageText = ctx.text("", 15f, Palette.TEXT_DIM).also { addView(it) }
            }, 20, lp(0, WRAP, 1f))
            row.addSpaced(ctx.card(focusable = true) {
                turboTitle = ctx.text("", 22f, bold = true).also { addView(it) }
                turboText = ctx.text("", 15f, Palette.TEXT_DIM).also { addView(it, lp().apply { topMargin = dp(8) }) }
                setOnClickListener { activity.show("turbo") }
            }, 0, lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f))
            addSpaced(row, 28)

            optimizeButton = ctx.actionButton(string(R.string.optimize), primary = true) { optimize() }
            val actions = ctx.horizontal()
            actions.addSpaced(optimizeButton, 16, LinearLayout.LayoutParams(WRAP, WRAP))
            killToggle = ctx.actionButton("") {
                graph.prefs.optimizeKillBackground = !graph.prefs.optimizeKillBackground
                renderToggle()
            }
            actions.addSpaced(killToggle, 0, LinearLayout.LayoutParams(WRAP, WRAP))
            addSpaced(actions, 16)
            status = ctx.text("", 16f, Palette.TEXT_DIM).also { addSpaced(it, 8) }
            addSpaced(ctx.text(string(R.string.optimize_honest_note), 14f, Palette.TEXT_DIM), 0)
        })
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
        when (kind) {
            EngineKind.NONE -> {
                turboTitle.text = string(R.string.turbo_card_off_title)
                turboTitle.setTextColor(Palette.WARN)
                turboText.text = string(R.string.turbo_card_off_text)
            }
            else -> {
                turboTitle.text = string(R.string.turbo_card_on_title)
                turboTitle.setTextColor(Palette.OK)
                turboText.text = string(if (kind == EngineKind.ROOT) R.string.turbo_card_on_root else R.string.turbo_card_on_adb)
            }
        }
    }

    private fun renderToggle() {
        killToggle.text = string(
            if (graph.prefs.optimizeKillBackground) R.string.toggle_free_ram_on else R.string.toggle_free_ram_off,
        )
    }

    private fun optimize() {
        if (running) return
        running = true
        optimizeButton.alpha = 0.6f
        scope.launch {
            try {
                val report = graph.optimizer.run(graph.prefs.optimizeKillBackground) { step ->
                    status.post {
                        status.text = string(if (step == OptimizeStep.CACHES) R.string.step_caches else R.string.step_ram)
                    }
                }
                status.text = ""
                refresh()
                val lines = buildList {
                    add(string(R.string.report_storage, Format.bytes(ctx, report.storageFreedBytes)))
                    add(string(R.string.report_ram, Format.bytes(ctx, report.ramFreedBytes)))
                    report.steps.filter { it.status == StepStatus.FAILED }.forEach {
                        add(string(R.string.report_step_failed, stepName(it.step)))
                    }
                    if (report.needsTurbo) add("\n" + string(R.string.report_needs_turbo))
                }
                dialog(string(R.string.report_title), lines.joinToString("\n"))
            } finally {
                running = false
                optimizeButton.alpha = 1f
            }
        }
    }

    private fun stepName(step: OptimizeStep) =
        string(if (step == OptimizeStep.CACHES) R.string.step_caches else R.string.step_ram)
}
