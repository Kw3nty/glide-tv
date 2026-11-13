package app.glidetv.debloat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class JournalEntry(val pkg: String, val disabledAt: Long)

/**
 * Что именно отключил Glide TV — чтобы «Вернуть всё» включало только наши изменения,
 * а не пакеты, которые владелец или завод отключили раньше.
 */
class DebloatJournal(context: Context) {
    private val file = File(context.filesDir, "debloat_journal.json")

    @Synchronized
    fun entries(): List<JournalEntry> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            JournalEntry(o.getString("pkg"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(pkg: String) {
        val list = entries().filterNot { it.pkg == pkg } + JournalEntry(pkg, System.currentTimeMillis())
        save(list)
    }

    @Synchronized
    fun remove(pkg: String) = save(entries().filterNot { it.pkg == pkg })

    fun contains(pkg: String): Boolean = entries().any { it.pkg == pkg }

    private fun save(list: List<JournalEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("pkg", it.pkg).put("at", it.disabledAt)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
