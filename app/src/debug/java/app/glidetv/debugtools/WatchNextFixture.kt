package app.glidetv.debugtools

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Кладёт в провайдер ТВ тестовые строки «Продолжить просмотр» от имени отладочной сборки.
 * Читает их release-сборка (другой пакет) — так проверяется и разрешение, и отбор строк.
 * Ожидаемо видны: «Дюна», «Без обложки», «Во все тяжкие»; остальные три отбор должен убрать.
 */
class WatchNextFixture : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = Uri.parse("content://android.media.tv/watch_next_program")
        val resolver = context.contentResolver
        val removed = runCatching { resolver.delete(uri, null, null) }.getOrDefault(-1)
        if (intent.hasExtra("clear")) {
            Log.i(TAG, "cleared $removed rows")
            return
        }
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val day = 24 * hour
        val settings = "intent:#Intent;action=android.settings.SETTINGS;end"
        fun row(title: String, type: Int, engaged: Long, position: Long?, duration: Long?, poster: String?, block: ContentValues.() -> Unit = {}) =
            ContentValues().apply {
                put("type", 0)
                put("watch_next_type", type)
                put("title", title)
                put("last_engagement_time_utc_millis", engaged)
                if (position != null) put("last_playback_position_millis", position)
                if (duration != null) put("duration_millis", duration)
                if (poster != null) put("poster_art_uri", poster)
                put("intent_uri", settings)
                put("internal_provider_id", title)
                block()
            }
        val rows = listOf(
            row("Дюна: Часть вторая", 0, now - hour, 40 * 60_000L, 100 * 60_000L,
                "https://raw.githubusercontent.com/Kw3nty/glide-tv/main/docs/screenshots/home-dock.png"),
            row("Во все тяжкие", 1, now - day, null, null, "android.resource://${context.packageName}/drawable/banner") {
                put("type", 3)
                put("season_display_number", "2")
                put("episode_display_number", "5")
                put("episode_title", "Breakage")
            },
            row("Без обложки", 0, now - 2 * hour, 6 * 60_000L, 60 * 60_000L, null),
            row("Старое: три месяца назад", 0, now - 90 * day, 10 * 60_000L, 60 * 60_000L, null),
            row("Досмотрено до конца", 0, now - 3 * hour, 99 * 60_000L, 100 * 60_000L, null),
            row("Реклама новинки", 2, now - hour, null, null, null),
        )
        var ok = 0
        for (values in rows) if (runCatching { resolver.insert(uri, values) }.getOrNull() != null) ok++
        Log.i(TAG, "removed $removed, inserted $ok of ${rows.size}")
    }

    private companion object {
        const val TAG = "GlideTVFixture"
    }
}
