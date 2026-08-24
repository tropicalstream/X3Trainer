plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The watch half of the telemetry chain. Deliberately the same shape as the
// rest of the project: no androidx, no Play Services, no Wear support
// library. Everything here is plain platform Android, which is why it can be
// a single small APK that sideloads onto any Wear OS watch.
android {
    namespace = "com.x3trainer.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.x3trainer.wear"
        // Wear OS 3 and up. The Pixel Watch 5 is well above this; the floor is
        // set by the runtime Bluetooth permissions (API 31), not by choice.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
}
