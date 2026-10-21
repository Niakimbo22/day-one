import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val rawgKey: String = localProps.getProperty("RAWG_KEY") ?: System.getenv("RAWG_KEY") ?: ""

android {
    namespace = "fr.niakimbo.dayone"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.niakimbo.dayone"
        minSdk = 26
        targetSdk = 35
        // Croissant à chaque build : plus jamais besoin de désinstaller pour mettre à jour
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "1.2"
        buildConfigField("String", "RAWG_KEY", "\"$rawgKey\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
