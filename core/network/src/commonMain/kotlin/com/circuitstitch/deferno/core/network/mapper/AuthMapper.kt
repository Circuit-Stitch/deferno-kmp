package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.dto.AuthenticatedUserDto

/**
 * The DTO→domain `User` mapping for `GET /auth/me` (#20) — the "condense at the edge" boundary of
 * ADR-0011. The faithful wire shape ([AuthenticatedUserDto], snake_case keys, string ids) stays
 * quarantined in `core:network`; everything above the network boundary sees only the clean
 * `core:model` [User]. The string `id`/`personal_org_id` become the typed [UserId]/[OrgId] identity
 * keys here (and a blank one would fail their `require`, surfacing a malformed identity at the seam).
 */
fun AuthenticatedUserDto.toDomain(): User = User(
    id = UserId(id),
    username = username,
    displayName = displayName,
    role = role,
    personalOrgId = OrgId(personalOrgId),
    orgSlug = orgSlug,
    isAdmin = isAdmin,
    consoleUrl = consoleUrl,
)
