package app.glidetv.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Дизайн-токены Glide TV: тёмно-синий + циан (как на баннере).
 * Контраст проверен: TEXT ≥ 11:1, TEXT_DIM ≥ 6.8:1, статусные цвета ≥ 4.6:1 на любой поверхности.
 * Фокус — белая заливка с тёмным текстом (17.9:1): на ТВ фокус — главный способ навигации.
 */
object Palette {
    val BG = Color.parseColor("#0A1630")
    val BG_DEEP = Color.parseColor("#071027")
    val CARD = Color.parseColor("#12244A")
    val CARD_FOCUS = Color.parseColor("#1B3366")
    /** Границы и разделители, которые несут смысл (≥ 3:1 к фону). */
    val OUTLINE = Color.parseColor("#4A6699")
    val ACCENT = Color.parseColor("#3EE6FF")
    val ON_ACCENT = Color.parseColor("#04132B")
    val ACCENT_2 = Color.parseColor("#8B9BFF")
    val TEXT = Color.parseColor("#F5F8FF")
    val TEXT_DIM = Color.parseColor("#AFC1E3")
    val FOCUS = Color.WHITE
    val ON_FOCUS = Color.parseColor("#0A1630")
    val OK = Color.parseColor("#4ADE80")
    val WARN = Color.parseColor("#FBBF24")
    val ERR = Color.parseColor("#FB7185")
}

/** Шкала размеров для экрана в 3 метрах: основной текст не меньше 16sp. */
object Type {
    const val DISPLAY = 34f
    const val TITLE = 28f
    const val SECTION = 21f
    const val BODY = 18f
    const val SECONDARY = 16f
    const val CAPTION = 14f
}

/** Тона статуса: цвет всегда дублируется иконкой и текстом. */
enum class Tone(val color: Int, val icon: Int) {
    INFO(Palette.TEXT_DIM, app.glidetv.R.drawable.ic_info),
    OK(Palette.OK, app.glidetv.R.drawable.ic_check),
    WARN(Palette.WARN, app.glidetv.R.drawable.ic_alert),
    ERROR(Palette.ERR, app.glidetv.R.drawable.ic_error),
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = Color.TRANSPARENT, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

private val FOCUSED = intArrayOf(android.R.attr.state_focused)
private val PRESSED = intArrayOf(android.R.attr.state_pressed)
private val SELECTED = intArrayOf(android.R.attr.state_selected)
private val DISABLED_FOCUSED = intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_focused)
private val DISABLED = intArrayOf(-android.R.attr.state_enabled)

/** Фон с явными состояниями: фокус — белая заливка, выключено — приглушённо (но фокус всё равно виден). */
fun focusBackground(context: Context, normal: Int, focused: Int = Palette.FOCUS, radiusDp: Int = 14,
                    selected: Int = Palette.CARD_FOCUS): Drawable {
    val r = context.dp(radiusDp).toFloat()
    return StateListDrawable().apply {
        // Выключено: только контур — отличается от обычной кнопки не одним цветом
        addState(DISABLED_FOCUSED, rounded(Color.TRANSPARENT, r, Palette.FOCUS, context.dp(2)))
        addState(DISABLED, rounded(Color.TRANSPARENT, r, Palette.OUTLINE, context.dp(2)))
        addState(FOCUSED, rounded(focused, r))
        addState(PRESSED, rounded(focused, r))
        addState(SELECTED, rounded(selected, r))
        addState(intArrayOf(), rounded(normal, r))
    }
}

/** Цвет текста/иконки поверх [focusBackground]. */
fun onFocusColors(normal: Int, focused: Int = Palette.ON_FOCUS, selected: Int = normal): ColorStateList =
    ColorStateList(
        arrayOf(DISABLED_FOCUSED, DISABLED, FOCUSED, PRESSED, SELECTED, intArrayOf()),
        intArrayOf(Palette.TEXT_DIM, Palette.TEXT_DIM, focused, focused, selected, normal),
    )

/** Лёгкое увеличение элемента в фокусе (стандартное поведение ТВ-интерфейсов, 150 мс). */
fun View.scaleOnFocus(scale: Float = 1.04f) {
    val previous = onFocusChangeListener
    setOnFocusChangeListener { v, has ->
        previous?.onFocusChange(v, has)
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(150).start()
    }
}

fun Context.text(
    value: CharSequence = "",
    sizeSp: Float = Type.BODY,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
): TextView = TextView(this).apply { style(value, sizeSp, color, bold) }

private fun TextView.style(value: CharSequence, sizeSp: Float, color: Int, bold: Boolean) {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    setLineSpacing(0f, 1.15f)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/** Векторная иконка с тонировкой; [colors] может быть списком состояний (фокус и т. п.). */
fun Context.icon(res: Int, colors: ColorStateList): Drawable =
    resources.getDrawable(res, theme).mutate().apply { setTintList(colors) }

fun Context.iconView(res: Int, color: Int, sizeDp: Int = 24): ImageView = ImageView(this).apply {
    setImageDrawable(icon(res, ColorStateList.valueOf(color)))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    // Иконки рядом с текстом — декоративные: экранный диктор их пропускает
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/**
 * Кнопка, которая в выключенном состоянии сохраняет фокус.
 *
 * Начиная с Android 9 система снимает фокус с view, которому сделали setEnabled(false), и отдаёт его
 * первому элементу окна: пользователь нажимает «Оптимизировать», а фокус оказывается в меню на «Главная».
 * Поэтому «выключено» здесь — собственное состояние: для системы кнопка остаётся включённой и держит
 * фокус, а нажатие игнорируется и из состояния для фона/цвета убирается state_enabled.
 */
private class ActionButton(context: Context) : TextView(context) {
    // Именно «выключено», а не «включено»: методы ниже вызываются и из конструктора View, когда поле
    // ещё не инициализировано и равно false — это должно означать обычное состояние
    private var inactive = false

    override fun setEnabled(enabled: Boolean) {
        if (inactive == !enabled) return
        inactive = !enabled
        refreshDrawableState()
    }

    override fun isEnabled(): Boolean = !inactive

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val state = super.onCreateDrawableState(extraSpace)
        if (!inactive) return state
        return state.filter { it != android.R.attr.state_enabled }.toIntArray()
    }

    override fun performClick(): Boolean = if (inactive) false else super.performClick()
}

/**
 * Кнопка для пульта: минимум 56dp по высоте, иконка + подпись, белая заливка в фокусе.
 * Во время операции кнопку выключаем через [TextView.setEnabled] — фокус остаётся, нажатие игнорируется
 * (см. [ActionButton]).
 */
fun Context.actionButton(label: CharSequence, primary: Boolean = false, iconRes: Int? = null,
                         onClick: () -> Unit): TextView =
    ActionButton(this).apply {
        style(label, Type.BODY, Palette.TEXT, bold = true)
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        minHeight = dp(56)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(if (iconRes != null) 20 else 26), dp(12), dp(26), dp(12))
        val colors = onFocusColors(if (primary) Palette.ON_ACCENT else Palette.TEXT)
        // Вторичная кнопка светлее карточки, чтобы не сливаться с ней
        background = focusBackground(context, if (primary) Palette.ACCENT else Palette.CARD_FOCUS)
        setTextColor(colors)
        if (iconRes != null) {
            val d = icon(iconRes, colors).apply { setBounds(0, 0, dp(24), dp(24)) }
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = dp(12)
        }
        setOnClickListener { onClick() }
        scaleOnFocus()
    }

fun Context.vertical(paddingDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        // Элемент в фокусе увеличивается (scaleOnFocus) — родитель не должен обрезать его края.
        // clipToPadding тоже: иначе кнопка у края карточки срезается по её внутреннему отступу
        clipChildren = false
        clipToPadding = false
        block()
    }

fun Context.horizontal(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        block()
    }

fun Context.card(focusable: Boolean = false, paddingDp: Int = 24, block: LinearLayout.() -> Unit = {}): LinearLayout =
    vertical(paddingDp) {
        if (focusable) {
            background = focusBackground(context, Palette.CARD, Palette.CARD_FOCUS, 20)
            // У карточки в фокусе заливка не белая (внутри много текста), поэтому добавляем рамку
            foregroundFocusRing(20)
            isFocusable = true
            isClickable = true
            scaleOnFocus(1.03f)
        } else {
            background = rounded(Palette.CARD, dp(20).toFloat())
        }
        block()
    }

/** Белая рамка 3dp поверх содержимого в фокусе (для карточек). */
private fun View.foregroundFocusRing(radiusDp: Int) {
    val ring = StateListDrawable().apply {
        addState(FOCUSED, rounded(Color.TRANSPARENT, dp(radiusDp).toFloat(), Palette.FOCUS, dp(3)))
        addState(intArrayOf(), rounded(Color.TRANSPARENT, dp(radiusDp).toFloat()))
    }
    // View.foreground есть с API 23; на старых ТВ рамку рисует фон
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        foreground = ring
    } else {
        background = StateListDrawable().apply {
            addState(FOCUSED, rounded(Palette.CARD_FOCUS, dp(radiusDp).toFloat(), Palette.FOCUS, dp(3)))
            addState(intArrayOf(), rounded(Palette.CARD, dp(radiusDp).toFloat()))
        }
    }
}

/** Небольшая плашка-метка (статус, размер, «рекомендуется»). */
fun Context.chip(label: CharSequence = "", color: Int = Palette.TEXT_DIM): TextView =
    text(label, Type.CAPTION, color, bold = true).apply { setChipColor(color) }

fun TextView.setChipColor(color: Int) {
    setTextColor(color)
    // Полупрозрачная подложка того же тона: метка читается и без цвета (есть текст)
    background = rounded(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), dp(8).toFloat())
    setPadding(dp(10), dp(4), dp(10), dp(4))
}

/** Заголовок экрана + подзаголовок; [gapDp] — отступ до содержимого. */
fun LinearLayout.screenHeader(title: String, subtitle: String? = null, gapDp: Int = 24): TextView? {
    addSpaced(context.text(title, Type.TITLE, bold = true), if (subtitle != null) 4 else gapDp)
    return subtitle?.let { addSpaced(context.text(it, Type.SECONDARY, Palette.TEXT_DIM), gapDp) as TextView }
}

/**
 * Прокрутка экрана для пульта.
 *
 * - Не обрезает детей: кнопка или карточка в фокусе увеличивается ([scaleOnFocus]) и рисует рамку
 *   за своими границами. Обычный ScrollView срезает всё по краям своего единственного ребёнка.
 * - Показывает раздел целиком: фокус на кнопке внутри карточки прокручивает экран так, чтобы была видна
 *   вся карточка (заголовок и пояснение), а не только кнопка.
 * - Листает по разделам: если элемент виден при прокрутке в самое начало — показываем начало (заголовок
 *   экрана не срезается), иначе верхний край ставится на промежуток между разделами. Текст и карточки
 *   не режутся сверху посередине. Чтобы это работало и в самом конце, под содержимым добавляется пустое
 *   место ([tail]): последняя «страница» тоже начинается с границы раздела.
 * - Если содержимое не помещается, край плавно затухает: видно, что экран листается. Длина затухания
 *   равна промежутку между разделами (16dp), поэтому сверху в него попадает только пустое место.
 *
 * Отступы экрана задаются содержимому ([setContentPadding]), а не самому ScrollView: затухание края
 * рисуется внутри отступов ScrollView, и при прокрутке над ним оставалась бы полоска незатухшего содержимого.
 */
class TvScrollView(context: Context) : ScrollView(context) {
    private var bottomPadding = 0
    private var tail = 0

    init {
        isVerticalScrollBarEnabled = false
        clipChildren = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(16))
    }

    fun setContentPadding(left: Int, top: Int, right: Int, bottom: Int) {
        bottomPadding = bottom
        (getChildAt(0) as? ViewGroup)?.let {
            it.setPadding(left, top, right, bottom + tail)
            it.clipToPadding = false
        }
    }

    /**
     * Положения прокрутки, в которых верхний край приходится на промежуток перед разделом
     * (прямым потомком содержимого). Промежуток должен быть не меньше затухания, иначе под него
     * попал бы предыдущий раздел.
     */
    private fun sectionStops(content: ViewGroup): List<Int> {
        val fade = verticalFadingEdgeLength
        val stops = ArrayList<Int>(content.childCount)
        var previousBottom = Int.MIN_VALUE
        for (i in 0 until content.childCount) {
            val section = content.getChildAt(i)
            if (section.visibility != View.VISIBLE) continue
            if (previousBottom != Int.MIN_VALUE && section.top - previousBottom >= fade) {
                stops += content.top + section.top - fade
            }
            previousBottom = section.bottom
        }
        return stops
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val content = getChildAt(0) as? ViewGroup ?: return
        // Сколько можно прокрутить без добавленного места и где ближайшая граница раздела после этого
        val maxScroll = content.height - tail - (height - paddingTop - paddingBottom)
        val wanted = if (maxScroll <= 0) 0
        else sectionStops(content).firstOrNull { it >= maxScroll }?.let { it - maxScroll } ?: 0
        if (wanted != tail) {
            tail = wanted
            // Менять отступ прямо во время layout нельзя — откладываем
            post {
                content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottomPadding + tail)
            }
        }
    }

    private var exactTarget = false

    /** Экран просит показать область (отчёт, панель сопряжения): показываем именно её, а не весь раздел. */
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        exactTarget = true
        try {
            return super.requestChildRectangleOnScreen(child, rectangle, immediate)
        } finally {
            exactTarget = false
        }
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val content = getChildAt(0) as? ViewGroup ?: return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val maxScroll = content.height - (height - paddingTop - paddingBottom)
        if (maxScroll <= 0) return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val fade = verticalFadingEdgeLength

        // Цель — весь раздел с элементом в фокусе, если он помещается на экране.
        // Для явного запроса (requestRectangleOnScreen) цель — ровно запрошенная область.
        val target = Rect(rect)
        if (!exactTarget) for (i in 0 until content.childCount) {
            val section = content.getChildAt(i)
            if (section.visibility != View.VISIBLE) continue
            val top = content.top + section.top
            val bottom = content.top + section.bottom
            if (rect.top >= top && rect.bottom <= bottom) {
                if (bottom - top <= height - 2 * fade) target.set(rect.left, top, rect.right, bottom)
                break
            }
        }

        // Цель видна целиком и не попадает под затухание (в начале и в конце экрана затухания нет)
        fun shows(scroll: Int): Boolean =
            target.top >= scroll + (if (scroll > 0) fade else 0) &&
                target.bottom <= scroll + height - (if (scroll < maxScroll) fade else 0)

        val stops = sectionStops(content).filter { it in 1..maxScroll }
        // Уже стоим на границе раздела и цель видна — не двигаемся (например, фокус переходит между
        // кнопками одного ряда, а ниже показан отчёт: он не должен уезжать)
        if (shows(scrollY) && (scrollY == 0 || scrollY in stops || scrollY == maxScroll)) return 0
        if (shows(0)) return -scrollY
        val best = stops.filter { shows(it) }.minByOrNull { Math.abs(it - scrollY) }
            ?: maxScroll.takeIf { shows(it) }
        return if (best != null) best - scrollY else super.computeScrollDeltaToGetChildRectOnScreen(target)
    }

    /**
     * Обработка «вверх»/«вниз» вместо стандартной. Стандартная прокручивает на полэкрана, если следующая
     * кнопка далеко или кнопок дальше нет, и останавливается где придётся — посреди текста или карточки.
     * Здесь фокус всегда переходит на следующую кнопку (экран встаёт по её разделу), а когда кнопок
     * больше нет, оставшееся содержимое листается от раздела к разделу.
     */
    override fun arrowScroll(direction: Int): Boolean {
        val content = getChildAt(0) as? ViewGroup ?: return super.arrowScroll(direction)
        val maxScroll = content.height - (height - paddingTop - paddingBottom)
        if (maxScroll <= 0) return super.arrowScroll(direction)
        val current = findFocus().takeIf { it !== this }
        val next = FocusFinder.getInstance().findNextFocus(this, current, direction)
        if (next != null) {
            // Прокрутку выполнит requestChildFocus → computeScrollDeltaToGetChildRectOnScreen
            return next.requestFocus(direction)
        }
        val stops = sectionStops(content).filter { it in 1 until maxScroll }
        val target = if (direction == View.FOCUS_DOWN) stops.firstOrNull { it > scrollY } ?: maxScroll
        else stops.lastOrNull { it < scrollY } ?: 0
        if (target == scrollY) return false
        smoothScrollTo(scrollX, target)
        if (current != null && current.isFocused) {
            // Кнопка в фокусе уехала за край — фокус забирает сам экран, чтобы он не был невидимым
            val r = Rect()
            current.getDrawingRect(r)
            offsetDescendantRectToMyCoords(current, r)
            if (r.bottom <= target || r.top >= target + height) {
                val focusability = descendantFocusability
                descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                requestFocus()
                descendantFocusability = focusability
            }
        }
        return true
    }
}

/**
 * Строка состояния операции: иконка или индикатор загрузки + текст.
 * Пустое состояние скрывает строку, чтобы не оставлять «дыр» в разметке.
 */
class StatusView(context: Context) : LinearLayout(context) {
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(Palette.ACCENT)
    }
    private val iconView = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val label = context.text("", Type.SECONDARY)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(32)
        addView(spinner, LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        addView(iconView, LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        addView(label, LayoutParams(0, WRAP, 1f))
        // Экранный диктор озвучивает изменения статуса без перевода фокуса
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        clear()
    }

    fun progress(text: CharSequence) = show(text, Palette.TEXT, null)
    fun show(text: CharSequence, tone: Tone) = show(text, tone.color, tone)
    fun info(text: CharSequence) = show(text, Tone.INFO)
    fun success(text: CharSequence) = show(text, Tone.OK)
    fun warn(text: CharSequence) = show(text, Tone.WARN)
    fun error(text: CharSequence) = show(text, Tone.ERROR)

    fun clear() {
        label.text = ""
        visibility = GONE
    }

    val text: CharSequence get() = label.text

    private fun show(text: CharSequence, color: Int, tone: Tone?) {
        visibility = VISIBLE
        label.text = text
        label.setTextColor(if (tone == null || tone == Tone.INFO) Palette.TEXT else color)
        spinner.visibility = if (tone == null) VISIBLE else GONE
        iconView.visibility = if (tone == null) GONE else VISIBLE
        if (tone != null) iconView.setImageDrawable(context.icon(tone.icon, ColorStateList.valueOf(color)))
    }
}

fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(width, height, weight)

/**
 * Добавить с отступом после элемента. Без [params] сохраняются размеры, заданные самому view
 * (например 24×24 у [iconView]); иначе в горизонтальном ряду — по содержимому, в вертикальном — на всю ширину.
 * Раньше здесь всегда было MATCH_PARENT: иконка в ряду растягивалась на всю ширину и «съедала» подпись.
 */
fun LinearLayout.addSpaced(view: View, marginDp: Int = 16, params: LinearLayout.LayoutParams? = null): View {
    val params = params
        ?: (view.layoutParams as? LinearLayout.LayoutParams)
        ?: if (orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(WRAP, WRAP) else lp()
    val m = dp(marginDp)
    if (orientation == LinearLayout.VERTICAL) params.bottomMargin = m else params.rightMargin = m
    addView(view, params)
    return view
}

/** Ряд кнопок с одинаковым отступом 16dp. */
fun LinearLayout.addButtons(vararg buttons: View) {
    buttons.forEachIndexed { i, b ->
        addSpaced(b, if (i == buttons.lastIndex) 0 else 16, LinearLayout.LayoutParams(WRAP, WRAP))
    }
}

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
