// Копия скрытого интерфейса фреймворка. Нужна, чтобы вызывать
// PackageManager.freeStorageAndNotify() через рефлексию на Android 5.x.
package android.content.pm;

oneway interface IPackageDataObserver {
    void onRemoveCompleted(in String packageName, boolean succeeded);
}
