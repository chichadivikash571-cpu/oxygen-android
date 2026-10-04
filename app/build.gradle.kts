plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.oxygen.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.oxygen.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("oxygen") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "oxygen123"
            keyAlias = "oxygen"
            keyPassword = "oxygen123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("oxygen")
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
