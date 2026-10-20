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

    @Test fun teamAdminPermissionsDoNotUnlockTheOtherWorkflow() {
        val pedAdmin = InspectionAccount(3, "ped-admin@test.local", setOf(
            "ped.capture", "offdock.capture", "container.capture", "scrap.capture",
            "records.correct_chassis", "records.assign_voyage", "duplicates.resolve",
            "voyages.manage", "catalogue.manage"
        ))
        val tallyAdmin = InspectionAccount(4, "tally-admin@test.local", setOf(
            "inspections.view", "inspections.create", "inspections.edit", "inspections.complete",
            "inspections.manage_all", "voyages.manage", "catalogue.manage"
        ))
        assertEquals(AppScreen.CAPTURE, pedAdmin.homeScreen())
        assertTrue(CaptureKind.entries.all { pedAdmin.canCapture(it) })
        assertFalse(pedAdmin.allowsScreen(AppScreen.INSPECTION))
        assertEquals(AppScreen.INSPECTION, tallyAdmin.homeScreen())
        assertFalse(tallyAdmin.allowsScreen(AppScreen.CAPTURE))
        assertFalse(tallyAdmin.canCaptureEvidence())
    }

    @Test fun generalAdminUsesExpandedApiPermissionsForBothWorkflows() {
        // Both APIs expand system.admin into the complete permission list.
        val general = InspectionAccount(5, "general@test.local", setOf(
            "system.admin", "ped.capture", "offdock.capture", "container.capture",
            "scrap.capture", "inspections.view", "inspections.create", "inspections.manage_all"
        ))
        assertTrue(general.allowsScreen(AppScreen.CAPTURE))
        assertTrue(general.allowsScreen(AppScreen.INSPECTION))
        assertTrue(CaptureKind.entries.all { general.canCapture(it) })
    }
}
