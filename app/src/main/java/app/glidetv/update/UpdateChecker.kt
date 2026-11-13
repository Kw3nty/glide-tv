package app.glidetv.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.glidetv.BuildConfig
import app.glidetv.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** SHA-256 из поля digest GitHub API ("sha256:…"), если GitHub его отдаёт. */
    val sha256: String?,
)

/**
 * Обновления из GitHub Releases (репозиторий задаётся в build.gradle.kts: glide.updateRepo).
 * Перед установкой проверяем SHA-256 и то, что APK подписан тем же ключом, что и установленный:
 * чужой APK система всё равно не поставит поверх, но так пользователь получит понятную ошибку.
 */
class UpdateChecker(private val context: Context) {

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.startsWith("OWNER/")) return@withContext null
        try {
            val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "GlideTV/${BuildConfig.VERSION_NAME}")
            }
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk") }
                .sortedByDescending { it.getString("name").contains("release") }
                .firstOrNull() ?: return@withContext null
            ReleaseInfo(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body"),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        } catch (t: Throwable) {
            Logx.w("Проверка обновлений", t)
            null
        }
    }

    fun isNewer(release: ReleaseInfo): Boolean =
        compareVersions(release.version, BuildConfig.VERSION_NAME.substringBefore('-')) > 0

    suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = updateDir().apply { mkdirs() }
        val target = File(dir, APK_NAME)
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        val total = conn.contentLength.toLong().takeIf { it > 0 } ?: release.apkSize
        val digest = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(((done * 100) / total).toInt())
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !release.sha256.equals(actual, ignoreCase = true)) {
            target.delete()
            throw SecurityException("SHA-256 mismatch")
        }
        if (!sameSigner(target)) {
            target.delete()
            throw SecurityException("APK signed with a different key")
        }
        target.setReadable(true, false) // для установщика на Android 5–6 (file://)
        target
    }

    /** Android 8+: «Установка неизвестных приложений» выдаётся отдельно для Glide TV. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun install(apk: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ApkProvider.uriFor(context, apk.name)
        else Uri.fromFile(apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun updateDir(): File =
        // До Android 7 установщик читает файл напрямую — кладём во внешний кеш
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) File(context.externalCacheDir ?: context.cacheDir, "updates")
        else File(context.cacheDir, "updates")

    @Suppress("DEPRECATION")
    private fun sameSigner(apk: File): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val mine = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners
            val theirs = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo?.apkContentsSigners
            mine != null && theirs != null && mine.toSet() == theirs.toSet()
        } else {
            val mine: PackageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val theirs: PackageInfo? = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
            val a = mine.signatures?.toSet()
            val b = theirs?.signatures?.toSet()
            a != null && b != null && a == b
        }
    }

    companion object {
        const val APK_NAME = "glide-tv-update.apk"

        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (d != 0) return d
            }
            return 0
        }
    }
}
