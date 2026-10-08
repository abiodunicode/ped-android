package com.ped.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChassisExtractionTest {
    @Test fun labeledShippingDocumentSelectsVinInsteadOfBookingNumber() {
        val text = "BOOKING: ABC123456789\nVIN/SERIAL: 1HGCM82633A004352\nVESSEL: SILVER RAY"
        assertEquals("1HGCM82633A004352", extractVinSerialValue(text))
    }
    @Test fun labelOnSeparateLineAndSpacedVinAreSupported() {
        assertEquals("1HGCM82633A004352", extractVinSerialValue("VIN/SERIAL\n1HG CM826 33A004352"))
    }
    @Test fun bareVinAndShortChassisHaveFallback() {
        assertEquals("1HGCM82633A004352", extractChassisCandidate("1HGCM82633A004352"))
        assertEquals("NZE1411234567", extractChassisCandidate("NZE1411234567"))
    }
    @Test fun unreadablePhotoDoesNotInventANumber() {
        assertNull(extractVinSerialValue(""))
        assertNull(extractChassisCandidate("TOYOTA MOTOR CORPORATION"))
    }
}
