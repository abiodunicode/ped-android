package com.ped.mobile

import com.ped.mobile.scanning.*
import org.junit.Assert.*
import org.junit.Test

class CargoMatchingTest {
    private val vin = "SALME15406A217483"
    private fun reference(vararg ids: String) = object : CargoLookup {
        override fun exact(candidates: List<String>) = candidates.filter { it in ids }.toSet()
        override fun similar(candidate: String) = ids.filter {
            it.length == candidate.length && cargoSignature(it) == cargoSignature(candidate) &&
                it.indices.count { i -> it[i] != candidate[i] } in 1..2
        }
    }

    @Test fun referenceSelectsChassisAmongUnlabelledShipmentNumbers() {
        val result = matchChassis("3906208000111416076\n$vin\n20261005", reference(vin))!!
        assertEquals(vin, result.number)
        assertTrue(result.exact)
    }

    @Test fun explicitChassisLabelOutranksKnownBookingNumber() {
        val result = matchChassis("BOOKING: $vin\nCHASSIS: NZE1411234567", reference(vin))!!
        assertEquals("NZE1411234567", result.number)
        assertFalse(result.exact)
    }

    @Test fun bookingOnSameLineDoesNotOverrideLabelledChassis() {
        assertEquals("NZE1411234567", matchChassis("CHASSIS: NZE1411234567 BOOKING: $vin", reference(vin))!!.number)
    }

    @Test fun confusedCharacterIsOnlySuggestedNotSilentlyReplaced() {
        val read = vin.replace('0', 'O')
        val result = matchChassis("VIN: $read", reference(vin))!!
        assertEquals(read, result.number)
        assertFalse(result.exact)
        assertEquals(listOf(vin), result.suggestions)
    }

    @Test fun multipleExactNumbersRemainAvailableForReview() {
        val other = "JTHBA30G855095744"
        val result = matchChassis("$vin\n$other", reference(vin, other))!!
        assertEquals(vin, result.number)
        assertEquals(listOf(other), result.suggestions)
    }

    @Test fun spacedAndNumericCargoIdsAreRecognised() {
        assertEquals(vin, matchChassis("VIN / SERIAL\nSAL ME15406 A217483", reference(vin))!!.number)
        assertEquals("12345678", matchChassis("CHASSIS: 12345678", reference("12345678"))!!.number)
    }

    @Test fun shortNewChassisWithExplicitLabelRemainsAllowed() {
        assertEquals("12345", matchChassis("CHASSIS: 12345", reference(vin))!!.number)
    }

    @Test fun missingReferenceStillAllowsNewChassis() {
        val result = matchChassis("VIN: $vin", null)!!
        assertEquals(vin, result.number)
        assertFalse(result.referenceAvailable)
    }

    @Test fun emptyPhotoDoesNotInventNumberAndWorkIsBounded() {
        assertNull(matchChassis("TOYOTA MOTOR CORPORATION", reference(vin)))
        assertTrue(chassisCandidates(("AB1234 ".repeat(100) + "\n").repeat(1000)).size <= 250)
    }
}
