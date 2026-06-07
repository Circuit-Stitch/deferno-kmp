package com.circuitstitch.deferno.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The domain [User] is the clean, condensed model of `GET /auth/me` (the network boundary maps the
 * wire DTO onto it — ADR-0011). This pins its shape: identity-critical fields are typed value classes
 * ([UserId]/[OrgId]) and the optional [User.consoleUrl] is genuinely nullable. JVM-fast path (ADR-0006).
 */
class UserTest {

    @Test
    fun carriesTheAuthenticatedIdentityAndOrgIsolationKey() {
        val user = User(
            id = UserId("1d35f62e-eed9-44de-96e8-e61a307af83f"),
            username = "sampleuser",
            displayName = "Sample User",
            role = "admin",
            personalOrgId = OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"),
            orgSlug = "u-e4h2qk",
            isAdmin = false,
            consoleUrl = "https://auth2.defernowork.com/ui/console",
        )

        assertEquals("sampleuser", user.username)
        assertEquals("Sample User", user.displayName)
        // personalOrgId is the Org isolation key (ADR-0002) — typed distinctly from the orgSlug.
        assertEquals(OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"), user.personalOrgId)
        assertEquals("u-e4h2qk", user.orgSlug)
    }

    @Test
    fun consoleUrlIsOptional() {
        val user = User(
            id = UserId("1d35f62e-eed9-44de-96e8-e61a307af83f"),
            username = "sampleuser",
            displayName = "Sample User",
            role = "member",
            personalOrgId = OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"),
            orgSlug = "u-e4h2qk",
            isAdmin = false,
            consoleUrl = null,
        )

        assertNull(user.consoleUrl)
    }
}
