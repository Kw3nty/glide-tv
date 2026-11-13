package app.glidetv.clean

import android.content.Context
import android.content.pm.IPackageDataObserver
import android.os.Build
import app.glidetv.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android 5.x: PackageManager.freeStorageAndNotify() доступен сторонним приложениям
 * с разрешением CLEAR_APP_CACHE. Система чистит кеши всех приложений, пока не освободит
 * запрошенный объём; просим «бесконечность» — значит, всё.
 * С Android 6 метод закрыт для сторонних приложений: там работает только «Турбо».
 */
object LegacyCacheCleaner {
    val isSupported: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M

    suspend fun clearAll(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isSupported) return@withContext false
        val pm = context.packageManager
        val method = runCatching {
            pm.javaClass.getMethod("freeStorageAndNotify", Long::class.javaPrimitiveType, IPackageDataObserver::class.java)
        }.getOrElse {
            Logx.w("freeStorageAndNotify недоступен", it)
            return@withContext false
        }
        withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val observer = object : IPackageDataObserver.Stub() {
                    override fun onRemoveCompleted(packageName: String?, succeeded: Boolean) {
                        if (cont.isActive) cont.resume(succeeded)
                    }
                }
                try {
                    method.invoke(pm, Long.MAX_VALUE, observer)
                } catch (t: Throwable) {
                    Logx.w("freeStorageAndNotify", t)
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }
}
