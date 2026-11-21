package app.glidetv.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.glidetv.debloat.CoreGuard
import app.glidetv.engine.ComponentRef
import app.glidetv.engine.EngineManager
import app.glidetv.engine.PackageName
import app.glidetv.engine.ShellCmd
import app.glidetv.util.Logx
import kotlinx.coroutines.delay

/** Что сейчас отвечает на кнопку Home. */
data class HomeStatus(
    /** Экран Glide TV включён как кандидат на Home. */
    val active: Boolean,
    /** Кнопка Home открывает именно экран Glide TV. */
    val glideIsHome: Boolean,
    /** Название приложения, которое открывает Home; пусто — ТВ ещё не выбрал. */
    val currentLabel: String,
    /** Название стандартного лаунчера, отключённого Glide TV; пусто — не отключали. */
    val disabledStockLabel: String,
    /** Есть стандартный лаунчер, который можно отключить, чтобы Home достался Glide TV. */
    val stockCanBeDisabled: Boolean,
    /**
     * Название системного приложения, которое заберёт Home, если отключить стандартный лаунчер
     * (на Google TV это мастер первой настройки). Пусто — такого нет.
     */
    val blockedBy: String,
)

enum class SwitchResult {
    /** Home открывает экран Glide TV. */
    DONE,
    /** Команда прошла, но ТВ по-прежнему открывает свой экран. */
    STOCK_STILL_HOME,
    /** Без «Турбо» (или на старом Android) выбрать экран может только сам пользователь. */
    NEED_PICKER,
    /** Стандартный лаунчер сильнее любого выбора пользователя: нужен «Турбо». Ничего не изменено. */
    NEED_TURBO,
    /**
     * Отключать стандартный лаунчер нельзя: Home достался бы не Glide TV, а другому системному приложению
     * (мастеру настройки Google TV, который на этом месте зацикливает систему). Ничего не изменено.
     */
    BLOCKED,
    FAILED,
}

enum class RestoreResult { DONE, NEED_TURBO, FAILED }

/**
 * Смена главного экрана ТВ на экран Glide TV и возврат.
 *
 * Правила, которые нельзя нарушать (иначе ТВ останется без главного экрана):
 * - стандартный лаунчер отключается только отдельным действием и только когда экран Glide TV
 *   уже включён и зарегистрирован как Home; если после отключения Home не достался Glide TV — откат;
 * - экран Glide TV выключается только когда на ТВ есть другой включённый лаунчер;
 * - запись о прежнем экране делается до первого изменения и не перезаписывается повторным включением.
 */
class HomeSwitcher(
    context: Context,
    private val config: HomeConfig,
    private val engines: EngineManager,
) {
    private val app = context.applicationContext
    private val pm: PackageManager = app.packageManager

    /** Псевдоним в манифесте с категорией HOME. По умолчанию выключен: Glide TV не лезет в выбор Home сам. */
    val component = ComponentName(app.packageName, ALIAS)
    private val selfRef get() = ComponentRef(engines.selfPkg, ALIAS)

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun isAliasEnabled(): Boolean =
        pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setAlias(enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    /** Кто отвечает на Home прямо сейчас. Пакет «android» — системный выбор: по умолчанию никто не назначен. */
    private fun resolved(): ComponentName? {
        val info = pm.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    private fun isGlide(c: ComponentName?) = c != null && c.packageName == app.packageName

    private fun isChooser(c: ComponentName?) = c == null || c.packageName == "android"

    /** Включённые лаунчеры, кроме Glide TV и запасного экрана «Настроек» (он не показывает приложения). */
    private fun otherLaunchers(): List<String> =
        pm.queryIntentActivities(homeIntent(), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != app.packageName && !CoreGuard.isCore(it) }
            .distinct()

    /** У чужого лаунчера приоритет фильтра выше нуля: такой получает Home независимо от выбора пользователя. */
    private fun strongerLauncherExists(): Boolean =
        pm.queryIntentActivities(homeIntent(), 0).any {
            val pkg = it.activityInfo?.packageName
            pkg != null && pkg != app.packageName && !CoreGuard.isCore(pkg) && it.priority > 0
        }

    /**
     * Системные приложения, которые заберут Home, как только стандартный лаунчер будет отключён: у их фильтра
     * приоритет выше нуля, а у экрана Glide TV он всегда ноль (Android учитывает приоритет только у приложений
     * из прошивки). На Google TV это мастер настройки com.google.android.tungsten.setupwraith: его
     * RecoveryActivity на месте Home зацикливает system_server, а перезагрузка в таком состоянии на части ТВ
     * кончается сбросом к заводским настройкам (разборы CleanYourTV, TROUBLESHOOTING.md).
     * set-home-activity при этом отвечает Success и ничего не меняет.
     */
    private fun blockers(stock: String?): List<String> =
        pm.queryIntentActivities(homeIntent(), 0)
            .filter { it.priority > 0 }
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != app.packageName && it != stock && !CoreGuard.isCore(it) }
            .distinct()

    /** Все, кто сейчас претендует на Home, с приоритетами — для журнала. */
    private fun claimants(): String =
        pm.queryIntentActivities(homeIntent(), 0).joinToString {
            "${it.activityInfo?.packageName}/${it.activityInfo?.name?.substringAfterLast('.')} p=${it.priority}"
        }

    private fun glideRegistered(): Boolean =
        pm.queryIntentActivities(homeIntent(), 0).any { it.activityInfo?.packageName == app.packageName }

    private fun label(pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun isEnabled(pkg: String): Boolean =
        runCatching { pm.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)

    /** Лаунчер, который мешает: прежний Home, а если он неизвестен — единственный другой лаунчер. */
    private fun stockPackage(): String? {
        val previous = ComponentRef.parse(config.previousHome)?.pkg?.value
        val others = otherLaunchers()
        return when {
            previous != null && previous in others -> previous
            others.size == 1 -> others[0]
            else -> null
        }
    }

    fun status(): HomeStatus {
        val now = resolved()
        val active = isAliasEnabled()
        val glide = isGlide(now)
        val disabled = config.disabledStock
        val stock = stockPackage()
        val blocker = if (active && !glide && disabled.isEmpty() && stock != null) blockers(stock).firstOrNull() else null
        return HomeStatus(
            active = active,
            glideIsHome = glide,
            currentLabel = if (isChooser(now)) "" else label(now!!.packageName),
            disabledStockLabel = if (disabled.isEmpty()) "" else label(disabled),
            stockCanBeDisabled = active && !glide && disabled.isEmpty() && stock != null && blocker == null,
            blockedBy = blocker?.let { label(it) }.orEmpty(),
        )
    }

    /** Включить экран Glide TV и попросить систему отдать ему кнопку Home. */
    suspend fun apply(): SwitchResult {
        val engine = engines.current()
        // Лаунчер с более высоким приоритетом (так у Android TV и Google TV) система выбирает всегда,
        // что бы пользователь ни отметил в списке «Главный экран по умолчанию». Без «Турбо» его
        // не обойти — ничего не меняем и говорим об этом сразу.
        if (engine == null && strongerLauncherExists()) return SwitchResult.NEED_TURBO
        val before = resolved()
        // Первая запись — правда о том, как было; повторное включение её не затирает
        if (config.previousHome.isEmpty() && !isChooser(before) && !isGlide(before)) {
            config.previousHome = before!!.flattenToString()
        }
        setAlias(true)
        if (engine == null) return SwitchResult.NEED_PICKER
        val result = engine.run(ShellCmd.SetHomeActivity(selfRef))
        Logx.i("Главный экран: set-home-activity → ${result.exitCode} ${result.output.take(80)}")
        delay(SETTLE_MS)
        return when {
            isGlide(resolved()) -> SwitchResult.DONE
            // Команды нет до Android 7: остаётся системный выбор главного экрана
            !result.ok -> SwitchResult.NEED_PICKER
            else -> SwitchResult.STOCK_STILL_HOME
        }
    }

    /**
     * Отключить стандартный лаунчер, который не отдаёт Home (так делает Google TV).
     * Ничего не удаляется; [restore] включает его обратно.
     */
    suspend fun disableStock(): SwitchResult {
        val engine = engines.current() ?: return SwitchResult.FAILED
        val pkg = stockPackage() ?: return SwitchResult.FAILED
        if (!isAliasEnabled() || !glideRegistered()) return SwitchResult.FAILED
        if (!PackageName.isValid(pkg) || pkg == app.packageName || CoreGuard.isCore(pkg)) return SwitchResult.FAILED
        // Проверка до любых изменений: если Home заберёт не Glide TV, лаунчер нельзя отключать даже на секунду
        val blockers = blockers(pkg)
        if (blockers.isNotEmpty()) {
            Logx.w("Главный экран: $pkg не отключаем — Home заберёт ${blockers.joinToString()}. Претенденты: ${claimants()}")
            return SwitchResult.BLOCKED
        }

        // Запись до команды: даже если процесс умрёт посередине, «Вернуть как было» знает, что включать
        config.disabledStock = pkg
        engine.run(ShellCmd.DisableUser(PackageName(pkg)))
        engine.run(ShellCmd.SetHomeActivity(selfRef))
        delay(SETTLE_MS)
        if (isGlide(resolved())) {
            Logx.i("Главный экран: стандартный лаунчер $pkg отключён")
            return SwitchResult.DONE
        }
        // Home не достался Glide TV — возвращаем лаунчер, чтобы ТВ не остался без главного экрана
        val got = resolved()?.flattenToShortString() ?: "никто"
        val seen = claimants()
        engine.run(ShellCmd.Enable(PackageName(pkg)))
        if (isEnabled(pkg)) config.disabledStock = ""
        Logx.w("Главный экран: $pkg отключён, но Home достался $got — откат. Претенденты: $seen")
        return SwitchResult.FAILED
    }

    /** Вернуть как было: включить отключённый лаунчер, отдать ему Home и выключить экран Glide TV. */
    suspend fun restore(): RestoreResult {
        val engine = engines.current()
        val stock = config.disabledStock
        if (stock.isNotEmpty()) {
            if (engine == null) return RestoreResult.NEED_TURBO
            if (PackageName.isValid(stock)) engine.run(ShellCmd.Enable(PackageName(stock)))
            if (!isEnabled(stock)) return RestoreResult.FAILED
            config.disabledStock = ""
        }
        val previous = ComponentRef.parse(config.previousHome)
        if (engine != null && previous != null) {
            val result = engine.run(ShellCmd.SetHomeActivity(previous))
            Logx.i("Главный экран: возврат на ${previous.flatten()} → ${result.exitCode}")
        }
        // Свой экран выключаем только когда Home есть кому отдать
        if (otherLaunchers().isEmpty()) return RestoreResult.FAILED
        setAlias(false)
        config.previousHome = ""
        return RestoreResult.DONE
    }

    /** Системный экран выбора лаунчера (есть не на всех ТВ). */
    fun openPicker(context: Context): Boolean = runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    companion object {
        const val ALIAS = "app.glidetv.home.HomeAlias"
        private const val SETTLE_MS = 500L
    }
}
