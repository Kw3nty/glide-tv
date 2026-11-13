package app.glidetv.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View

/**
 * Кольцевой индикатор (RAM / память). Рисуется вручную — без зависимостей.
 * Цвет дублируется числом в центре и подписью [caption], поэтому смысл не держится только на цвете.
 */
class RingGauge(context: Context, private val sizeDp: Int = 120) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.CARD_FOCUS
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_DIM
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private var gradient: SweepGradient? = null

    var caption: String = ""
        set(v) { field = v; invalidate() }

    var percent: Int = 0
        set(v) {
            field = v.coerceIn(0, 100)
            arc.color = when {
                field >= 90 -> Palette.ERR
                field >= 75 -> Palette.WARN
                else -> Palette.ACCENT
            }
            contentDescription = "$field% $caption"
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = context.dp(sizeDp)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = minOf(w, h).toFloat()
        gradient = SweepGradient(side / 2, side / 2, intArrayOf(Palette.ACCENT, Palette.ACCENT_2, Palette.ACCENT), null)
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        val stroke = side * 0.1f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val pad = stroke / 2 + 2
        rect.set(pad, pad, side - pad, side - pad)
        canvas.drawArc(rect, 135f, 270f, false, track)
        // Норма — фирменный градиент; предупреждение/ошибка — сплошной статусный цвет
        arc.shader = if (percent < 75) gradient else null
        if (percent > 0) canvas.drawArc(rect, 135f, 270f * percent / 100f, false, arc)
        value.textSize = side * 0.25f
        val cy = side / 2 - (value.descent() + value.ascent()) / 2
        canvas.drawText("$percent%", side / 2, cy - if (caption.isEmpty()) 0f else side * 0.06f, value)
        if (caption.isNotEmpty()) {
            small.textSize = side * 0.12f
            canvas.drawText(caption, side / 2, cy + side * 0.13f, small)
        }
    }
}
