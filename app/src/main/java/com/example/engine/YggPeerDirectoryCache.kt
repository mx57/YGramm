// File: app/src/main/java/com/example/engine/YggPeerDirectoryCache.kt
package com.example.engine

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last public-directory snapshot this device actually read, kept on disk.
 *
 * Why it exists: the candidate pool for the scanner comes from one HTTPS document, and on an
 * allow-listing uplink that document is exactly the thing that may be unreachable — the address
 * is not on the whitelist. Without a cached copy the scan drops back to the ~46 nodes shipped in
 * the catalog, and those age out. A snapshot read yesterday still names endpoints worth probing
 * today, and it is labelled as old rather than passed off as live: every peer keeps the publisher's
 * own sighting stamp, so the UI can say how stale the list is instead of hiding it.
 *
 * Only the fields the scanner needs to rank and dial a candidate are stored. No probe result is
 * cached: whether an endpoint answers from this network is a fact about the current link, and a
 * stale "yes" is the claim that got this app in trouble before.
 */
object YggPeerDirectoryCache {

    private const val FILE_NAME = "ygg_peer_directory_snapshot.json"

    /** Caps the file at a few hundred nodes; the scan probes a fraction of that per pass anyway. */
    private const val MAX_TRACKED_PEERS = 400

    fun save(context: Context, fetch: DirectoryFetch): Boolean {
        if (fetch.errorDetail != null || fetch.fromCache || fetch.peers.isEmpty()) return false
        val peers = JSONArray()
        fetch.peers.take(MAX_TRACKED_PEERS).forEach { peer ->
            peers.put(
                JSONObject()
                    .put("uri", peer.endpointUri)
                    .put("cc", peer.countryCode)
                    .put("flag", peer.countryFlag)
                    .put("name", peer.countryNameRu)
                    .put("city", peer.cityRu)
                    .put("transport", peer.transportType)
                    .put("sni", peer.sniDomain)
                    .put("whitelist", peer.whitelistCompatible)
                    .put("key", peer.ed25519PubKeyShort)
                    .put("seenAt", peer.lastSeenAtMs)
                    .put("rtt", peer.directoryRttMs)
            )
        }
        val document = JSONObject()
            .put("snapshotUpdatedAtMs", fetch.snapshotUpdatedAtMs)
            .put("savedAtMs", fetch.fetchedAtMs)
            .put("reportedTotal", fetch.reportedTotal)
            .put("peers", peers)
        return runCatching { file(context).writeText(document.toString()) }.isSuccess
    }

    /** The stored snapshot, or null when nothing usable was ever read. */
    fun load(context: Context): DirectoryFetch? {
        val raw = runCatching { file(context).readText() }.getOrNull() ?: return null
        return runCatching { parse(raw) }.getOrNull()
    }

    internal fun parse(rawJson: String): DirectoryFetch? {
        val document = JSONObject(rawJson)
        val array = document.optJSONArray("peers") ?: return null
        val peers = mutableListOf<DiscoveredYggPeer>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val uri = entry.optString("uri").trim()
            if (uri.isEmpty()) continue
            peers.add(
                DiscoveredYggPeer(
                    id = "cached_${uri.lowercase().hashCode().toUInt()}",
                    countryCode = entry.optString("cc"),
                    countryFlag = entry.optString("flag"),
                    countryNameRu = entry.optString("name"),
                    cityRu = entry.optString("city"),
                    endpointUri = uri,
                    transportType = entry.optString("transport"),
                    sniDomain = entry.optString("sni"),
                    latencyMs = -1,
                    whitelistCompatible = entry.optBoolean("whitelist", false),
                    ed25519PubKeyShort = entry.optString("key", "ключ неизвестен"),
                    lastSeenAtMs = entry.optLong("seenAt", 0L),
                    directoryRttMs = entry.optInt("rtt", -1)
                )
            )
        }
        if (peers.isEmpty()) return null
        return DirectoryFetch(
            peers = peers,
            reportedTotal = document.optInt("reportedTotal", peers.size),
            usableCount = peers.size,
            skippedDown = 0,
            skippedStale = 0,
            skippedOldProtocol = 0,
            fetchedAtMs = document.optLong("savedAtMs", 0L),
            snapshotUpdatedAtMs = document.optLong("snapshotUpdatedAtMs", 0L),
            fromCache = true
        )
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
