plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "lk.stanford.attendance"
    compileSdk = 34

    defaultConfig {
        applicationId = "lk.stanford.attendance"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // tflite model eka compress wenna epa (memory-map karanna ona)
    androidResources { noCompress += "tflite" }

    // Fixed signing key: app eka update karaddi staff phones walata uninstall karanna ona nehe.
    // (Keystore eka backup ekak thiyaganna. Nathi unoth staff okkoma app eka uninstall karala nawatha install karanna wenawa.)
    signingConfigs {
        create("release") {
            storeFile = file("attendance.p12")
            storeType = "pkcs12"
            storePassword = "LFUCvGLfm36MvAOxIQe0GEvc"
            keyAlias = "attendance"
            keyPassword = "LFUCvGLfm36MvAOxIQe0GEvc"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
