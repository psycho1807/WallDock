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
        versionCode = 23
        versionName = "0.9.2"
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
