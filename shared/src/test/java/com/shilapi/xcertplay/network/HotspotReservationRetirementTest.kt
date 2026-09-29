package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class HotspotReservationRetirementTest {
    @Test fun oldReservationCannotCloseTheReplacementAfterRetirement() {
        for (sdk in listOf(26, 27)) {
            val retired = HotspotReservationRetirement<Reservation>()
            val first = Reservation()
            val replacement = Reservation()
            var active: Reservation? = first
            var closes = 0
            val close: (Reservation) -> Unit = {
                closes++
                active = null // Models WifiManager closing whichever reservation is current.
            }

            retired.close(first, sdk, close)
            active = replacement
            retired.close(first, sdk, close)

            assertSame(replacement, active)
            assertEquals(1, closes)
            retired.close(replacement, sdk, close)
            assertEquals(null, active)
            assertEquals(2, closes)
        }
    }

    @Test fun distinctReservationsAreRetiredByIdentityEvenIfEqual() {
        val retired = HotspotReservationRetirement<Reservation>()
        val first = Reservation()
        val second = Reservation()
        val closed = mutableListOf<Reservation>()

        retired.close(first, 26) { closed += it }
        retired.close(second, 26) { closed += it }
        retired.close(first, 26) { closed += it }

        assertEquals(2, closed.size)
        assertSame(first, closed[0])
        assertSame(second, closed[1])
    }

    @Test fun failedCloseStillRetiresTheOldReservation() {
        val retired = HotspotReservationRetirement<Reservation>()
        val reservation = Reservation()
        var closes = 0
        assertThrows(IOException::class.java) {
            retired.close(reservation, 27) {
                closes++
                throw IOException("Framework failure after stopping the old hotspot")
            }
        }

        retired.close(reservation, 27) { closes++ }

        assertEquals(1, closes)
    }

    @Test fun otherAndroidVersionsKeepTheirExistingCloseBehavior() {
        for (sdk in listOf(25, 28, 29, 35, 37)) {
            val retired = HotspotReservationRetirement<Reservation>()
            val reservation = Reservation()
            var closes = 0

            repeat(2) { retired.close(reservation, sdk) { closes++ } }

            assertEquals(2, closes)
        }
    }

    // Equal values must not merge independent framework objects.
    private class Reservation {
        override fun equals(other: Any?): Boolean = other is Reservation
        override fun hashCode(): Int = 0
    }
}
