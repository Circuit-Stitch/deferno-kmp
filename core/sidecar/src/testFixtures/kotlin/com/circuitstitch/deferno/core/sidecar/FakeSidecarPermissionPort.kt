package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A scriptable [SidecarPermissionPort] for [DefaultSidecarSpeechPort]'s preflight tests (#120/#172):
 * per-capability canned statuses plus the canned outcome a `not_determined` request settles to — the
 * same model as the [StubHelper]'s TCC. The real port over the real socket is exercised by
 * [SidecarPermissionPortE2ETest]; the speech port's preflight only needs the seam.
 */
class FakeSidecarPermissionPort(
    /** Per-capability canned status; an unscripted capability reads GRANTED. */
    val statuses: MutableMap<String, PermissionStatusValue> = mutableMapOf(),
    /** What a request against a NOT_DETERMINED status settles it to. */
    var requestOutcome: PermissionStatusValue = PermissionStatusValue.GRANTED,
) : SidecarPermissionPort {

    /** Every capability a [request] was issued for — the prompts a person would have seen, in order. */
    val requested = mutableListOf<String>()

    override suspend fun status(capability: String): PermissionStatusValue =
        statuses[capability] ?: PermissionStatusValue.GRANTED

    override suspend fun request(capability: String): PermissionStatusValue {
        requested += capability
        if (statuses[capability] == PermissionStatusValue.NOT_DETERMINED) {
            statuses[capability] = requestOutcome
        }
        return status(capability)
    }

    override fun changes(): Flow<PermissionStatusWire> = emptyFlow()
}
