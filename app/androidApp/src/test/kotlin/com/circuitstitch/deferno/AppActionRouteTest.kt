package com.circuitstitch.deferno

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Google Assistant App Actions deep-link parse (ADR-0036, #248/#249): the Uri → [AppActionRoute]
 * half of MainActivity's routing, unit-tested without launching the activity (the signed-out / Active
 * Account behaviour lives in RootComponentTest; the live-Assistant binding is a manual check). Robolectric
 * supplies a real `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
class AppActionRouteTest {

    @Test
    fun planHost_mapsToOpenPlan() {
        assertEquals(AppActionRoute.OpenPlan, appActionRoute(Uri.parse("com.circuitstitch.deferno://plan")))
    }

    @Test
    fun createHostWithTitle_mapsToAddTask_withTheVerbatimSlot() {
        assertEquals(
            AppActionRoute.AddTask("buy milk"),
            appActionRoute(Uri.parse("com.circuitstitch.deferno://create?title=buy%20milk")),
        )
    }

    @Test
    fun createHostWithoutATitle_isNotAnAction() {
        // No slot → nothing to create (the RootComponent also guards a blank title, but we don't even route).
        assertNull(appActionRoute(Uri.parse("com.circuitstitch.deferno://create")))
    }

    @Test
    fun theOAuthRedirectHostIsNotAnAppAction() {
        // Same scheme, but `auth` is the OAuth redirect (handled by forwardAuthRedirect), not an App Action.
        assertNull(appActionRoute(Uri.parse("com.circuitstitch.deferno://auth?code=xyz")))
    }

    @Test
    fun aForeignSchemeIsIgnored() {
        assertNull(appActionRoute(Uri.parse("https://example.com/plan")))
        assertNull(appActionRoute(null))
    }
}
