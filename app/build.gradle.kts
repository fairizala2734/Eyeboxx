plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.eyebox"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.eyebox"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["usesCleartextTraffic"] = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        // Jika Anda menaruh .tflite di app/src/main/ml/
        mlModelBinding = true
        viewBinding = true
    }

    packaging {
        // Hindari bentrok native libs (umum di mediapipe+tflite)
        resources.excludes += setOf(
            "META-INF/**",
            "org/bytedeco/**"
        )
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // MediaPipe Tasks Vision
    implementation ("com.google.mediapipe:tasks-vision:0.10.14")

    // CameraX
    implementation ("androidx.camera:camera-core:1.3.4")
    implementation ("androidx.camera:camera-camera2:1.3.4")
    implementation ("androidx.camera:camera-lifecycle:1.3.4")
    implementation ("androidx.camera:camera-view:1.3.4")

    // Lifecycle
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Kotlin coroutines (optional untuk off-main work)
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // (Opsional) delegate NNAPI / GPU
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.14.0")

    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}