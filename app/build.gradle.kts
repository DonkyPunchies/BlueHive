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
        applicationId = "com.bluehive.tv"
        minSdk = 25
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default for the ${netSecConfig} placeholder in AndroidManifest.xml.
        // MUST exist here: a placeholder with no value fails the manifest merge
        // for EVERY variant that does not set one, so debug and any future build
        // type would stop building the moment release started overriding it.
        //
        // The permissive file is the safe default because it is the one that can
        // only ever be too lenient on a LAN address. The release block overrides
        // it to the strict config as soon as all three backend URLs are https.
        manifestPlaceholders["netSecConfig"] = "network_security_config"

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

            // PROD-first, DEV as the fallback. Nothing changes until a
            // BLUEHIVE_PROD_* property exists, so release builds keep pointing at
            // the LAN box exactly as before — but the cutover is then just adding
            //   BLUEHIVE_PROD_BASE_URL=https://api.<domain>/
            // to gradle.properties, with no edit to this file.
            //
            // https matters beyond the cert: the release network-security-config
            // sets cleartextTrafficPermitted=false, so an http:// prod URL would
            // be refused by the platform, not merely insecure. And HTTP/3 needs
            // TLS — see the h3 block in the backend Caddyfile.
            fun prop(name: String): String? =
                project.findProperty("BLUEHIVE_PROD_$name")?.toString()
                    ?: project.findProperty("BLUEHIVE_DEV_$name")?.toString()

            val prodBaseUrl     = prop("BASE_URL")     ?: "http://192.168.1.136:8000/"
            val prodApiKey      = prop("API_KEY")      ?: ""
            val prodPlatformUrl = prop("PLATFORM_URL") ?: "http://192.168.1.136:9000"
            val prodUpdateUrl   = prop("UPDATE_URL")   ?: "http://192.168.1.136:8090"

            buildConfigField("String", "API_BASE_URL", "\"$prodBaseUrl\"")
            buildConfigField("String", "API_KEY", "\"$prodApiKey\"")
            buildConfigField("String", "PLATFORM_BASE_URL", "\"$prodPlatformUrl\"")
            // Same dev IP for now — see comment in debug block. The prod cutover
            // is a one-line change here, nothing else in the app touches this.
            buildConfigField("String", "UPDATE_BASE_URL", "\"$prodUpdateUrl\"")

            // ── Network security config, DERIVED from the URLs above ──────────
            // Deliberately not a separate -P flag. A flag is a second thing to
            // remember, and the two would drift: someone sets the https URLs,
            // forgets the flag, and ships a public APK still carrying a standing
            // permission to speak plaintext to 192.168.1.136. Reading the scheme
            // means the security posture cannot disagree with the destination.
            //
            //   all three https  -> network_security_config_public  (NO cleartext
            //                       exceptions at all; the platform refuses any
            //                       http:// the app attempts)
            //   anything http    -> network_security_config         (keeps the
            //                       192.168.1.136 exception so LAN builds work)
            //
            // The strict config is what makes a missed https:// fail LOUDLY —
            // "Cleartext HTTP traffic to <host> not permitted", naming the host —
            // instead of silently shipping tokens over the open internet.
            val allHttps = listOf(prodBaseUrl, prodPlatformUrl, prodUpdateUrl)
                .all { it.startsWith("https://") }
            manifestPlaceholders["netSecConfig"] =
                if (allHttps) "network_security_config_public" else "network_security_config"
            println(
                "BlueHive release: netSecConfig=" +
                    manifestPlaceholders["netSecConfig"] +
                    (if (allHttps) " (public, cleartext BLOCKED)"
                     else " (LAN, cleartext allowed to 192.168.1.136)")
            )

            // ENABLED 2026-08-12. This was false, which quietly disabled the whole
            // proguard-rules.pro file below — including the -assumenosideeffects
            // block that strips android.util.Log. Every Log.d/i/w/e call, and the
            // strings they build, shipped in the release APK, and the diagnostics
            // "Send Logs" feature then uploaded that output to the backend.
            //
            // Minification also raises the cost of patching the APK. It does not
            // make it impossible — client-side checks are presentation, server-side
            // is enforcement — but there is no reason to ship readable class names.
            //
            // IF A RELEASE BUILD MISBEHAVES AND DEBUG DOES NOT, LOOK HERE FIRST.
            // R8 breaks reflection-based code SILENTLY: Gson fields come back null
            // rather than throwing, and a renamed AIDL interface simply fails to
            // bind. Keep rules for both are in proguard-rules.pro — io.companion.host
            // is a published contract and must never be obfuscated.
            isMinifyEnabled = true

            // Left OFF deliberately. Resource shrinking removes drawables R8 cannot
            // see referenced, breaking anything resolved by name at runtime
            // (getIdentifier, painterResource behind a variable). The APK is ~198MB
            // and nearly all of that is cronet's embedded native libraries, which
            // resource shrinking does not touch — so it buys little here while adding
            // a second variable to any regression. Revisit once minification has been
            // running cleanly for a while.
            isShrinkResources = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.getByName("release")

            // armeabi-v7a ONLY for shipped builds.
            //
            // Every ARM Android TV device runs v7a, including 64-bit ones, so this
            // is the universal choice — while x86_64 only serves emulators and a
            // handful of x86 boxes. It cost nothing until cronet-embedded arrived;
            // that ships a full Chromium network stack per ABI, which took the
            // release APK from ~30MB to 204MB. Dropping the unused ABI gives most
            // of that back. Re-add x86_64 only if you actually ship to x86.
            ndk {
                abiFilters.clear()
                abiFilters += listOf("armeabi-v7a")
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

// ── Workaround: AGP 9.3.1 + Kotlin 2.3.0 + minification ───────────────────────
//
// Turning on isMinifyEnabled activates AGP's `produceReleaseComposeMapping` task,
// which improves Compose frame names in R8 stack traces. That task resolves
// org.jetbrains.kotlin:compose-group-mapping at AGP's OWN built-in Kotlin version
// (2.2.10, via android.builtInKotlin=true in gradle.properties) rather than the
// project's — and that artifact was never published at 2.2.10.
//
// Verified 2026-08-12 by fetching the POMs directly:
//     2.2.10  Maven Central 404   Google maven 404
//     2.3.0   Maven Central 200   <- the version this project actually uses
//
// So the build failed with "Could not find org.jetbrains.kotlin:compose-group-
// mapping:2.2.10" the moment minification was switched on. Known-bad interaction
// with Kotlin 2.3 — see JetBrains KT-83266.
//
// Pinning the request to the project's Kotlin version resolves it. Scoped to that
// single configuration so no other dependency resolution is affected, and read
// from the version catalog so it follows any future Kotlin bump automatically.
configurations.matching { it.name.contains("composeMappingProducer") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "compose-group-mapping") {
            useVersion(libs.versions.kotlin.get())
        }
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

    // ── HTTP/3 (QUIC) data source ────────────────────────────────────────────
    // OkHttp cannot speak HTTP/3 at all. Cronet (Chrome's network stack) can,
    // and that is the whole point here: CUJO reads the hostname from the TLS
    // ClientHello on TCP, while QUIC runs over UDP/443 which consumer filters
    // overwhelmingly do not inspect. MEASURED: GeckoView reached vidfast.vc
    // where curl failed 0/12 from the same box, and the CDNs advertise
    // `alt-svc: h3=":443"` — so the browser has a road the player doesn't.
    //
    // cronet-embedded, not play-services-cronet: it adds ~10MB but works even
    // where Play Services Cronet is missing or stale, which on a cheap TV box
    // is not a safe assumption. ExoPlayerConfig falls back to OkHttp if the
    // engine fails to build, so this can only add a path, never remove one.
    implementation("androidx.media3:media3-datasource-cronet:${media3Version}")
    implementation("org.chromium.net:cronet-embedded:119.6045.31")


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
    // DNS-over-HTTPS for stream fetches — see ExoPlayerConfig.okHttpClient().
    implementation(libs.okhttp.dnsoverhttps)
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
