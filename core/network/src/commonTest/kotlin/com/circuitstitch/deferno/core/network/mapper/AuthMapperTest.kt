package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.dto.AuthenticatedUserDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The `AuthenticatedUserDto` → domain `User` mapping (#20). Mirrors the captured `auth-me.json`
 * field set (the contract-fixture harness asserts the DTO parse separately, #19): the snake_case
 * wire keys become clean domain fields, the string `id`/`personal_org_id` become the typed
 * [UserId]/[OrgId] identity keys, and the optional `console_url` / defaulted `is_admin` are preserved.
 */
class AuthMapperTest {

    private fun dto(
        id: String = "1d35f62e-eed9-44de-96e8-e61a307af83f",
        personalOrgId: String = "ebca93e5-d663-4624-9fe9-c5361b5b4390",
        isAdmin: Boolean = false,
        consoleUrl: String? = "https://auth2.defernowork.com/ui/console",
    ) = AuthenticatedUserDto(
        id = id,
        username = "sampleuser",
        displayName = "Sample User",
        role = "admin",
        personalOrgId = personalOrgId,
        orgSlug = "u-e4h2qk",
        isAdmin = isAdmin,
        consoleUrl = consoleUrl,
    )

    @Test
    fun mapsEveryFieldOntoTheDomainUser() {
        val user = dto().toDomain()

        assertEquals(UserId("1d35f62e-eed9-44de-96e8-e61a307af83f"), user.id)
        assertEquals("sampleuser", user.username)
        assertEquals("Sample User", user.displayName)
        assertEquals("admin", user.role)
        // personal_org_id is the Org isolation key (ADR-0002) → typed OrgId.
        assertEquals(OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"), user.personalOrgId)
        assertEquals("u-e4h2qk", user.orgSlug)
        assertEquals(false, user.isAdmin)
        assertEquals("https://auth2.defernowork.com/ui/console", user.consoleUrl)
    }

    @Test
    fun absentConsoleUrlMapsToNull() {
        assertNull(dto(consoleUrl = null).toDomain().consoleUrl)
    }

    @Test
    fun aBlankIdentityIdFailsAtTheSeam() {
        // A malformed identity (blank id / personal_org_id) must not silently become a key — the
        // value-class `require` rejects it at the boundary rather than letting it flow upward.
        assertFailsWith<IllegalArgumentException> { dto(id = "").toDomain() }
        assertFailsWith<IllegalArgumentException> { dto(personalOrgId = "").toDomain() }
    }
}
