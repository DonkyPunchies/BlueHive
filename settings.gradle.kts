// settings.gradle.kts (Root Project)

pluginManagement {
    repositories {
        google()
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) // ✅ Better practice
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }

        // ✅ Mozilla's official repo for GeckoView
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "BlueHive"
include(":app")
include(":baselineprofile")