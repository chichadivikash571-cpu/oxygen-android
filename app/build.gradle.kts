plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") version "4.5.0" apply false
}

// Firebase config is intentionally not committed. Add app/google-services.json after
// registering ai.oxygen.app in your own Firebase project to enable real push delivery.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
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

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
