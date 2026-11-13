package app.glidetv.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.glidetv.AppGraph
import app.glidetv.GlideApp
import app.glidetv.R
import app.glidetv.engine.EngineKind
import app.glidetv.ui.widgets.MATCH
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.focusBackground
import app.glidetv.ui.widgets.lp
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
                turboBadge.text = when {
                    st.connecting -> getString(R.string.turbo_connecting)
                    st.kind == EngineKind.ADB -> getString(R.string.turbo_on_adb)
                    st.kind == EngineKind.ROOT -> getString(R.string.turbo_on_root)
                    else -> getString(R.string.turbo_off)
                }
                turboBadge.setTextColor(if (st.kind != EngineKind.NONE) Palette.OK else Palette.TEXT_DIM)
            }
        }
        scope.launch { graph.engines.restore() }
    }

    private fun buildLayout(): View {
        content = FrameLayout(this)
        val nav = vertical {
            setPadding(dp(8), dp(4), dp(24), dp(4))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.banner)
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(dp(180), WRAP).apply { bottomMargin = dp(8) })
            turboBadge = text("", 14f, Palette.TEXT_DIM).also {
                addView(it, lp().apply { bottomMargin = dp(20); leftMargin = dp(8) })
            }
            addNav(this, R.string.nav_dashboard, "dashboard") { DashboardScreen(this@MainActivity) }
            addNav(this, R.string.nav_clean, "clean") { CleanScreen(this@MainActivity) }
            addNav(this, R.string.nav_apps, "apps") { DebloatScreen(this@MainActivity) }
            addNav(this, R.string.nav_turbo, "turbo") { TurboScreen(this@MainActivity) }
            addNav(this, R.string.nav_settings, "settings") { SettingsScreen(this@MainActivity) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.BG)
            // Безопасная зона overscan ~5 % (TV-OV)
            setPadding(dp(40), dp(27), dp(40), dp(27))
            addView(nav, LinearLayout.LayoutParams(dp(220), MATCH))
            addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    private fun addNav(parent: LinearLayout, label: Int, key: String, factory: () -> Screen) {
        val item = text(getString(label), 19f, Palette.TEXT, bold = true).apply {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = focusBackground(context, android.graphics.Color.TRANSPARENT, Palette.CARD_FOCUS, 12)
            setOnClickListener { show(key) }
            tag = key
        }
        parent.addView(item, lp().apply { bottomMargin = dp(6) })
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
