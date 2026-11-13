package app.glidetv.debloat

import android.content.Context
import app.glidetv.system.PlatformInfo
import app.glidetv.util.Logx
import org.json.JSONObject

data class ProfilePackage(val pkg: String, val active: Boolean, val note: String)

data class DeviceProfile(
    val id: String,
    val title: String,
    val os: String,
    val brand: String,
    val keys: Set<String>,
    val packages: List<ProfilePackage>,
)

/** Насколько часто пакет отключают на других ТВ: a — отключён, c — оставлен по выбору владельца. */
data class PackageStat(val active: Int, val commented: Int, val note: String) {
    val lists: Int get() = active + commented
}

/**
 * Проверенные списки CleanYourTV (MIT): какие пакеты владельцы конкретных моделей
 * ТВ отключали без последствий. Генерируется скриптом tools/gen_debloat_db.py.
 */
class DebloatDb(
    val revision: String,
    val profiles: List<DeviceProfile>,
    val stats: Map<String, PackageStat>,
) {
    /** Лучший профиль для этого устройства или null, если модели в базе нет. */
    fun match(p: PlatformInfo): DeviceProfile? {
        val model = p.model.lowercase()
        val modelCompact = model.replace(" ", "").replace("_", "-")
        val device = p.device.lowercase()
        val product = p.product.lowercase()
        val brand = p.brand.lowercase()
        val manufacturer = p.manufacturer.lowercase()

        return profiles.map { prof ->
            var score = 0
            if (device.isNotEmpty() && device in prof.keys) score += 4
            if (model in prof.keys || modelCompact in prof.keys) score += 4
            if (product.isNotEmpty() && product in prof.keys) score += 3
            if (prof.brand == brand || prof.brand == manufacturer) score += 1
            prof to score
        }.filter { it.second >= 4 }
            .maxByOrNull { it.second }
            ?.first
    }

    companion object {
        fun load(context: Context): DebloatDb = try {
            val text = context.assets.open("debloat_db.json").bufferedReader().use { it.readText() }
            parse(text)
        } catch (t: Throwable) {
            Logx.e("debloat_db.json не загружен", t)
            DebloatDb("none", emptyList(), emptyMap())
        }

        fun parse(text: String): DebloatDb {
            val root = JSONObject(text)
            val profilesJson = root.getJSONArray("profiles")
            val profiles = (0 until profilesJson.length()).map { i ->
                val o = profilesJson.getJSONObject(i)
                val keysJson = o.getJSONArray("keys")
                val pkgsJson = o.getJSONArray("pkgs")
                DeviceProfile(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    os = o.optString("os"),
                    brand = o.optString("brand"),
                    keys = (0 until keysJson.length()).map { keysJson.getString(it) }.toSet(),
                    packages = (0 until pkgsJson.length()).map { j ->
                        val a = pkgsJson.getJSONArray(j)
                        ProfilePackage(a.getString(0), a.getInt(1) == 1, a.optString(2))
                    },
                )
            }
            val statsJson = root.getJSONObject("packages")
            val stats = HashMap<String, PackageStat>(statsJson.length())
            statsJson.keys().forEach { k ->
                val o = statsJson.getJSONObject(k)
                stats[k] = PackageStat(o.optInt("a"), o.optInt("c"), o.optString("n"))
            }
            return DebloatDb(root.optString("revision"), profiles, stats)
        }
    }
}
