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

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.health.connect.client)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Reads a photo's embedded EXIF orientation tag so captured images
    // display right-side-up before cropping -- camera JPEGs are
    // typically stored in the sensor's native (landscape) pixel
    // layout with an EXIF tag saying how to rotate for display, rather
    // than actually rotating the pixel data itself (cheaper for the
    // camera to write). BitmapFactory.decodeStream() ignores that tag
    // entirely, which is why a portrait photo was showing up sideways
    // in the crop screen every time (see design discussion) -- not a
    // bug in OUR crop/rotate logic, just a step that was never being
    // done at all.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Forces a real Guava dependency into the graph -- without this,
    // Health Connect's transitive deps can pull in the empty
    // com.google.guava:listenablefuture:1.0 placeholder artifact (some
    // libraries use it when they only need the ListenableFuture
    // INTERFACE, not full Guava), and Gradle's default version-based
    // conflict resolution can end up picking that empty placeholder
    // over the real Guava that CameraX needs an actual implementation
    // from -- causing "Cannot access class 'ListenableFuture'" at
    // compile time even though the import resolves fine. See:
    // https://github.com/google/guava/issues/2960
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.transition:transition:1.7.0")

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}