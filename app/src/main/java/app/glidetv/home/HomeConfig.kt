package app.glidetv.home

import android.content.Context
import android.content.SharedPreferences

/** Раскладка главного экрана: чем пресеты отличаются по-настоящему, а не только цветом. */
enum class HomeLayout { GRID, SHELF, LIST, TILES, ROWS }

/** Как выглядит ряд избранного. */
enum class DockStyle { BAND, NONE, GLASS }

/** Фон главного экрана. Все фоны рисуются кодом: без картинок в APK, без сети. */
enum class HomeBackground(val key: String) {
    NAVY("navy"), BLACK("black"), GRAPHITE("graphite"), AURORA("aurora"), SUNSET("sunset"), OCEAN("ocean");

    companion object {
        fun of(key: String?): HomeBackground? = values().firstOrNull { it.key == key }
    }
}

/**
 * Пресет — набор значений по умолчанию. Выбор пресета сбрасывает колонки, фон и подписи
 * к значениям пресета; после этого каждое из них можно поменять отдельно.
 */
enum class HomePreset(
    val key: String,
    val layout: HomeLayout,
    val columns: Int,
    val background: HomeBackground,
    val dock: DockStyle,
    val labels: Boolean,
) {
    /** Полоса избранного и сетка под ней. */
    DOCK("dock", HomeLayout.GRID, 5, HomeBackground.NAVY, DockStyle.BAND, true),

    /** Чистый чёрный для OLED: без полосы и подписей, название только у приложения в фокусе. */
    OLED("oled", HomeLayout.GRID, 6, HomeBackground.BLACK, DockStyle.NONE, false),

    /** Полупрозрачная «стеклянная» полоса поверх нарисованного фона. */
    GLASS("glass", HomeLayout.GRID, 5, HomeBackground.AURORA, DockStyle.GLASS, true),

    /** Название приложения крупно, свечение в его цвете, одна лента внизу (верхняя полка CleanYourTV). */
    CINEMA("cinema", HomeLayout.SHELF, 6, HomeBackground.BLACK, DockStyle.NONE, false),

    /** Только часы и список названий, без иконок (форк Zen list). */
    ZEN("zen", HomeLayout.LIST, 5, HomeBackground.SUNSET, DockStyle.NONE, false),

    /** Плоские цветные плитки с названием внутри (форк Retro Launchers, тема Retro Metro). */
    METRO("metro", HomeLayout.TILES, 5, HomeBackground.BLACK, DockStyle.NONE, true),

    /** Горизонтальные ряды с заголовками (форк Retro Launchers, Leanback Launcher 2014 года). */
    ROWS("rows", HomeLayout.ROWS, 5, HomeBackground.GRAPHITE, DockStyle.NONE, true);

    companion object {
        fun of(key: String?): HomePreset? = values().firstOrNull { it.key == key }
    }
}

/**
 * Настройки главного экрана Glide TV и запись о том, что было до его включения.
 * Хранятся отдельно от остальных настроек: главный экран читает их при каждом показе.
 */
class HomeConfig(context: Context) : HomeStore {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("glide_home", Context.MODE_PRIVATE)

    /** Растёт при каждом изменении вида: главный экран перерисовывается, только если значение изменилось. */
    val stamp: Int get() = sp.getInt(K_STAMP, 0)

    private fun change(block: (SharedPreferences.Editor) -> Unit) {
        val editor = sp.edit()
        block(editor)
        editor.putInt(K_STAMP, stamp + 1)
        editor.apply()
    }

    val preset: HomePreset get() = HomePreset.of(sp.getString(K_PRESET, null)) ?: HomePreset.DOCK

    /** Выбрать пресет: колонки, фон и подписи возвращаются к его значениям. Избранное не трогаем. */
    fun applyPreset(preset: HomePreset) = change {
        it.putString(K_PRESET, preset.key)
        it.remove(K_COLUMNS).remove(K_BACKGROUND).remove(K_LABELS)
    }

    var columns: Int
        get() = sp.getInt(K_COLUMNS, preset.columns).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        set(v) = change { it.putInt(K_COLUMNS, v.coerceIn(MIN_COLUMNS, MAX_COLUMNS)) }

    var background: HomeBackground
        get() = HomeBackground.of(sp.getString(K_BACKGROUND, null)) ?: preset.background
        set(v) = change { it.putString(K_BACKGROUND, v.key) }

    /** Подписи под всеми приложениями. Если выключено, название видно только у приложения в фокусе. */
    var labels: Boolean
        get() = sp.getBoolean(K_LABELS, preset.labels)
        set(v) = change { it.putBoolean(K_LABELS, v) }

    var clock: Boolean
        get() = sp.getBoolean(K_CLOCK, true)
        set(v) = change { it.putBoolean(K_CLOCK, v) }

    /** Строка «Продолжить просмотр» в «Кино». Единственное, ради чего главный экран ходит в сеть (обложки). */
    var watchNext: Boolean
        get() = sp.getBoolean(K_WATCH, true)
        set(v) = change { it.putBoolean(K_WATCH, v) }

    /** Пакеты в ряду избранного, по порядку. Пусто — ряд собирается автоматически. */
    var favorites: List<String>
        get() = split(sp.getString(K_FAVORITES, ""))
        set(v) = change { it.putString(K_FAVORITES, v.distinct().joinToString(SEP)) }

    /** Пакеты, скрытые с главного экрана (сами приложения не отключаются). */
    var hidden: Set<String>
        get() = split(sp.getString(K_HIDDEN, "")).toSet()
        set(v) = change { it.putString(K_HIDDEN, v.joinToString(SEP)) }

    /** Компонент «пакет/класс», который отвечал на кнопку Home до Glide TV. Пусто — ещё не меняли. */
    override var previousHome: String
        get() = sp.getString(K_PREVIOUS, "") ?: ""
        set(v) = sp.edit().putString(K_PREVIOUS, v).apply()

    /**
     * Пакеты, которые Glide TV отключил сам ради Home, через запятую: лаунчер, за ним мастер настройки Google TV.
     * Только их «Вернуть как было» включает обратно.
     */
    override var disabledStock: String
        get() = sp.getString(K_DISABLED, "") ?: ""
        set(v) = sp.edit().putString(K_DISABLED, v).apply()

    private fun split(value: String?): List<String> =
        value.orEmpty().split(SEP).filter { it.isNotBlank() }

    companion object {
        const val MIN_COLUMNS = 4
        const val MAX_COLUMNS = 7
        private const val SEP = ","
        private const val K_STAMP = "stamp"
        private const val K_PRESET = "preset"
        private const val K_COLUMNS = "columns"
        private const val K_BACKGROUND = "background"
        private const val K_LABELS = "labels"
        private const val K_CLOCK = "clock"
        private const val K_WATCH = "watch_next"
        private const val K_FAVORITES = "favorites"
        private const val K_HIDDEN = "hidden"
        private const val K_PREVIOUS = "previous_home"
        private const val K_DISABLED = "disabled_stock"
    }
}
