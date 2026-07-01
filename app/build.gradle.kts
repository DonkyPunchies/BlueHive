// build.gradle.kts

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val stripUbo by tasks.registering {
    val rawXpi = layout.projectDirectory.file("src/main/assets/ublock_origin-1.64.0.xpi")
    val workDir = layout.buildDirectory.dir("uboPrune")
    inputs.file(rawXpi)
    outputs.file("src/main/assets/addons/ublock0-stripped.xpi")

    doLast {
        delete(workDir)

        // Unzip everything except manifest.json
        copy {
            from(zipTree(rawXpi))
            into(workDir)
            exclude("manifest.json")
        }

        // Unzip only manifest.json, then rewrite it
        val manifestFile = workDir.get().file("manifest.json").asFile
        manifestFile.parentFile.mkdirs()

        project.zipTree(rawXpi).matching {
            include("manifest.json")
        }.forEach { f ->
            manifestFile.writeText(
                f.readText().let { text ->
                    val parser = JsonSlurper()
                    @Suppress("UNCHECKED_CAST")
                    val obj = parser.parseText(text) as Map<String, Any>

                    val pruned = mutableMapOf<String, Any>()
                    obj.forEach { (k, v) ->
                        when (k) {
                            // drop all UI/desktop‐only keys:
                            "browser_action", "page_action", "sidebar_action",
                            "menus", "contextMenus", "notifications",
                            "bookmarks", "history" -> {
                                // skip
                            }

                            "permissions" -> {
                                pruned["permissions"] = listOf(
                                    "webRequest",
                                    "webRequestBlocking",
                                    "storage"
                                )
                            }

                            "host_permissions" -> {
                                pruned["host_permissions"] = listOf("<all_urls>")
                            }

                            else -> pruned[k] = v
                        }
                    }

                    // ensure minimum fields remain
                    if (!pruned.containsKey("permissions")) {
                        pruned["permissions"] = listOf("webRequest", "webRequestBlocking", "storage")
                    }
                    if (!pruned.containsKey("host_permissions")) {
                        pruned["host_permissions"] = listOf("<all_urls>")
                    }

                    JsonOutput.prettyPrint(JsonOutput.toJson(pruned))
                }
            )
        }

        // Ensure output directory exists
        file("src/main/assets/addons").mkdirs()

        // Re-zip into the addons folder
        ant.withGroovyBuilder {
            "zip"(
                mapOf(
                    "destfile" to "${project.projectDir}/src/main/assets/addons/ublock0-stripped.xpi",
                    "basedir" to workDir.get().asFile
                )
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(stripUbo)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.androidx.baselineprofile)
}


// ── Version source of truth ──────────────────────────────────────────────
// versionCode/versionName come from app/version.properties (checked in), NOT
// from the build clock. This makes the version a deliberate, monotonic release
// act: bump version.properties, rebuild, ship. Both Gradle and the future #7
// upload tooling read this same file, so the APK's stamped versionCode and the
// server manifest's latest_version_code can never drift. Fail loud if it's
// missing or malformed — a silent fallback would reintroduce the very
// non-determinism we're removing.
val versionProps = Properties().apply {
    val f = file("version.properties")
    require(f.exists()) { "version.properties missing at ${f.absolutePath}" }
    f.inputStream().use { load(it) }
}
val appVersionCode = (versionProps.getProperty("versionCode")
    ?: error("versionCode missing from version.properties")).trim().toInt()
val appVersionName = (versionProps.getProperty("versionName")
    ?: error("versionName missing from version.properties")).trim()

// ── Release signing ──────────────────────────────────────────────────────
// Credentials live in keystore.properties at the project root — GITIGNORED,
// never committed. The keystore file itself lives OUTSIDE the repo entirely.
// If keystore.properties is absent the signingConfig stays unconfigured and
// assembleRelease fails at signing — exactly the behavior we want. A
// debug-signed APK on the update server would permanently weld the install
// base to a throwaway key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.bluehive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.bluehive"
        minSdk = 25
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters.clear()
            abiFilters += listOf("armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("RELEASE_STORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
            }
            // No fallback. If keystore.properties is missing, this config stays
            // unconfigured and assembleRelease fails at signing — exactly the
            // behavior we want. A debug-signed release APK must never exist.
        }
    }

    buildTypes {
        debug {
            val devBaseUrl = project.findProperty("BLUEHIVE_DEV_BASE_URL")?.toString()
                ?: "http://192.168.1.136:8000/"
            val devApiKey = project.findProperty("BLUEHIVE_DEV_API_KEY")?.toString()
                ?: ""
            val devPlatformUrl = project.findProperty("BLUEHIVE_DEV_PLATFORM_URL")?.toString()
                ?: "http://192.168.1.136:9000"
            val devUpdateUrl = project.findProperty("BLUEHIVE_DEV_UPDATE_URL")?.toString()
                ?: "http://192.168.1.136:8090"

            buildConfigField("String", "API_BASE_URL", "\"$devBaseUrl\"")
            buildConfigField("String", "API_KEY", "\"$devApiKey\"")
            buildConfigField("String", "PLATFORM_BASE_URL", "\"$devPlatformUrl\"")
            // Self-update manifest host (#7). Public, unauthenticated, identity-
            // independent — see bluehive-updates container. Swapping to
            // https://updates.<domain> at prod time is the ONLY change needed.
            buildConfigField("String", "UPDATE_BASE_URL", "\"$devUpdateUrl\"")

            isMinifyEnabled = false
            isShrinkResources = false

            ndk {
                //noinspection ChromeOsAbiSupport
                abiFilters.clear()
                abiFilters += listOf("armeabi-v7a", "x86_64")
            }
        }



        release {

            // 🔹 Use the same values as debug for now
            val prodBaseUrl = project.findProperty("BLUEHIVE_DEV_BASE_URL")?.toString()
                ?: "http://192.168.1.136:8000/"
            val prodApiKey = project.findProperty("BLUEHIVE_DEV_API_KEY")?.toString()
                ?: ""
            val prodPlatformUrl = project.findProperty("BLUEHIVE_DEV_PLATFORM_URL")?.toString()
                ?: "http://192.168.1.136:9000"
            val prodUpdateUrl = project.findProperty("BLUEHIVE_DEV_UPDATE_URL")?.toString()
                ?: "http://192.168.1.136:8090"

            buildConfigField("String", "API_BASE_URL", "\"$prodBaseUrl\"")
            buildConfigField("String", "API_KEY", "\"$prodApiKey\"")
            buildConfigField("String", "PLATFORM_BASE_URL", "\"$prodPlatformUrl\"")
            // Same dev IP for now — see comment in debug block. The prod cutover
            // is a one-line change here, nothing else in the app touches this.
            buildConfigField("String", "UPDATE_BASE_URL", "\"$prodUpdateUrl\"")

            isMinifyEnabled = false
            isShrinkResources = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.getByName("release")

            ndk {
                abiFilters.clear()
                abiFilters += listOf("armeabi-v7a", "x86_64")
            }
        }





    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
        aidl = true   // ← AGP 8+ disables AIDL by default; the host contract needs it
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {

    // ═══════════════════════════════════════════════════════════
    // BASELINE PROFILE
    // ═══════════════════════════════════════════════════════════
    implementation(libs.androidx.profileinstaller)        // applies the profile at install/first-run
    baselineProfile(project(":baselineprofile"))          // consumes the generated profile

    implementation("androidx.media3:media3-datasource-okhttp:1.9.0")

    implementation("com.github.exjunk:ThanosEffect:1.0.2")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation(libs.androidx.animation.core)
    implementation(libs.androidx.ui.unit)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui.test)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.androidx.runtime)
    implementation(libs.foundation)
    implementation(libs.foundation.layout)
    implementation(libs.androidx.compose.foundation.foundation)
    implementation(libs.androidx.compose.foundation)

    // ═══════════════════════════════════════════════════════════
    // JETPACK COMPOSE
    // ═══════════════════════════════════════════════════════════
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.2")

    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.tv:tv-material:1.0.0-alpha10")

    // coil-compose removed from here — it lives in the IMAGE LOADING block below
    // with the rest of the Coil artifacts so all versions stay in sync.

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")



    // ═══════════════════════════════════════════════════════════
    // MEDIA3 (ExoPlayer)
    // ═══════════════════════════════════════════════════════════
    val media3Version = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation(libs.media3.exoplayer.hls)

    implementation("androidx.media3:media3-datasource-okhttp:${media3Version}")
    implementation(libs.okhttp.urlconnection)


    // ═══════════════════════════════════════════════════════════
    // YOUTUBE EXTRACTION
    // ═══════════════════════════════════════════════════════════
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.25.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // ═══════════════════════════════════════════════════════════
    // IMAGE LOADING — all Coil artifacts from the same source
    // ═══════════════════════════════════════════════════════════
    // Previously coil-compose was ALSO declared in the Compose section
    // above as "io.coil-kt:coil-compose:2.7.0". Having two declarations
    // of the same artifact at potentially different versions causes D8 to
    // generate SAM-bridge lambdas (ExternalSyntheticLambda) that are
    // incompatible at runtime, producing the ClassCastException in
    // BlueHiveApplication.onCreate(). Single source of truth here.
    implementation(libs.coil)
    implementation(libs.coil.video)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    // ═══════════════════════════════════════════════════════════
    // NETWORKING
    // ═══════════════════════════════════════════════════════════
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    // ═══════════════════════════════════════════════════════════
    // LIFECYCLE & COROUTINES
    // ═══════════════════════════════════════════════════════════
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")

    // ═══════════════════════════════════════════════════════════
    // ANDROID CORE
    // ═══════════════════════════════════════════════════════════
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.leanback)

    // ═══════════════════════════════════════════════════════════
    // GECKO VIEW
    // ═══════════════════════════════════════════════════════════
    implementation(libs.geckoview.runtime)

    // ═══════════════════════════════════════════════════════════
    // MISC
    // ═══════════════════════════════════════════════════════════
    implementation(libs.common)
    implementation(libs.app.update.ktx)
    implementation(libs.androidx.viewfinder.core)

    // ═══════════════════════════════════════════════════════════
    // TESTING
    // ═══════════════════════════════════════════════════════════
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
