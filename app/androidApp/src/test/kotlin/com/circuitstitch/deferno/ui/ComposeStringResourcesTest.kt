package com.circuitstitch.deferno.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.circuitstitch.deferno.core.designsystem.resources.Res
import com.circuitstitch.deferno.core.designsystem.resources.activity_when_pattern
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the compose-resource packaging seam: localized `Res.string` values live in
 * core:designsystem's composeResources and reach the Android target as assets ONLY while the
 * `deferno.compose.library` convention keeps `androidResources.enable = true` (CMP-9547). Without
 * it the AGP 9 KMP android target silently drops them from the APK and from Robolectric's merged
 * assets, and every `stringResource` call throws MissingResourceException — so if this test fails
 * that way, check the convention plugin first.
 */
@RunWith(RobolectricTestRunner::class)
class ComposeStringResourcesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun depModuleResString_resolvesUnderRobolectric() {
        composeRule.setContent { Text(stringResource(Res.string.activity_when_pattern)) }
        composeRule.onNodeWithText("MMM d · HH:mm").assertExists()
    }
}
