// Копия скрытого интерфейса фреймворка для getPackageSizeInfo() на Android 5–7.1.
package android.content.pm;

import android.content.pm.PackageStats;

oneway interface IPackageStatsObserver {
    void onGetStatsCompleted(in PackageStats pStats, boolean succeeded);
}
