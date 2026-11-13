package app.glidetv.engine

/**
 * Имя пакета, проверенное по грамматике Android. Всё, что попадает в shell,
 * проходит через этот тип: так исключается инъекция команд через имя пакета.
 */
@JvmInline
value class PackageName(val value: String) {
    init {
        require(PATTERN.matches(value)) { "bad package name: $value" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        fun isValid(value: String): Boolean = PATTERN.matches(value)
    }
}

/** Разрешения, которые «Турбо» может выдать самому Glide TV. Только себе и только из этого списка. */
enum class SelfPermission(val value: String) {
    WRITE_SECURE_SETTINGS("android.permission.WRITE_SECURE_SETTINGS"),
}

/** App-ops, которые «Турбо» включает самому Glide TV. */
enum class SelfAppOp(val op: String, val minSdk: Int) {
    USAGE_STATS("GET_USAGE_STATS", 21),
    ALL_FILES("MANAGE_EXTERNAL_STORAGE", 30),
    OVERLAY("SYSTEM_ALERT_WINDOW", 23),
}

/** Допустимые значения масштаба анимаций в «Параметрах разработчика». */
enum class AnimScale(val value: String) { OFF("0"), HALF("0.5"), NORMAL("1") }

/** Чтение состояния без изменений. */
enum class Query(val command: String) {
    WHOAMI("id"),
    WIFI_OWNERS("dumpsys wifi"),
    ADB_SECURE("getprop ro.adb.secure"),
    DISABLED_PACKAGES("pm list packages -d"),
    HOME_ACTIVITY("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"),
}

/**
 * Все команды, которые Glide TV вообще умеет отправлять в shell.
 * Произвольных строк нет: каждая команда собирается из проверенных частей.
 */
sealed interface ShellCmd {
    fun render(selfPkg: PackageName): String

    /** Освободить кеш всех приложений (система сама чистит, пока не освободит запрошенный объём). */
    data object TrimCaches : ShellCmd {
        override fun render(selfPkg: PackageName) = "pm trim-caches 999G"
    }

    /** Завершить все «безопасные для завершения» фоновые процессы. */
    data object KillAll : ShellCmd {
        override fun render(selfPkg: PackageName) = "am kill-all"
    }

    data class ForceStop(val pkg: PackageName) : ShellCmd {
        override fun render(selfPkg: PackageName) = "am force-stop $pkg"
    }

    /** Отключить пакет для основного пользователя. Ничего не удаляется. */
    data class DisableUser(val pkg: PackageName) : ShellCmd {
        override fun render(selfPkg: PackageName) = "pm disable-user --user 0 $pkg"
    }

    data class Enable(val pkg: PackageName) : ShellCmd {
        override fun render(selfPkg: PackageName) = "pm enable --user 0 $pkg"
    }

    /** AOT-компиляция: ускоряет холодный старт тяжёлых приложений (Android 7+). */
    data class CompileSpeed(val pkg: PackageName) : ShellCmd {
        override fun render(selfPkg: PackageName) = "cmd package compile -m speed -f $pkg"
    }

    data class CompileReset(val pkg: PackageName) : ShellCmd {
        override fun render(selfPkg: PackageName) = "cmd package compile --reset $pkg"
    }

    /** Запрет фоновой работы (Android 9+). write-settings: иначе app-op теряется при перезагрузке. */
    data class BackgroundRestrict(val pkg: PackageName, val restrict: Boolean) : ShellCmd {
        override fun render(selfPkg: PackageName) =
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ${if (restrict) "ignore" else "allow"}; cmd appops write-settings"
    }

    data class SetAnimations(val scale: AnimScale) : ShellCmd {
        override fun render(selfPkg: PackageName) = listOf(
            "window_animation_scale", "transition_animation_scale", "animator_duration_scale",
        ).joinToString("; ") { "settings put global $it ${scale.value}" }
    }

    data class GrantSelf(val permission: SelfPermission) : ShellCmd {
        override fun render(selfPkg: PackageName) = "pm grant $selfPkg ${permission.value}"
    }

    data class AllowSelfAppOp(val op: SelfAppOp) : ShellCmd {
        override fun render(selfPkg: PackageName) = "appops set $selfPkg ${op.op} allow"
    }

    data object PersistAppOps : ShellCmd {
        override fun render(selfPkg: PackageName) = "cmd appops write-settings"
    }

    /** Включить Wireless debugging (Android 11+), чтобы переподключаться после перезагрузки. */
    data object EnableWirelessDebugging : ShellCmd {
        override fun render(selfPkg: PackageName) = "settings put global adb_wifi_enabled 1"
    }

    /** Выключить отладку полностью. Соединение «Турбо» после этого оборвётся. */
    data object DisableDebugging : ShellCmd {
        override fun render(selfPkg: PackageName) =
            "settings put global adb_wifi_enabled 0; settings put global adb_enabled 0"
    }

    data class Read(val query: Query) : ShellCmd {
        override fun render(selfPkg: PackageName) = query.command
    }
}

data class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}
