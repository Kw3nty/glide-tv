package app.glidetv.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.glidetv.engine.ComponentRef
import app.glidetv.engine.EngineManager
import app.glidetv.engine.PackageName
import app.glidetv.engine.Query
import app.glidetv.engine.ShellCmd
import app.glidetv.util.Logx
import kotlinx.coroutines.delay

/** [HomeDevice] на настоящем ТВ: PackageManager для чтения, «Турбо» для команд. */
class AndroidHomeDevice(
    context: Context,
    private val engines: EngineManager,
) : HomeDevice {
    private val app = context.applicationContext
    private val pm: PackageManager = app.packageManager

    /** Псевдоним в манифесте с категорией HOME. По умолчанию выключен: Glide TV не лезет в выбор Home сам. */
    private val alias = ComponentName(app.packageName, HomeSwitcher.ALIAS)

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    override val self: String get() = app.packageName

    override val hasShell: Boolean get() = engines.current() != null

    override fun claimants(): List<HomeClaimant> =
        pm.queryIntentActivities(homeIntent(), 0).mapNotNull {
            val info = it.activityInfo ?: return@mapNotNull null
            HomeClaimant(info.packageName, info.name, it.priority)
        }

    override fun resolvedHome(): String? {
        val info = pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        return ComponentName(info.packageName, info.name).flattenToString()
    }

    override fun isEnabled(pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)

    override fun isAliasEnabled(): Boolean =
        pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    override fun setAlias(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
    }

    override fun label(pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    @Suppress("DEPRECATION")
    override fun wifiInUse(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }.getOrDefault(true) // не узнали — считаем, что на Wi-Fi: так безопаснее

    override suspend fun disable(pkg: String) {
        val engine = engines.current() ?: return
        if (PackageName.isValid(pkg)) engine.run(ShellCmd.DisableUser(PackageName(pkg)))
    }

    override suspend fun enable(pkg: String) {
        val engine = engines.current() ?: return
        if (PackageName.isValid(pkg)) engine.run(ShellCmd.Enable(PackageName(pkg)))
    }

    override suspend fun setHome(component: String): Boolean {
        val engine = engines.current() ?: return false
        val ref = ComponentRef.parse(component) ?: return false
        return engine.run(ShellCmd.SetHomeActivity(ref)).ok
    }

    override suspend fun wifiOwners(): Set<String>? {
        val engine = engines.current() ?: return null
        val result = engine.run(ShellCmd.Read(Query.WIFI_OWNERS))
        if (!result.ok || result.output.isBlank()) return null
        return WifiOwners.parse(result.output)
    }

    override suspend fun settle() = delay(SETTLE_MS)

    override fun log(message: String, warning: Boolean) {
        if (warning) Logx.w(message) else Logx.i(message)
    }

    private companion object {
        const val SETTLE_MS = 500L
    }
}

/** Владельцы сохранённых сетей Wi-Fi из вывода `dumpsys wifi` (поле cname=). */
object WifiOwners {
    private val CNAME = Regex("cname=([A-Za-z0-9_.:]+)")

    fun parse(dumpsys: String): Set<String> = CNAME.findAll(dumpsys).map { it.groupValues[1] }.toSet()
}
