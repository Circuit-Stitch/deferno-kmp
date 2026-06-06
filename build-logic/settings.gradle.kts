// build-logic is an included build (composite) that publishes this project's
// convention plugins. It resolves plugin artifacts itself, so it declares its own
// repositories, and shares the root version catalog as `libs`.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
