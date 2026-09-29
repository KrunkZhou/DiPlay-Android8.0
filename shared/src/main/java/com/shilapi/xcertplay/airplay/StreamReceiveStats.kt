package com.shilapi.xcertplay.airplay

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
internal class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var maxProcessNs = 0L
    private var totalReadNs = 0L
    private var totalProcessNs = 0L
    private var fastReads = 0
    private var slowReads = 0
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var lateOrDuplicate = 0

    fun reading() { readStart = nowNs() }

    fun received(size: Int, sequence: Int? = null) {
        processingStart = nowNs()
        val readNs = processingStart - readStart
        maxReadNs = maxOf(maxReadNs, readNs)
        totalReadNs += readNs
        if (readNs < 1_000_000L) fastReads++
        if (readNs > 25_000_000L) slowReads++
        packets++
        bytes += size
        if (sequence != null) {
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                nextSequence = (sequence + 1) and 0xffff
            } else lateOrDuplicate++
        }
    }

    fun processed() {
        val processNs = nowNs() - processingStart
        maxProcessNs = maxOf(maxProcessNs, processNs)
        totalProcessNs += processNs
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        runCatching { report("Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
            "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
            "lateOrDuplicate=$lateOrDuplicate ended=$ended " +
            "readAvgUs=${totalReadNs / maxOf(1, packets) / 1000} " +
            "processAvgUs=${totalProcessNs / maxOf(1, packets) / 1000} " +
            "readUnder1Ms=$fastReads readOver25Ms=$slowReads") }
        windowStart = now
        packets = 0
        bytes = 0
        maxReadNs = 0
        maxProcessNs = 0
        totalReadNs = 0
        totalProcessNs = 0
        fastReads = 0
        slowReads = 0
        forwardGapPackets = 0
        lateOrDuplicate = 0
    }
}
