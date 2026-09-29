package com.shilapi.xcertplay.network

import java.io.IOException

/** Parses the legacy WifiConfiguration value without the Android 9 MacAddress class. */
internal fun parseHotspotBssid(value: String): ByteArray {
    val octets = value.split(':')
    if (octets.size != 6 || octets.any { octet ->
            octet.length !in 1..2 || octet.any { it.digitToIntOrNull(16) == null }
        }) {
        throw IOException("LocalOnlyHotspot reported an invalid BSSID: $value")
    }
    return ByteArray(6) { octets[it].toInt(16).toByte() }
}
