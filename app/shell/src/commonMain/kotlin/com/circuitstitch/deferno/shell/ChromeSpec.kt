package com.circuitstitch.deferno.shell

/**
 * The adaptive chrome the shell's single top bar renders for the foreground **in-chrome** surface
 * (Cand 1): one bar, computed here in the shell from the active [Destination] + its tier-3 drill-down,
 * so the per-screen headers are gone and the header buttons no longer "come and go" arbitrarily.
 *
 * Compose-free and in `app/shell` commonMain (iOS-visible), so every platform renders the same spec
 * natively (ADR-0003): Android/desktop in Compose (`ShellChrome`), iOS in SwiftUI. The literal [title]
 * is shell **presentation** state — not a domain label and not the nav-suite label (a Destination's nav
 * row reads "Plan" while its screen [title] reads "Today") — so it lives with the chrome it drives and
 * each platform shows the same words.
 *
 * [drilled] picks the leading nav affordance: `false` → the ☰ menu that opens the drawer; `true` → a ←
 * back that pops the active Destination's drill-down (the View routes it to the shell's `onBack`).
 * [actions] are the trailing actions for this surface — global create affordances (plus a per-screen
 * Refresh) at a Destination root, empty when drilled into a detail.
 */
data class ChromeSpec(
    val title: String,
    val drilled: Boolean = false,
    val actions: List<ChromeAction> = emptyList(),
)

/**
 * A trailing top-bar action: [kind] picks the glyph + content description (platform-injected — the View
 * maps it), [onInvoke] runs the intent (a shell or component handler). Deliberately a plain class with a
 * lambda, not a `data class`: it is rendered, never compared or serialized.
 */
class ChromeAction(val kind: ChromeActionKind, val onInvoke: () -> Unit)

/** The fixed catalog of top-bar actions — each platform View maps a kind to its glyph + a11y label. */
enum class ChromeActionKind { Refresh, BrainDump, New }
