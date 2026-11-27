package org.sih.itantra.network.monitor

import java.util.concurrent.ConcurrentHashMap

/**
 * NTP-style clock-offset estimation between this phone and each peer, from ping/ACK exchanges.
 *
 * A ping leaves at local time t0, the peer stamps its own clock T in the ACK, and the ACK arrives at
 * t1. Assuming a symmetric path, the peer read its clock at local time t0 + rtt/2, so
 * offset = T - (t0 + rtt/2) (peer clock minus local clock). The error is at most rtt/2, so the
 * sample with the lowest round trip among the recent ones is the most trustworthy.
 */
class PeerClockSync(private val keep: Int = 12, private val maxRttMs: Long = 5_000) {

    private val samples = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Long>>>()

    /** Record one exchange: ping sent at [sentAt], ACK received at [receivedAt], peer clock [peerClock]. */
    fun record(peer: String, sentAt: Long, receivedAt: Long, peerClock: Long) {
        val rtt = receivedAt - sentAt
        if (rtt < 0 || rtt > maxRttMs) return
        val q = samples.getOrPut(peer.trim()) { ArrayDeque() }
        synchronized(q) {
            q.addLast(rtt to (peerClock - (sentAt + rtt / 2)))
            while (q.size > keep) q.removeFirst()
        }
    }

    /** Peer clock minus local clock in ms, or null if this peer hasn't answered a ping yet. */
    fun offsetMs(peer: String): Long? = samples[peer.trim()]?.let { q ->
        synchronized(q) { q.minByOrNull { it.first }?.second }
    }

    /** Converts a timestamp read from [peer]'s clock to this phone's clock (unchanged if unknown). */
    fun toLocal(peer: String, peerTime: Long): Long = peerTime - (offsetMs(peer) ?: 0L)
}
