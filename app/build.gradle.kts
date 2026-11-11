// app/build.gradle.kts
// AGP 9.2+ (Kotlin is built into AGP, so there is no kotlin-android plugin), Gradle 9.4.1+, JDK 17.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.docscanner"
    compileSdk = 37                           // Compose 1.12+ requires compileSdk 37 (needs AGP 9.2+)

    defaultConfig {
        applicationId = "com.example.docscanner"
        minSdk = 29                           // Android 10+: saving to Documents via MediaStore needs no storage permission
        targetSdk = 36                        // Google Play requires targeting API 36 from 31 Aug 2026
        versionCode = 1
        versionName = "1.0"

        // OpenCV ships native libs for every ABI; keep the two that real phones use
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // Not used by the GitHub build (it makes the debug APK, which installs directly on a phone).
            // Minification stays off until the app has been tested on a device with it on.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    // ───────────── AndroidX Core / Lifecycle (MVVM) / Activity ─────────────
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")     // collectAsStateWithLifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")   // viewModel() in composables
    implementation("androidx.activity:activity-compose:1.13.0")

    // ───────────── Jetpack Compose (all UI versions come from the BOM) ─────────────
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation (Camera → Crop → Result)
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // ───────────── Coroutines ─────────────
    val coroutinesVersion = "1.11.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    // Task<T>.await() so ML Kit can be used inside coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")
    // ListenableFuture<T>.await() for CameraX
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")

    // ───────────── CameraX (Preview + ImageCapture only) ─────────────
    val cameraxVersion = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")            // PreviewView

    // ───────────── OpenCV (official package from Maven Central) ─────────────
    implementation("org.opencv:opencv:4.14.0")

    // ───────────── ML Kit OCR (bundled model: works offline, no INTERNET permission) ─────────────
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // ───────────── Tests ─────────────
    testImplementation("junit:junit:4.13.2")
}
