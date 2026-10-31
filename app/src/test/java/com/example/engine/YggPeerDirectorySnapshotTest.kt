// File: app/src/test/java/com/example/engine/YggPeerDirectorySnapshotTest.kt
package com.example.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scanner reads one HTTPS document and then ranks what it says. Both halves of that were
 * measured against the real publisher: the body is 181 KiB uncompressed against 15 KiB gzipped,
 * and it is regenerated hourly, so every `last_seen` in a freshly read document already sits up
 * to an hour behind the reader's clock. These tests pin those two facts, because ignoring either
 * one turns a directory of two hundred live nodes into "0 fresh nodes".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggPeerDirectorySnapshotTest {

    private val nowMs = 1_792_998_100_000L

    private fun doc(updatedSec: Long, seenOffsetsSec: List<Long>): String {
        val entries = seenOffsetsSec.mapIndexed { index, offset ->
            """"tls://10.0.0.$index:443": {
              "up": true, "key": "${(index + 1).toString().repeat(64).take(64)}",
              "updated": $updatedSec, "last_seen": ${updatedSec - offset},
              "proto_minor": 5, "response_ms": ${40 + index}
            }"""
        }.joinToString(",\n        ")
        return """
        {
          "europe/germany.md": {
            $entries
          }
        }
        """.trimIndent()
    }

    @Test
    fun `keeps a complete snapshot whose publisher stamp lags the reader's clock`() {
        // The regression this pins: a document read 40 minutes after the publisher wrote it is
        // the directory working exactly as designed, and comparing every sighting against
        // System.currentTimeMillis() rejected all of it.
        val stampSec = nowMs / 1000L - 2_400L
        val snapshot = YggPeerScannerCatalog.parseDirectorySnapshot(
            doc(stampSec, listOf(60L, 300L, 700L)),
            nowMs
        )

        assertEquals(3, snapshot.reportedTotal)
        assertEquals(3, snapshot.usableCount)
        assertEquals(0, snapshot.skippedStale)
        assertEquals(stampSec * 1000L, snapshot.snapshotUpdatedAtMs)
        assertEquals(2_400_000L, snapshot.snapshotAgeMs(nowMs))
    }

    @Test
    fun `drops a sighting that is old relative to the snapshot and counts it`() {
        val stampSec = nowMs / 1000L - 120L
        val snapshot = YggPeerScannerCatalog.parseDirectorySnapshot(
            doc(stampSec, listOf(30L, YggPeerScannerCatalog.freshWindowMs / 1000L + 60L)),
            nowMs
        )

        assertEquals(2, snapshot.reportedTotal)
        assertEquals(1, snapshot.usableCount)
        assertEquals(1, snapshot.skippedStale)
        assertEquals("tls://10.0.0.0:443", snapshot.peers.first().endpointUri)
    }

    @Test
    fun `says so when the document carries no publisher stamp`() {
        val untyped = """
        { "europe/germany.md": {
            "tls://10.0.0.1:443": { "up": true, "proto_minor": 5, "response_ms": 10 }
        } }
        """.trimIndent()
        val snapshot = YggPeerScannerCatalog.parseDirectorySnapshot(untyped, nowMs)

        assertEquals(0L, snapshot.snapshotUpdatedAtMs)
        assertEquals(-1L, snapshot.snapshotAgeMs(nowMs))
        // Without a stamp the reader's clock is all there is, and a node the directory never
        // timestamped is not evidence that it is up.
        assertEquals(0, snapshot.usableCount)
        assertEquals(1, snapshot.skippedStale)
    }

    @Test
    fun `keeps a directory that stopped being regenerated out of the trusted age`() {
        val stampSec = nowMs / 1000L - 5 * 3_600L
        val snapshot = YggPeerScannerCatalog.parseDirectorySnapshot(doc(stampSec, listOf(30L)), nowMs)

        assertTrue(
            "a five-hour-old snapshot must read as stale",
            snapshot.snapshotAgeMs(nowMs) > YggPeerScannerCatalog.snapshotTrustedMs
        )
    }

    @Test
    fun `stores a snapshot on disk and reloads it labelled as cached`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        clearCacheFile(context)
        val stampSec = nowMs / 1000L - 600L
        val live = YggPeerScannerCatalog.parseDirectorySnapshot(doc(stampSec, listOf(30L, 90L)), nowMs)

        assertTrue(YggPeerDirectoryCache.save(context, live))
        val reloaded = YggPeerDirectoryCache.load(context)
        requireNotNull(reloaded)

        assertTrue(reloaded.fromCache)
        assertEquals(2, reloaded.peers.size)
        assertEquals(live.snapshotUpdatedAtMs, reloaded.snapshotUpdatedAtMs)
        assertEquals(live.peers.map { it.endpointUri }, reloaded.peers.map { it.endpointUri })
        assertEquals(live.peers.map { it.lastSeenAtMs }, reloaded.peers.map { it.lastSeenAtMs })
        assertTrue(reloaded.peers.all { it.whitelistCompatible })
        assertEquals("10.0.0.0", reloaded.peers.first().sniDomain)

        // No probe result survives the round trip: whether an endpoint answers from here is a
        // fact about the current link, and a cached "yes" would be a claim nothing measured.
        assertTrue(reloaded.peers.all { it.latencyMs == -1 })
    }

    @Test
    fun `refuses to overwrite the last good snapshot with a failed read`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        clearCacheFile(context)
        val live = YggPeerScannerCatalog.parseDirectorySnapshot(
            doc(nowMs / 1000L - 60L, listOf(30L, 70L)),
            nowMs
        )
        assertTrue(YggPeerDirectoryCache.save(context, live))

        assertFalse(YggPeerDirectoryCache.save(context, live.copy(errorDetail = "соединение сброшено")))
        assertFalse(YggPeerDirectoryCache.save(context, live.copy(peers = emptyList())))
        assertFalse(YggPeerDirectoryCache.save(context, live.copy(fromCache = true)))

        val reloaded = YggPeerDirectoryCache.load(context)
        requireNotNull(reloaded)
        assertEquals(2, reloaded.peers.size)
    }

    @Test
    fun `has nothing to offer before the first successful read`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        clearCacheFile(context)

        assertNull(YggPeerDirectoryCache.load(context))
        assertNull(YggPeerDirectoryCache.parse("""{"peers": []}"""))
        assertNull(YggPeerDirectoryCache.parse("""{"snapshotUpdatedAtMs": 1}"""))
    }

    /** Robolectric reuses one filesDir across the class, so each disk test starts from nothing. */
    private fun clearCacheFile(context: android.content.Context) {
        java.io.File(context.filesDir, "ygg_peer_directory_snapshot.json").delete()
    }
}
