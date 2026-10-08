plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sk.fleer.kopia"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.fleer.kopia"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    // Deliberately minimal: no Compose, no Material Components, no networking
    // library. Extraction is HTTP + JSON (org.json ships with Android), muxing
    // is MediaMuxer, saving is MediaStore - all platform APIs. Keeping this list
    // short is the whole point of the rewrite.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
