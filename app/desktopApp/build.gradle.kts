plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.circuitstitch.deferno.desktop.MainKt")
}

dependencies {
    // Shared KMP feature slices (resolve to their JVM variants). The Compose Desktop
    // Views land in a later UI issue (ADR-0003 — desktop View = Compose Desktop).
    implementation(project(":feature:auth"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:plan"))
    implementation(project(":core:designsystem"))
}
