package app.glidetv.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View

/** Кольцевой индикатор (RAM / память). Рисуется вручную — без зависимостей. */
class RingGauge(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Palette.BG_DEEP
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    var percent: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            arc.color = when {
                field >= 90 -> Palette.ERR
                field >= 75 -> Palette.WARN
                else -> Palette.ACCENT
            }
            arc.shader = null
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = context.dp(132)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = width * 0.09f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val pad = stroke / 2 + 2
        val side = minOf(width, height).toFloat()
        rect.set(pad, pad, side - pad, side - pad)
        canvas.drawArc(rect, 135f, 270f, false, track)
        if (percent < 75) {
            arc.shader = SweepGradient(side / 2, side / 2, intArrayOf(Palette.ACCENT, Palette.ACCENT_2, Palette.ACCENT), null)
        }
        canvas.drawArc(rect, 135f, 270f * percent / 100f, false, arc)
        label.textSize = side * 0.24f
        val y = side / 2 - (label.descent() + label.ascent()) / 2
        canvas.drawText("$percent%", side / 2, y, label)
    }
}
