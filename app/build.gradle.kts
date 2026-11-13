import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Подпись релиза: keystore.properties локально или переменные окружения в GitHub Actions.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "app.glidetv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.glidetv"
        // Android TV 5.0 / Fire OS 5 — самые старые поддерживаемые приставки
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0"

        // Репозиторий GitHub, откуда приходят обновления (owner/name)
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("glide.updateRepo") ?: "OWNER/glide-tv"}\"")

        resourceConfigurations += listOf("en", "ru")
        vectorDrawables.useSupportLibrary = false
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "GLIDE_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "GLIDE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "GLIDE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "GLIDE_KEY_PASSWORD")
                // v1 нужна для Android 5–6, v2/v3 — для новых версий
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.md",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    lint {
        // В CI lint проверяет только NewApi: на Android 5–6 вызов API без проверки версии = падение.
        // Защищённые разрешения (WRITE_SECURE_SETTINGS и т. п.) выдаются через ADB — это осознанно.
        checkOnly += setOf("NewApi")
        abortOnError = true
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.libadb)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.hiddenapibypass)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
