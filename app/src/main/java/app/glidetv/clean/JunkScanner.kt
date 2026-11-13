package app.glidetv.clean

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

enum class JunkKind {
    /** Установочные APK, оставшиеся после установки приложений. */
    APK,
    /** Логи, временные файлы, дампы. */
    TEMP,
    /** Кеш миниатюр галереи. */
    THUMBNAILS,
    /** Кеш приложений во внешней памяти (Android/data/<pkg>/cache, доступен до Android 10). */
    EXTERNAL_CACHE,
    /** Пустые папки. */
    EMPTY_DIR,
}

data class JunkItem(val file: File, val kind: JunkKind, val sizeBytes: Long) {
    /** APK — потенциально нужные файлы, поэтому по умолчанию не выбраны. */
    val selectedByDefault: Boolean get() = kind != JunkKind.APK
}

/** Поиск мусора в общей памяти (/sdcard). Ничего не удаляет без явного выбора пользователя. */
class JunkScanner(private val context: Context) {

    suspend fun scan(): List<JunkItem> = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory() ?: return@withContext emptyList()
        // Без доступа к памяти список был бы пустым, и это выглядело бы как «мусора нет»
        if (!root.canRead()) throw java.io.IOException("no read access to $root")
        val result = mutableListOf<JunkItem>()
        walk(root, depth = 0, out = result)
        externalCaches(root, result)
        result.sortedByDescending { it.sizeBytes }
    }

    private suspend fun walk(dir: File, depth: Int, out: MutableList<JunkItem>) {
        coroutineContext.ensureActive()
        if (depth > 8) return
        val children = dir.listFiles() ?: return
        // Пустые папки верхнего уровня (Movies, Music, DCIM…) — стандартные, их не трогаем
        if (children.isEmpty() && depth >= 2) {
            out += JunkItem(dir, JunkKind.EMPTY_DIR, 0)
            return
        }
        for (f in children) {
            val name = f.name
            if (f.isDirectory) {
                // Android/data и Android/obb — данные приложений: их обрабатывает externalCaches()
                if (depth == 0 && name == "Android") continue
                if (name == ".thumbnails") {
                    out += JunkItem(f, JunkKind.THUMBNAILS, sizeOf(f))
                    continue
                }
                walk(f, depth + 1, out)
            } else {
                val lower = name.lowercase()
                when {
                    lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk") ->
                        out += JunkItem(f, JunkKind.APK, f.length())
                    TEMP_SUFFIXES.any { lower.endsWith(it) } || lower.startsWith("~") ->
                        out += JunkItem(f, JunkKind.TEMP, f.length())
                }
            }
        }
    }

    /** До Android 11 можно читать Android/data/<pkg>/cache других приложений. */
    private fun externalCaches(root: File, out: MutableList<JunkItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        val data = File(root, "Android/data")
        data.listFiles()?.forEach { pkgDir ->
            if (pkgDir.name == context.packageName) return@forEach
            val cache = File(pkgDir, "cache")
            val size = sizeOf(cache)
            if (size > 0) out += JunkItem(cache, JunkKind.EXTERNAL_CACHE, size)
        }
    }

    suspend fun delete(items: Collection<JunkItem>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (item in items) {
            coroutineContext.ensureActive()
            val ok = when (item.kind) {
                JunkKind.EXTERNAL_CACHE, JunkKind.THUMBNAILS -> deleteChildren(item.file)
                else -> if (item.file.isDirectory) item.file.deleteRecursively() else item.file.delete()
            }
            if (ok) freed += item.sizeBytes
        }
        freed
    }

    private fun deleteChildren(dir: File): Boolean =
        dir.listFiles()?.all { it.deleteRecursively() } ?: true

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else f.walkTopDown().maxDepth(12).filter { it.isFile }.sumOf { it.length() }

    companion object {
        private val TEMP_SUFFIXES = listOf(".log", ".tmp", ".temp", ".trace", ".dmp")
    }
}
