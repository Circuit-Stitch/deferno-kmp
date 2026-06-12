# Per-platform feature View bodies are intentionally duplicated, not collapsed into commonMain

**Context.** ADR-0004's #27 amendment gives each feature slice a `:ui` submodule
(`deferno.compose.library`, Android + JVM, no iOS) whose `androidMain` holds the Android-native Compose
screen and whose `commonMain` holds stateless, platform-neutral atoms — with the wording "reuse them …
each View diverges from the shared atoms exactly as much as its platform needs". As desktop reached
parity, each slice grew a full `jvmMain` screen body beside its `androidMain` one
(`SettingsScreen`/`SettingsDesktopScreen`, `SearchScreen`/`SearchDesktopScreen`,
`PlanScreen`/`PlanDesktopScreen`), and these bodies are often nearly identical. An architecture review
flagged the duplication as a depth/"deepening" opportunity — hoist each stateless `…Content()` into the
slice's `commonMain` and reduce the per-platform Views to thin shells — and ADR-0004's "reuse … diverge
as needed" wording reads as a mandate to do exactly that.

**Decision.** Keep the per-platform feature `:ui` **screen bodies deliberately duplicated**; do not
collapse them into a shared `commonMain` `…Content()`. The `commonMain` layer of a `:ui` module is for
**stateless, platform-neutral atoms** (e.g. `ProfileAtoms`, `TaskRow`) — whole screen bodies are not
promoted to it. The platforms diverge by design (ADR-0007's "a stretched phone is an explicit non-goal"
— desktop reading widths, keyboard/IME affordances, the adaptive navigation suite vs. a desktop
rail/drawer, desktop-only category filters), and we want each platform's screen free to evolve
independently rather than become one body webbed with `if (platform)` branches. The duplication is the
accepted cost of that per-platform flexibility. This **amends ADR-0004's #27** — its "reuse … diverge
as needed" applies to the **atoms**, not the screen bodies — and **generalises ADR-0017's** shell-scoped
rejection of a shared Compose `shell/ui` module to every feature `:ui` slice, on the same ADR-0003/0007
"genuinely native per platform" footing.

**Consequences.** Near-identical screens stay duplicated across `androidMain`/`jvmMain`, and a fix to
shared screen behaviour may need applying in both — accepted in exchange for independent platform
evolution and no `if (platform)` web. A future architecture review will see the duplication and should
consult this ADR before re-proposing a `commonMain` collapse. Genuinely platform-neutral, stateless
pieces still belong in `commonMain` as atoms; the line drawn here is at *screen bodies*, not at all
sharing.
