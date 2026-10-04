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

    // Only register a signing config if the throwaway sideload keystore is
    // actually present, otherwise a fresh clone fails at configuration time.
    val sideloadKeystore = file("sideload.keystore")
    if (sideloadKeystore.exists()) {
        signingConfigs {
            create("sideload") {
                storeFile = sideloadKeystore
                storePassword = System.getenv("SIDELOAD_STORE_PASSWORD") ?: "soundboard"
                keyAlias = System.getenv("SIDELOAD_KEY_ALIAS") ?: "soundboard"
                keyPassword = System.getenv("SIDELOAD_KEY_PASSWORD") ?: "soundboard"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to the debug key when no sideload keystore exists, so
            // `assembleRelease` always produces an installable APK.
            signingConfig = signingConfigs.findByName("sideload")
                ?: signingConfigs.getByName("debug")
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

    lint {
        // Print the full report into the build log so CI (and anyone without
        // the HTML report) can see exactly which issue failed the build.
        textReport = true
        textOutput = file("stdout")
        // Don't let a style/deprecation lint stop a sideload build from
        // producing an installable APK. Run `./gradlew lintRelease` to see
        // the full report whenever you want it.
        abortOnError = false
        checkReleaseBuilds = true
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

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Root shell (Magisk / topjohnwu libsu)
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.github.topjohnwu.libsu:io:5.2.2")
}
