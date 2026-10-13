import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release key lives outside the repository, in ~/.kopia (see the
// "kópia-kľúč" backup). Without it a release build fails loudly instead of
// producing something signed with a different key - which would refuse to
// install over every copy already out there.
val releaseKey = Properties().apply {
    val file = File(System.getProperty("user.home"), ".kopia/keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "sk.fleer.kopia"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.fleer.kopia"
        minSdk = 29
        targetSdk = 35
        versionCode = 101
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            if (releaseKey.isNotEmpty()) {
                storeFile = File(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
    testOptions {
        unitTests.all { it.testLogging { events("passed", "failed", "skipped", "standardOut") } }
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

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws on the JVM; the real library
    // lets the extraction code run unchanged in desktop tests.
    testImplementation("org.json:json:20240303")
}
