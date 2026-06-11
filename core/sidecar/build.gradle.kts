plugins {
    // The repo's first JVM-only core module (ADR-0024/0025): the Sidecar client is the OS-agnostic
    // JVM half of the native-sidecar substrate and has no KMP targets by design (see settings.gradle.kts
    // + deferno.jvm.library). Coverage is folded in via the convention and gated by the root aggregation.
    id("deferno.jvm.library")
    // The IPC frames are JSON over a local socket — model them with kotlinx.serialization. Applied per
    // module via alias (declared `apply false` at the root), the same pattern core/network uses.
    alias(libs.plugins.kotlin.serialization)
    // The StubHelper (the Linux stub Helper, #118) lives in testFixtures, not test: it is the reference
    // server half every consumer's Linux fast-path test drives — this module's own E2E suite and
    // core/speech's SidecarSpeechToText + selector tests (#119), via testFixtures(project(":core:sidecar")).
    `java-test-fixtures`
}

dependencies {
    // The wire contract: a polymorphic sealed Frame hierarchy + JsonElement payloads, decoded with a
    // tolerant reader (ADR-0005), mirroring core/network's DTO layer. `api`: JsonElement is part of the
    // public client surface (request/openStream params, stream events) and SidecarJson is the canonical
    // codec a consumer (#119's SidecarSpeechToText) decodes those events with.
    api(libs.kotlinx.serialization.json)
    // Flow / StateFlow / SharedFlow are part of the public client surface (openStream, pushes, state),
    // so `api` keeps them on consumers' compile classpath.
    api(libs.kotlinx.coroutines.core)

    // The StubHelper speaks the production wire format (FrameCodec + SidecarJson, so it can't drift
    // from the client's) and serves canned frames from coroutines.
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    // The Linux fast-path end-to-end test (ADR-0006/0024): runTest drives the suspend client against a
    // real Unix-socket stub Helper, and Turbine asserts the server-stream + push Flows.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

// The language-neutral golden frames in `contracts/sidecar/` ARE the cross-codebase contract a native
// Helper (Swift/…) implements against. Mount them as test resources from the single source of truth (no
// copy) so SidecarFrameSerializationTest proves the shipping code decodes exactly what the spec documents.
sourceSets {
    test {
        resources.srcDir(rootProject.layout.projectDirectory.dir("contracts/sidecar/fixtures"))
    }
}
