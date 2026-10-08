plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.tubebeam"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "dev.tubebeam"
        minSdk = 26
        targetSdk = 36
        // CI passes -PversionName=<tag> -PversionCode=<run number> for releases.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        // Checked-in debug key so every build (each runs in a fresh container) has the same
        // signature and installs over the previous one. Standard Android debug credentials.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Release signing comes from the environment (CI secrets or a local shell); unsigned otherwise.
    val releaseKeystore = System.getenv("TUBEBEAM_KEYSTORE")?.let(rootProject::file)?.takeIf { it.exists() }
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("TUBEBEAM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("TUBEBEAM_KEY_ALIAS") ?: "tubebeam"
            keyPassword = System.getenv("TUBEBEAM_KEY_PASSWORD") ?: System.getenv("TUBEBEAM_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kadb)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
