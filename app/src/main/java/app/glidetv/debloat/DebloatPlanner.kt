package app.glidetv.debloat

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import app.glidetv.engine.EngineManager
import app.glidetv.engine.PackageName
import app.glidetv.system.PlatformInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BlockReason { CORE, HOME, TV_INPUT, INPUT_HANDLER, SETTINGS_HANDLER, KEYBOARD, SELF }

data class Candidate(
    val pkg: String,
    val label: String,
    val note: String,
    /** Отмечен по умолчанию. */
    val recommended: Boolean,
    /** Отключать можно, но есть известные последствия — показываем предупреждение. */
    val risk: Risk?,
    /** У приложения есть иконка в лаунчере: это выбор пользователя, по умолчанию не отмечаем. */
    val userVisible: Boolean,
    /** Сколько владельцев других ТВ отключили этот пакет. */
    val disabledByOthers: Int,
    val enabledNow: Boolean,
    val disabledByGlide: Boolean,
)

data class DebloatPlan(
    val profile: DeviceProfile?,
    val candidates: List<Candidate>,
    val blocked: Map<String, BlockReason>,
)

/**
 * Строит список с галочками. Логика выбора по умолчанию:
 *  - никогда не показываем ядро, лаунчеры, обработчики входов HDMI/тюнера, настройки, клавиатуры;
 *  - приложения с иконкой (Netflix, YouTube Music…) показываем, но не отмечаем — это выбор пользователя;
 *  - фоновые пакеты отмечаем, если их отключили на этой модели или массово (≥80 %) на других ТВ.
 */
class DebloatPlanner(
    private val context: Context,
    private val platform: PlatformInfo,
    /** База ~1 МБ JSON: загружаем лениво и не в главном потоке. */
    private val dbProvider: () -> DebloatDb,
    @Suppress("unused") private val engines: EngineManager,
) {
    private val pm = context.packageManager

    suspend fun plan(journal: DebloatJournal): DebloatPlan = withContext(Dispatchers.Default) {
        val db = dbProvider()
        val profile = db.match(platform)
        val profilePkgs = profile?.packages?.associateBy { it.pkg }.orEmpty()
        val journaled = journal.entries().map { it.pkg }.toSet()
        val blocked = dynamicBlocks()

        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 24) PackageManager.MATCH_DISABLED_COMPONENTS
        else PackageManager.GET_DISABLED_COMPONENTS
        val installed = pm.getInstalledApplications(flags)

        val candidates = installed.mapNotNull { ai ->
            val pkg = ai.packageName
            if (!PackageName.isValid(pkg)) return@mapNotNull null
            val stat = db.stats[pkg]
            val inProfile = profilePkgs[pkg]
            val known = stat != null || inProfile != null || pkg in journaled
            if (!known) return@mapNotNull null
            if (CoreGuard.isNever(pkg) || pkg in blocked) return@mapNotNull null
            // уже отключён не нами — не показываем, чтобы не путать
            if (!ai.enabled && pkg !in journaled) return@mapNotNull null

            val userVisible = hasLauncherEntry(pkg)
            val risk = CoreGuard.warning(pkg)
            val massDisabled = stat != null && stat.active >= 3 &&
                stat.active.toDouble() / stat.lists.coerceAtLeast(1) >= 0.8
            val recommended = ai.enabled && !userVisible && risk == null &&
                ((inProfile?.active == true) || (inProfile == null && massDisabled))
            Candidate(
                pkg = pkg,
                label = label(ai),
                note = inProfile?.note?.takeIf { it.isNotBlank() } ?: stat?.note.orEmpty(),
                recommended = recommended,
                risk = risk,
                userVisible = userVisible,
                disabledByOthers = stat?.active ?: 0,
                enabledNow = ai.enabled,
                disabledByGlide = pkg in journaled,
            )
        }.sortedWith(
            compareByDescending<Candidate> { it.disabledByGlide }
                .thenByDescending { it.recommended }
                .thenBy { it.userVisible }
                .thenByDescending { it.disabledByOthers }
                .thenBy { it.label.lowercase() },
        )
        DebloatPlan(profile, candidates, blocked)
    }

    /** Защиты, которые зависят от конкретной прошивки (аналог проверок CleanYourTV disable.sh). */
    private fun dynamicBlocks(): Map<String, BlockReason> {
        val out = HashMap<String, BlockReason>()
        out[context.packageName] = BlockReason.SELF

        // Всё, что может быть Home: без лаунчера ТВ загрузится в пустой экран или FallbackHome
        queryActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            .forEach { out[it] = BlockReason.HOME }
        // Приложения, открывающие входы HDMI/AV/каналы: иначе кнопка Input откроет не то или ничего
        queryActivities(Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.item/channel"))
            .forEach { out.putIfMissing(it, BlockReason.INPUT_HANDLER) }
        // Обработчики настроек: с ними уходит и путь в «Для разработчиков»
        queryActivities(Intent(Settings.ACTION_SETTINGS)).forEach { out.putIfMissing(it, BlockReason.SETTINGS_HANDLER) }
        // Клавиатуры
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.inputMethodList.forEach { out.putIfMissing(it.packageName, BlockReason.KEYBOARD) }
        // Слой входов HDMI/тюнера: отключение даёт «Нет сигнала» навсегда (CleanYourTV #321)
        tvInputPackages().forEach { out.putIfMissing(it, BlockReason.TV_INPUT) }
        return out
    }

    @Suppress("DEPRECATION")
    private fun tvInputPackages(): Set<String> = runCatching {
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .filter { it.requestedPermissions?.contains("android.permission.TV_INPUT_HARDWARE") == true }
            .map { it.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun queryActivities(intent: Intent): List<String> {
        val flags = if (Build.VERSION.SDK_INT >= 23) PackageManager.MATCH_ALL else 0
        return pm.queryIntentActivities(intent, flags).map { it.activityInfo.packageName }.distinct()
    }

    private fun hasLauncherEntry(pkg: String): Boolean {
        val leanback = if (Build.VERSION.SDK_INT >= 21) pm.getLeanbackLaunchIntentForPackage(pkg) else null
        return leanback != null || pm.getLaunchIntentForPackage(pkg) != null
    }

    private fun label(ai: ApplicationInfo): String =
        runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
}

/** Map.putIfAbsent() появился в Android только с API 24 — свой вариант для Android 5–6. */
private fun <K, V> MutableMap<K, V>.putIfMissing(key: K, value: V) {
    if (!containsKey(key)) put(key, value)
}
