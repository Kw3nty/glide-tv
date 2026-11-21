package app.glidetv

import app.glidetv.home.WatchItem
import app.glidetv.home.WatchNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Отбор строк «Продолжить просмотр»: что из системной таблицы попадает на главный экран. */
class WatchNextTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val self = "app.glidetv"
    private val allowed = setOf("tv.kino", "tv.serials", self)

    private fun item(
        id: Long,
        title: String = "Фильм $id",
        pkg: String = "tv.kino",
        type: Int = WatchNext.TYPE_CONTINUE,
        engagedAt: Long = now - hour,
        positionMs: Long = 10 * 60_000L,
        durationMs: Long = 100 * 60_000L,
        subtitle: String = "",
    ) = WatchItem(id, pkg, title, subtitle, null, null, type, engagedAt, positionMs, durationMs)

    private fun pick(vararg rows: WatchItem) = WatchNext.pick(rows.toList(), now, allowed, self).map { it.id }

    @Test
    fun `continue and next episode are shown, everything else is not`() {
        assertEquals(
            listOf(1L, 2L),
            pick(
                item(1, type = WatchNext.TYPE_CONTINUE),
                item(2, type = WatchNext.TYPE_NEXT, engagedAt = now - 2 * hour),
                item(3, type = 2), // новинка
                item(4, type = 3), // «буду смотреть»
            ),
        )
    }

    @Test
    fun `newest first`() {
        assertEquals(
            listOf(3L, 1L, 2L),
            pick(item(1, engagedAt = now - 2 * hour), item(2, engagedAt = now - 3 * day), item(3, engagedAt = now - hour)),
        )
    }

    @Test
    fun `rows older than a month and a half are dropped`() {
        assertEquals(listOf(1L), pick(item(1, engagedAt = now - 44 * day), item(2, engagedAt = now - 46 * day)))
    }

    @Test
    fun `a row without a time is kept and goes last`() {
        assertEquals(listOf(2L, 1L), pick(item(1, engagedAt = 0), item(2)))
    }

    @Test
    fun `watched to the end is dropped, but not for the next episode`() {
        val watched = 99 * 60_000L
        assertEquals(
            listOf(2L, 3L),
            pick(
                item(1, positionMs = watched),
                item(2, positionMs = 94 * 60_000L),
                item(3, type = WatchNext.TYPE_NEXT, positionMs = watched, engagedAt = now - 2 * hour),
            ),
        )
    }

    @Test
    fun `unknown progress is not treated as finished`() {
        val unknown = item(1, positionMs = -1, durationMs = -1)
        assertEquals(-1f, unknown.progress)
        assertEquals(listOf(1L), pick(unknown))
        assertEquals(0.1f, item(2).progress, 0.001f)
        assertEquals(1f, item(3, positionMs = 500 * 60_000L).progress)
    }

    @Test
    fun `hidden, removed and own rows are dropped`() {
        assertEquals(
            listOf(1L),
            pick(item(1), item(2, pkg = "tv.removed"), item(3, pkg = self), item(4, title = "")),
        )
    }

    @Test
    fun `the same title from one app is shown once, the newest row`() {
        assertEquals(
            listOf(2L, 3L, 4L),
            pick(
                item(1, title = "Сериал", subtitle = "S1 · E2", engagedAt = now - 5 * hour),
                item(2, title = "Сериал", subtitle = "S1 · E2", engagedAt = now - hour),
                item(3, title = "Сериал", subtitle = "S1 · E3", engagedAt = now - 2 * hour),
                item(4, title = "Сериал", subtitle = "S1 · E2", pkg = "tv.serials", engagedAt = now - 3 * hour),
            ),
        )
    }

    @Test
    fun `no more than twelve`() {
        val rows = (1L..30L).map { item(it, engagedAt = now - it * hour) }
        val picked = WatchNext.pick(rows, now, allowed, self)
        assertEquals(12, picked.size)
        assertTrue(picked.first().id == 1L && picked.last().id == 12L)
    }
}
