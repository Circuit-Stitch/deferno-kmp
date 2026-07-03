package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.MfaStatus
import com.circuitstitch.deferno.core.model.TotpEnrollment
import com.circuitstitch.deferno.core.network.dto.ApiTokenViewDto
import com.circuitstitch.deferno.core.network.dto.MfaEnrollStartDto
import com.circuitstitch.deferno.core.network.dto.MfaStatusDto
import kotlin.time.Instant

/**
 * The DTO→domain mapping for the account-security surface (CONTRACT-NOTES → "Security & 2FA") —
 * the ADR-0011 "condense at the edge" boundary: the snake_case wire shapes stay quarantined in
 * `core:network`; everything above sees the clean `core:model` types. ISO-8601 timestamps become
 * typed [Instant]s here (a malformed one fails the parse at the seam, not in a View).
 */

fun MfaStatusDto.toDomain(): MfaStatus = MfaStatus(
    totpEnabled = mfaEnabled,
    emailBackup = emailBackup,
)

fun MfaEnrollStartDto.toDomain(): TotpEnrollment = TotpEnrollment(
    secret = secret,
    uri = uri,
)

fun ApiTokenViewDto.toDomain(): ConnectedDevice = ConnectedDevice(
    id = id,
    name = name,
    createdAt = Instant.parse(createdAt),
    lastUsedAt = lastUsedAt?.let(Instant::parse),
)
