package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class UsbTransferLimitsTest {
    @Test fun android8ReadRequestsStayWithinThePlatformLimitAcrossReuses() {
        for (sdk in listOf(26, 27)) {
            for (capacity in listOf(32 * 1024, 64 * 1024)) {
                val buffer = ByteBuffer.allocateDirect(capacity)
                repeat(3) {
                    UsbTransferLimits.prepareRead(buffer, sdk)
                    assertEquals(capacity, buffer.capacity())
                    assertEquals(16 * 1024, buffer.remaining())
                    // A completed read and consumer flip must not change the next request limit.
                    buffer.position(buffer.limit())
                    buffer.flip()
                    buffer.position(buffer.limit())
                }
            }
        }
    }

    @Test fun newerAndroidPreservesTheLargerReadWindow() {
        for (sdk in listOf(28, 37)) {
            val buffer = ByteBuffer.allocateDirect(64 * 1024)
            buffer.limit(16 * 1024)
            UsbTransferLimits.prepareRead(buffer, sdk)
            assertEquals(64 * 1024, buffer.remaining())
        }
        val smallBuffer = ByteBuffer.allocateDirect(512)
        UsbTransferLimits.prepareRead(smallBuffer, 26)
        assertEquals(512, smallBuffer.remaining())
    }

    @Test fun completeLegacyMuxFramesFitWithoutLosingPayloadAtChunkBoundaries() {
        val source = ByteArray(64 * 1024 + 7) { (it * 31).toByte() }
        val headers = 16 + 20
        for (sdk in listOf(26, 27)) {
            val payloadLimit = UsbTransferLimits.payloadSize(16 * 1024, headers, sdk)
            val recovered = ByteArrayOutputStream()
            var offset = 0
            while (offset < source.size) {
                val length = minOf(payloadLimit, source.size - offset)
                val frame = ByteArray(headers + length)
                source.copyInto(frame, headers, offset, offset + length)
                assertTrue(frame.size <= 16 * 1024)
                recovered.write(frame, headers, length)
                offset += length
            }
            assertArrayEquals(source, recovered.toByteArray())
        }
    }

    @Test fun newAndroidRetainsItsFullMuxPayload() {
        assertEquals(16 * 1024, UsbTransferLimits.payloadSize(16 * 1024, 36, 28))
        assertEquals(512, UsbTransferLimits.payloadSize(512, 36, 26))
    }
}
