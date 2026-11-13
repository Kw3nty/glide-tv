package app.glidetv.system

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

data class StorageInfo(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
}

class StorageRepository(@Suppress("unused") private val context: Context) {
    /** Внутренняя память (/data): именно её съедают кеши приложений. */
    fun internal(): StorageInfo {
        val stat = StatFs(Environment.getDataDirectory().path)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            StorageInfo(stat.totalBytes, stat.availableBytes)
        } else {
            @Suppress("DEPRECATION")
            StorageInfo(stat.blockCount.toLong() * stat.blockSize, stat.availableBlocks.toLong() * stat.blockSize)
        }
    }
}
