plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.questsoundboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.questsoundboard"
        // Quest 1 runs Android 10 (API 29). Quest 2/3/3S run Horizon OS on
        // Android 12L (API 32). minSdk 29 covers the whole family.
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // Quest 1 = Snapdragon 835, Quest 2/3/3S = XR2 / XR2 Gen 2. All arm64.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("sideload") {
            // Debug-style key so the APK can be sideloaded with adb install.
            storeFile = file("sideload.keystore")
            storePassword = "soundboard"
            keyAlias = "soundboard"
            keyPassword = "soundboard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("sideload")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Root shell (Magisk / topjohnwu libsu)
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:io:5.2.2")
}
