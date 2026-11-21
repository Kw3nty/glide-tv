package app.glidetv.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.glidetv.GlideApp
import app.glidetv.R
import app.glidetv.home.DockStyle
import app.glidetv.home.HomeBackground
import app.glidetv.home.HomeConfig
import app.glidetv.home.HomeSwitcher
import app.glidetv.ui.MainActivity
import app.glidetv.ui.widgets.MATCH
import app.glidetv.ui.widgets.Palette
import app.glidetv.ui.widgets.TvScrollView
import app.glidetv.ui.widgets.Type
import app.glidetv.ui.widgets.WRAP
import app.glidetv.ui.widgets.actionButton
import app.glidetv.ui.widgets.chip
import app.glidetv.ui.widgets.dp
import app.glidetv.ui.widgets.focusBackground
import app.glidetv.ui.widgets.horizontal
import app.glidetv.ui.widgets.lp
import app.glidetv.ui.widgets.rounded
import app.glidetv.ui.widgets.text
import app.glidetv.ui.widgets.vertical
import app.glidetv.util.Locales
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Приложение на главном экране: баннер для ТВ, а если его нет — обычная иконка. */
class HomeApp(
    val pkg: String,
    val label: String,
    val launch: Intent,
    val banner: Drawable?,
    val icon: Drawable?,
)

object HomeApps {
    /** Запись в избранном «пользователь сам убрал всё»: ряд не собирается автоматически заново. */
    const val NONE = "-"

    /** Чем заполняется ряд избранного, пока пользователь не выбрал сам. */
    private val POPULAR = listOf(
        "com.google.android.youtube.tv", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta",
        "ru.kinopoisk.tv", "com.netflix.ninja", "ru.ivi.client", "ru.more.play", "ru.rt.video.app.tv",
        "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "org.xbmc.kodi", "org.videolan.vlc",
    )

    /** Приложения с ярлыком для ТВ, затем обычные (поставленные с флешки часто без ТВ-ярлыка). */
    fun load(context: Context): List<HomeApp> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, HomeApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val activity = info.activityInfo ?: continue
                if (out.containsKey(activity.packageName)) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setComponent(ComponentName(activity.packageName, activity.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val banner = runCatching { activity.loadBanner(pm) }.getOrNull()
                val icon = if (banner == null) runCatching { info.loadIcon(pm) }.getOrNull() else null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(activity.packageName)
                out[activity.packageName] = HomeApp(activity.packageName, label, launch, banner, icon)
            }
        }
        return out.values.toList()
    }

    /** Делит приложения на ряд избранного и сетку. Скрытые не попадают никуда. */
    fun arrange(all: List<HomeApp>, config: HomeConfig, columns: Int): Pair<List<HomeApp>, List<HomeApp>> {
        val hidden = config.hidden
        val visible = all.filter { it.pkg !in hidden }.sortedBy { it.label.lowercase(Locale.ROOT) }
        val chosen = config.favorites
        val favorites = if (chosen.isEmpty()) {
            val popular = visible.filter { it.pkg in POPULAR }.sortedBy { POPULAR.indexOf(it.pkg) }
            (popular + visible.filter { it.pkg !in POPULAR }).take(columns)
        } else {
            val byPkg = visible.associateBy { it.pkg }
            chosen.mapNotNull { byPkg[it] }
        }
        val dock = favorites.take(columns)
        val used = favorites.map { it.pkg }.toSet()
        return dock to (favorites.drop(columns) + visible.filter { it.pkg !in used })
    }
}

/** Фоны, нарисованные кодом: градиенты и мягкие пятна света. */
object HomeBackgrounds {
    fun create(context: Context, background: HomeBackground): Drawable {
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        return when (background) {
            HomeBackground.NAVY -> ColorDrawable(Palette.BG)
            HomeBackground.BLACK -> ColorDrawable(Color.BLACK)
            HomeBackground.AURORA -> LayerDrawable(arrayOf(
                ColorDrawable(Color.parseColor("#070A14")),
                glow("#5500E5A0", 0.18f, 0.2f, width * 0.55f),
                glow("#554B6BFF", 0.82f, 0.15f, width * 0.5f),
                glow("#44B14BFF", 0.6f, 0.95f, width * 0.5f),
            ))
            HomeBackground.SUNSET -> linear(GradientDrawable.Orientation.TOP_BOTTOM, "#120A26", "#3E163C", "#8A3329")
            HomeBackground.OCEAN -> linear(GradientDrawable.Orientation.TL_BR, "#03101C", "#062B44", "#0A5A62")
        }
    }

    private fun glow(color: String, x: Float, y: Float, radius: Float) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(Color.parseColor(color), Color.TRANSPARENT)
        setGradientCenter(x, y)
        gradientRadius = radius
    }

    private fun linear(orientation: GradientDrawable.Orientation, vararg colors: String) =
        GradientDrawable(orientation, colors.map { Color.parseColor(it) }.toIntArray())
}

/**
 * Главный экран ТВ от Glide TV: часы, ряд избранного и сетка остальных приложений.
 * Без сети, фоновых служб и рекомендаций. Вид задаётся [HomeConfig] (пресет + свои настройки).
 *
 * Открывается двумя способами: как Home (через псевдоним в манифесте) и как предпросмотр из Glide TV.
 * В предпросмотре «Назад» закрывает экран, в режиме Home — возвращает фокус в начало.
 */
class HomeActivity : Activity() {
    private lateinit var config: HomeConfig
    private lateinit var root: HomeRoot
    private val scope = MainScope()

    private var apps: List<HomeApp> = emptyList()
    private var stamp = -1
    private var rendered = false
    private var preview = false
    private var clock: TextView? = null
    private var date: TextView? = null
    private var clockJob: Job? = null
    private var loadJob: Job? = null
    private var focusPkg: String? = null
    private var firstPkg: String? = null
    private var firstPill: View? = null
    private var registered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    /** Влево/вправо фокус не перескакивает в другой ряд: с последнего приложения в ряду он остаётся на месте. */
    private class HomeRoot(context: Context) : FrameLayout(context) {
        override fun focusSearch(focused: View?, direction: Int): View? {
            val next = super.focusSearch(focused, direction)
            if (focused == null || next == null || next === focused) return next
            val sideways = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
            return if (sideways && !sameRow(focused, next)) focused else next
        }

        private fun sameRow(a: View, b: View): Boolean {
            val pa = IntArray(2).also { a.getLocationOnScreen(it) }
            val pb = IntArray(2).also { b.getLocationOnScreen(it) }
            return pa[1] < pb[1] + b.height && pb[1] < pa[1] + a.height
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = GlideApp.graph(this).homeConfig
        preview = intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true
        root = HomeRoot(this)
        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nowPreview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        if (nowPreview != preview) {
            preview = nowPreview
            if (rendered) render()
        } else if (!preview) {
            // Home на главном экране — в начало, как у стандартного лаунчера
            focusFirst()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
        registered = true
        reload()
        clockJob = scope.launch {
            while (isActive) {
                updateClock()
                delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
            }
        }
    }

    override fun onStop() {
        if (registered) {
            unregisterReceiver(packageReceiver)
            registered = false
        }
        clockJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Перечитать приложения. Экран перерисовывается, только если изменился их список или настройки вида. */
    private fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { HomeApps.load(this@HomeActivity) }.getOrDefault(emptyList())
            }
            val same = loaded.map { it.pkg } == apps.map { it.pkg }
            apps = loaded
            if (!same || !rendered || stamp != config.stamp) render()
        }
    }

    private fun render() {
        stamp = config.stamp
        rendered = true
        val keep = focusPkg
        window.setBackgroundDrawable(HomeBackgrounds.create(this, config.background))
        root.removeAllViews()
        clock = null
        date = null

        val columns = config.columns
        val gap = dp(16)
        val side = dp(48)
        val tileWidth = (resources.displayMetrics.widthPixels - 2 * side - gap * (columns - 1)) / columns
        val (dock, grid) = HomeApps.arrange(apps, config, columns)
        firstPkg = (dock.firstOrNull() ?: grid.firstOrNull())?.pkg

        val content = vertical {
            addView(header(), lp().apply { bottomMargin = dp(24) })
            if (dock.isNotEmpty()) {
                // Полоса шире сетки на свой внутренний отступ: избранное стоит ровно над колонками
                addView(dockRow(dock, tileWidth, gap), lp().apply {
                    leftMargin = -dp(16)
                    rightMargin = -dp(16)
                    bottomMargin = dp(24)
                })
            }
            grid.chunked(columns).forEach { addView(row(it, tileWidth, gap), lp().apply { bottomMargin = dp(16) }) }
            if (dock.isEmpty() && grid.isEmpty()) {
                addView(text(getString(R.string.home_no_apps), Type.BODY, Palette.TEXT_DIM))
            }
        }
        val scroll = TvScrollView(this).apply {
            addView(content)
            // Безопасная зона overscan ~5 %
            setContentPadding(side, dp(27), side, dp(27))
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        if (preview) {
            root.addView(
                chip(getString(R.string.home_preview_hint), Palette.TEXT),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) },
            )
        }
        updateClock()
        root.post {
            val target = keep?.let { root.findViewWithTag<View>(it) }
                ?: firstPkg?.let { root.findViewWithTag<View>(it) }
                ?: firstPill
            target?.requestFocus()
        }
    }

    private fun header(): View = horizontal {
        if (config.clock) {
            addView(vertical {
                clock = text("", 52f, Palette.TEXT).apply {
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    includeFontPadding = false
                }.also { addView(it) }
                date = text("", Type.SECONDARY, Palette.TEXT_DIM).also { addView(it) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        } else {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }
        val glide = pill(getString(R.string.app_name), R.drawable.ic_bolt) {
            if (preview) leavePreview() else open(Intent(this@HomeActivity, MainActivity::class.java))
        }
        firstPill = glide
        addView(glide, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(16) })
        addView(pill(getString(R.string.home_tv_settings), R.drawable.ic_settings) {
            open(Intent(Settings.ACTION_SETTINGS))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    /** Кнопка в шапке: полупрозрачная, чтобы читаться на любом фоне; в фокусе — белая, как везде в Glide TV. */
    private fun pill(label: String, iconRes: Int, onClick: () -> Unit): TextView =
        actionButton(label, iconRes = iconRes, onClick = onClick).apply {
            background = focusBackground(context, Color.argb(36, 255, 255, 255), radiusDp = 28)
        }

    private fun dockRow(apps: List<HomeApp>, tileWidth: Int, gap: Int): View = row(apps, tileWidth, gap).apply {
        val pad = dp(16)
        setPadding(pad, pad, pad, dp(8))
        val radius = dp(24).toFloat()
        background = when (config.preset.dock) {
            DockStyle.BAND -> rounded(Color.argb(26, 255, 255, 255), radius)
            DockStyle.GLASS -> rounded(Color.argb(44, 255, 255, 255), radius, Color.argb(96, 255, 255, 255), dp(1))
            DockStyle.NONE -> null
        }
    }

    private fun row(apps: List<HomeApp>, tileWidth: Int, gap: Int): LinearLayout = horizontal {
        gravity = Gravity.TOP
        apps.forEachIndexed { i, app ->
            addView(tile(app, tileWidth), LinearLayout.LayoutParams(tileWidth, WRAP).apply { if (i > 0) leftMargin = gap })
        }
    }

    private fun tile(app: HomeApp, width: Int): View {
        val height = width * 9 / 16
        val radius = dp(12).toFloat()
        val art = ImageView(this).apply {
            background = rounded(Color.argb(36, 255, 255, 255), radius)
            if (app.banner != null) {
                setImageDrawable(app.banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = height / 5
                setPadding(pad, pad, pad, pad)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Рамка фокуса — отдельный слой поверх баннера: View.foreground есть только с Android 6
        val ring = View(this).apply {
            isDuplicateParentStateEnabled = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(Color.TRANSPARENT, radius, Palette.FOCUS, dp(3)))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
        }
        val frame = FrameLayout(this).apply {
            isDuplicateParentStateEnabled = true
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val always = config.labels
        val rest = if (always) 0.75f else 0f
        val label = text(app.label, Type.CAPTION, Palette.TEXT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = rest
        }
        return vertical {
            isFocusable = true
            isClickable = true
            contentDescription = app.label
            tag = app.pkg
            addView(frame, LinearLayout.LayoutParams(width, height))
            addView(label, LinearLayout.LayoutParams(width, WRAP).apply { topMargin = dp(6) })
            setOnFocusChangeListener { v, has ->
                if (has) focusPkg = app.pkg
                val scale = if (has) 1.08f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                label.animate().alpha(if (has) 1f else rest).setDuration(150).start()
            }
            setOnClickListener { open(app.launch, app.label) }
            setOnLongClickListener { showMenu(app); true }
        }
    }

    private fun open(intent: Intent, label: String = "") {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            Toast.makeText(this, getString(R.string.home_cant_open, label), Toast.LENGTH_SHORT).show()
        }
    }

    /** Меню приложения (удержание OK или кнопка Menu): избранное, порядок, скрыть, сведения. */
    private fun showMenu(app: HomeApp) {
        val (dock, _) = HomeApps.arrange(apps, config, config.columns)
        // Пока пользователь ничего не выбирал, избранное — это автоматически собранный ряд
        val favorites = config.favorites.filter { it != HomeApps.NONE }.ifEmpty {
            if (config.favorites.isEmpty()) dock.map { it.pkg } else emptyList()
        }
        val index = favorites.indexOf(app.pkg)
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun save(list: List<String>) {
            config.favorites = list.ifEmpty { listOf(HomeApps.NONE) }
        }
        if (index >= 0) {
            if (index > 0) items += getString(R.string.home_menu_left) to { save(favorites.moved(index, index - 1)) }
            if (index < favorites.lastIndex) items += getString(R.string.home_menu_right) to { save(favorites.moved(index, index + 1)) }
            items += getString(R.string.home_menu_fav_remove) to { save(favorites - app.pkg) }
        } else {
            // В начало ряда: в ряд помещается столько приложений, сколько колонок, и добавленное должно быть видно
            items += getString(R.string.home_menu_fav_add) to { save(listOf(app.pkg) + favorites) }
        }
        items += getString(R.string.home_menu_hide) to {
            if (index >= 0) save(favorites - app.pkg)
            config.hidden = config.hidden + app.pkg
        }
        items += getString(R.string.home_menu_info) to {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)), app.label)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(app.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                items[which].second()
                if (stamp != config.stamp) render()
            }
            .show()
    }

    private fun List<String>.moved(from: Int, to: Int): List<String> =
        toMutableList().apply { add(to, removeAt(from)) }

    /**
     * Выход из предпросмотра обратно в Glide TV. Если этот экран уже назначен главным, закрывать его нельзя:
     * система тут же открыла бы его заново поверх Glide TV. Тогда просто возвращаем обычный режим.
     */
    private fun leavePreview() {
        preview = false
        open(Intent(this, MainActivity::class.java))
        val alias = ComponentName(this, HomeSwitcher.ALIAS)
        val isHome = packageManager.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (isHome) {
            if (rendered) render()
        } else {
            finish()
        }
    }

    private fun focusFirst() {
        firstPkg?.let { root.findViewWithTag<View>(it) }?.requestFocus()
    }

    private fun updateClock() {
        val now = Date()
        clock?.text = android.text.format.DateFormat.getTimeFormat(this).format(now)
        val locale = locale()
        date?.text = SimpleDateFormat("EEEE, d MMMM", locale).format(now).replaceFirstChar { it.titlecase(locale) }
    }

    @Suppress("DEPRECATION")
    private fun locale(): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) resources.configuration.locales[0]
        else resources.configuration.locale

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (preview) leavePreview() else focusFirst()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                val pkg = currentFocus?.tag as? String
                val app = apps.firstOrNull { it.pkg == pkg }
                if (app != null) {
                    showMenu(app)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_PREVIEW = "preview"
    }
}
