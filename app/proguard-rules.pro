# libadb-android: протокол и рефлексия к Conscrypt
-keep class io.github.muntashirakon.** { *; }
-dontwarn io.github.muntashirakon.**

# BouncyCastle: провайдеры ищутся по имени класса
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# HiddenApiBypass
-keep class org.lsposed.hiddenapibypass.** { *; }

# Прокси для скрытых интерфейсов PackageManager (IPackageDataObserver / IPackageStatsObserver)
-keep class android.content.pm.IPackageDataObserver { *; }
-keep class android.content.pm.IPackageStatsObserver { *; }

-dontwarn javax.naming.**
-dontwarn java.awt.**
