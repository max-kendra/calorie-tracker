import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Reads secrets from local.properties (gitignored, same convention
// Android already uses for sdk.dir) instead of hardcoding them here --
// this file (build.gradle.kts) gets committed to git, local.properties
// does not.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

android {
    namespace = "com.mealtracker.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mealtracker.android"
        // Android 8.0+ -- matches what we agreed on (covers effectively
        // all real-world devices at this point without much loss).
        minSdk = 26
        // Android 16 -- matches your phone.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Both read from local.properties -- see MEAL_TRACKER_BASE_URL /
        // MEAL_TRACKER_API_KEY in local.properties.example for what to add.
        // Falls back to an obviously-wrong placeholder if not set, so a
        // missing config fails loudly (connection error) rather than
        // silently using someone else's key.
        buildConfigField(
            "String", "BASE_URL",
            "\"${localProperties.getProperty("MEAL_TRACKER_BASE_URL", "http://MISSING-SET-IN-local.properties/")}\""
        )
        buildConfigField(
            "String", "API_KEY",
            "\"${localProperties.getProperty("MEAL_TRACKER_API_KEY", "MISSING-SET-IN-local.properties")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
