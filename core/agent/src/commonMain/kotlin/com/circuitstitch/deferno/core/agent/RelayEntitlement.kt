package com.circuitstitch.deferno.core.agent

/**
 * The Active [[Account]]'s **relay entitlement** (#150, ADR-0027): whether the Deferno relay will serve
 * this Account's inference. This is the **per-Account** half of the gate (the device-local engine
 * selection, [InferenceEnginePreference], is the per-device half). Read through this interface so it is
 * **fake-able in tests** (#150 AC) — the
 * real source is the backend's entitlement exposure (Kyle-Falconer/Deferno#345), not yet landed, so the
 * app binds a [FakeRelayEntitlement] until it does.
 *
 * `suspend` so the real source can hit the network; a constant fake answers synchronously.
 */
fun interface RelayEntitlement {
    /** Whether the Active Account may use the relay. */
    suspend fun isEntitled(): Boolean
}

/**
 * A flippable constant [RelayEntitlement] for the app (until Deferno#345) and tests. The app binds it
 * `entitled = false` — the relay isn't deployed yet, so no Account is entitled, the Settings row renders
 * a clear disabled state, and no inference is attempted (#150 AC2). Tests flip [entitled] (a mutable
 * `var`) to prove the gate toggles **without restart** (#150 AC4) — the [InferenceEngineCatalog] re-reads it per call.
 * **Measured** (commonTest) via the gate tests.
 */
class FakeRelayEntitlement(var entitled: Boolean = false) : RelayEntitlement {
    override suspend fun isEntitled(): Boolean = entitled
}
