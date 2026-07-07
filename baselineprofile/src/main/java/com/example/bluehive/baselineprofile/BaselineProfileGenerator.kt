package com.example.bluehive.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Generates a Baseline Profile for BlueHive.
 *
 * Run: ./gradlew :app:generateBaselineProfile   (spins up the aospApi34 managed device)
 *
 * The profile AOT-compiles the hot startup + Compose paths so they aren't JIT'd
 * live on the onn box (the `Compiler allocated ... HomeScreenComposeContent` cost).
 *
 * ENTRY POINT: BlueHive is a companion app — no launcher intent exists, so the
 * no-arg startActivityAndWait() throws "Unable to acquire intent". We enter
 * through LoadingScreenActivity, which is exported ONLY in the generation
 * variant (see app/src/nonMinifiedRelease/AndroidManifest.xml). That captures
 * the splash video player, AppWarmup's full fan-out + carousel image prefetch,
 * and the Coil pipeline — the heaviest cold-start code. The warm-up's network
 * calls fail on the emulator (no host token); the CODE paths still execute,
 * and executed code is exactly what the profile records.
 *
 * NOTE on depth: without a host token the app closes itself after the splash
 * (session check fails), so the deep journey (profile → home → details) only
 * runs if the build reaches Home — it's guarded by a foreground check and a
 * runCatching. Seed/stub auth in the generation build to capture
 * HomeScreenComposeContent; until then this profile covers Application init +
 * splash + warm-up, which is the bulk of the first-launch JIT cost.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.example.bluehive",
        includeInStartupProfile = true   // also emits a startup profile (extra cold-start win)
    ) {
        pressHome()
        startActivityAndWait(
            Intent().apply {
                component = ComponentName(packageName, "com.example.bluehive.LoadingScreenActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        device.waitForIdle()

        // Cover the whole splash window: the bee-logo screen runs AppWarmup
        // (parallel API fan-out + full trending/trailer image prefetch) during
        // this time. Failed fetches return fast, so 10s comfortably covers the
        // no-auth path end to end.
        Thread.sleep(10_000)

        // ── Deep journey (fires only if the build actually reached Home) ─────
        // Without seeded auth the app has already closed itself by now; the
        // foreground check stops us from D-pad-mashing the emulator launcher.
        runCatching {
            if (device.currentPackageName != packageName) return@runCatching

            // Profile picker → select the focused profile.
            device.pressDPadCenter()
            device.waitForIdle()
            Thread.sleep(2_000)

            // Warm the home shelves: walk a row, drop down, walk again.
            repeat(6) { device.pressDPadRight(); Thread.sleep(150) }
            device.pressDPadDown()
            device.waitForIdle()
            Thread.sleep(500)
            repeat(4) { device.pressDPadRight(); Thread.sleep(150) }

            // Open a details screen, let it compose, back out.
            device.pressDPadCenter()
            device.waitForIdle()
            Thread.sleep(2_500)
            device.pressBack()
            device.waitForIdle()
            Thread.sleep(1_000)
        }
    }
}
