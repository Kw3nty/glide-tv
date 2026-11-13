package app.glidetv.debloat

/**
 * Пакеты, которые Glide TV не отключает никогда. Основано на защитах CleanYourTV
 * (tools/disable.sh: CORE_RE/CORE_OK, #884, #1578) и разборе их отчётов о поломках.
 */
object CoreGuard {
    /** Ядро Android и ТВ-middleware: без них ТВ может перестать загружаться. */
    private val CORE = Regex(
        "^(android|android\\.ext\\.(services|shared)|com\\.android\\.sdksandbox|com\\.android\\.location\\.fused|" +
            "com\\.android\\.systemui|com\\.android\\.shell|com\\.android\\.phone|com\\.android\\.settings|" +
            "com\\.android\\.tv\\.settings|com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|" +
            "ext\\.services|ext\\.shared|modulemetadata)|com\\.google\\.android\\.overlay\\.modules.*|" +
            "com\\.android\\.providers\\..*|com\\.cltv\\.mal|com\\.mediatek\\.(tis|tv\\.service|tv\\.agent))$",
    )
    private val CORE_OK = Regex("^com\\.android\\.providers\\.(calendar|contacts|userdictionary|blockednumber)$")

    /** Отключение ломает конкретные функции ТВ (сеть, пульт, магазин, лаунчер, настройки). */
    private val NEVER = setOf(
        // Мастер Google TV: владеет сохранённой при настройке Wi-Fi-сетью — её удалит система
        "com.google.android.tungsten.setupwraith",
        // Стоковые лаунчеры: своего лаунчера у Glide TV нет
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher", "com.amazon.tv.launcher", "com.amazon.firehomestarter",
        // Магазин, сервисы Google, WebView
        "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.webview", "com.android.webview", "com.google.android.trichromelibrary",
        // Пульт и Bluetooth
        "com.google.android.tv.remote.service", "com.android.bluetooth", "com.google.android.tv.remote",
        // Xiaomi: «Настройки» не открываются без TvService (CleanYourTV #1310, #3085)
        "mitv.service", "com.xiaomi.android.tvsetup.partnercustomizer",
        // OnePlus: без него HDMI показывает «Нет сигнала» (#321)
        "com.heytap.tv.support",
        // NVIDIA SHIELD: без него не работает приложение-пульт (bisected на двух SHIELD)
        "com.nvidia.shield.remote.server",
        // Клавиатура Android TV по умолчанию
        "com.google.android.leanback.ime", "com.google.android.inputmethod.latin",
    )

    /** Можно отключить, но по умолчанию не отмечаем: пользователь мог этим пользоваться. */
    private val WARN = mapOf(
        "com.google.android.katniss" to Risk.VOICE,
        "com.google.android.speech.pumpkin" to Risk.VOICE,
        "com.google.android.apps.mediashell" to Risk.CAST,
        "com.google.android.tvrecommendations" to Risk.REMOTE_APPS,
        "com.google.android.apps.tv.dreamx" to Risk.SCREENSAVER,
        "com.google.android.backdrop" to Risk.SCREENSAVER,
        "com.google.android.marvin.talkback" to Risk.ACCESSIBILITY,
        "com.google.android.tts" to Risk.ACCESSIBILITY,
        "com.google.android.tv" to Risk.LIVE_TV,
        "fusion.android.tv.demo" to Risk.INPUT_KEY,
    )

    fun isCore(pkg: String): Boolean = CORE.matches(pkg) && !CORE_OK.matches(pkg)

    fun isNever(pkg: String): Boolean = isCore(pkg) || pkg in NEVER

    fun warning(pkg: String): Risk? = WARN[pkg]
}

enum class Risk { VOICE, CAST, REMOTE_APPS, SCREENSAVER, ACCESSIBILITY, LIVE_TV, INPUT_KEY }
