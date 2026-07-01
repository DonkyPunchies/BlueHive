// Project build.gradle.kts

// The plugins { ... } block in the top-level build file is used to declare the plugins and their versions that will be
// available to the various modules (sub-projects) within your entire Android project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

