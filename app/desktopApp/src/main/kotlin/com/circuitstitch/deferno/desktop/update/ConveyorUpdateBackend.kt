package com.circuitstitch.deferno.desktop.update

import dev.hydraulic.conveyor.control.SoftwareUpdateController

/**
 * Production [UpdateBackend] over Conveyor's [SoftwareUpdateController] (#103, ADR-0021). The controller
 * is a singleton that only exists when the app runs from a Conveyor-built package;
 * [SoftwareUpdateController.getInstance] returns `null` otherwise (dev `./gradlew run`), which this maps
 * to [UpdateAvailability.NOT_PACKAGED] so the UI degrades to "install the packaged app to get updates".
 *
 * [fallbackVersion] (the generated `DesktopBuildConfig.APP_VERSION`) is shown when unpackaged; in a real
 * package the controller reports the authoritative `app.version`, which equals the same source of truth.
 */
internal class ConveyorUpdateBackend(
    private val controller: SoftwareUpdateController?,
    private val fallbackVersion: String,
) : UpdateBackend {

    override val currentVersion: String
        get() = controller?.currentVersion?.version ?: fallbackVersion

    override fun availability(): UpdateAvailability {
        val controller = controller ?: return UpdateAvailability.NOT_PACKAGED
        return when (controller.canTriggerUpdateCheckUI()) {
            SoftwareUpdateController.Availability.AVAILABLE -> UpdateAvailability.AVAILABLE
            // Linux: the control API is unimplemented; the package manager owns updates (ADR-0021, #105).
            SoftwareUpdateController.Availability.UNIMPLEMENTED -> UpdateAvailability.PACKAGE_MANAGER
            else -> UpdateAvailability.OTHER
        }
    }

    override suspend fun checkForUpdate(): CheckResult {
        val controller = controller ?: error("checkForUpdate() requires a Conveyor package")
        val running = controller.currentVersion
        // Blocking HTTP fetch against the update site; UpdateManager calls this on Dispatchers.IO.
        val latest = controller.currentVersionFromRepository
        return if (latest > running) {
            CheckResult.Available(latest.version)
        } else {
            CheckResult.UpToDate(latest.version)
        }
    }

    override fun triggerUpdate() {
        controller?.triggerUpdateCheckUI()
    }

    companion object {
        /** Bind to the process's Conveyor controller (`null` when the app is not Conveyor-packaged). */
        fun create(fallbackVersion: String): ConveyorUpdateBackend =
            ConveyorUpdateBackend(SoftwareUpdateController.getInstance(), fallbackVersion)
    }
}
