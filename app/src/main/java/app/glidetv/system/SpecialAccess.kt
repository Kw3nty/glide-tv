package app.glidetv.system

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings

/**
 * Специальные доступы. На многих ТВ-прошивках экранов для них нет,
 * поэтому «Турбо» умеет выдавать их сам через appops (см. TurboSetup).
 */
object SpecialAccess {

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Доступ к общей памяти для поиска мусора. */
    fun hasStorageAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Открыть системный экран; false — если на этой прошивке его нет. */
    fun openUsageAccessSettings(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun openAllFilesSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return tryStart(
            context,
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        ) || tryStart(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    fun openDeveloperSettings(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) ||
            // Fire OS / часть ТВ-прошивок: экран есть, но action не объявлен
            tryStart(context, Intent().setClassName("com.android.tv.settings",
                "com.android.tv.settings.system.development.DevelopmentActivity")) ||
            tryStart(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    fun openAppDetails(context: Context, pkg: String): Boolean =
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
