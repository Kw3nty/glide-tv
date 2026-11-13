package app.glidetv.system

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageStatsObserver
import android.content.pm.PackageManager
import android.content.pm.PackageStats
import android.os.Build
import android.os.Process
import app.glidetv.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class AppEntry(
    val pkg: String,
    val label: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val cacheBytes: Long = -1,
    val dataBytes: Long = -1,
    val codeBytes: Long = -1,
) {
    val totalBytes: Long get() = listOf(cacheBytes, dataBytes, codeBytes).filter { it > 0 }.sum()
    val sizesKnown: Boolean get() = cacheBytes >= 0
}

class AppsRepository(private val context: Context) {
    private val pm = context.packageManager

    /** Все установленные пакеты, включая отключённые. */
    @Suppress("DEPRECATION")
    fun installed(): List<AppEntry> {
        val flags = if (Build.VERSION.SDK_INT >= 24) PackageManager.MATCH_DISABLED_COMPONENTS
        else PackageManager.GET_DISABLED_COMPONENTS
        return pm.getInstalledApplications(flags).map { ai ->
            AppEntry(
                pkg = ai.packageName,
                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName),
                isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                enabled = ai.enabled,
            )
        }
    }

    fun isEnabled(pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Размеры приложений. Android 8+: StorageStatsManager (нужен usage-доступ).
     * Android 5–7.1: скрытый getPackageSizeInfo (разрешение GET_PACKAGE_SIZE).
     */
    suspend fun withSizes(apps: List<AppEntry>): List<AppEntry> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sizesModern(apps) else sizesLegacy(apps)
    }

    private fun sizesModern(apps: List<AppEntry>): List<AppEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return apps
        if (!SpecialAccess.hasUsageAccess(context)) return apps
        val ssm = context.getSystemService(StorageStatsManager::class.java) ?: return apps
        val user = Process.myUserHandle()
        return apps.map { app ->
            try {
                val ai = pm.getApplicationInfo(app.pkg, 0)
                val st = ssm.queryStatsForPackage(ai.storageUuid, app.pkg, user)
                app.copy(cacheBytes = st.cacheBytes, dataBytes = st.dataBytes - st.cacheBytes, codeBytes = st.appBytes)
            } catch (t: Throwable) {
                app
            }
        }
    }

    private fun sizesLegacy(apps: List<AppEntry>): List<AppEntry> {
        val method = runCatching {
            pm.javaClass.getMethod("getPackageSizeInfo", String::class.java, IPackageStatsObserver::class.java)
        }.getOrElse {
            Logx.w("getPackageSizeInfo недоступен", it)
            return apps
        }
        return apps.map { app ->
            val latch = CountDownLatch(1)
            var result: PackageStats? = null
            val observer = object : IPackageStatsObserver.Stub() {
                override fun onGetStatsCompleted(pStats: PackageStats?, succeeded: Boolean) {
                    if (succeeded) result = pStats
                    latch.countDown()
                }
            }
            try {
                method.invoke(pm, app.pkg, observer)
                latch.await(2, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                Logx.w("размер ${app.pkg}", t)
            }
            val s = result ?: return@map app
            app.copy(
                cacheBytes = s.cacheSize + s.externalCacheSize,
                dataBytes = s.dataSize + s.externalDataSize,
                codeBytes = s.codeSize + s.externalCodeSize,
            )
        }
    }
}
