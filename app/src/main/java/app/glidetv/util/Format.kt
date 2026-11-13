package app.glidetv.util

import android.content.Context
import app.glidetv.R
import java.util.Locale

object Format {
    /** 1536 → «1,5 КБ». Десятичные единицы не используем: Android показывает двоичные. */
    fun bytes(context: Context, value: Long): String {
        val v = value.coerceAtLeast(0L).toDouble()
        val units = context.resources.getStringArray(R.array.byte_units)
        var size = v
        var i = 0
        while (size >= 1024 && i < units.size - 1) {
            size /= 1024
            i++
        }
        val pattern = if (i == 0 || size >= 100) "%.0f %s" else "%.1f %s"
        return String.format(Locale.getDefault(), pattern, size, units[i])
    }

    fun percent(part: Long, total: Long): Int =
        if (total <= 0) 0 else ((part * 100.0) / total).toInt().coerceIn(0, 100)
}
