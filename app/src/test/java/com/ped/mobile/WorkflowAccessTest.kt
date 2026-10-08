package com.ped.mobile

import com.ped.mobile.inspection.*
import com.ped.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowAccessTest {
    @Test fun captureClerkOnlyGetsEvidence() {
        val user = InspectionAccount(1, "capture@test.local", setOf("ped.capture", "offdock.capture", "container.capture", "scrap.capture"))
        assertEquals(AppScreen.CAPTURE, user.homeScreen())
        assertTrue(CaptureKind.entries.all { user.canCapture(it) })
        assertFalse(user.allowsScreen(AppScreen.INSPECTION))
    }
    @Test fun tallyClerkNeverOpensEvidenceEvenWhenRestored() {
        val user = InspectionAccount(2, "tally@test.local", setOf("inspections.view", "inspections.create"))
        assertEquals(AppScreen.INSPECTION, user.homeScreen())
        assertFalse(user.allowsScreen(AppScreen.CAPTURE))
        assertFalse(user.canCaptureEvidence())
        assertTrue(user.allowsScreen(AppScreen.SYNC))
    }
    @Test fun missingOrRevokedPermissionsFailClosed() {
        for (user in listOf(null, InspectionAccount(1,"legacy@test.local"), InspectionAccount(1,"revoked@test.local", emptySet()))) {
            assertFalse(user.canCaptureEvidence())
            assertFalse(user.canViewTally())
            assertEquals(AppScreen.ACCOUNT, user.homeScreen())
        }
    }
}
