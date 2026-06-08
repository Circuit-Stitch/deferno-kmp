package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.feature.profile.ProfileState
import com.circuitstitch.deferno.feature.profile.ui.ProfileScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Roborazzi screenshot baselines (#70) for the Profile Destination across its `/auth/me` states in the
 * Deferno palette (light + dark) — the signed-in identity card (avatar/name/@handle/Org chip + the
 * Account controls), the loading strip, and the offline/unavailable notice. Record with
 * `./gradlew :app:androidApp:recordRoborazziDebug`; with no Roborazzi mode set `captureRoboImage` is a
 * no-op, so these also run as part of the normal unit-test task.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, darkTheme: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun profile_signedIn_light() = capture("profile_signed_in_light") {
        ProfileScreen(FakeProfileComponent(ProfileState.SignedIn(sampleUser)))
    }

    @Test
    fun profile_signedIn_dark() = capture("profile_signed_in_dark", darkTheme = true) {
        ProfileScreen(FakeProfileComponent(ProfileState.SignedIn(sampleUser)))
    }

    @Test
    fun profile_loading_light() = capture("profile_loading_light") {
        ProfileScreen(FakeProfileComponent(ProfileState.Loading))
    }

    @Test
    fun profile_unavailable_light() = capture("profile_unavailable_light") {
        ProfileScreen(FakeProfileComponent(ProfileState.Unavailable))
    }

    @Test
    fun profile_reauthRequired_light() = capture("profile_reauth_required_light") {
        ProfileScreen(FakeProfileComponent(ProfileState.ReauthRequired))
    }
}
