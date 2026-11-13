package app.glidetv.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.glidetv.AppGraph
import app.glidetv.GlideApp
import app.glidetv.R
import app.glidetv.engine.EngineKind
import app.glidetv.ui.widgets.MATCH
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.Type
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.chip
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.focusBackground
import app.glidetv.ui.widgets.icon
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.onFocusColors
import app.glidetv.ui.widgets.setChipColor
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.vertical
import app.glidetv.util.Locales
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Единственная Activity. Слева — навигация, справа — текущий экран.
 * Back с любого экрана возвращает на «Главную», с «Главной» — выход на home screen ТВ (TV-DB).
 */
class MainActivity : Activity() {
    lateinit var graph: AppGraph
        private set
    val scope = MainScope()

    private lateinit var content: FrameLayout
    private lateinit var turboBadge: TextView
    private val navItems = mutableListOf<Pair<TextView, () -> Screen>>()
    private var current: Screen? = null
    private val screens = HashMap<String, Screen>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = GlideApp.graph(this)
        setContentView(buildLayout())
        show("dashboard")
        scope.launch {
            graph.engines.state.collectLatest { st ->
                val on = st.kind != EngineKind.NONE
                turboBadge.text = when {
                    st.connecting -> getString(R.string.turbo_connecting)
                    st.kind == EngineKind.ADB -> getString(R.string.turbo_on_adb)
                    st.kind == EngineKind.ROOT -> getString(R.string.turbo_on_root)
                    else -> getString(R.string.turbo_off)
                }
                val color = if (on) Palette.OK else Palette.TEXT_DIM
                turboBadge.setChipColor(color)
                turboBadge.setCompoundDrawables(
                    icon(R.drawable.ic_bolt, ColorStateList.valueOf(color)).apply { setBounds(0, 0, dp(18), dp(18)) },
                    null, null, null,
                )
            }
        }
        scope.launch { graph.engines.restore() }
    }

    private fun buildLayout(): View {
        content = FrameLayout(this)
        val nav = vertical {
            setPadding(0, 0, dp(12), 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.banner)
                adjustViewBounds = true
                contentDescription = getString(R.string.app_name)
            }, LinearLayout.LayoutParams(dp(128), WRAP).apply { bottomMargin = dp(8) })
            turboBadge = chip().apply { compoundDrawablePadding = dp(6) }.also {
                addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(16) })
            }
            addNav(this, R.string.nav_dashboard, R.drawable.ic_home, "dashboard") { DashboardScreen(this@MainActivity) }
            addNav(this, R.string.nav_clean, R.drawable.ic_clean, "clean") { CleanScreen(this@MainActivity) }
            addNav(this, R.string.nav_apps, R.drawable.ic_apps, "apps") { DebloatScreen(this@MainActivity) }
            addNav(this, R.string.nav_turbo, R.drawable.ic_bolt, "turbo") { TurboScreen(this@MainActivity) }
            addNav(this, R.string.nav_settings, R.drawable.ic_settings, "settings") { SettingsScreen(this@MainActivity) }
        }
        // Меню в ScrollView: на ТВ с крупным масштабом интерфейса нижние пункты не обрезаются, а прокручиваются
        val navScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(nav)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.BG)
            // Безопасная зона overscan ~5 % (TV-OV)
            setPadding(dp(48), dp(27), dp(48), dp(27))
            addView(navScroll, LinearLayout.LayoutParams(dp(240), MATCH))
            addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    /** Пункт меню: иконка + подпись. Выбранный — подложка и циан, в фокусе — белая заливка. */
    private fun addNav(parent: LinearLayout, label: Int, iconRes: Int, key: String, factory: () -> Screen) {
        val colors = onFocusColors(Palette.TEXT, selected = Palette.ACCENT)
        val item = text(getString(label), Type.BODY, bold = true).apply {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextColor(colors)
            background = focusBackground(context, Color.TRANSPARENT, radiusDp = 14)
            setCompoundDrawables(icon(iconRes, colors).apply { setBounds(0, 0, dp(24), dp(24)) }, null, null, null)
            compoundDrawablePadding = dp(16)
            setOnClickListener { show(key) }
            tag = key
        }
        parent.addView(item, lp().apply { bottomMargin = dp(4) })
        navItems += item to factory
    }

    fun show(key: String) {
        val screen = screens.getOrPut(key) {
            navItems.first { it.first.tag == key }.second()
        }
        if (screen === current) {
            screen.defaultFocus()?.requestFocus()
            return
        }
        current?.onHide()
        current = screen
        navItems.forEach { (v, _) -> v.isSelected = v.tag == key }
        content.removeAllViews()
        (screen.view.parent as? FrameLayout)?.removeView(screen.view)
        // Поле 12dp вокруг экрана: увеличенная в фокусе кнопка или карточка не обрезается краем
        screen.view.setPadding(dp(12), dp(8), dp(12), dp(8))
        (screen.view as? ViewGroup)?.clipToPadding = false
        content.addView(screen.view, FrameLayout.LayoutParams(MATCH, MATCH))
        screen.onShow()
        screen.view.post { screen.defaultFocus()?.requestFocus() }
    }

    fun recreateScreens() {
        current?.onHide()
        current = null
        screens.clear()
        recreate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && current !is DashboardScreen) {
            show("dashboard")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var paused = false

    override fun onPause() {
        super.onPause()
        paused = true
        current?.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Вернулись из системных настроек (например, после включения отладки) — обновляем экран
        if (paused) {
            paused = false
            current?.onResume()
        }
    }

    override fun onDestroy() {
        current?.onHide()
        scope.cancel()
        super.onDestroy()
    }
}
