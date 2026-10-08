package com.ped.mobile.inspection

import com.ped.mobile.data.AppScreen
import com.ped.mobile.data.CaptureKind

internal fun InspectionAccount?.canCapture(kind: CaptureKind) = this?.can(kind.name.lowercase() + ".capture") == true
internal fun InspectionAccount?.canCaptureEvidence() = CaptureKind.entries.any { canCapture(it) }
internal fun InspectionAccount?.canViewTally() = this?.can("inspections.view") == true
internal fun InspectionAccount?.homeScreen() = when {
    canCaptureEvidence() -> AppScreen.CAPTURE
    canViewTally() -> AppScreen.INSPECTION
    else -> AppScreen.ACCOUNT
}
internal fun InspectionAccount?.allowsScreen(screen: AppScreen) = when (screen) {
    AppScreen.CAPTURE -> canCaptureEvidence()
    AppScreen.INSPECTION -> canViewTally()
    else -> true
}
