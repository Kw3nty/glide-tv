package app.glidetv.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Как на этом устройстве включается ADB без компьютера. */
enum class AdbRoute {
    /** Fire OS: «ADB debugging» сразу открывает 127.0.0.1:5555. */
    FIRE_TV_ADB,
    /** Android TV ≤ 13: «USB debugging» (часто и сетевой ADB на 5555). */
    USB_DEBUG_TCP,
    /** Android 11+: Wireless debugging, нужно сопряжение по коду. Основной путь для Android 14+. */
    WIRELESS_PAIRING,
}

data class PlatformInfo(
    val sdk: Int,
    val release: String,
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val isTv: Boolean,
    val isFireTv: Boolean,
    val isGoogleTv: Boolean,
    val isLowRam: Boolean,
) {
    /** Fire OS 5 = API 22, 6 = 25, 7 = 28, 8 = 30. */
    val fireOsMajor: Int
        get() = when {
            !isFireTv -> 0
            sdk <= 22 -> 5
            sdk <= 25 -> 6
            sdk <= 28 -> 7
            else -> 8
        }

    /**
     * На Fire OS 5 `pm disable-user` отвечает SecurityException, а `pm hide` «врёт» об успехе
     * (CleanYourTV #577). Там раздел отключения приложений скрываем.
     */
    val canDisablePackages: Boolean get() = !(isFireTv && fireOsMajor <= 5)

    /** До Android 6 сторонним приложениям разрешено чистить кеш всех приложений. */
    val canClearAllCachesWithoutPrivileges: Boolean get() = sdk < Build.VERSION_CODES.M

    /** С Android 14 killBackgroundProcesses() завершает только собственные процессы. */
    val canKillOtherAppsWithoutPrivileges: Boolean get() = sdk < 34

    val preferredAdbRoute: AdbRoute
        get() = when {
            isFireTv -> AdbRoute.FIRE_TV_ADB
            sdk >= 34 -> AdbRoute.WIRELESS_PAIRING
            else -> AdbRoute.USB_DEBUG_TCP
        }

    val supportsWirelessDebugging: Boolean get() = sdk >= Build.VERSION_CODES.R

    val displayName: String
        get() = buildString {
            append(manufacturer.replaceFirstChar { it.uppercase() })
            append(' ')
            append(model)
            append(" · Android ")
            append(release)
            if (isFireTv) append(" (Fire OS ").append(fireOsMajor).append(')')
            else if (isGoogleTv) append(" (Google TV)")
        }

    companion object {
        fun detect(context: Context): PlatformInfo {
            val pm = context.packageManager
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val isFire = pm.hasSystemFeature("amazon.hardware.fire_tv") ||
                (Build.MANUFACTURER.equals("Amazon", ignoreCase = true) && Build.MODEL.startsWith("AFT"))
            val isGoogleTv = isInstalled(pm, "com.google.android.apps.tv.launcherx")
            @Suppress("DEPRECATION")
            val isTv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
            return PlatformInfo(
                sdk = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE ?: "?",
                brand = Build.BRAND.orEmpty(),
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                product = Build.PRODUCT.orEmpty(),
                isTv = isTv,
                isFireTv = isFire,
                isGoogleTv = isGoogleTv,
                isLowRam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) am.isLowRamDevice else false,
            )
        }

        fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
            pm.getPackageInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
