package com.example.viewmodel

import com.example.data.YggPeerEntity
import com.example.viewmodel.controllers.classifyWatchdogSpares
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autopilot is the only thing in this app that deletes peer rows on its own, so the two ways it
 * can be wrong about a spare are pinned here: dropping a node the live core still handshakes with,
 * and dropping the whole reserve because this process has not seen it answer yet.
 */
class WatchdogSpareDecisionTest {

    private fun peer(id: String, uri: String = "tcp://10.0.0.1:200") = YggPeerEntity(
        peerId = id,
        endpointUri = uri,
        transportType = "TCP_YGGDRASIL",
        sniDomain = "",
        regionLabel = "тест",
        latencyMs = 40,
        rxBytes = 0L,
        txBytes = 0L,
        isConnected = true,
        isWhitelistCompatible = false,
        ed25519Verified = false
    )

    private val managed = listOf(
        peer("peer_watchdog_1", "tcp://10.0.0.1:200"),
        peer("peer_watchdog_2", "tcp://10.0.0.2:201"),
        peer("peer_watchdog_3", "tcp://10.0.0.3:202"),
    )

    @Test
    fun `a peer the core answered is never dropped`() {
        val decision = classifyWatchdogSpares(
            managed = managed,
            answered = { it.endpointUri == "tcp://10.0.0.1:200" },
            sinceMs = mapOf("peer_watchdog_1" to 0L, "peer_watchdog_2" to 0L, "peer_watchdog_3" to 0L),
            nowMs = 900_000L,
            graceMs = 300_000L
        )
        assertEquals(listOf("peer_watchdog_2", "peer_watchdog_3"), decision.prunePeerIds)
        assertEquals(900_000L, decision.stampedSinceMs["peer_watchdog_1"])
    }

    @Test
    fun `a spare saved by an earlier process starts the window instead of ending it`() {
        // Cold start: the rows are in the database, the grace clock is not in this process.
        val decision = classifyWatchdogSpares(
            managed = managed,
            answered = { false },
            sinceMs = emptyMap(),
            nowMs = 30_000L,
            graceMs = 300_000L
        )
        assertTrue("restart must not empty the reserve", decision.prunePeerIds.isEmpty())
        assertEquals(3, decision.stampedSinceMs.size)
    }

    @Test
    fun `only the spare past its grace is dropped and the rest keep their countdown`() {
        val decision = classifyWatchdogSpares(
            managed = managed,
            answered = { false },
            sinceMs = mapOf(
                "peer_watchdog_1" to 1_000_000L,
                "peer_watchdog_3" to 100_000L
            ),
            nowMs = 1_200_000L,
            graceMs = 300_000L
        )
        assertEquals(listOf("peer_watchdog_3"), decision.prunePeerIds)
        assertEquals(1_000_000L, decision.stampedSinceMs["peer_watchdog_1"])
        assertEquals(1_200_000L, decision.stampedSinceMs["peer_watchdog_2"])
    }
}
