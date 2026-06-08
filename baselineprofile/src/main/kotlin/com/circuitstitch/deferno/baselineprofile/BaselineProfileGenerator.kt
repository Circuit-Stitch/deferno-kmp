package com.circuitstitch.deferno.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's **startup** Baseline Profile.
 *
 * [BaselineProfileRule.collect] launches `com.circuitstitch.deferno` from cold several times (until the
 * captured method/class set stabilises), recording the classes + methods exercised up to the first
 * frame. The `androidx.baselineprofile` Gradle plugin merges the result into app/androidApp's release
 * APK, where ProfileInstaller uses it to AOT-compile that hot path on install — replacing the runtime
 * DEX verification + JIT the `run-from-apk` install otherwise pays on every launch.
 *
 * `includeInStartupProfile = true` additionally emits the narrower *startup* profile that drives
 * `dexopt`'s startup-class prioritisation. The journey is intentionally just the launch: this is the
 * cold-start path that dominates time-to-first-frame. Run via `:app:androidApp:generateBaselineProfile`
 * with a device/emulator (API 28+) attached.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = "com.circuitstitch.deferno",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
