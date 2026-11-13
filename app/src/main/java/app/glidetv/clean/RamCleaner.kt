package app.glidetv.clean

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Без прав (Android ≤ 13): killBackgroundProcesses() для каждого пакета. Система завершает
 * только процессы в фоновом/кешированном состоянии — играющее видео и сервисы не трогаются.
 *
 * Делается по кнопке, а не по таймеру: Android сам управляет памятью, и постоянное
 * «убийство» процессов замедляет следующий запуск приложений.
 */
class RamCleaner(private val context: Context) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    val isSupported: Boolean get() = Build.VERSION.SDK_INT < 34

    /** @return сколько пакетов обработано. */
    suspend fun killBackground(): Int = withContext(Dispatchers.Default) {
        if (!isSupported) return@withContext 0
        val keep = protectedPackages()
        val pkgs = context.packageManager.getInstalledApplications(0)
            .map { it.packageName }
            .filter { it !in keep }
        pkgs.forEach { runCatching { am.killBackgroundProcesses(it) } }
        pkgs.size
    }

    /** Себя, лаунчер, клавиатуру и системный UI не трогаем. */
    private fun protectedPackages(): Set<String> {
        val pm = context.packageManager
        val home = pm.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val ime = imm.enabledInputMethodList.map { it.packageName }
        return buildSet {
            add(context.packageName)
            add("android")
            add("com.android.systemui")
            home?.let { add(it) }
            addAll(ime)
        }
    }
}
