package app.glidetv.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import app.glidetv.util.Logx
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Строка «Продолжить просмотр», которую приложение само положило в системную таблицу ТВ. */
data class WatchItem(
    val id: Long,
    val pkg: String,
    val title: String,
    /** «Сезон 1 · Серия 3 · Название серии» — что есть. */
    val subtitle: String,
    val poster: String?,
    val intentUri: String?,
    /** [WatchNext.TYPE_CONTINUE], [WatchNext.TYPE_NEXT] и т. д. */
    val type: Int,
    val engagedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
) {
    /** Доля просмотренного 0..1; -1 — приложение её не сообщило. */
    val progress: Float
        get() = if (durationMs > 0 && positionMs >= 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else -1f
}

/**
 * «Продолжить просмотр» (Watch Next) из провайдера ТВ: `content://android.media.tv/watch_next_program`.
 *
 * Как это устроено (разбор в CleanYourTV, docs/watch-next.md; логика — TvProvider.createSqlParams):
 * - нужно обычное runtime-разрешение `android.permission.READ_TV_LISTINGS`; с ним видны свои строки и все строки,
 *   которые приложения пометили `searchable`. Системное ACCESS_ALL_EPG_DATA стороннему приложению недоступно;
 * - запрос без условия отбора: с условием провайдер бросает SecurityException. Читаем всё и отбираем сами;
 * - колонки называем явно: на части прошивок у таблицы нет `split_id`, и запрос с проекцией null падает;
 * - строки живут месяцами и бывают рекламой, поэтому отбор по типу, давности и доле просмотренного — [pick].
 *
 * Что покажется, зависит от приложений: одни пишут сюда историю, другие (например, YouTube) — нет.
 */
object WatchNext {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"

    const val TYPE_CONTINUE = 0
    const val TYPE_NEXT = 1

    // Строкой, а не Uri: отбор строк ([pick]) проверяется обычными тестами, без Android
    private const val TABLE = "content://android.media.tv/watch_next_program"
    private val COLUMNS = arrayOf(
        "_id", "package_name", "title", "episode_title", "season_display_number", "episode_display_number",
        "poster_art_uri", "intent_uri", "watch_next_type", "last_engagement_time_utc_millis",
        "last_playback_position_millis", "duration_millis",
    )

    private const val MAX_AGE_MS = 45L * 24 * 60 * 60 * 1000
    private const val FINISHED = 0.95f
    private const val LIMIT = 12

    /** Таблица появилась в Android 8. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Все видимые строки как есть. Пустой список — нет разрешения, нет таблицы или никто ничего не писал. */
    fun read(context: Context): List<WatchItem> {
        if (!hasPermission(context)) return emptyList()
        return runCatching {
            val out = ArrayList<WatchItem>()
            context.contentResolver.query(Uri.parse(TABLE), COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val season = c.getString(4).orEmpty().trim()
                    val episode = c.getString(5).orEmpty().trim()
                    val episodeTitle = c.getString(3).orEmpty().trim()
                    out += WatchItem(
                        id = c.getLong(0),
                        pkg = c.getString(1).orEmpty(),
                        title = c.getString(2).orEmpty().trim(),
                        subtitle = listOfNotNull(
                            season.takeIf { it.isNotEmpty() }?.let { "S$it" },
                            episode.takeIf { it.isNotEmpty() }?.let { "E$it" },
                            episodeTitle.takeIf { it.isNotEmpty() },
                        ).joinToString(" · "),
                        poster = c.getString(6)?.trim()?.takeIf { it.isNotEmpty() },
                        intentUri = c.getString(7)?.trim()?.takeIf { it.isNotEmpty() },
                        type = if (c.isNull(8)) TYPE_CONTINUE else c.getInt(8),
                        engagedAt = c.getLong(9),
                        positionMs = if (c.isNull(10)) -1 else c.getLong(10),
                        durationMs = if (c.isNull(11)) -1 else c.getLong(11),
                    )
                }
            }
            out
        }.onFailure { Logx.w("Продолжить просмотр: таблицу прочитать не удалось", it) }.getOrDefault(emptyList())
    }

    /**
     * Что показывать: только «продолжить» и «следующая серия» (остальное — новинки и реклама), не старше
     * полутора месяцев, не досмотренное до конца, от установленных и не скрытых приложений, новые первыми.
     */
    fun pick(rows: List<WatchItem>, now: Long, allowed: Set<String>, self: String): List<WatchItem> =
        rows.asSequence()
            .filter { it.type == TYPE_CONTINUE || it.type == TYPE_NEXT }
            .filter { it.title.isNotEmpty() && it.pkg != self && it.pkg in allowed }
            .filter { it.engagedAt <= 0 || now - it.engagedAt <= MAX_AGE_MS }
            .filter { it.type != TYPE_CONTINUE || it.progress < FINISHED }
            .sortedByDescending { it.engagedAt }
            .distinctBy { it.pkg to (it.title + "|" + it.subtitle) }
            .take(LIMIT)
            .toList()

    /**
     * Intent строки. Его составило чужое приложение, поэтому запускаем не как есть: без селектора, без выдачи
     * доступа к URI (иначе через Glide TV можно было бы открыть чужому приложению файлы его провайдера)
     * и только не в сам Glide TV. null — открыть нельзя, тогда запускаем само приложение.
     */
    fun intentOf(item: WatchItem, self: String): Intent? {
        val raw = item.intentUri ?: return null
        val intent = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        if (intent.`package` == self || intent.component?.packageName == self) return null
        intent.selector = null
        intent.clipData = null
        intent.flags = intent.flags and GRANT_FLAGS.inv()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
}

/**
 * Обложки для «Продолжить просмотр». Приложения дают их ссылкой: обычно https, реже content:// или
 * android.resource://. Это единственное место, где главный экран ходит в сеть, и только пока строка включена.
 * Кеш — в памяти, на диск ничего не пишется.
 */
object Posters {
    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val TIMEOUT_MS = 6000

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(uri: String): Bitmap? = cache.get(uri)

    /** Загрузить и уменьшить до ширины около [width]. Вызывать не в главном потоке. null — не получилось. */
    fun load(context: Context, uri: String, width: Int): Bitmap? {
        cache.get(uri)?.let { return it }
        val bytes = runCatching { fetch(context, uri) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cache.put(uri, bitmap)
        return bitmap
    }

    private fun fetch(context: Context, uri: String): ByteArray? {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme?.lowercase()) {
            "https" -> {
                val connection = URL(uri).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.instanceFollowRedirects = true
                    if (connection.responseCode != 200) null else connection.inputStream.use { limited(it) }
                } finally {
                    connection.disconnect()
                }
            }
            // http без шифрования не открываем; file:// — тем более: это были бы файлы самого Glide TV
            "content", "android.resource" -> context.contentResolver.openInputStream(parsed)?.use { limited(it) }
            else -> null
        }
    }

    private fun limited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }
}
