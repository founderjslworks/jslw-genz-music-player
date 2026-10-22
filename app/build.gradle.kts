plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jslw.genz"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jslw.genz"
        // KitKat 4.4 (API 19) and up
        minSdk = 19
        // Google Play: new apps must target Android 16 (API 36)
        targetSdk = 36
        versionCode = 6
        versionName = "1.6"
        multiDexEnabled = true
        // Leave vector PNG generation ON for API < 21 (notifications/widgets on KitKat need PNGs).
    }

    // Release signing: the upload keystore is supplied by the build machine / CI secrets, never stored in the repo.
    //   GENZ_KEYSTORE=/path/upload.jks  GENZ_KEYSTORE_PASSWORD=…  GENZ_KEY_ALIAS=…  GENZ_KEY_PASSWORD=…
    val ks = System.getenv("GENZ_KEYSTORE")
    signingConfigs {
        if (ks != null && file(ks).exists()) {
            create("upload") {
                storeFile = file(ks)
                storePassword = System.getenv("GENZ_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GENZ_KEY_ALIAS")
                keyPassword = System.getenv("GENZ_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
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
