plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.ianrastall.offlinemaps"
    compileSdk = 36
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "io.github.ianrastall.offlinemaps"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "0.9.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("distribution") {
            val path = System.getenv("OFFLINE_MAPS_ANDROID_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("OFFLINE_MAPS_ANDROID_STORE_PASSWORD")
                keyAlias = "offline-maps"
                keyPassword = System.getenv("OFFLINE_MAPS_ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += listOf("mbtiles", "pbf", "png", "webp") }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.webkit:webkit:1.18.0")
}
