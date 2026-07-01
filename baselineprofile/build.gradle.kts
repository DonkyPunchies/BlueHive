plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.example.bluehive.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28   // baseline-profile generation requires API 28+
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Generation runs on an x86_64 emulator; keep arm too for connected runs.
        ndk { abiFilters += listOf("x86_64", "armeabi-v7a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // This test module instruments your app.
    targetProjectPath = ":app"

    // Rooted AOSP emulator, managed by Gradle — no manual rooting needed.
    testOptions {
        managedDevices {
            localDevices {
                create("aospApi34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"   // AOSP = rooted; required to capture the profile
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Producer config: which device generates, and don't fall back to a connected device.
baselineProfile {
    managedDevices += "aospApi34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}