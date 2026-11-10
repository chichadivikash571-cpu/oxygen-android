plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val oneSignalAppId = providers.gradleProperty("onesignalAppId").orElse("").get()

android {
    namespace = "ai.oxygen.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.oxygen.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        buildConfigField(
            "String",
            "ONESIGNAL_APP_ID",
            "\"\u0024{oneSignalAppId.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )
    }

    buildFeatures {
        buildConfig = true
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
    implementation("com.onesignal:OneSignal:5.10.2")
}
