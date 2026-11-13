package app.glidetv.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/** Переключение языка RU/EN без AndroidX AppCompat (работает с API 21). */
object Locales {
    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences("glide", Context.MODE_PRIVATE).getString("language", "") ?: ""
        if (lang.isEmpty()) return base
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            base.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            base.resources.updateConfiguration(config, base.resources.displayMetrics)
            base
        }
    }
}
