package app.glidetv.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Палитра Glide TV (тёмно-синий + циан, как на баннере). */
object Palette {
    val BG = Color.parseColor("#0B1B3A")
    val BG_DEEP = Color.parseColor("#081430")
    val CARD = Color.parseColor("#13294F")
    val CARD_FOCUS = Color.parseColor("#1B3A6E")
    val ACCENT = Color.parseColor("#3EE6FF")
    val ACCENT_2 = Color.parseColor("#7C8CFF")
    val TEXT = Color.parseColor("#FFFFFF")
    val TEXT_DIM = Color.parseColor("#9FB3D9")
    val OK = Color.parseColor("#5CFFB0")
    val WARN = Color.parseColor("#FFC857")
    val ERR = Color.parseColor("#FF8A80")
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

/** Фон с явным состоянием фокуса: на ТВ фокус — главный способ навигации. */
fun focusBackground(context: Context, normal: Int, focused: Int, radiusDp: Int = 14): Drawable {
    val r = context.dp(radiusDp).toFloat()
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(focused, r, Palette.ACCENT, context.dp(3)))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(focused, r, Palette.ACCENT, context.dp(3)))
        addState(intArrayOf(android.R.attr.state_selected), rounded(focused, r))
        addState(intArrayOf(), rounded(normal, r))
    }
}

/** Лёгкое увеличение элемента в фокусе (стандартное поведение ТВ-интерфейсов). */
fun View.scaleOnFocus(scale: Float = 1.04f) {
    val previous = onFocusChangeListener
    setOnFocusChangeListener { v, has ->
        previous?.onFocusChange(v, has)
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(120).start()
    }
}

fun Context.text(
    value: CharSequence = "",
    sizeSp: Float = 18f,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/** Кнопка для пульта: фокусируемая, с заметной рамкой фокуса. */
fun Context.actionButton(label: CharSequence, primary: Boolean = false, onClick: () -> Unit): TextView =
    text(label, 18f, if (primary) Palette.BG else Palette.TEXT, bold = true).apply {
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        setPadding(dp(26), dp(14), dp(26), dp(14))
        background = if (primary) {
            focusBackground(context, Palette.ACCENT, Color.parseColor("#8AF1FF"))
        } else {
            focusBackground(context, Palette.CARD, Palette.CARD_FOCUS)
        }
        if (primary) setTextColor(ColorStateList.valueOf(Palette.BG))
        setOnClickListener { onClick() }
        scaleOnFocus()
    }

fun Context.vertical(paddingDp: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        block()
    }

fun Context.horizontal(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        block()
    }

fun Context.card(focusable: Boolean = false, block: LinearLayout.() -> Unit = {}): LinearLayout =
    vertical(20) {
        background = if (focusable) focusBackground(context, Palette.CARD, Palette.CARD_FOCUS, 18)
        else rounded(Palette.CARD, dp(18).toFloat())
        if (focusable) {
            isFocusable = true
            isClickable = true
            scaleOnFocus(1.03f)
        }
        block()
    }

fun lp(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(width, height, weight)

fun LinearLayout.addSpaced(view: View, marginDp: Int = 12, params: LinearLayout.LayoutParams = lp()): View {
    val m = dp(marginDp)
    if (orientation == LinearLayout.VERTICAL) params.bottomMargin = m else params.rightMargin = m
    addView(view, params)
    return view
}

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
