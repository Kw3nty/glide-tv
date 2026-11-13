package app.glidetv.debloat

import android.content.Context
import android.content.pm.PackageManager
import app.glidetv.engine.EngineManager
import app.glidetv.engine.PackageName
import app.glidetv.engine.Query
import app.glidetv.engine.ShellCmd
import app.glidetv.system.PlatformInfo
import app.glidetv.util.Logx
import kotlinx.coroutines.delay

data class PackageOutcome(val pkg: String, val ok: Boolean, val message: String)

/**
 * Отключает/включает пакеты через `pm disable-user --user 0` / `pm enable --user 0`.
 * Ничего не удаляется. По одному пакету за команду: цепочки `pm …; pm …`
 * на некоторых прошивках молча обрываются после первой (CleanYourTV TROUBLESHOOTING).
 */
class DebloatExecutor(
    private val context: Context,
    private val platform: PlatformInfo,
    private val engines: EngineManager,
    private val journal: DebloatJournal,
) {
    suspend fun disable(pkgs: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<PackageOutcome> {
        val engine = engines.current() ?: return pkgs.map { PackageOutcome(it, false, "no engine") }
        if (!platform.canDisablePackages) return pkgs.map { PackageOutcome(it, false, "firmware refuses") }

        val safe = pkgs.filterNot { CoreGuard.isNever(it) }
        val wifiOwnedBySetup = setupWizardOwnsWifi()
        val out = mutableListOf<PackageOutcome>()
        safe.forEachIndexed { i, pkg ->
            onProgress(i + 1, safe.size)
            if (pkg == SETUP_WIZARD && wifiOwnedBySetup != false) {
                out += PackageOutcome(pkg, false, "setupwraith owns Wi-Fi")
                return@forEachIndexed
            }
            val r = engine.run(ShellCmd.DisableUser(PackageName(pkg)))
            delay(150)
            val disabled = !isEnabled(pkg)
            if (disabled) journal.add(pkg)
            out += PackageOutcome(pkg, disabled, if (disabled) "ok" else r.output.lineSequence().firstOrNull().orEmpty())
            Logx.i("disable $pkg → ${if (disabled) "ok" else "FAILED: ${r.output.take(120)}"}")
        }
        return out
    }

    suspend fun enable(pkgs: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<PackageOutcome> {
        val engine = engines.current() ?: return pkgs.map { PackageOutcome(it, false, "no engine") }
        return pkgs.mapIndexed { i, pkg ->
            onProgress(i + 1, pkgs.size)
            val r = engine.run(ShellCmd.Enable(PackageName(pkg)))
            delay(100)
            val enabled = isEnabled(pkg)
            if (enabled) journal.remove(pkg)
            PackageOutcome(pkg, enabled, if (enabled) "ok" else r.output.take(120))
        }
    }

    /** «Вернуть всё»: включить только то, что отключил Glide TV. */
    suspend fun restoreAll(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<PackageOutcome> =
        enable(journal.entries().map { it.pkg }, onProgress)

    private fun isEnabled(pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Сеть Wi-Fi, сохранённая мастером первой настройки Google TV, принадлежит setupwraith,
     * и система удаляет её при его отключении. null — проверить не удалось (считаем, что владеет).
     */
    private suspend fun setupWizardOwnsWifi(): Boolean? {
        val engine = engines.current() ?: return null
        val r = engine.run(ShellCmd.Read(Query.WIFI_OWNERS))
        if (!r.ok || r.output.isBlank()) return null
        val owners = Regex("cname=([A-Za-z0-9_.:]+)").findAll(r.output).map { it.groupValues[1] }.toSet()
        if (owners.isEmpty()) return null
        return owners.any { it.contains("setupwraith") }
    }

    private companion object {
        const val SETUP_WIZARD = "com.google.android.tungsten.setupwraith"
    }
}
