package org.sih.itantra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.sih.itantra.network.monitor.PeerClockSync

class PeerClockSyncTest {

    @Test
    fun symmetricPathRecoversTheOffset() {
        val sync = PeerClockSync()
        // Peer clock runs 7_000 ms ahead; 100 ms each way.
        val t0 = 1_000_000L
        sync.record("N_1", sentAt = t0, receivedAt = t0 + 200, peerClock = t0 + 100 + 7_000)
        assertEquals(7_000L, sync.offsetMs("N_1"))
        assertEquals(t0, sync.toLocal("N_1", t0 + 7_000))
    }

    @Test
    fun lowestRoundTripSampleWins() {
        val sync = PeerClockSync()
        val t0 = 5_000_000L
        // Slow, asymmetric exchange (skewed estimate) then a fast, accurate one
        sync.record("N_2", sentAt = t0, receivedAt = t0 + 1_000, peerClock = t0 + 900 - 3_000)
        sync.record("N_2", sentAt = t0 + 5_000, receivedAt = t0 + 5_020, peerClock = t0 + 5_010 - 3_000)
        assertEquals(-3_000L, sync.offsetMs("N_2"))
    }

    @Test
    fun unknownPeerAndBadSamplesAreIgnored() {
        val sync = PeerClockSync()
        assertNull(sync.offsetMs("N_3"))
        assertEquals(42L, sync.toLocal("N_3", 42L))
        sync.record("N_3", sentAt = 10, receivedAt = 5, peerClock = 99)       // negative rtt
        sync.record("N_3", sentAt = 0, receivedAt = 60_000, peerClock = 99)   // stale
        assertNull(sync.offsetMs("N_3"))
    }
}
