package com.circuitstitch.deferno.core.data.attachment

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A narrow read seam over on-device attachment storage for the Settings > Storage usage read-out (#211),
 * so the Settings slice can observe device storage without depending on the full [LocalAttachmentRepository]
 * (AccountScope). The shell backs it with the per-Account repository (`localAttachmentRepository`); Settings
 * tests and hosts that don't wire it use [Inert]. Read-only and offline-first (ADR-0001): observe-via-Flow.
 */
fun interface OnDeviceStorageUsage {
    /** On-device brain-dump recordings, largest first; empty when none. */
    fun brainDumpRecordings(): Flow<List<LocalAttachment>>

    companion object {
        /** No on-device usage to report — the default for Settings tests and unwired hosts. */
        val Inert: OnDeviceStorageUsage = OnDeviceStorageUsage { flowOf(emptyList<LocalAttachment>()) }
    }
}
