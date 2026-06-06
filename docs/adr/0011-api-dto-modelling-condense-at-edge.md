# API DTO modelling: faithful flat wire, condensed domain

**Context.** The Deferno API (live envelope `version: 0.1`) shapes its payloads in ways that fight a
naive Kotlin model. List elements are a generic `ItemEnvelope<T>` whose `T` is `#[serde(flatten)]`-ed
into the envelope (so `id`/`title` sit beside `ref`/`org_slug`/`sequence`/`type`), but
**kotlinx.serialization has no `@flatten`**. The full-item view is a closed `oneOf{task,habit,chore,
event}` discriminated by an *injected* `type` — duplicated as a redundant `kind` on `/items` only.
"Status" is overloaded across **six** wire enums with inconsistent casing — `TaskStatus`
(`in-progress`, hyphen), `DefStatus`, `OccurrenceStatus`/`ChoreOccurrenceStatus`/
`DerivedChoreOccurrenceStatus` (`in_progress`, underscore). And there is a **read/write asymmetry**:
the client *sets* a coarse occurrence action (`in_progress` / `done` / `skipped`|`dropped`) while the
server *derives* the finer read states (`scheduled`, `missed`, and the `done_on_time`/`done_late`
split). See also ADR-0005 (version window) and ADR-0001 (no `updated_at` → LWW).

**Decision.** Two layers, with all the wire ugliness quarantined in `core:network`:

- **Faithful wire DTOs.** Per-endpoint, **flat** data classes that combine the `ItemEnvelope` fields
  with the (flattened) payload — *not* a generic `ItemEnvelope<T>` (kotlinx can't flatten). Lossless:
  every enum value via an explicit `@SerialName` carrying the exact wire token, plus an `Unknown`
  fallback so additive values never crash the tolerant reader (ADR-0005).
- **`Item` union** = sealed `ItemView` with `@JsonClassDiscriminator("type")` and serial names
  `task`/`habit`/`chore`/`event`; the redundant `kind` is ignored.
- **A DTO→domain mapper at the network boundary** condenses to clean inner types. The domain replaces
  the overloaded "status" with three distinctly-named enums (see `CONTEXT.md`): **WorkingState**
  (Task), **DefinitionState** (the Habit/Chore/Event "light switch"), and **OccurrenceState** (one
  firing; `Done` carries on-time/late punctuality; `Missed` kept distinct from `Skipped`). Domain enum
  constants are idiomatic Kotlin (PascalCase); the wire casing lives only on `@SerialName`.
- **Occurrence writes** use a coarse domain `OccurrenceAction { Start, Complete, Skip }`; the mapper
  emits the kind-appropriate wire token (chore `skipped` vs event `dropped`). `Scheduled`/`Missed`/
  punctuality are read-only/server-derived and never written.
- `in-review` on `DefinitionState` is retained faithfully pending a backend clarification.

**Considered & rejected.** A generic `ItemEnvelope<T>` + hand-written `JsonTransformingSerializer`
(more machinery than v1 warrants); one unified `Status` enum (impossible — six enums over three
distinct axes with inconsistent casing); codegen DTOs (botches the injected discriminator, the
flatten, and the tolerant-reader defaults — ADR-0005).

**Consequences.** The rest of the app sees only the clean domain `Item`; wire shape changes are a
localized edit to the DTOs + mapper. The SQLDelight schema (ADR-0001, #21) is modelled from the
**domain** types, not the wire. New additive enum values degrade to `Unknown` rather than failing.
