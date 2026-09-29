package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoInputBufferPolicyTest {
    @Test fun android8UsesPlatformDefaultWhileNewerAndroidKeepsExistingRequest() {
        for (sdk in listOf(26, 27)) assertNull(VideoInputBufferPolicy(sdk).requestedSizeBytes)
        for (sdk in listOf(28, 29, 36)) {
            assertEquals(VideoInputBufferPolicy.MAX_SIZE_BYTES, VideoInputBufferPolicy(sdk).requestedSizeBytes)
        }
    }

    @Test fun fittingAccessUnitsKeepPlatformDefault() {
        val policy = VideoInputBufferPolicy(26)
        assertFalse(policy.growFor(512, 1024))
        assertFalse(policy.growFor(1024, 1024))
        assertNull(policy.requestedSizeBytes)
    }

    @Test fun growthAccommodatesTheCompleteAccessUnitAndRetainsHeadroom() {
        val policy = VideoInputBufferPolicy(27)
        assertTrue(policy.growFor(3000, 1024))
        assertEquals(3000, policy.requestedSizeBytes)
        assertTrue(policy.growFor(3001, 3000))
        assertEquals(6000, policy.requestedSizeBytes)
        assertFalse(policy.growFor(3001, 6000))
        assertEquals(6000, policy.requestedSizeBytes)
    }

    @Test fun ignoredRequestsGrowOnlyUpToTheQueueBudget() {
        val policy = VideoInputBufferPolicy(26)
        val maximum = VideoInputBufferPolicy.MAX_SIZE_BYTES
        assertTrue(policy.growFor(maximum / 2, 1024))
        assertTrue(policy.growFor(maximum / 2, 1024))
        assertEquals(maximum, policy.requestedSizeBytes)
        assertFalse(policy.growFor(maximum / 2, 1024))
        assertEquals(maximum, policy.requestedSizeBytes)
        assertFalse(VideoInputBufferPolicy(28).growFor(maximum / 2, 1024))
    }

    @Test fun budgetBoundaryIsAcceptedButOversizedOrOverflowingSizesAreRejected() {
        val policy = VideoInputBufferPolicy(26)
        val maximum = VideoInputBufferPolicy.MAX_SIZE_BYTES
        assertFalse(policy.growFor(maximum + 1, 1024))
        assertFalse(policy.growFor(Int.MAX_VALUE, 1024))
        assertNull(policy.requestedSizeBytes)
        assertTrue(policy.growFor(maximum, maximum - 1))
        assertEquals(maximum, policy.requestedSizeBytes)
        assertFalse(policy.growFor(Int.MAX_VALUE, maximum))
        assertEquals(maximum, policy.requestedSizeBytes)
    }
}
