package app.glidetv.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Лог в logcat плюс кольцевой буфер в памяти: его можно показать на экране «Турбо»
 * и скопировать в отчёт об ошибке. Никуда не отправляется.
 */
object Logx {
    private const val TAG = "GlideTV"
    private const val MAX = 400
    private val buffer = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    private fun push(level: Char, msg: String) {
        if (buffer.size >= MAX) buffer.removeFirst()
        buffer.addLast("${fmt.format(Date())} $level $msg")
    }

    fun i(msg: String) { Log.i(TAG, msg); push('I', msg) }
    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t); push('W', if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t); push('E', if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()
}
