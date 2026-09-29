package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer

/** Android 8 rejects larger async requests and truncates larger synchronous USB transfers. */
internal object UsbTransferLimits {
    private const val ANDROID_P = 28
    private const val LEGACY_TRANSFER_BYTES = 16 * 1024

    fun prepareRead(buffer: ByteBuffer, sdkInt: Int) {
        buffer.clear()
        if (sdkInt < ANDROID_P) buffer.limit(minOf(buffer.capacity(), LEGACY_TRANSFER_BYTES))
    }

    fun payloadSize(preferredBytes: Int, headerBytes: Int, sdkInt: Int): Int {
        require(preferredBytes > 0)
        require(headerBytes in 0 until LEGACY_TRANSFER_BYTES)
        return if (sdkInt < ANDROID_P) {
            minOf(preferredBytes, LEGACY_TRANSFER_BYTES - headerBytes)
        } else {
            preferredBytes
        }
    }
}
