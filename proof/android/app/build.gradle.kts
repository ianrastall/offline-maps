plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.ianrastall.offlinemaps.proof"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.ianrastall.offlinemaps.proof"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-proof"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // A proof installed only on test devices: signed with the debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Tiles, glyphs and the overview are read as they are.
    androidResources {
        noCompress += listOf("mbtiles", "pbf", "png", "webp")
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // MapLibre's own HTTP client; the proof answers map.local requests in an interceptor.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.18.0")
}
