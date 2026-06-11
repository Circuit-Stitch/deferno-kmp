package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **wire** forms of the `hotkeys` capability (#125, ADR-0024): register/unregister a global
 * hotkey, and the push payload when one fires. The `id` is the client-chosen handle echoed back in
 * every [SidecarTopics.HotkeyFired] push; registering an already-registered id **replaces** it, and
 * unregistering an unknown id is an idempotent ack. None of these carry private content (a key
 * position is configuration, not user text), so they are not redacted.
 */
@Serializable
data class RegisterHotkeyWire(
    /** The client-chosen handle for this binding (echoed in [HotkeyFiredWire.id]). */
    val id: Long,
    /** A [SidecarHotkeyKeys] name — a single `a`–`z` / `0`–`9` character or a named key. */
    val key: String,
    /** A non-empty modifier set (`invalid_params` when empty — an unmodified global key is hostile). */
    val modifiers: Set<HotkeyModifier>,
)

/** The [SidecarMethods.UnregisterHotkey] params. */
@Serializable
data class UnregisterHotkeyWire(val id: Long)

/** The [SidecarTopics.HotkeyFired] push payload: which registered binding fired. */
@Serializable
data class HotkeyFiredWire(val id: Long)

/** A hotkey modifier on the wire. */
@Serializable
enum class HotkeyModifier {
    @SerialName("command") COMMAND,
    @SerialName("option") OPTION,
    @SerialName("control") CONTROL,
    @SerialName("shift") SHIFT,
}

/**
 * The contract's canonical hotkey key names (`contracts/sidecar/protocol-v1.md`): single characters
 * `a`–`z` / `0`–`9` plus the named keys, all interpreted at the **ANSI key position**
 * (layout-independent). The stub validates against this set exactly as the Swift Helper's key table
 * does, so an invalid key fails `invalid_params` identically on both.
 */
object SidecarHotkeyKeys {
    val All: Set<String> =
        (('a'..'z') + ('0'..'9')).map { it.toString() }.toSet() +
            setOf("space", "return", "escape", "tab") +
            (1..12).map { "f$it" }.toSet()
}
