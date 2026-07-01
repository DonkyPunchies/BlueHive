package com.example.bluehive.baselineprofile

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
 * NOTE on depth: BaselineProfileRule clean-installs each iteration, so there is no
 * session. The deep journey (profile → home → scroll → details) only runs if the
 * generation build actually reaches Home. Without an auth bypass it lands on the
 * pairing/login screen — still captures startup + Compose runtime + login Compose,
 * but NOT HomeScreenComposeContent. The runCatching block makes that case a no-op
 * instead of a failure. Seed/stub auth in the generation build to capture Home.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.example.bluehive",
        includeInStartupProfile = true   // also emits a startup profile (extra cold-start win)
    ) {
        // Always captured: cold start + first frame of whatever renders.
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        Thread.sleep(3_000)

        // ── Deep journey (fires only if the build reaches Home) ──────────────
        runCatching {
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