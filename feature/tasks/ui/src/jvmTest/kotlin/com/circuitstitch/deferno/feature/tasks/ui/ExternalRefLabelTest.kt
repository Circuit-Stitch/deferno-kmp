package com.circuitstitch.deferno.feature.tasks.ui

import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.ItemSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure external-provenance display helpers. These drive both the tree row and the detail, so they're
 * proven once here on the JVM-fast path; the composables that consume them stay thin.
 */
class ExternalRefLabelTest {

    @Test
    fun externalRefLabelDerivesTheDimmedGitHubPrefixFromATrackerRef() {
        assertEquals("[GitHub#42]", externalRefLabel(ItemSource.GitHub, "octo/repo#42"))
        // The number is the trailing `#N`, not any digits mid-ref.
        assertEquals("[GitHub#7]", externalRefLabel(ItemSource.GitHub, "octo/repo2#7"))
    }

    @Test
    fun externalRefLabelIsNullWithoutSourceRefOrTrailingNumber() {
        assertNull(externalRefLabel(null, "octo/repo#42")) // native item
        assertNull(externalRefLabel(ItemSource.GitHub, null)) // no ref
        // A calendar id (`{calendar_id}:{event_id}`) has no trailing `#N` → no prefix.
        assertNull(externalRefLabel(ItemSource.GoogleCalendar, "cal-id:event-id"))
        assertNull(externalRefLabel(ItemSource.GitHub, "owner/repo")) // ref without a number
    }

    @Test
    fun sourceLabelIsTheProviderDisplayName() {
        assertEquals("GitHub", sourceLabel(ItemSource.GitHub))
        assertEquals("Google Calendar", sourceLabel(ItemSource.GoogleCalendar))
    }

    @Test
    fun sourceOriginLabelIsTheRefForTrackersAndTheProviderLabelOtherwise() {
        // A tracker ref is its own origin label.
        assertEquals(
            "octo/repo#42",
            sourceOriginLabel(ExternalRef(ItemSource.GitHub, "octo/repo#42", "https://gh/42")),
        )
        // A non-tracker id (no `#`) falls back to the provider label until name resolution lands.
        assertEquals(
            "Google Calendar",
            sourceOriginLabel(ExternalRef(ItemSource.GoogleCalendar, "cal-id:event-id")),
        )
    }
}
