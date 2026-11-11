plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.walldock"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.walldock"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.3.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // Clé de test fixe : chaque APK compilé par GitHub peut s'installer par-dessus le précédent
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
