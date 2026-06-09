package com.circuitstitch.deferno.desktop.update

/**
 * The seam over Conveyor's `SoftwareUpdateController` so [UpdateManager]'s state machine is unit
 * testable without a real Conveyor package (the controller singleton only exists inside an installed
 * app). [ConveyorUpdateBackend] is the production binding; tests supply a fake.
 */
interface UpdateBackend {
    /** The running app's version — the package's `app.version`, or a build-time fallback unpackaged. */
    val currentVersion: String

    /** Whether the OS-native update UI can be triggered here (AVAILABLE only on packaged Win/Mac). */
    fun availability(): UpdateAvailability

    /**
     * Fetch the latest published version and compare it to [currentVersion]. Blocks on HTTP, so the
     * caller runs it off the UI thread. Throws on a network/parse failure.
     */
    suspend fun checkForUpdate(): CheckResult

    /** Hand off to the OS-native update + restart flow (the app closes and relaunches updated). */
    fun triggerUpdate()
}

/** Coarse availability of the in-app updater, mapped from Conveyor's finer-grained `Availability`. */
enum class UpdateAvailability {
    /** Packaged on a self-updating OS (Windows/macOS) — checks + triggers work. */
    AVAILABLE,

    /** Not launched from a Conveyor package (dev/unpackaged run). */
    NOT_PACKAGED,

    /** Linux — updates come from the package manager, not in-app (Conveyor reports UNIMPLEMENTED). */
    PACKAGE_MANAGER,

    /** Any other Conveyor-reported unavailability (unsupported package type, non-GUI, …). */
    OTHER,
}

/** The outcome of [UpdateBackend.checkForUpdate]: a newer version is published, or already current. */
sealed interface CheckResult {
    val latestVersion: String

    data class Available(override val latestVersion: String) : CheckResult
    data class UpToDate(override val latestVersion: String) : CheckResult
}
