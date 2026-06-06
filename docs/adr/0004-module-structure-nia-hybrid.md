# Module structure: NIA-style hybrid, co-located Android Views, convention plugins

**Context.** The shared-presentation KMP core (ADR-0003) with native UI per platform needs a module
layout that delivers strong modularity and DRY builds at corporation scale.

**Decision.** A hybrid taxonomy:
- **`core/*`** (layered foundations): `model`, `common`, `network`, `database`, `secure`, `data`,
  `domain` (thin), `designsystem`.
- **`feature/*`** (vertical slices): `auth`, `tasks`, `plan` for v1 — each owns its shared Decompose
  component + ViewModel + state in `commonMain`.
- **`app/*`** (per-platform entry points): `androidApp`, `iosApp`, `desktopApp`.
- **`build-logic/`** convention plugins from day one.

Android Compose Views live **co-located in each feature module's `androidMain`** (UI beside its
shared presentation). iOS SwiftUI Views live **centralized in the `iosApp` Xcode project** (per-
feature folders), because Swift cannot live in a Kotlin module.

**Considered & rejected.** Layer-only or feature-only taxonomies (hybrid scales better); all Android
UI lumped in `androidApp` (co-location gives better view modularity).

**Consequences.** A deliberate **Android-co-located / iOS-centralized** View asymmetry — it's
inherent to KMP + native SwiftUI, not an oversight. v1 keeps granularity deliberately small
(core/* + 3 features); modules earn further isolation rather than being split pre-emptively.
