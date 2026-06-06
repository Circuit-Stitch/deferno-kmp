# iosApp

The iOS application entry point. Two halves live here:

- **`build.gradle.kts`** — the `:app:iosApp` Gradle module. It bundles the shared
  feature slices into a single static **`Deferno.framework`** (targets `iosX64`,
  `iosArm64`, `iosSimulatorArm64`). This half builds on any host for klib compilation;
  the framework binary links on a **macOS runner** (ADR-0006).
- **`iosApp/`** — the **Xcode project** (SwiftUI). It is created on macOS with Xcode
  and links the `Deferno` framework produced above. SwiftUI Views are centralized here,
  in per-feature folders (the deliberate Android-co-located / iOS-centralized asymmetry
  of ADR-0004). The placeholder Swift sources in `iosApp/` show the intended shape; the
  `.xcodeproj` itself is generated on macOS and is not committed from the Linux scaffold.

## Wiring the framework (done on macOS)

1. Open `iosApp/iosApp.xcodeproj` in Xcode (created via the KMP project wizard or by hand).
2. Add a **Run Script** build phase that invokes `:app:iosApp:embedAndSignAppleFrameworkForXcode`
   (or use the `assembleDefernoXCFramework`/`embedAndSign` tasks) so Gradle builds and
   embeds `Deferno.framework`.
3. `import Deferno` from Swift; host the exported Decompose components in SwiftUI.

Until features expose Swift-facing APIs, the framework is intentionally empty (see the
`export(...)` note in `build.gradle.kts`).
