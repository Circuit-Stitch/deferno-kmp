package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement

/**
 * The **Permission capability port** (#120, ADR-0024): a typed facade over [SidecarClient] for the
 * `permissions` capability — introspect a permission's [status], [request] it (the discrete
 * not-determined → prompt → granted/denied flow), and observe out-of-band [changes]. The wire
 * mechanics (method ids, [SidecarJson], `JsonElement` payloads) stay inside this port, like its
 * siblings ([SidecarSpeechPort], [SidecarNotificationPort]); a consumer condenses
 * [PermissionStatusValue] to its own domain at the edge (ADR-0011) — core/speech's
 * `SidecarSpeechToText` maps it onto the shared `DictationStatus.Permission*` UX states.
 *
 * Like [SidecarSpeechPort], this port is an interface: its consumer lives out-of-module with its own
 * unit tests, which fake this seam.
 *
 * Permission state is **live-introspected device state** — an [[App setting]] in CONTEXT.md terms:
 * scoped to this install on this device, never persisted by the client, never synced, never crossing
 * Accounts. The OS (TCC on macOS) is the single source of truth; this port only reads and prompts.
 */
interface SidecarPermissionPort {

    /**
     * One capability's current permission state ([SidecarMethods.QueryPermission]) — **introspection
     * only, never prompts** (the contract's invariant). Degrades, never throws: no Helper, a Helper
     * that doesn't broker permissions, a failed call, or an unreadable reply all report
     * [PermissionStatusValue.UNKNOWN] (≈ "introspection has nothing to say"), which a consumer must
     * treat as open — not foreclosed.
     */
    suspend fun status(capability: String): PermissionStatusValue

    /**
     * Resolve one capability's permission ([SidecarMethods.RequestPermission]): on `not_determined`
     * the Helper fires the real OS prompt and answers the **settled** state (the call suspends while
     * the person decides); in any other state it reports the current state without prompting — an OS
     * denial is terminal (re-requesting never re-prompts; only the OS settings surface flips it, see
     * [SidecarPermissionSettingsLinks]). Degrades to [PermissionStatusValue.UNKNOWN] like [status].
     */
    suspend fun request(capability: String): PermissionStatusValue

    /**
     * The Helper's out-of-band [SidecarTopics.PermissionChanged] pushes — every capability's, as the
     * Helper observes states settle (a TCC prompt answered, a Settings flip). Decoding is tolerant
     * (ADR-0005): an unreadable payload is dropped, never thrown at a collector. Emits only while the
     * connection is up; it completes/idles silently otherwise (subscribe is passive — it never dials).
     */
    fun changes(): Flow<PermissionStatusWire>
}

/**
 * The production [SidecarPermissionPort] over a [SidecarClient]. Both calls degrade to
 * [PermissionStatusValue.UNKNOWN] rather than throw — a permission check can never crash a consumer's
 * availability path (the same posture as [DefaultSidecarSpeechPort.readiness]).
 */
class DefaultSidecarPermissionPort(private val client: SidecarClient) : SidecarPermissionPort {

    override suspend fun status(capability: String): PermissionStatusValue =
        call(
            SidecarMethods.QueryPermission,
            SidecarJson.encodeToJsonElement(QueryPermissionWire.serializer(), QueryPermissionWire(capability)),
        )

    override suspend fun request(capability: String): PermissionStatusValue =
        call(
            SidecarMethods.RequestPermission,
            SidecarJson.encodeToJsonElement(RequestPermissionWire.serializer(), RequestPermissionWire(capability)),
        )

    override fun changes(): Flow<PermissionStatusWire> = client.pushes
        .filter { it.topic == SidecarTopics.PermissionChanged }
        .mapNotNull { push ->
            runCatching { SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), push.payload) }
                .getOrNull() // tolerant at the seam (ADR-0005): an unreadable push is dropped
        }

    private suspend fun call(method: String, params: JsonElement): PermissionStatusValue = try {
        client.connect()
        when {
            SidecarCapabilities.Permissions !in client.capabilities() -> PermissionStatusValue.UNKNOWN
            else -> {
                val result = client.request(method, params)
                if (result == null) {
                    PermissionStatusValue.UNKNOWN
                } else {
                    runCatching {
                        SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result).status
                    }.getOrDefault(PermissionStatusValue.UNKNOWN)
                }
            }
        }
    } catch (_: SidecarException) {
        PermissionStatusValue.UNKNOWN
    }
}

/**
 * The **deep-link** leg of the permission port (#120): where the OS lets the person flip a foreclosed
 * permission. On macOS that is the System Settings Privacy pane for the capability — the TCC grant
 * belongs to the *Helper's* signed identity (ADR-0024), and a denial is terminal until flipped there.
 * `null` on hosts with no such surface (Linux/Windows — no Helper TCC to flip) and for capability ids
 * without a documented pane. Pure mapping, OS-guarded here so callers need no `os.name` logic.
 */
object SidecarPermissionSettingsLinks {

    /** The `x-apple.systempreferences:` URI for [capability]'s Privacy pane, or `null` off-macOS / unmapped. */
    fun forCapability(
        capability: String,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): String? {
        if (!osName.contains("mac", ignoreCase = true)) return null
        return when (capability) {
            SidecarPermissionCapabilities.Microphone ->
                "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"
            SidecarPermissionCapabilities.Speech ->
                "x-apple.systempreferences:com.apple.preference.security?Privacy_SpeechRecognition"
            else -> null
        }
    }
}
