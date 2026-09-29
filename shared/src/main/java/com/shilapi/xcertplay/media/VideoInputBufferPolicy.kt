package com.shilapi.xcertplay.media

/** Keeps Android 8 vendor decoders on their default allocation until a full frame needs more. */
internal class VideoInputBufferPolicy(sdkInt: Int) {
    var requestedSizeBytes: Int? = if (sdkInt >= 28) MAX_SIZE_BYTES else null
        private set

    /** Returns true only when the next decoder configuration should request a larger buffer. */
    fun growFor(accessUnitBytes: Int, actualCapacityBytes: Int): Boolean {
        if (actualCapacityBytes < 0 || accessUnitBytes <= actualCapacityBytes ||
            accessUnitBytes !in 1..MAX_SIZE_BYTES
        ) return false

        val previous = requestedSizeBytes ?: 0
        val baseline = maxOf(previous, actualCapacityBytes)
        val next = maxOf(accessUnitBytes.toLong(), baseline.toLong() * 2)
            .coerceAtMost(MAX_SIZE_BYTES.toLong()).toInt()
        if (next <= previous) return false
        requestedSizeBytes = next
        return true
    }

    companion object {
        // Matches the video queue budget; never request a buffer smaller than a complete access unit.
        const val MAX_SIZE_BYTES = 8 * 1024 * 1024
    }
}
