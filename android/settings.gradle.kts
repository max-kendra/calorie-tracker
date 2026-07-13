pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack -- needed for UCrop (image cropping), which isn't
        // published to Maven Central/Google's repos.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Meal Tracker"
include(":app")
