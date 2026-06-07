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
shared presentation). *(Amended by #27 — see the amendment below: Compose can't share a module with
iOS, so the Views live in a sibling `:feature:*:ui` submodule instead.)* iOS SwiftUI Views live
**centralized in the `iosApp` Xcode project** (per-feature folders), because Swift cannot live in a
Kotlin module.

**Considered & rejected.** Layer-only or feature-only taxonomies (hybrid scales better); all Android
UI lumped in `androidApp` (co-location gives better view modularity).

**Consequences.** A deliberate **Android-co-located / iOS-centralized** View asymmetry — it's
inherent to KMP + native SwiftUI, not an oversight. v1 keeps granularity deliberately small
(core/* + 3 features); modules earn further isolation rather than being split pre-emptively.

---

## Amendment (2026-06, #27): Compose Views live in a per-slice `:feature:*:ui` submodule

Implementing the first Android Views (#27) surfaced a hard toolchain constraint the original
"co-located in each feature module's `androidMain`" wording can't satisfy: **the Compose compiler
plugin is module-wide and breaks the module's iOS compilation.** The feature *logic* modules target
iOS (they host the shared Decompose components for SwiftUI to drive), and applying Compose there
fails `:feature:*:compileKotlinIosX64` with `IncompatibleComposeRuntimeVersionException` — the iOS
classpath must carry no Compose runtime (iOS = SwiftUI). Compose therefore cannot live in the
feature logic module *at all*, not even in its `androidMain`.

**Revised decision.** Each slice's Compose Views live in a **sibling UI submodule** —
`:feature:tasks:ui`, `:feature:plan:ui` — applying `deferno.compose.library` (the Compose platforms
only: **Android + JVM/desktop, no iOS**, exactly like `core/designsystem`). Within that submodule:

- **`androidMain`** holds the **Android-native screens** (touch; single-pane now, size-class-adaptive
  later). They are deliberately *not* shared to desktop/iOS: a native desktop/iOS View is the
  intended large-screen experience (ADR-0007), not this phone layout stretched.
- **`commonMain`** holds the platform-neutral, stateless **atoms** (task rows, status badges, pane
  headers, empty/loading states) so a future desktop (`jvmMain`) View — and the design system — can
  reuse them. Each View diverges from the shared atoms exactly as much as its platform needs.

The original asymmetry holds and gains one wrinkle: the Android Views sit one module *beside* the
slice's shared presentation rather than *inside* it. App entry points depend on the `:ui` submodules;
the shared logic module stays Compose-free and iOS-capable.

Until auth + DI land (ADR-0008), a **TEMPORARY** in-memory demo host in
`app/androidApp/src/main/.../demo` renders these Views on sample data, and the Compose UI +
Roborazzi screenshot tests live in `app/androidApp` (a `com.android.application` — the conventional
Roborazzi home, reusing the Compose-BOM test infra). Both are throwaway, replaced by the
DI-provided scene graph. (Native desktop and iOS Views, plus the kotlinx-datetime 0.7 migration the
desktop/JVM Compose runtime will need, are tracked as their own follow-ups.)
