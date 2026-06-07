plugins {
    id("deferno.kmp.library")
}

// Low-level DI vocabulary (ADR-0008 / ADR-0014): the scope-key markers + the per-platform
// PlatformContext handle. Depended on by every module that contributes DI bindings
// (@ContributesBinding/@ContributesTo) AND by core:di (which merges them) — sitting *below* both
// so distributed contribution doesn't create a scopes ⇄ merge dependency cycle. Holds no behaviour,
// so it brings no dependencies of its own.
kotlin {
    android {
        namespace = "com.circuitstitch.deferno.core.scopes"
    }
}
