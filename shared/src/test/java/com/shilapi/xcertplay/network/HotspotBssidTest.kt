package com.shilapi.xcertplay.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HotspotBssidTest {
    @Test fun preservesEveryOctetWithoutAndroidMacAddress() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x1a, 0xb0.toByte(), 0x0c, 0xff.toByte(), 0x00),
            parseHotspotBssid("02:1a:B0:c:fF:0"),
        )
        assertArrayEquals(ByteArray(6), parseHotspotBssid("00:00:00:00:00:00"))
    }

    @Test fun malformedValuesCannotSelectAnUnrelatedInterface() {
        for (value in listOf(
            "", "02:00:00:00:00", "02:00:00:00:00:00:00", "02:00:00:00:00:100",
            "02:00:00:00:00:gg", "02:00:00:00:00:", "02:00:00:00:00:-1",
            "02:00:00:00:00:+1", " 02:00:00:00:00:00", "02-00-00-00-00-00",
        )) {
            try {
                parseHotspotBssid(value)
                fail("Accepted malformed BSSID: $value")
            } catch (error: IOException) {
                assertTrue(error.message!!.contains("invalid BSSID"))
            }
        }
    }
}
