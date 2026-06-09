package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **wire** form of a capability's permission state — the [SidecarMethods.QueryPermission] result
 * and the [SidecarTopics.PermissionChanged] push payload (ADR-0024). A contract-owned type (the Sidecar
 * module stays a leaf); #120's permission ports condense it to the shared `DictationStatus.Permission*`
 * domain at the edge (ADR-0011/0024). Carries no private content, so it is not redacted.
 */
@Serializable
data class PermissionStatusWire(
    /** The capability this state is about, e.g. `"mic"` or `"speech"`. */
    val capability: String,
    // Defaulted so [SidecarJson]'s `coerceInputValues` can coerce an unrecognized wire value to
    // [PermissionStatusValue.UNKNOWN] rather than throwing (tolerant reader, ADR-0005).
    val status: PermissionStatusValue = PermissionStatusValue.UNKNOWN,
)

/**
 * A capability's permission state on the wire. Decoding is tolerant (ADR-0005): an unrecognized value
 * coerces to [UNKNOWN] via [SidecarJson]'s `coerceInputValues`.
 */
@Serializable
enum class PermissionStatusValue {
    @SerialName("granted") GRANTED,
    @SerialName("denied") DENIED,
    @SerialName("not_determined") NOT_DETERMINED,
    @SerialName("restricted") RESTRICTED,
    @SerialName("unknown") UNKNOWN,
}
