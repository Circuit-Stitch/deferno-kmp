package com.circuitstitch.deferno.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `cadence_mode` token codec (#401) — the one place the client decides what a chore's advance mode
 * means, so every claim the type's KDoc makes about the wire is pinned here rather than re-derived at
 * the six call sites that read or write the field.
 *
 * Two of those claims are load-bearing and easy to get wrong:
 *
 * 1. **Absent is not unknown.** `Chore.cadence_mode` is `#[serde(default)]` over a `#[default] Rolling`
 *    variant with no `skip_serializing_if`, and a boot migration back-filled every legacy row, so an
 *    absent token *is* rolling. Locally the same holds from the other side — every client-created chore
 *    has a NULL column — so decoding absence to an "unknown" would mislabel the entire cache.
 * 2. **An unrecognised token survives verbatim.** Mirroring `Cadence.Unmodelled` (#382): flattening a
 *    future third mode into `Rolling` would not merely fail to render it, it would rewrite how the
 *    user's chore schedules on the next write-back.
 */
class CadenceModeTest {

    @Test
    fun anAbsentOrBlankTokenIsRollingAndNotAnUnknown() {
        // The distinction the whole type turns on: this is `Rolling`, NOT `Unmodelled("")`.
        assertEquals(CadenceMode.Rolling, cadenceModeFromWire(null))
        assertEquals(CadenceMode.Rolling, cadenceModeFromWire(""))
    }

    @Test
    fun bothWireTokensDecodeToTheirModes() {
        assertEquals(CadenceMode.Rolling, cadenceModeFromWire("rolling"))
        assertEquals(CadenceMode.Fixed, cadenceModeFromWire("fixed"))
    }

    @Test
    fun anUnrecognisedTokenIsPreservedVerbatimRatherThanFlattenedToTheDefault() {
        assertEquals(CadenceMode.Unmodelled("drifting"), cadenceModeFromWire("drifting"))
        // Case matters: the wire is `snake_case` serde, so `Rolling` is NOT the `rolling` token. Silently
        // accepting it would mean this codec had a second, undocumented spelling of the same mode.
        assertEquals(CadenceMode.Unmodelled("Rolling"), cadenceModeFromWire("Rolling"))
    }

    @Test
    fun everyModeGoesBackOutUnderItsWireTokenAndNeverItsKotlinName() {
        // The persisted/exported format. "Rolling" (the variant name) written into the column or into
        // `items.json` would strip the mode off every row and corrupt every restore.
        assertEquals("rolling", CadenceMode.Rolling.wireToken)
        assertEquals("fixed", CadenceMode.Fixed.wireToken)
        assertEquals("drifting", CadenceMode.Unmodelled("drifting").wireToken)
    }

    @Test
    fun everyTokenSurvivesAFullTokenToModeToTokenRoundTrip() {
        for (token in listOf("rolling", "fixed", "drifting", "every_other_time")) {
            assertEquals(token, cadenceModeFromWire(token).wireToken, "round-trip of $token")
        }
        // The absent token is the one deliberate asymmetry: it comes back as the EXPLICIT `rolling` the
        // server itself serializes, which is the same reading and never a different mode.
        assertEquals("rolling", cadenceModeFromWire(null).wireToken)
    }

    @Test
    fun aNamelessUnmodelledModeEmitsNoTokenAtAllRatherThanABlankOne() {
        // Unreachable through `cadenceModeFromWire` (a blank token is Rolling), so this is the residual
        // hand-built case. Omitting the key lets the reader on either end supply Rolling — the one value
        // a mode with no name could not have contradicted — instead of persisting a meaningless `""`.
        assertNull(CadenceMode.Unmodelled("").wireToken)
    }
}
