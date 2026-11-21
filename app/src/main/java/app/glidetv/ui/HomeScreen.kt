package app.glidetv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.glidetv.R
import app.glidetv.home.HomeBackground
import app.glidetv.home.HomeConfig
import app.glidetv.home.HomeLayout
import app.glidetv.home.HomePreset
import app.glidetv.home.RestoreResult
import app.glidetv.home.SwitchResult
import app.glidetv.ui.home.HomeActivity
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.StatusView
import app.glidetv.ui.widgets.TvScrollView
import app.glidetv.ui.widgets.Type
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.addButtons
import app.glidetv.ui.widgets.addSpaced
import app.glidetv.ui.widgets.card
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.focusBackground
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.icon
import app.glidetv.ui.widgets.iconView
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.onFocusColors
import app.glidetv.ui.widgets.screenHeader
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.vertical
import kotlinx.coroutines.launch

/**
 * «Экран ТВ»: заменить стандартный главный экран экраном Glide TV, выбрать пресет,
 * настроить под себя и вернуть как было.
 */
class HomeScreen(activity: MainActivity) : Screen(activity) {
    private val config get() = graph.homeConfig
    private val switcher get() = graph.homeSwitcher

    private lateinit var state: TextView
    private lateinit var makeButton: TextView
    private lateinit var restoreButton: TextView
    private lateinit var previewButton: TextView
    private lateinit var stockRow: LinearLayout
    private lateinit var stockButton: TextView
    private lateinit var presetHint: TextView
    private lateinit var clockButton: TextView
    private lateinit var labelsButton: TextView
    private lateinit var gridNote: TextView
    private lateinit var hiddenButton: TextView
    private lateinit var status: StatusView
    private val presetButtons = ArrayList<Pair<HomePreset, TextView>>()
    private val columnButtons = ArrayList<Pair<Int, TextView>>()
    private val backgroundButtons = ArrayList<Pair<HomeBackground, TextView>>()

    override fun createView(): View = TvScrollView(ctx).apply {
        addView(ctx.vertical {
            screenHeader(string(R.string.home_title), string(R.string.home_subtitle), gapDp = 16)

            addSpaced(ctx.card {
                state = ctx.text("", Type.BODY).also { addSpaced(it, 16) }
                makeButton = ctx.actionButton(string(R.string.home_make), primary = true, iconRes = R.drawable.ic_home) { makeHome() }
                restoreButton = ctx.actionButton(string(R.string.home_restore), iconRes = R.drawable.ic_restore) { restore() }
                previewButton = ctx.actionButton(string(R.string.home_preview), iconRes = R.drawable.ic_search) { preview() }
                addView(ctx.horizontal { addButtons(makeButton, restoreButton, previewButton) })
                stockButton = ctx.actionButton(string(R.string.home_disable_stock), iconRes = R.drawable.ic_power) { confirmDisableStock() }
                stockRow = ctx.horizontal { addButtons(stockButton) }
                addView(stockRow, lp().apply { topMargin = ctx.dp(16) })
            }, 16)

            status = StatusView(ctx).also { addSpaced(it, 16) }

            section(this, R.drawable.ic_tv, R.string.home_preset) {
                // Семь кнопок в один ряд не помещаются в карточку
                HomePreset.values().toList().chunked(3).forEachIndexed { i, rowItems ->
                    addView(ctx.horizontal {
                        addButtons(*rowItems.map { preset ->
                            choice(presetName(preset)) { config.applyPreset(preset); render() }.also { presetButtons += preset to it }
                        }.toTypedArray())
                    }, lp().apply { if (i > 0) topMargin = ctx.dp(16) })
                }
                presetHint = ctx.text("", Type.SECONDARY, Palette.TEXT_DIM)
                addView(presetHint, lp().apply { topMargin = ctx.dp(16) })
            }

            section(this, R.drawable.ic_apps, R.string.home_grid) {
                addSpaced(ctx.text(string(R.string.home_columns), Type.SECONDARY, Palette.TEXT_DIM), 8)
                addSpaced(ctx.horizontal {
                    addButtons(*(HomeConfig.MIN_COLUMNS..HomeConfig.MAX_COLUMNS).map { n ->
                        choice(n.toString()) { config.columns = n; render() }.also { columnButtons += n to it }
                    }.toTypedArray())
                }, 16)
                clockButton = ctx.actionButton("") { config.clock = !config.clock; render() }
                labelsButton = ctx.actionButton("") { config.labels = !config.labels; render() }
                addView(ctx.horizontal { addButtons(clockButton, labelsButton) })
                gridNote = ctx.text(string(R.string.home_grid_na), Type.SECONDARY, Palette.TEXT_DIM)
                addView(gridNote, lp().apply { topMargin = ctx.dp(16) })
            }

            section(this, R.drawable.ic_globe, R.string.home_background) {
                // Пять кнопок в один ряд не помещаются в карточку
                HomeBackground.values().toList().chunked(3).forEachIndexed { i, rowItems ->
                    addView(ctx.horizontal {
                        addButtons(*rowItems.map { bg ->
                            choice(backgroundName(bg)) { config.background = bg; render() }.also { backgroundButtons += bg to it }
                        }.toTypedArray())
                    }, lp().apply { if (i > 0) topMargin = ctx.dp(16) })
                }
            }

            section(this, R.drawable.ic_info, R.string.home_apps_title, last = true) {
                addView(ctx.text(string(R.string.home_customize_hint), Type.SECONDARY, Palette.TEXT_DIM))
                hiddenButton = ctx.actionButton("", iconRes = R.drawable.ic_restore) { config.hidden = emptySet(); render() }
                addView(ctx.horizontal { addButtons(hiddenButton) }, lp().apply { topMargin = ctx.dp(16) })
            }
        })
    }

    private fun section(parent: LinearLayout, iconRes: Int, title: Int, last: Boolean = false, body: LinearLayout.() -> Unit) {
        parent.addSpaced(ctx.card {
            addView(ctx.horizontal {
                addSpaced(ctx.iconView(iconRes, Palette.ACCENT), 12)
                addView(ctx.text(string(title), Type.SECTION, bold = true))
            }, lp().apply { bottomMargin = ctx.dp(16) })
            body()
        }, if (last) 0 else 16)
    }

    /** Кнопка выбора. Место под галочку есть всегда: при выборе ширина кнопки не меняется. */
    private fun choice(label: String, onPick: () -> Unit): TextView =
        ctx.actionButton(label, iconRes = R.drawable.ic_check, onClick = onPick)

    /** Выбранный вариант отмечен не только цветом: подложка, циан и галочка. */
    private fun TextView.mark(selected: Boolean) {
        isSelected = selected
        val colors = onFocusColors(if (selected) Palette.ACCENT else Palette.TEXT)
        background = focusBackground(ctx, if (selected) Palette.BG_DEEP else Palette.CARD_FOCUS)
        setTextColor(colors)
        val size = ctx.dp(24)
        val drawable = if (selected) ctx.icon(R.drawable.ic_check, colors) else ColorDrawable(Color.TRANSPARENT)
        drawable.setBounds(0, 0, size, size)
        setCompoundDrawables(drawable, null, null, null)
    }

    private fun presetName(preset: HomePreset) = string(when (preset) {
        HomePreset.DOCK -> R.string.home_preset_dock
        HomePreset.OLED -> R.string.home_preset_oled
        HomePreset.GLASS -> R.string.home_preset_glass
        HomePreset.CINEMA -> R.string.home_preset_cinema
        HomePreset.ZEN -> R.string.home_preset_zen
        HomePreset.METRO -> R.string.home_preset_metro
        HomePreset.ROWS -> R.string.home_preset_rows
    })

    private fun presetHint(preset: HomePreset) = string(when (preset) {
        HomePreset.DOCK -> R.string.home_preset_dock_hint
        HomePreset.OLED -> R.string.home_preset_oled_hint
        HomePreset.GLASS -> R.string.home_preset_glass_hint
        HomePreset.CINEMA -> R.string.home_preset_cinema_hint
        HomePreset.ZEN -> R.string.home_preset_zen_hint
        HomePreset.METRO -> R.string.home_preset_metro_hint
        HomePreset.ROWS -> R.string.home_preset_rows_hint
    })

    private fun backgroundName(background: HomeBackground) = string(when (background) {
        HomeBackground.NAVY -> R.string.home_bg_navy
        HomeBackground.BLACK -> R.string.home_bg_black
        HomeBackground.GRAPHITE -> R.string.home_bg_graphite
        HomeBackground.AURORA -> R.string.home_bg_aurora
        HomeBackground.SUNSET -> R.string.home_bg_sunset
        HomeBackground.OCEAN -> R.string.home_bg_ocean
    })

    override fun defaultFocus(): View =
        if (makeButton.visibility == View.VISIBLE) makeButton else restoreButton

    override fun onShow() = render()

    private fun render() {
        val s = switcher.status()
        state.text = buildString {
            append(when {
                s.glideIsHome -> string(R.string.home_current_glide)
                s.currentLabel.isEmpty() -> string(R.string.home_current_unknown)
                else -> string(R.string.home_current_other, s.currentLabel)
            })
            if (s.active && !s.glideIsHome) append("\n").append(string(R.string.home_active_not_home))
            if (s.blockedBy.isNotEmpty()) append("\n").append(string(R.string.home_blocked, s.blockedBy))
            if (s.disabledStockLabel.isNotEmpty()) append("\n").append(string(R.string.home_stock_disabled, s.disabledStockLabel))
        }

        val showRestore = s.active || s.disabledStockLabel.isNotEmpty()
        val showMake = !showRestore
        val showStock = s.stockCanBeDisabled && graph.engines.isPrivileged
        // Кнопка в фокусе сейчас скроется — система отдала бы фокус первому пункту меню.
        // Сначала показываем новые кнопки, переводим фокус на видимую и только потом скрываем старые.
        val vanishing = (makeButton.isFocused && !showMake) || (restoreButton.isFocused && !showRestore) ||
            (stockButton.isFocused && !showStock)
        if (showMake) makeButton.visibility = View.VISIBLE
        if (showRestore) restoreButton.visibility = View.VISIBLE
        if (vanishing) (if (showRestore) restoreButton else makeButton).requestFocus()
        if (!showMake) makeButton.visibility = View.GONE
        if (!showRestore) restoreButton.visibility = View.GONE
        stockRow.visibility = if (showStock) View.VISIBLE else View.GONE

        presetButtons.forEach { (preset, button) -> button.mark(preset == config.preset) }
        columnButtons.forEach { (n, button) -> button.mark(n == config.columns) }
        backgroundButtons.forEach { (bg, button) -> button.mark(bg == config.background) }
        presetHint.text = presetHint(config.preset)
        clockButton.text = string(if (config.clock) R.string.home_clock_on else R.string.home_clock_off)
        labelsButton.text = string(if (config.labels) R.string.home_labels_on else R.string.home_labels_off)

        // Не во всякой раскладке настройка что-то меняет: в списке нет колонок, а подписи под баннерами
        // есть только в сетке и рядах. Такие кнопки выключены (контур вместо заливки, см. focusBackground):
        // не нажимаются, но фокус на них остаётся и виден.
        val layout = config.preset.layout
        val usesColumns = layout != HomeLayout.LIST
        val usesLabels = layout == HomeLayout.GRID || layout == HomeLayout.ROWS
        columnButtons.forEach { (_, button) -> button.isEnabled = usesColumns }
        labelsButton.isEnabled = usesLabels
        gridNote.visibility = if (usesColumns && usesLabels) View.GONE else View.VISIBLE

        val hidden = config.hidden.size
        if (hidden == 0 && hiddenButton.isFocused) clockButton.requestFocus()
        hiddenButton.text = string(R.string.home_show_hidden, hidden)
        hiddenButton.visibility = if (hidden > 0) View.VISIBLE else View.GONE
    }

    private fun setBusy(busy: Boolean) {
        makeButton.isEnabled = !busy
        restoreButton.isEnabled = !busy
        stockButton.isEnabled = !busy
    }

    private fun preview() {
        ctx.startActivity(Intent(ctx, HomeActivity::class.java).putExtra(HomeActivity.EXTRA_PREVIEW, true))
    }

    // Операции идут в scope Activity: системный экран выбора лаунчера ставит экран на паузу,
    // а его собственный scope при этом отменяется — результат не дошёл бы до интерфейса.

    private fun makeHome() {
        setBusy(true)
        status.progress(string(R.string.home_working))
        activity.scope.launch {
            var offerStock = false
            when (switcher.apply()) {
                SwitchResult.DONE -> status.success(string(R.string.home_done))
                SwitchResult.STOCK_STILL_HOME -> {
                    status.warn(string(R.string.home_stock_wins))
                    offerStock = true
                }
                SwitchResult.NEED_TURBO -> status.warn(string(R.string.home_need_turbo))
                SwitchResult.NEED_PICKER -> {
                    if (switcher.openPicker(ctx)) {
                        status.info(string(R.string.home_pick_manually))
                    } else {
                        // Ни «Турбо», ни системного выбора: оставлять экран включённым бессмысленно
                        switcher.restore()
                        status.warn(string(R.string.home_need_turbo))
                    }
                }
                // apply() ничего не отключает, поэтому BLOCKED не возвращает; ветка нужна для полноты when
                SwitchResult.BLOCKED, SwitchResult.WIFI_OWNED, SwitchResult.FAILED -> status.error(string(R.string.home_failed))
            }
            setBusy(false)
            render()
            // На Android TV так бывает почти всегда: сразу предлагаем второй шаг, а не оставляем искать кнопку.
            // Если второй шаг опасен (Home забрал бы мастер настройки Google TV), вместо окна — объяснение.
            val blockedBy = switcher.status().blockedBy
            if (offerStock && blockedBy.isNotEmpty()) status.warn(string(R.string.home_blocked, blockedBy))
            else if (offerStock && stockRow.visibility == View.VISIBLE) confirmDisableStock()
        }
    }

    private fun restore() {
        setBusy(true)
        status.progress(string(R.string.home_working))
        activity.scope.launch {
            when (switcher.restore()) {
                RestoreResult.DONE -> status.success(string(R.string.home_restored))
                RestoreResult.NEED_TURBO -> status.warn(string(R.string.home_restore_need_turbo))
                RestoreResult.FAILED -> status.error(string(R.string.home_failed))
            }
            setBusy(false)
            render()
        }
    }

    private fun confirmDisableStock() {
        // Свой диалог вместо Screen.dialog: фокус по умолчанию на «Отмена». Окно открывается само после
        // «Сделать главным экраном», и повторное нажатие OK по инерции не должно ничего отключить.
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(string(R.string.home_disable_stock))
            .setMessage(disableStockText())
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.home_disable_stock_confirm) { _, _ -> disableStock() }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    /** На Google TV вместе с лаунчером отключается мастер настройки — об этом и о последствиях говорим заранее. */
    private fun disableStockText(): String {
        val wizard = switcher.status().wizardLabel
        return if (wizard.isEmpty()) string(R.string.home_disable_stock_text)
        else string(R.string.home_disable_stock_text_wizard, wizard)
    }

    private fun disableStock() {
        setBusy(true)
        status.progress(string(R.string.home_working))
        activity.scope.launch {
            when (switcher.disableStock()) {
                SwitchResult.DONE -> status.success(string(R.string.home_done))
                SwitchResult.BLOCKED -> status.warn(string(R.string.home_blocked, switcher.status().blockedBy))
                SwitchResult.WIFI_OWNED -> status.warn(string(R.string.home_wifi_owned))
                else -> status.error(string(R.string.home_failed))
            }
            setBusy(false)
            render()
        }
    }
}
