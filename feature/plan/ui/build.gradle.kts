plugins {
    // Compose UI library on the Compose platforms only (Android + JVM/desktop), no iOS — holds the
    // Plan slice's Compose View (#27). Sibling of `:feature:plan` for the same reason as tasks/ui.
    id("deferno.compose.library")
}

kotlin {
    android {
        namespace = "com.circuitstitch.deferno.feature.plan.ui"
    }

    sourceSets {
        // commonMain holds the whole dashboard — the "See the trees" restyle lifted the Today/What's-next/
        // Focus body itself here, not just the atoms (PlanDashboard.kt), so Android and desktop render one
        // source and jvmMain only wraps it at a reading width.
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))
            // The Views render `PlanComponent` and read the shared ✦ precedence (`suggestedTask`, #375) —
            // both from commonMain code, so the slice dependency belongs here rather than being declared
            // once per platform. Mirrors feature/tasks/ui, whose commonMain helper does the same.
            implementation(project(":feature:plan"))

            implementation(libs.compose.runtime)
            // stringResource/pluralStringResource over core:designsystem's shared string catalog
            // (its public Res) — designsystem only `implementation`s this artifact (not transitive).
            implementation(libs.compose.components.resources)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        // No per-platform dependency block: androidMain carries no sources at all, and jvmMain holds only
        // PlanDesktopScreen, which centres the shared `PlanScreen` at a reading width — the desktop
        // counterpart rather than the phone layout stretched, which ADR-0007 names an explicit non-goal.
        // Both compile against the slice through commonMain above.

        // The dashboard's own render tests on the JVM-fast path (no device) — the same harness
        // feature/tasks/ui uses. Every one of them goes through a real composition of the shared body
        // both platforms draw, which is the only way to answer what they ask: the choice cards' "why"
        // line; that the day list keeps the curated order around the ✦ banner; that the ✦ highlight lands
        // on the suggested row and on no other, so a day of nothing but recurring rows gets none at all;
        // and that What's-next picks from the three cards it draws rather than from the whole day. The ✦
        // precedence itself is `:feature:plan`'s (`suggestedTask`), tested in that module's commonTest —
        // which is where it has to live to be *compiled* against the Apple targets that read the same
        // function, and to run on the Android host as well as the JVM. (Only the JVM and Android host runs
        // happen in CI: ci.yml is `ubuntu-latest`, so the Apple test tasks self-disable there — ADR-0006,
        // the gap #368 tracks.)
        jvmTest.dependencies {
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
