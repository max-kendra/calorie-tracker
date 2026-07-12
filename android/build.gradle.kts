// Top-level build file -- declares plugins used by sub-projects (just
// :app here) without applying them at the root level. This is the
// standard modern Gradle pattern (matches what Android Studio's project
// wizard generates).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
