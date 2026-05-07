plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rokidgames.headpong"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidgames.headpong"
        minSdk = 32                 // Sprite ist Android 12 / API 32 — angeglichen an die GazeMou-App, die nachweislich läuft.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Bewusst KEINE abiFilters — universal APK, vermeidet "INSTALL_FAILED_NO_MATCHING_ABIS"-Fallen.
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
