// File: app/src/test/java/com/example/engine/YggPeerScannerFreshnessTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scanner decides which nodes deserve a socket from what the public directory reports
 * about them, so that decision is tested against the directory's real document shape
 * (up / last_seen / proto_minor / response_ms / key) instead of a invented one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggPeerScannerFreshnessTest {

    private val nowMs = 1_792_998_100_000L

    private fun directoryDoc(): String = """
    {
      "europe/germany.md": {
        "tls://128.140.56.86:443": {
          "up": true, "key": "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0",
          "imported": 1705748407, "updated": 1792998010, "last_seen": 1792998096,
          "proto_minor": 5, "response_ms": 41
        },
        "tcp://old.node.example:1514": {
          "up": true, "key": "1111111111111111111111111111111111111111111111111111111111111111",
          "last_seen": 1792998099, "proto_minor": 5, "response_ms": 1200
        },
        "tls://dead.node.example:9999": {
          "up": false, "key": "2222222222222222222222222222222222222222222222222222222222222222",
          "last_seen": 1792998098, "proto_minor": 5, "response_ms": 30
        },
        "tls://ancient.node.example:54321": {
          "up": true, "key": "3333333333333333333333333333333333333333333333333333333333333333",
          "last_seen": 1705748407, "proto_minor": 5, "response_ms": 90
        },
        "tls://legacy.node.example:443": {
          "up": true, "key": "4444444444444444444444444444444444444444444444444444444444444444",
          "last_seen": 1792998099, "proto_minor": 4, "response_ms": 15
        },
        "tcp://128.140.56.86:443": {
          "up": true, "key": "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0",
          "last_seen": 1792998096, "proto_minor": 5, "response_ms": 95
        }
      },
      "europe/russia.md": {
        "wss://donotclickthis.link:443/api/v2/socket": {
          "up": true, "last_seen": 1792998090, "proto_minor": 5, "response_ms": 210
        }
      }
    }
    """.trimIndent()

    @Test
    fun `drops the entries a scan would waste a socket on and counts them`() {
        val snapshot = YggPeerScannerCatalog.parseDirectorySnapshot(directoryDoc(), nowMs)

        assertEquals(7, snapshot.reportedTotal)
        assertEquals(4, snapshot.usableCount)
        assertEquals(1, snapshot.skippedDown)
        assertEquals(1, snapshot.skippedStale)
        assertEquals(1, snapshot.skippedOldProtocol)
        assertNullDetail(snapshot)

        val uris = snapshot.peers.map { it.endpointUri }
        assertFalse(uris.any { it.contains("dead.node.example") })
        assertFalse(uris.any { it.contains("ancient.node.example") })
        assertFalse(uris.any { it.contains("legacy.node.example") })
    }

    @Test
    fun `orders candidates freshest first and the directory rtt second`() {
        val snapshot = YggPeerScannerCatalog.parseDirectorySnapshot(directoryDoc(), nowMs)
        val seen = snapshot.peers.map { it.lastSeenAtMs }

        assertEquals(seen, seen.sortedDescending())
        assertEquals("tcp://old.node.example:1514", snapshot.peers.first().endpointUri)
        // The two 128.140.56.86 entries share one sighting, so the tie-break has to be the
        // directory's own measured response time, not the order the JSON happened to list them.
        assertEquals("tls://128.140.56.86:443", snapshot.peers[1].endpointUri)
        assertEquals(41, snapshot.peers[1].directoryRttMs)
        assertEquals("tcp://128.140.56.86:443", snapshot.peers[2].endpointUri)
        assertEquals(95, snapshot.peers[2].directoryRttMs)
    }

    @Test
    fun `reads the real fields instead of inventing them`() {
        val snapshot = YggPeerScannerCatalog.parseDirectorySnapshot(directoryDoc(), nowMs)
        val keyed = snapshot.peers.first { it.endpointUri == "tls://128.140.56.86:443" }

        assertEquals("key:0f1e2d3c4b5a…", keyed.ed25519PubKeyShort)
        assertTrue(keyed.whitelistCompatible)
        assertTrue(keyed.freshWithin(YggPeerScannerCatalog.freshWindowMs, nowMs))

        // No key published: the card has to say so rather than show a fabricated prefix.
        val keyless = snapshot.peers.first { it.endpointUri.startsWith("wss://") }
        assertEquals("ключ неизвестен", keyless.ed25519PubKeyShort)
        assertEquals(210, keyless.directoryRttMs)
        assertTrue("wss endpoint is whitelist-shaped", keyless.whitelistCompatible)
    }

    @Test
    fun `same endpoint under two schemes stays two candidates`() {
        val snapshot = YggPeerScannerCatalog.parseDirectorySnapshot(directoryDoc(), nowMs)
        val hosts = snapshot.peers.map { it.endpointUri }

        assertTrue(hosts.contains("tls://128.140.56.86:443"))
        assertTrue(hosts.contains("tcp://128.140.56.86:443"))
        assertEquals(hosts.distinct().size, hosts.size)
    }

    @Test
    fun `freshness text never claims a precision the data does not have`() {
        assertEquals("виден 0 с назад", YggPeerScannerCatalog.formatFreshness(nowMs, nowMs))
        assertEquals("виден 9 с назад", YggPeerScannerCatalog.formatFreshness(nowMs - 9_000L, nowMs))
        assertEquals("виден 4 мин назад", YggPeerScannerCatalog.formatFreshness(nowMs - 250_000L, nowMs))
        assertEquals("виден 2 ч назад", YggPeerScannerCatalog.formatFreshness(nowMs - 7_200_000L, nowMs))
        assertEquals("виден 3 сут назад", YggPeerScannerCatalog.formatFreshness(nowMs - 259_200_000L, nowMs))
        assertEquals("свежесть неизвестна", YggPeerScannerCatalog.formatFreshness(0L, nowMs))
    }

    private fun assertNullDetail(snapshot: DirectoryFetch) {
        assertTrue("parse failure reported: ${snapshot.errorDetail}", snapshot.errorDetail == null)
    }
}
