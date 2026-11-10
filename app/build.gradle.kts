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
        versionCode = 3
        versionName = "0.3"
        buildConfigField("String", "ONESIGNAL_APP_ID", "\"$oneSignalAppId\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("oxygen") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "oxygen123"
            keyAlias = "oxygen"
            keyPassword = "oxygen123"
        }
    }

    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("oxygen") } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.onesignal:OneSignal:5.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
