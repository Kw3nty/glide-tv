package app.glidetv.system

import android.app.ActivityManager
import android.content.Context
import java.io.File

data class RamInfo(
    val totalBytes: Long,
    val availBytes: Long,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availBytes).coerceAtLeast(0)
}

class MemoryRepository(private val context: Context) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun snapshot(): RamInfo {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        // MemAvailable из /proc/meminfo точнее availMem на старых ядрах (учитывает page cache)
        val memAvailable = readMemInfoKb("MemAvailable")?.times(1024)
        return RamInfo(
            totalBytes = mi.totalMem,
            availBytes = memAvailable ?: mi.availMem,
            lowMemory = mi.lowMemory,
            thresholdBytes = mi.threshold,
        )
    }

    /** Температура SoC, если прошивка её отдаёт; на многих ТВ-приставках файл недоступен. */
    fun cpuTemperatureC(): Float? {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        val values = zones.mapNotNull { zone ->
            runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull()
        }.map { if (it > 1000f) it / 1000f else it }
            .filter { it in 10f..120f }
        return values.maxOrNull()
    }

    private fun readMemInfoKb(key: String): Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()
        }
    }.getOrNull()
}
