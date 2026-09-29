package com.shilapi.xcertplay.network

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Android 8 reservation finalizers call close again, even after an explicit close. Because the
 * framework stops the WifiManager's current hotspot, an old reservation can stop a newer one.
 * Keep retired instances strongly reachable for the process lifetime; do not evict them or retain
 * cleanup callbacks. Newer Android versions keep their existing release behavior.
 */
internal class HotspotReservationRetirement<T : Any> {
    private val retired = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

    fun close(reservation: T, sdkInt: Int, closeReservation: (T) -> Unit) {
        if (sdkInt in 26..27) {
            synchronized(retired) {
                // Retain before closing, including when the framework close throws. A second
                // attempt could otherwise stop a replacement hotspot after a partial close.
                if (!retired.add(reservation)) return
            }
        }
        closeReservation(reservation)
    }
}
