plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jslw.genz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jslw.genz"
        // KitKat 4.4 (API 19) and up
        minSdk = 19
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        // Leave vector PNG generation ON for API < 21 (notifications/widgets on KitKat need PNGs).
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

// IMPORTANT: these versions are pinned on purpose. They are the last releases that still
// support minSdk 19 (KitKat). Do NOT let Android Studio "upgrade" them, newer versions
// require Android 5.0+ and the build will fail with a manifest-merger minSdk error.
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.multidex:multidex:2.0.1")
}
