package app.glidetv.util

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("glide", Context.MODE_PRIVATE)

    /** Пользователь хотя бы раз успешно включил «Турбо» через встроенный ADB. */
    var turboEnabled: Boolean
        get() = sp.getBoolean("turbo_enabled", false)
        set(v) = sp.edit().putBoolean("turbo_enabled", v).apply()

    /** Последний порт, на котором удалось подключиться к adbd (5555, свой порт прошивки или TLS). */
    var lastAdbPort: Int
        get() = sp.getInt("last_adb_port", -1)
        set(v) = sp.edit().putInt("last_adb_port", v).apply()

    /** Подключение шло по TLS (Wireless debugging, Android 11+). */
    var lastAdbTls: Boolean
        get() = sp.getBoolean("last_adb_tls", false)
        set(v) = sp.edit().putBoolean("last_adb_tls", v).apply()

    /** Разрешить использовать root, если он найден. */
    var allowRoot: Boolean
        get() = sp.getBoolean("allow_root", true)
        set(v) = sp.edit().putBoolean("allow_root", v).apply()

    /** Пользователь уже подтверждал запрос root — можно использовать его при запуске. */
    var rootGranted: Boolean
        get() = sp.getBoolean("root_granted", false)
        set(v) = sp.edit().putBoolean("root_granted", v).apply()

    /** «Освобождать RAM» в сценарии «Оптимизировать». */
    var optimizeKillBackground: Boolean
        get() = sp.getBoolean("opt_kill_bg", true)
        set(v) = sp.edit().putBoolean("opt_kill_bg", v).apply()

    /** "" — как в системе, иначе "ru" / "en". */
    var language: String
        get() = sp.getString("language", "") ?: ""
        set(v) = sp.edit().putString("language", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()
}
