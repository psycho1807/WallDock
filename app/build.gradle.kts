plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.walldock"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.walldock"
        minSdk = 26
        targetSdk = 36
        versionCode = 36
        versionName = "0.17.2"
    }

    buildFeatures {
        buildConfig = true
    }

    // Clé officielle : déchiffrée par GitHub Actions (secret WALLDOCK_KEY_PASSWORD), jamais en clair dans le dépôt
    val releaseStore = System.getenv("WALLDOCK_KEYSTORE")
    val releasePassword = System.getenv("WALLDOCK_KEY_PASSWORD")
    signingConfigs {
        if (releaseStore != null && releasePassword != null) {
            create("release") {
                storeFile = file(releaseStore)
                storeType = "PKCS12"
                storePassword = releasePassword
                keyAlias = "walldock"
                keyPassword = releasePassword
            }
        }
    }

    // Deux versions : « github » (APK GitHub / Obtainium, complète, mises à jour automatiques)
    // et « play » (Google Play : ni mise à jour par l'appli, ni service d'accessibilité).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_STORE", "false")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_STORE", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

dependencies {
    // Détection de « Hey Jarvis » sur la tablette (modèles openWakeWord au format TFLite)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
