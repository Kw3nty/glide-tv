package app.glidetv.ui.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.glidetv.GlideApp
import app.glidetv.R
import app.glidetv.home.DockStyle
import app.glidetv.home.HomeBackground
import app.glidetv.home.HomeConfig
import app.glidetv.home.HomeLayout
import app.glidetv.home.HomeSwitcher
import app.glidetv.home.Posters
import app.glidetv.home.WatchItem
import app.glidetv.home.WatchNext
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

/** Приложение на главном экране: баннер для ТВ, обычная иконка и цвет, взятый из них. */
class HomeApp(
    val pkg: String,
    val label: String,
    val launch: Intent,
    val banner: Drawable?,
    val icon: Drawable?,
    /** Приглушённый цвет приложения: плитки «Плитки» и свечение «Кино». Белый текст на нём читается. */
    val color: Int,
    val isGame: Boolean,
) {
    /** Картинка для плитки-баннера 16:9. */
    val art: Drawable? get() = banner ?: icon
}

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
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(activity.packageName)
                val color = runCatching { tint(icon ?: banner) }.getOrDefault(NEUTRAL)
                out[activity.packageName] = HomeApp(activity.packageName, label, launch, banner, icon, color, isGame(activity.applicationInfo))
            }
        }
        return out.values.toList()
    }

    @Suppress("DEPRECATION")
    private fun isGame(info: ApplicationInfo?): Boolean {
        if (info == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.category == ApplicationInfo.CATEGORY_GAME) return true
        return info.flags and ApplicationInfo.FLAG_IS_GAME != 0
    }

    private val NEUTRAL = Color.parseColor("#3A4658")

    /**
     * Средний цвет картинки, приведённый к виду «фон под белый текст»: насыщенность и яркость в рамках.
     * Рисуем копию: у оригинала границы уже выставлены тем ImageView, в котором он показан.
     */
    private fun tint(drawable: Drawable?): Int {
        val copy = drawable?.constantState?.newDrawable()?.mutate() ?: return NEUTRAL
        val size = 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        copy.setBounds(0, 0, size, size)
        copy.draw(Canvas(bitmap))
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (p in pixels) {
            if (Color.alpha(p) < 128) continue
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
            n++
        }
        if (n == 0L) return NEUTRAL
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        // Серые и белые иконки («Настройки») цвета не дают
        if (hsv[1] < 0.12f) return NEUTRAL
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        // Потолок яркости: даже на жёлтом и голубом белый текст держит контраст около 4.5:1
        hsv[2] = hsv[2].coerceIn(0.32f, 0.5f)
        return Color.HSVToColor(hsv)
    }

    /** Делит приложения на ряд избранного и остальные. Скрытые не попадают никуда. */
    fun arrange(all: List<HomeApp>, config: HomeConfig, columns: Int, self: String): Pair<List<HomeApp>, List<HomeApp>> {
        // Сам Glide TV скрыть нельзя: его плитка — дорога к настройкам экрана и к «Вернуть как было»
        val hidden = config.hidden - self
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
            HomeBackground.GRAPHITE -> linear(GradientDrawable.Orientation.TOP_BOTTOM, "#232832", "#12151B", "#0B0D11")
            HomeBackground.AURORA -> LayerDrawable(arrayOf(
                ColorDrawable(Color.parseColor("#070A14")),
                glow(Color.parseColor("#5500E5A0"), 0.18f, 0.2f, width * 0.55f),
                glow(Color.parseColor("#554B6BFF"), 0.82f, 0.15f, width * 0.5f),
                glow(Color.parseColor("#44B14BFF"), 0.6f, 0.95f, width * 0.5f),
            ))
            HomeBackground.SUNSET -> linear(GradientDrawable.Orientation.TOP_BOTTOM, "#120A26", "#3E163C", "#8A3329")
            HomeBackground.OCEAN -> linear(GradientDrawable.Orientation.TL_BR, "#03101C", "#062B44", "#0A5A62")
        }
    }

    fun glow(color: Int, x: Float, y: Float, radius: Float) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(color, Color.TRANSPARENT)
        setGradientCenter(x, y)
        gradientRadius = radius
    }

    private fun linear(orientation: GradientDrawable.Orientation, vararg colors: String) =
        GradientDrawable(orientation, colors.map { Color.parseColor(it) }.toIntArray())
}

/**
 * Главный экран ТВ от Glide TV. Без сети, фоновых служб и рекомендаций.
 * Раскладку задаёт пресет ([HomeLayout]), остальное — [HomeConfig]:
 *
 * - GRID — часы, полоса избранного и сетка («Док», «Чёрный OLED», «Стекло»);
 * - SHELF — название приложения крупно, свечение в его цвете и одна лента внизу («Кино»);
 * - LIST — часы и список названий без иконок («Дзен»);
 * - TILES — плоские цветные плитки с названием внутри («Плитка»);
 * - ROWS — горизонтальные ряды с заголовками («Ряды»).
 *
 * Открывается двумя способами: как Home (через псевдоним в манифесте) и как предпросмотр из Glide TV.
 * В предпросмотре «Назад» возвращает в Glide TV, в режиме Home — переводит фокус в начало.
 */
class HomeActivity : Activity() {
    private lateinit var config: HomeConfig
    private lateinit var root: HomeRoot
    private val scope = MainScope()

    private var apps: List<HomeApp> = emptyList()
    private var watch: List<WatchItem> = emptyList()
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

    // «Кино»: название приложения в фокусе и свечение в его цвете
    private var shelfTitle: TextView? = null
    private var shelfGlow: GradientDrawable? = null
    private var shelfColor = Color.TRANSPARENT
    private var glowAnimator: ValueAnimator? = null

    // «Дзен»: список прокручивается так, чтобы строка в фокусе стояла по центру
    private var listScroll: ScrollView? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    /**
     * Влево/вправо фокус не перескакивает в другой ряд: с последнего приложения в ряду он остаётся на месте.
     * В «Дзене» рядов нет (слева кнопки, справа список), там правило выключено.
     */
    private class HomeRoot(context: Context) : FrameLayout(context) {
        var rowLock = true

        override fun focusSearch(focused: View?, direction: Int): View? {
            val next = super.focusSearch(focused, direction)
            if (focused == null || next == null || next === focused) return next
            val sideways = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
            return when {
                rowLock -> if (sideways && !sameRow(focused, next)) focused else next
                // «Дзен»: вверх/вниз фокус остаётся в своей колонке — с конца списка не прыгает на кнопки слева
                !sideways && !sameColumn(focused, next) -> focused
                else -> next
            }
        }

        private fun sameColumn(a: View, b: View): Boolean {
            val pa = IntArray(2).also { a.getLocationOnScreen(it) }
            val pb = IntArray(2).also { b.getLocationOnScreen(it) }
            return pa[0] < pb[0] + b.width && pb[0] < pa[0] + a.width
        }

        private fun sameRow(a: View, b: View): Boolean {
            val pa = IntArray(2).also { a.getLocationOnScreen(it) }
            val pb = IntArray(2).also { b.getLocationOnScreen(it) }
            return pa[1] < pb[1] + b.height && pb[1] < pa[1] + a.height
        }
    }

    /**
     * Горизонтальная лента. Обычный HorizontalScrollView ставит элемент в фокусе вплотную к краю экрана,
     * и его увеличенная рамка срезается. Здесь у края всегда остаётся поле [inset].
     */
    private class RowScroll(context: Context, private val inset: Int) : HorizontalScrollView(context) {
        init {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
            val wide = Rect(rect.left - inset, rect.top, rect.right + inset, rect.bottom)
            return super.computeScrollDeltaToGetChildRectOnScreen(wide)
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
        glowAnimator?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** Перечитать приложения. Экран перерисовывается, только если изменился их список или настройки вида. */
    private fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch {
            val wantWatch = config.preset.layout == HomeLayout.SHELF && config.watchNext
            val (loaded, rows) = withContext(Dispatchers.IO) {
                val list = runCatching { HomeApps.load(this@HomeActivity) }.getOrDefault(emptyList())
                list to (if (wantWatch) WatchNext.read(this@HomeActivity) else emptyList())
            }
            val allowed = loaded.map { it.pkg }.toSet() - config.hidden
            val picked = WatchNext.pick(rows, System.currentTimeMillis(), allowed, packageName)
            val same = loaded.map { it.pkg } == apps.map { it.pkg } && picked == watch
            apps = loaded
            watch = picked
            if (!same || !rendered || stamp != config.stamp) render()
        }
    }

    // ───────────────────────────── Сборка экрана ─────────────────────────────

    private val side get() = dp(48)
    private val gap get() = dp(16)

    private fun tileWidth(columns: Int) =
        (resources.displayMetrics.widthPixels - 2 * side - gap * (columns - 1)) / columns

    private fun render() {
        stamp = config.stamp
        rendered = true
        val keep = focusPkg
        window.setBackgroundDrawable(HomeBackgrounds.create(this, config.background))
        root.removeAllViews()
        clock = null
        date = null
        firstPill = null
        shelfTitle = null
        shelfGlow = null
        listScroll = null
        glowAnimator?.cancel()

        val layout = config.preset.layout
        val columns = config.columns
        val (dock, rest) = HomeApps.arrange(apps, config, columns, packageName)
        firstPkg = (dock.firstOrNull() ?: rest.firstOrNull())?.pkg
        root.rowLock = layout != HomeLayout.LIST

        val screen = when {
            dock.isEmpty() && rest.isEmpty() -> emptyScreen()
            layout == HomeLayout.GRID -> gridScreen(dock, rest, columns)
            layout == HomeLayout.SHELF -> shelfScreen(dock + rest, columns)
            layout == HomeLayout.LIST -> listScreen(dock + rest)
            layout == HomeLayout.TILES -> tilesScreen(dock + rest, columns)
            else -> rowsScreen(dock, rest, columns)
        }
        root.addView(screen, FrameLayout.LayoutParams(MATCH, MATCH))
        if (preview) {
            // Подсказка стоит там, где в раскладке пусто: в «Дзене» по центру снизу идёт список,
            // в «Рядах» слева снизу — кнопки настроек.
            val place = when (layout) {
                HomeLayout.LIST -> Gravity.START
                HomeLayout.ROWS -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            root.addView(
                chip(getString(R.string.home_preview_hint), Palette.TEXT),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or place).apply {
                    bottomMargin = dp(20)
                    leftMargin = side
                    rightMargin = side
                },
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

    /** Вертикальная прокрутка экрана с безопасной зоной overscan ~5 %. */
    private fun scrolling(content: LinearLayout): View = TvScrollView(this).apply {
        addView(content)
        setContentPadding(side, dp(27), side, dp(27))
    }

    private fun emptyScreen(): View = scrolling(vertical {
        addView(header(), lp().apply { bottomMargin = dp(24) })
        addView(text(getString(R.string.home_no_apps), Type.BODY, Palette.TEXT_DIM))
    })

    /** «Док», «Чёрный OLED», «Стекло»: часы, полоса избранного, сетка. */
    private fun gridScreen(dock: List<HomeApp>, rest: List<HomeApp>, columns: Int): View {
        val width = tileWidth(columns)
        return scrolling(vertical {
            addView(header(), lp().apply { bottomMargin = dp(24) })
            if (dock.isNotEmpty()) {
                // Полоса шире сетки на свой внутренний отступ: избранное стоит ровно над колонками
                addView(dockRow(dock, width), lp().apply {
                    leftMargin = -dp(16)
                    rightMargin = -dp(16)
                    bottomMargin = dp(24)
                })
            }
            rest.chunked(columns).forEach { addView(row(it) { app -> bannerTile(app, width) }, lp().apply { bottomMargin = dp(16) }) }
        })
    }

    /** «Плитка»: плоские цветные плитки, название внутри. По мотивам Retro Metro. */
    private fun tilesScreen(all: List<HomeApp>, columns: Int): View {
        val width = tileWidth(columns)
        return scrolling(vertical {
            addView(header(), lp().apply { bottomMargin = dp(24) })
            all.chunked(columns).forEach { addView(row(it) { app -> colorTile(app, width) }, lp().apply { bottomMargin = dp(16) }) }
        })
    }

    /** «Ряды»: избранное, приложения, игры — каждый ряд листается вбок. По мотивам Leanback Launcher. */
    private fun rowsScreen(dock: List<HomeApp>, rest: List<HomeApp>, columns: Int): View {
        val width = tileWidth(columns)
        val games = rest.filter { it.isGame }
        val others = rest.filter { !it.isGame }
        return scrolling(vertical {
            if (config.clock) {
                addView(horizontal {
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    clock = text("", Type.TITLE, Palette.TEXT).apply {
                        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    }.also { addView(it) }
                }, lp().apply { bottomMargin = dp(8) })
            }
            fun section(title: Int, body: View) {
                addView(vertical {
                    addView(text(getString(title), Type.SECTION, Palette.TEXT_DIM), lp().apply { bottomMargin = dp(4) })
                    // Лента идёт от края до края экрана, хотя заголовок стоит в безопасной зоне
                    addView(body, lp().apply {
                        leftMargin = -side
                        rightMargin = -side
                    })
                }, lp().apply { bottomMargin = dp(20) })
            }
            if (dock.isNotEmpty()) section(R.string.home_row_favorites, ribbon(dock) { app -> bannerTile(app, width) })
            if (others.isNotEmpty()) section(R.string.home_row_apps, ribbon(others) { app -> bannerTile(app, width) })
            if (games.isNotEmpty()) section(R.string.home_row_games, ribbon(games) { app -> bannerTile(app, width) })
            section(R.string.home_row_settings, horizontal {
                setPadding(side, dp(12), side, dp(12))
                pills(this)
            })
        })
    }

    /** «Кино»: название приложения крупно, свечение в его цвете, одна лента внизу. По мотивам верхней полки CleanYourTV. */
    private fun shelfScreen(all: List<HomeApp>, columns: Int): View {
        val width = tileWidth(columns)
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        shelfColor = Color.TRANSPARENT
        val glow = HomeBackgrounds.glow(Color.TRANSPARENT, 0.3f, 0.35f, screenWidth * 0.75f)
        shelfGlow = glow
        return FrameLayout(this).apply {
            clipChildren = false
            addView(View(context).apply { background = glow }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(vertical {
                setPadding(side, dp(27), side, dp(27))
                addView(header())
                addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
                shelfTitle = text("", 56f, Palette.TEXT).apply {
                    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                }.also { addView(it, lp().apply { bottomMargin = dp(if (watch.isEmpty()) 20 else 12) }) }
                if (watch.isNotEmpty()) {
                    // «Продолжить просмотр»: карточки крупнее баннеров приложений — это главное на экране
                    val cardWidth = tileWidth(5)
                    addView(text(getString(R.string.home_watch_title), Type.CAPTION, Palette.TEXT_DIM))
                    addView(ribbon(watch) { item -> watchCard(item, cardWidth) }, lp().apply {
                        leftMargin = -side
                        rightMargin = -side
                    })
                }
                addView(ribbon(all) { app -> bannerTile(app, width, labelled = false) }, lp().apply {
                    leftMargin = -side
                    rightMargin = -side
                    bottomMargin = dp(36)
                })
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    /** «Дзен»: только часы и список названий, без иконок. По мотивам Zen list. */
    private fun listScreen(all: List<HomeApp>): View = horizontal {
        setPadding(side, dp(27), side, dp(27))
        addView(vertical {
            gravity = Gravity.CENTER_VERTICAL
            if (config.clock) {
                // «12:45 PM» длиннее «12:45»: в 12-часовом формате шрифт меньше, иначе время не влезает в колонку
                val size = if (android.text.format.DateFormat.is24HourFormat(context)) 96f else 72f
                clock = text("", size, Palette.TEXT).apply {
                    typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                }.also { addView(it) }
                date = text("", Type.SECTION, Palette.TEXT_DIM).also { addView(it, lp().apply { bottomMargin = dp(40) }) }
            }
            addView(horizontal { pills(this) })
        }, LinearLayout.LayoutParams(0, MATCH, 1f))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(96))
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            addView(vertical {
                // Поля сверху и снизу: первая и последняя строки тоже могут встать по центру
                val edge = resources.displayMetrics.heightPixels / 2 - dp(60)
                setPadding(dp(24), edge, 0, edge)
                all.forEach { addView(listItem(it)) }
            })
        }
        listScroll = scroll
        addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
    }

    // ───────────────────────────── Части экрана ─────────────────────────────

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
        pills(this)
    }

    /**
     * Две кнопки, которые есть в любой раскладке: сеть Wi-Fi и настройки ТВ.
     * Сам Glide TV открывается своей плиткой среди приложений (скрыть её нельзя).
     */
    private fun pills(parent: LinearLayout) {
        val wifi = pill(getString(R.string.home_wifi), R.drawable.ic_wifi) { openWifi() }
        firstPill = wifi
        parent.addView(wifi, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(16) })
        parent.addView(pill(getString(R.string.home_tv_settings), R.drawable.ic_settings) {
            open(Intent(Settings.ACTION_SETTINGS))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    /** Кнопка шапки: полупрозрачная, чтобы читаться на любом фоне; в фокусе — белая, как везде в Glide TV. */
    private fun pill(label: String, iconRes: Int, onClick: () -> Unit): TextView =
        actionButton(label, iconRes = iconRes, onClick = onClick).apply {
            background = focusBackground(context, Color.argb(36, 255, 255, 255), radiusDp = 28)
        }

    private fun dockRow(apps: List<HomeApp>, tileWidth: Int): View = row(apps) { app -> bannerTile(app, tileWidth) }.apply {
        val pad = dp(16)
        setPadding(pad, pad, pad, dp(8))
        val radius = dp(24).toFloat()
        background = when (config.preset.dock) {
            DockStyle.BAND -> rounded(Color.argb(26, 255, 255, 255), radius)
            DockStyle.GLASS -> rounded(Color.argb(44, 255, 255, 255), radius, Color.argb(96, 255, 255, 255), dp(1))
            DockStyle.NONE -> null
        }
    }

    private fun <T> row(items: List<T>, tile: (T) -> View): LinearLayout = horizontal {
        gravity = Gravity.TOP
        items.forEachIndexed { i, item ->
            val view = tile(item)
            // Плитка без подписи задаёт свой размер сама; остальные — по содержимому
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(WRAP, WRAP)
            if (i > 0) params.leftMargin = gap
            addView(view, params)
        }
    }

    /** Лента от края до края экрана, листается вбок. */
    private fun <T> ribbon(items: List<T>, tile: (T) -> View): View = RowScroll(this, side).apply {
        addView(row(items, tile).apply { setPadding(side, dp(12), side, dp(12)) })
    }

    /**
     * Карточка «Продолжить просмотр»: обложка, полоска просмотренного, рамка фокуса. Пока обложка грузится
     * (или её нет) — название текстом. Название и серия крупно показываются на полке, когда карточка в фокусе.
     */
    private fun watchCard(item: WatchItem, width: Int): View {
        val height = width * 9 / 16
        val radius = dp(12).toFloat()
        val app = apps.firstOrNull { it.pkg == item.pkg }
        val shelfText = if (item.subtitle.isEmpty()) item.title else "${item.title} · ${item.subtitle}"
        val art = ImageView(this).apply {
            background = rounded(Color.argb(36, 255, 255, 255), radius)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = roundedOutline(radius)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val fallback = text(item.title, Type.CAPTION, Palette.TEXT).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        val card = FrameLayout(this).apply {
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fallback, FrameLayout.LayoutParams(MATCH, MATCH))
            if (item.progress >= 0f) {
                // Полоска не только цветом: длина заполненной части и есть доля просмотренного
                addView(horizontal {
                    background = rounded(Color.argb(150, 0, 0, 0), dp(2).toFloat())
                    addView(View(context).apply { background = rounded(Palette.ACCENT, dp(2).toFloat()) },
                        LinearLayout.LayoutParams(0, MATCH, item.progress.coerceAtLeast(0.02f)))
                    addView(View(context), LinearLayout.LayoutParams(0, MATCH, 1f - item.progress.coerceAtLeast(0.02f)))
                }, FrameLayout.LayoutParams(MATCH, dp(4), Gravity.BOTTOM).apply {
                    leftMargin = dp(10)
                    rightMargin = dp(10)
                    bottomMargin = dp(8)
                })
            }
            addView(focusRing(radius), FrameLayout.LayoutParams(MATCH, MATCH))
            isFocusable = true
            isClickable = true
            contentDescription = listOf(shelfText, app?.label.orEmpty()).filter { it.isNotEmpty() }.joinToString(". ")
            tag = "watch:${item.id}"
            setOnFocusChangeListener { v, has ->
                if (has) {
                    focusPkg = "watch:${item.id}"
                    showOnShelf(shelfText, app?.color ?: Color.parseColor("#3A4658"))
                }
                val s = if (has) 1.08f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(150).start()
            }
            setOnClickListener { openWatch(item, app) }
            layoutParams = LinearLayout.LayoutParams(width, height)
        }
        val uri = item.poster ?: return card
        fun show(bitmap: android.graphics.Bitmap) {
            art.setImageBitmap(bitmap)
            fallback.visibility = View.GONE
        }
        val ready = Posters.cached(uri)
        if (ready != null) {
            show(ready)
        } else {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { Posters.load(this@HomeActivity, uri, width) }
                if (bitmap != null) show(bitmap)
            }
        }
        return card
    }

    /** Открыть то, что приложение просило открыть; не вышло — само приложение. */
    private fun openWatch(item: WatchItem, app: HomeApp?) {
        val deep = WatchNext.intentOf(item, packageName)
        if (deep != null && runCatching { startActivity(deep) }.isSuccess) return
        if (app != null) open(app.launch, app.label)
        else Toast.makeText(this, getString(R.string.home_cant_open, item.title), Toast.LENGTH_SHORT).show()
    }

    private fun roundedOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    /** Рамка фокуса — отдельный слой поверх картинки: View.foreground есть только с Android 6. */
    private fun focusRing(radius: Float) = View(this).apply {
        isDuplicateParentStateEnabled = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.TRANSPARENT, radius, Palette.FOCUS, dp(3)))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    /** Общее для любого элемента-приложения: фокус, запуск, меню. */
    private fun bind(view: View, app: HomeApp, scale: Float, onFocus: (Boolean) -> Unit = {}) {
        view.isFocusable = true
        view.isClickable = true
        view.contentDescription = app.label
        view.tag = app.pkg
        view.setOnFocusChangeListener { v, has ->
            if (has) {
                focusPkg = app.pkg
                showOnShelf(app.label, app.color)
            }
            val s = if (has) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(150).start()
            onFocus(has)
        }
        view.setOnClickListener { open(app.launch, app.label) }
        view.setOnLongClickListener { showMenu(app); true }
    }

    /** Баннер 16:9 с подписью под ним. */
    private fun bannerTile(app: HomeApp, width: Int, labelled: Boolean = true): View {
        val height = width * 9 / 16
        val radius = dp(12).toFloat()
        val art = ImageView(this).apply {
            background = rounded(Color.argb(36, 255, 255, 255), radius)
            setImageDrawable(app.art)
            if (app.banner != null) {
                scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = height / 5
                setPadding(pad, pad, pad, pad)
            }
            outlineProvider = roundedOutline(radius)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val frame = FrameLayout(this).apply {
            isDuplicateParentStateEnabled = true
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(focusRing(radius), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        if (!labelled) {
            // В «Кино» название показывает полка над лентой
            bind(frame, app, 1.08f)
            frame.isDuplicateParentStateEnabled = false
            frame.layoutParams = LinearLayout.LayoutParams(width, height)
            return frame
        }
        val rest = if (config.labels) 0.75f else 0f
        val label = text(app.label, Type.CAPTION, Palette.TEXT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = rest
        }
        return vertical {
            addView(frame, LinearLayout.LayoutParams(width, height))
            addView(label, LinearLayout.LayoutParams(width, WRAP).apply { topMargin = dp(6) })
            bind(this, app, 1.08f) { has -> label.animate().alpha(if (has) 1f else rest).setDuration(150).start() }
        }
    }

    /** Плоская плитка в цвете приложения: иконка сверху, название снизу. */
    private fun colorTile(app: HomeApp, width: Int): View {
        val height = width * 3 / 5
        val radius = dp(4).toFloat()
        val pad = dp(14)
        return FrameLayout(this).apply {
            background = rounded(app.color, radius)
            addView(ImageView(context).apply {
                setImageDrawable(app.icon ?: app.banner)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
                leftMargin = pad
                topMargin = pad
            })
            addView(text(app.label, Type.SECONDARY, Color.WHITE, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                leftMargin = pad
                rightMargin = pad
                bottomMargin = dp(10)
            })
            addView(focusRing(radius), FrameLayout.LayoutParams(MATCH, MATCH))
            bind(this, app, 1.06f)
            layoutParams = LinearLayout.LayoutParams(width, height)
        }
    }

    /** Строка «Дзена»: только название. Фокус — белый цвет, увеличение и метка слева (не одним цветом). */
    private fun listItem(app: HomeApp): View {
        val marker = View(this).apply {
            background = rounded(Palette.ACCENT, dp(2).toFloat())
            alpha = 0f
        }
        val name = text(app.label, 30f, Palette.TEXT_DIM).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        return horizontal {
            setPadding(0, dp(10), 0, dp(10))
            addView(marker, LinearLayout.LayoutParams(dp(4), dp(28)).apply { rightMargin = dp(16) })
            addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
            bind(this, app, 1.15f) { has ->
                name.setTextColor(if (has) Palette.TEXT else Palette.TEXT_DIM)
                marker.animate().alpha(if (has) 1f else 0f).setDuration(150).start()
                if (has) centerInList(this)
            }
            // Увеличение от левого края: строки растут вправо, а не расползаются в обе стороны
            pivotX = 0f
            post { pivotY = height / 2f }
        }
    }

    private fun centerInList(item: View) {
        val scroll = listScroll ?: return
        scroll.post { scroll.smoothScrollTo(0, item.top + item.height / 2 - scroll.height / 2) }
    }

    /** «Кино»: показать то, что в фокусе, на полке — название и свечение в цвете приложения. */
    private fun showOnShelf(label: String, color: Int) {
        val title = shelfTitle ?: return
        val glow = shelfGlow ?: return
        title.text = label
        val target = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofArgb(shelfColor, target).apply {
            duration = 350
            addUpdateListener {
                shelfColor = it.animatedValue as Int
                glow.colors = intArrayOf(shelfColor, Color.TRANSPARENT)
            }
            start()
        }
    }

    // ───────────────────────────── Действия ─────────────────────────────

    /** Экран сети: на ТВ это «Сеть и Интернет». Нет такого экрана в прошивке — открываем обычные настройки. */
    private fun openWifi() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        val target = if (wifi.resolveActivity(packageManager) != null) wifi else Intent(Settings.ACTION_SETTINGS)
        open(target, getString(R.string.home_wifi))
    }

    private fun open(intent: Intent, label: String = "") {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            Toast.makeText(this, getString(R.string.home_cant_open, label), Toast.LENGTH_SHORT).show()
        }
    }

    /** Меню приложения (удержание OK или кнопка Menu): избранное, порядок, скрыть, сведения. */
    private fun showMenu(app: HomeApp) {
        val (dock, _) = HomeApps.arrange(apps, config, config.columns, packageName)
        // Пока пользователь ничего не выбирал, избранное — это автоматически собранный ряд
        val favorites = config.favorites.filter { it != HomeApps.NONE }.ifEmpty {
            if (config.favorites.isEmpty()) dock.map { it.pkg } else emptyList()
        }
        val index = favorites.indexOf(app.pkg)
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun save(list: List<String>) {
            config.favorites = list.ifEmpty { listOf(HomeApps.NONE) }
        }
        // В списке избранное идёт сверху вниз, в остальных раскладках — слева направо
        val vertical = config.preset.layout == HomeLayout.LIST
        val earlier = getString(if (vertical) R.string.home_menu_up else R.string.home_menu_left)
        val later = getString(if (vertical) R.string.home_menu_down else R.string.home_menu_right)
        if (index >= 0) {
            if (index > 0) items += earlier to { save(favorites.moved(index, index - 1)) }
            if (index < favorites.lastIndex) items += later to { save(favorites.moved(index, index + 1)) }
            items += getString(R.string.home_menu_fav_remove) to { save(favorites - app.pkg) }
        } else {
            // В начало ряда: в ряд помещается столько приложений, сколько колонок, и добавленное должно быть видно
            items += getString(R.string.home_menu_fav_add) to { save(listOf(app.pkg) + favorites) }
        }
        if (app.pkg != packageName) {
            items += getString(R.string.home_menu_hide) to {
                if (index >= 0) save(favorites - app.pkg)
                config.hidden = config.hidden + app.pkg
            }
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
