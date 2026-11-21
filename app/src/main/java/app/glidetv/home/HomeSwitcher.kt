package app.glidetv.home

import android.content.Context
import android.content.Intent
import app.glidetv.debloat.CoreGuard
import app.glidetv.engine.PackageName

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
     * Название мастера первой настройки Google TV, который придётся отключить вместе с лаунчером
     * (иначе Home достанется ему). Пусто — мастера нет. Интерфейс предупреждает об этом отдельно.
     */
    val wizardLabel: String,
    /**
     * Название незнакомого системного приложения, которое заберёт Home, если отключить стандартный лаунчер.
     * Такое Glide TV не трогает. Пусто — такого нет.
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
    /** Home достался бы незнакомому системному приложению. Ничего не изменено. */
    BLOCKED,
    /**
     * Сеть Wi-Fi сохранена мастером настройки: отключим его — ТВ забудет сеть. Ничего не изменено;
     * пользователю нужно забыть сеть и подключиться заново (тогда она принадлежит системе).
     */
    WIFI_OWNED,
    FAILED,
}

enum class RestoreResult { DONE, NEED_TURBO, FAILED }

/** Приложение, претендующее на кнопку Home, и приоритет его фильтра. */
data class HomeClaimant(val pkg: String, val cls: String, val priority: Int)

/** Где HomeSwitcher хранит то, что нужно для возврата. */
interface HomeStore {
    /** Компонент «пакет/класс», который отвечал на Home до Glide TV. */
    var previousHome: String

    /** Пакеты, отключённые Glide TV, через запятую: первым лаунчер, за ним мастер настройки. */
    var disabledStock: String
}

/**
 * Всё, что HomeSwitcher узнаёт о ТВ и делает на нём. Логика отделена от Android, чтобы порядок шагов
 * (что отключается первым, что включается первым, как идёт откат) проверялся модульными тестами:
 * Google TV с мастером настройки на эмуляторе не воспроизвести.
 */
interface HomeDevice {
    /** Пакет самого Glide TV. */
    val self: String

    /** Есть «Турбо»: можно выполнять команды shell. */
    val hasShell: Boolean

    /** Включённые activity с категорией HOME. */
    fun claimants(): List<HomeClaimant>

    /** «пакет/класс» того, кто отвечает на Home сейчас. Пакет «android» — системный выбор, null — никто. */
    fun resolvedHome(): String?

    fun isEnabled(pkg: String): Boolean
    fun isAliasEnabled(): Boolean
    fun setAlias(enabled: Boolean)
    fun label(pkg: String): String

    /** ТВ сейчас в сети через Wi-Fi. */
    fun wifiInUse(): Boolean

    suspend fun disable(pkg: String)
    suspend fun enable(pkg: String)

    /** `cmd package set-home-activity`. false — команды нет (до Android 7) или она отказала. */
    suspend fun setHome(component: String): Boolean

    /** Кому принадлежат сохранённые сети Wi-Fi. null — узнать не удалось. */
    suspend fun wifiOwners(): Set<String>?

    /** Дать системе применить изменения. */
    suspend fun settle()

    fun log(message: String, warning: Boolean = false)
}

/**
 * Смена главного экрана ТВ на экран Glide TV и возврат.
 *
 * Правила, которые нельзя нарушать (иначе ТВ останется без главного экрана):
 * - стандартный лаунчер отключается только отдельным действием и только когда экран Glide TV
 *   уже включён и зарегистрирован как Home; если после отключения Home не достался Glide TV — откат;
 * - экран Glide TV выключается только когда на ТВ есть другой включённый лаунчер;
 * - запись о прежнем экране делается до первого изменения и не перезаписывается повторным включением.
 *
 * Google TV. Там на Home претендует ещё и мастер первой настройки (setupwraith): приоритет его фильтра выше
 * нуля, а у экрана Glide TV он всегда ноль (Android учитывает приоритет только у приложений из прошивки).
 * Стоит отключить лаунчер — Home уходит мастеру; его RecoveryActivity на этом месте зацикливает
 * system_server, а перезагрузка в таком состоянии на части ТВ кончается сбросом к заводским настройкам
 * (разборы CleanYourTV, docs/TROUBLESHOOTING.md). set-home-activity при этом отвечает Success и ничего
 * не меняет. Отсюда главное правило:
 *
 * **мастер включён, а лаунчер выключен — такого состояния не должно быть ни на секунду.**
 * Отключаем: сначала мастер, потом лаунчер. Включаем: сначала лаунчер, потом мастер.
 */
class HomeSwitcher(
    private val device: HomeDevice,
    private val store: HomeStore,
) {
    private val selfComponent get() = "${device.self}/$ALIAS"

    private fun pkgOf(component: String?) = component?.substringBefore('/')

    private fun isGlide(component: String?) = pkgOf(component) == device.self

    private fun isChooser(component: String?) = component == null || pkgOf(component) == "android"

    /** Включённые лаунчеры, кроме Glide TV, запасного экрана «Настроек» и мастера настройки (они не лаунчеры). */
    private fun otherLaunchers(): List<String> =
        device.claimants().map { it.pkg }
            .filter { it != device.self && !CoreGuard.isCore(it) && it !in WIZARDS }
            .distinct()

    /** У чужого лаунчера приоритет фильтра выше нуля: такой получает Home независимо от выбора пользователя. */
    private fun strongerLauncherExists(): Boolean =
        device.claimants().any { it.pkg != device.self && !CoreGuard.isCore(it.pkg) && it.priority > 0 }

    private fun glideRegistered(): Boolean = device.claimants().any { it.pkg == device.self }

    /** Системные приложения, которые заберут Home, как только лаунчер [stock] будет отключён. */
    private fun blockers(stock: String): List<String> =
        device.claimants().filter { it.priority > 0 }.map { it.pkg }
            .filter { it != device.self && it != stock && !CoreGuard.isCore(it) }
            .distinct()

    private fun describeClaimants(): String =
        device.claimants().joinToString { "${it.pkg}/${it.cls.substringAfterLast('.')} p=${it.priority}" }

    private fun disabledByUs(): List<String> =
        store.disabledStock.split(SEP).map { it.trim() }.filter { PackageName.isValid(it) }

    private fun record(packages: List<String>) {
        store.disabledStock = packages.joinToString(SEP)
    }

    /** Лаунчер, который мешает: прежний Home, а если он неизвестен — единственный другой лаунчер. */
    private fun stockPackage(): String? {
        val previous = pkgOf(store.previousHome.ifEmpty { null })
        val others = otherLaunchers()
        return when {
            previous != null && previous in others -> previous
            others.size == 1 -> others[0]
            else -> null
        }
    }

    fun status(): HomeStatus {
        val now = device.resolvedHome()
        val active = device.isAliasEnabled()
        val glide = isGlide(now)
        val disabled = disabledByUs()
        val stock = stockPackage()
        val pending = active && !glide && disabled.isEmpty() && stock != null
        val blockers = if (pending) blockers(stock!!) else emptyList()
        val unknown = blockers.firstOrNull { it !in WIZARDS }
        val wizard = blockers.firstOrNull { it in WIZARDS }
        return HomeStatus(
            active = active,
            glideIsHome = glide,
            currentLabel = if (isChooser(now)) "" else device.label(pkgOf(now)!!),
            disabledStockLabel = disabled.firstOrNull()?.let { device.label(it) }.orEmpty(),
            stockCanBeDisabled = pending && unknown == null,
            wizardLabel = if (unknown == null) wizard?.let { device.label(it) }.orEmpty() else "",
            blockedBy = unknown?.let { device.label(it) }.orEmpty(),
        )
    }

    /** Включить экран Glide TV и попросить систему отдать ему кнопку Home. */
    suspend fun apply(): SwitchResult {
        // Лаунчер с более высоким приоритетом (так у Android TV и Google TV) система выбирает всегда,
        // что бы пользователь ни отметил в списке «Главный экран по умолчанию». Без «Турбо» его
        // не обойти — ничего не меняем и говорим об этом сразу.
        if (!device.hasShell && strongerLauncherExists()) return SwitchResult.NEED_TURBO
        val before = device.resolvedHome()
        // Первая запись — правда о том, как было; повторное включение её не затирает
        if (store.previousHome.isEmpty() && !isChooser(before) && !isGlide(before)) store.previousHome = before!!
        device.setAlias(true)
        if (!device.hasShell) return SwitchResult.NEED_PICKER
        val ok = device.setHome(selfComponent)
        device.log("Главный экран: set-home-activity → ${if (ok) "Success" else "отказ"}")
        device.settle()
        return when {
            isGlide(device.resolvedHome()) -> SwitchResult.DONE
            // Команды нет до Android 7: остаётся системный выбор главного экрана
            !ok -> SwitchResult.NEED_PICKER
            else -> SwitchResult.STOCK_STILL_HOME
        }
    }

    /**
     * Отключить стандартный лаунчер, который не отдаёт Home, а на Google TV — сначала мастер настройки.
     * Ничего не удаляется; [restore] включает всё обратно.
     */
    suspend fun disableStock(): SwitchResult {
        if (!device.hasShell) return SwitchResult.FAILED
        val stock = stockPackage() ?: return SwitchResult.FAILED
        if (!device.isAliasEnabled() || !glideRegistered()) return SwitchResult.FAILED
        if (!PackageName.isValid(stock) || stock == device.self || CoreGuard.isCore(stock)) return SwitchResult.FAILED

        // Всё проверяем до первого изменения: если Home достанется не Glide TV, лаунчер не отключаем вовсе
        val wizards = blockers(stock)
        val unknown = wizards.filter { it !in WIZARDS }
        if (unknown.isNotEmpty()) {
            device.log("Главный экран: $stock не отключаем — Home заберёт ${unknown.joinToString()}. Претенденты: ${describeClaimants()}", true)
            return SwitchResult.BLOCKED
        }
        if (wizards.isNotEmpty() && wizardOwnsWifi(wizards)) {
            device.log("Главный экран: сеть Wi-Fi принадлежит ${wizards.joinToString()} — не отключаем, иначе ТВ её забудет", true)
            return SwitchResult.WIFI_OWNED
        }

        // Запись до команд: даже если процесс умрёт посередине, «Вернуть как было» знает, что включать
        record(listOf(stock) + wizards)
        for (wizard in wizards) {
            device.disable(wizard)
            if (device.isEnabled(wizard)) {
                device.log("Главный экран: $wizard отключить не удалось — лаунчер не трогаем", true)
                undo(stock, wizards)
                return SwitchResult.FAILED
            }
        }
        if (wizards.isNotEmpty()) {
            device.settle()
            device.log("Главный экран: мастер настройки ${wizards.joinToString()} отключён")
            // Кто-то ещё претендует на Home с приоритетом — дальше идти нельзя
            val left = blockers(stock)
            if (left.isNotEmpty()) {
                device.log("Главный экран: Home всё ещё заберёт ${left.joinToString()} — откат. Претенденты: ${describeClaimants()}", true)
                undo(stock, wizards)
                return SwitchResult.BLOCKED
            }
        }

        device.disable(stock)
        device.setHome(selfComponent)
        device.settle()
        if (isGlide(device.resolvedHome())) {
            device.log("Главный экран: стандартный лаунчер $stock отключён")
            return SwitchResult.DONE
        }
        // Home не достался Glide TV — возвращаем лаунчер, чтобы ТВ не остался без главного экрана
        val got = device.resolvedHome() ?: "никто"
        val seen = describeClaimants()
        undo(stock, wizards)
        device.log("Главный экран: $stock отключён, но Home достался $got — откат. Претенденты: $seen", true)
        return SwitchResult.FAILED
    }

    /** Сеть, сохранённая мастером при первой настройке, принадлежит ему, и система удалит её вместе с ним. */
    private suspend fun wizardOwnsWifi(wizards: List<String>): Boolean {
        val owners = device.wifiOwners()
        // Узнать не удалось или сохранённых сетей не видно: рискуем, только если ТВ сейчас не на Wi-Fi
        if (owners.isNullOrEmpty()) return device.wifiInUse()
        return owners.any { owner -> wizards.any { owner.contains(it) } }
    }

    /** Откат и возврат: лаунчер включается первым, мастер — только когда лаунчер уже включён. */
    private suspend fun undo(stock: String, wizards: List<String>): Boolean {
        if (!device.isEnabled(stock)) device.enable(stock)
        if (!device.isEnabled(stock)) {
            // Мастер без лаунчера забрал бы Home — оставляем его выключенным; запись остаётся для повтора
            device.log("Главный экран: $stock включить не удалось, мастер настройки оставлен выключенным", true)
            return false
        }
        for (wizard in wizards) if (!device.isEnabled(wizard)) device.enable(wizard)
        val still = wizards.filter { !device.isEnabled(it) }
        record(still)
        if (still.isNotEmpty()) device.log("Главный экран: не удалось включить ${still.joinToString()}", true)
        return still.isEmpty()
    }

    /** Вернуть как было: включить отключённое (лаунчер первым), отдать ему Home и выключить экран Glide TV. */
    suspend fun restore(): RestoreResult {
        val disabled = disabledByUs()
        if (disabled.isNotEmpty()) {
            if (!device.hasShell) return RestoreResult.NEED_TURBO
            val wizards = disabled.filter { it in WIZARDS }
            val launchers = disabled - wizards.toSet()
            for (launcher in launchers) device.enable(launcher)
            val launchersLeft = launchers.filter { !device.isEnabled(it) }
            if (launchersLeft.isNotEmpty()) {
                record(launchersLeft + wizards)
                return RestoreResult.FAILED
            }
            // Мастер включаем, только когда на ТВ есть включённый лаунчер сильнее него
            if (wizards.isNotEmpty() && otherLaunchers().isEmpty()) {
                record(wizards)
                device.log("Главный экран: стандартного лаунчера нет — мастер настройки оставлен выключенным", true)
                return RestoreResult.FAILED
            }
            for (wizard in wizards) device.enable(wizard)
            val wizardsLeft = wizards.filter { !device.isEnabled(it) }
            record(wizardsLeft)
            if (wizardsLeft.isNotEmpty()) return RestoreResult.FAILED
        }
        val previous = store.previousHome
        if (device.hasShell && previous.isNotEmpty()) {
            val ok = device.setHome(previous)
            device.log("Главный экран: возврат на $previous → ${if (ok) "Success" else "отказ"}")
        }
        // Свой экран выключаем только когда Home есть кому отдать
        if (otherLaunchers().isEmpty()) return RestoreResult.FAILED
        device.setAlias(false)
        store.previousHome = ""
        return RestoreResult.DONE
    }

    /**
     * Проверка после загрузки. Некоторые прошивки Google TV включают мастер настройки обратно при каждом
     * холодном старте; с выключенным лаунчером Home тогда уходит мастеру (чёрный экран). Отключаем мастер
     * снова, а если не выходит — возвращаем стандартный лаунчер. true — что-то пришлось чинить.
     */
    suspend fun heal(): Boolean {
        val disabled = disabledByUs()
        val launchers = disabled.filter { it !in WIZARDS }
        if (launchers.isEmpty() || launchers.any { device.isEnabled(it) }) return false
        val back = WIZARDS.filter { it in disabled && device.isEnabled(it) }
        if (back.isEmpty() || !device.hasShell) return false
        device.log("Главный экран: прошивка включила ${back.joinToString()} обратно — отключаем снова", true)
        for (wizard in back) device.disable(wizard)
        if (back.any { device.isEnabled(it) }) {
            device.log("Главный экран: мастер не отключается — возвращаем стандартный лаунчер", true)
            for (launcher in launchers) device.enable(launcher)
            record(disabled.filter { !device.isEnabled(it) })
            return true
        }
        device.setHome(selfComponent)
        device.settle()
        if (!isGlide(device.resolvedHome())) {
            device.log("Главный экран: Home достался ${device.resolvedHome()} — возвращаем стандартный лаунчер", true)
            undo(launchers.first(), back)
        }
        return true
    }

    /** Системный экран выбора лаунчера (есть не на всех ТВ). */
    fun openPicker(context: Context): Boolean = runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    companion object {
        const val ALIAS = "app.glidetv.home.HomeAlias"
        private const val SEP = ","

        /**
         * Мастера первой настройки, которые Glide TV умеет отключать ради Home. Только известные: в повседневной
         * работе ТВ они не участвуют, а отключать вслепую любое системное приложение с приоритетом нельзя.
         */
        val WIZARDS = setOf("com.google.android.tungsten.setupwraith")
    }
}
