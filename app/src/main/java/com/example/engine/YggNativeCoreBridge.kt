// File: app/src/main/java/com/example/engine/YggNativeCoreBridge.kt
package com.example.engine

import org.json.JSONObject

/**
 * JNI bridge to the real Yggdrasil Go core (`libygggram.so`).
 * Each active profile runs its own independent node; every call is keyed by
 * profileId. Every native call returns a JSON string {"ok":bool,...} and never
 * throws across the boundary; this object degrades gracefully to "unavailable"
 * when the prebuilt `.so` is not packaged.
 */
object YggNativeCoreBridge {

    @Volatile
    var isNativeLibraryLoaded: Boolean = false
        private set

    init {
        isNativeLibraryLoaded = try {
            System.loadLibrary("ygggram")
            true
        } catch (_: Throwable) {
            false
        }
    }

    data class NativeCoreResult(
        val ok: Boolean,
        val rawJson: String,
        val error: String
    )

    /**
     * Live state of a profile's Go core: how many public links are up, how many
     * nodes the routing tree knows and how many encrypted sessions exist.
     */
    data class CoreMeshState(
        val available: Boolean,
        val liveLinks: Int = 0,
        val totalLinks: Int = 0,
        val knownNodes: Int = 0,
        val sessionCount: Int = 0,
        val restartCount: Int = 0,
        // Endpoint (query stripped, lowercased) -> link latency in ms for the links
        // the core itself reports up. The TCP scanner cannot know this: a node can
        // refuse a plain handshake while the Yggdrasil link over the same endpoint
        // carries traffic, and the scanner used to grey out exactly those peers.
        val upPeers: Map<String, Int> = emptyMap()
    )

    private external fun nativeInitCore(profileId: String, mnemonic: String, filesDir: String): String?
    private external fun nativeStopCore(profileId: String): String?
    private external fun nativeStopAllCores(): String?
    private external fun nativeAddPeer(profileId: String, peerUri: String, sinInterface: String): String?
    private external fun nativeRemovePeer(profileId: String, peerUri: String, sinInterface: String): String?
    private external fun nativeGetPeersJson(profileId: String): String?
    private external fun nativeSendOverlay(profileId: String, destPubKeyHex: String, payloadBase64: String): String?
    private external fun nativeOverlayRoute(profileId: String, destPubKeyHex: String): String?
    private external fun nativePollOverlay(profileId: String, timeoutMs: Int): String?
    private external fun nativeGetLinkLog(): String?
    private external fun nativeRetryPeers(profileId: String): String?
    private external fun nativeProbeOverlay(profileId: String, timeoutMs: Int): String?

    private val unavailableResult: NativeCoreResult
        get() = NativeCoreResult(
            ok = false,
            rawJson = "",
            error = "Нативное ядро Yggdrasil недоступно (libygggram.so не загружена)"
        )

    private fun invoke(nativeCall: () -> String?): NativeCoreResult {
        if (!isNativeLibraryLoaded) return unavailableResult
        val raw = try {
            nativeCall() ?: return NativeCoreResult(false, "", "Нативное ядро вернуло пустой ответ")
        } catch (t: Throwable) {
            return NativeCoreResult(false, "", "Нативное ядро упало: ${t.javaClass.simpleName}: ${t.message}")
        }
        val parsed = try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
        return if (parsed?.optBoolean("ok", false) == true) {
            NativeCoreResult(ok = true, rawJson = raw, error = "")
        } else {
            NativeCoreResult(
                ok = false,
                rawJson = raw,
                error = parsed?.optString("error").orEmpty().ifBlank {
                    "Нативное ядро вернуло ошибку без описания"
                }
            )
        }
    }

    private fun parsed(rawJson: String): JSONObject? = try {
        JSONObject(rawJson)
    } catch (_: Exception) {
        null
    }

    fun extractJsonField(rawJson: String, field: String): String =
        parsed(rawJson)?.optString(field, "").orEmpty()

    /**
     * Reads a numeric field from the core's JSON answer. Returns [fallback] when the
     * field is missing or not a number.
     */
    fun extractJsonInt(rawJson: String, field: String, fallback: Int = -1): Int =
        parsed(rawJson)?.optInt(field, fallback) ?: fallback

    /**
     * Reads a boolean field from the core's JSON answer.
     */
    fun extractJsonBool(rawJson: String, field: String, fallback: Boolean = false): Boolean =
        parsed(rawJson)?.optBoolean(field, fallback) ?: fallback

    fun initCore(profileId: String, mnemonic: String, filesDir: String): NativeCoreResult =
        invoke { nativeInitCore(profileId, mnemonic, filesDir) }

    fun stopCore(profileId: String): NativeCoreResult =
        invoke { nativeStopCore(profileId) }

    fun stopAllCores(): NativeCoreResult =
        invoke { nativeStopAllCores() }

    fun addPeer(profileId: String, peerUri: String, sinInterface: String = ""): NativeCoreResult =
        invoke { nativeAddPeer(profileId, peerUri, sinInterface) }

    fun removePeer(profileId: String, peerUri: String, sinInterface: String = ""): NativeCoreResult =
        invoke { nativeRemovePeer(profileId, peerUri, sinInterface) }

    fun getPeersJson(profileId: String): NativeCoreResult =
        invoke { nativeGetPeersJson(profileId) }

    /**
     * Reads the live mesh state of a profile's core. This is the authoritative
     * source for "is the node really connected": the Kotlin side can only see
     * TCP/TLS reachability of a peer list, while the core knows which public
     * links are up and which remote nodes its routing tree reached.
     */
    fun readMeshState(profileId: String): CoreMeshState {
        val result = getPeersJson(profileId)
        if (!result.ok) return CoreMeshState(available = false)
        val raw = result.rawJson
        val upPeers = readCoreUpPeers(raw)
        return CoreMeshState(
            available = true,
            liveLinks = extractJsonInt(raw, "upCount", 0),
            totalLinks = extractJsonInt(raw, "total", 0),
            knownNodes = extractJsonInt(raw, "knownNodes", 0),
            sessionCount = extractJsonInt(raw, "sessionCount", 0),
            upPeers = upPeers
        )
    }

    /**
     * Per-link state straight from the core's dial table. The URI the core reports
     * carries the `maxbackoff` query it appended, so it is stripped here to match the
     * endpoint exactly as the database stores it.
     */
    internal fun readCoreUpPeers(rawJson: String): Map<String, Int> {
        val peers = try {
            JSONObject(rawJson).optJSONArray("peers")
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val result = HashMap<String, Int>(peers.length())
        for (i in 0 until peers.length()) {
            val peer = peers.optJSONObject(i) ?: continue
            if (!peer.optBoolean("Up", false)) continue
            val endpoint = peer.optString("URI").substringBefore("?").trim().lowercase()
            if (endpoint.isNotBlank()) {
                result[endpoint] = (peer.optLong("Latency", 0L) / 1_000_000L).toInt()
            }
        }
        return result
    }

    /**
     * The URI exactly as the Go core must receive it.
     *
     * yggdrasil reads `?sni=<hostname>` off the peer URI (src/core/link.go) and uses it
     * as the TLS ServerName, while Go's crypto/tls refuses to put an IP literal into SNI
     * (RFC 6066) — which is what a `tls://95.217.35.148:443` link looks like on the wire:
     * a handshake with no server name at all, on port 443, from a node whose traffic is
     * being filtered. A hostname sniDomain turns that into an ordinary-looking HTTPS
     * hello without touching any of the crypto: the overlay still peers with the same
     * key, and the certificate is the node's own as before.
     *
     * Only tls:// honours it, so every other scheme and every value that is not a
     * hostname is passed through unchanged instead of being silently rewritten.
     */
    fun corePeerUri(endpointUri: String, sniDomain: String): String {
        val uri = endpointUri.trim()
        if (!uri.startsWith("tls://") || uri.contains('?')) return uri
        val sni = sniDomain.trim().lowercase().trim('.')
        if (!isSniHostname(sni)) return uri
        return "$uri?sni=$sni"
    }

    /**
     * A dotted name Go's crypto/tls is willing to put in a ClientHello.
     *
     * "1.2.3.4" satisfies the label shape, so the pattern alone is not enough: an IP
     * literal is exactly what RFC 6066 forbids as a server name, and Go drops it in
     * silence instead of refusing the dial.
     */
    private fun isSniHostname(value: String): Boolean {
        if (!value.matches(SNI_HOSTNAME)) return false
        return !value.split('.').all { it.toIntOrNull() != null }
    }

    private val SNI_HOSTNAME =
        Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /**
     * Whether this peer's handshake will actually name a host on the wire.
     *
     * Two ways to get there: a `?sni=` the tls:// link accepts, or a tls:// / wss:// URI
     * whose own host is a name — those two link types already copy it into ServerName
     * before any option is read. An IP endpoint without a usable sniDomain is the case
     * that stays silent, and it is the one a filtering provider sees as a bare TLS hello.
     */
    fun sendsHostnameSni(endpointUri: String, sniDomain: String): Boolean {
        val uri = endpointUri.trim()
        if (corePeerUri(uri, sniDomain) != uri) return true
        if (!uri.startsWith("tls://") && !uri.startsWith("wss://")) return false
        val host = uri.substringAfter("://").substringBefore('/')
            .substringBeforeLast(':').trimStart('[').trimEnd(']')
        return isSniHostname(host)
    }

    /**
     * Whether an endpoint sits where an allow-listing provider lets traffic through.
     *
     * The scanner stores this as a per-peer flag, but the boot gate has URIs only and a peer
     * pasted in by hand never went through the scanner at all, so the rule is re-read from the
     * endpoint here: port 443, or a WebSocket tunnel that already rides on it.
     */
    fun isWhitelistEndpoint(endpointUri: String): Boolean {
        val uri = endpointUri.trim().substringBefore('?')
        if (uri.startsWith("wss://", ignoreCase = true)) return true
        val authority = uri.substringAfter("://", "").substringBefore('/')
        return authority.substringAfterLast(':', "").toIntOrNull() == 443
    }

    /**
     * Interrupts the core's reconnect backoff so a node that lost its public
     * peers dials them again immediately.
     */
    fun retryPeers(profileId: String): NativeCoreResult =
        invoke { nativeRetryPeers(profileId) }

    /**
     * Runs a real round trip through the 0200::/7 overlay: the core addresses a
     * remote node from its routing tree and waits until that node answers with a
     * session acknowledgement. A node cannot address itself, so the answer can only
     * come from a genuinely remote peer.
     */
    fun probeOverlay(profileId: String, timeoutMs: Int = 20000): NativeCoreResult =
        invoke { nativeProbeOverlay(profileId, timeoutMs) }

    /**
     * Number of public peer links that are currently up, as reported by the
     * core. -1 when the core cannot be queried at all.
     */
    fun livePeerCount(profileId: String): Int {
        val result = getPeersJson(profileId)
        if (!result.ok) return -1
        val raw = result.rawJson
        val marker = "\"upCount\":"
        val start = raw.indexOf(marker)
        if (start < 0) return -1
        var i = start + marker.length
        var value = 0
        var digits = 0
        while (i < raw.length && raw[i].isDigit()) {
            value = value * 10 + (raw[i] - '0')
            i++
            digits++
        }
        return if (digits > 0) value else -1
    }

    /**
     * Sends one datagram over the real Yggdrasil overlay to the node identified
     * by its Ed25519 public key (ironwood session addressing).
     */
    fun sendOverlay(profileId: String, destNodePubKeyHex: String, payload: ByteArray): NativeCoreResult =
        invoke {
            nativeSendOverlay(
                profileId,
                destNodePubKeyHex,
                android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
            )
        }

    /**
     * Whether the profile's node has learned an overlay route to [destNodePubKeyHex].
     * A successful [sendOverlay] alone proves nothing: ironwood only forwards a frame
     * when its pathfinder has a path entry, otherwise it starts a route lookup and drops
     * the packet if nobody answers. null when the core could not be queried.
     */
    fun hasOverlayRoute(profileId: String, destNodePubKeyHex: String): Boolean? {
        val result = invoke { nativeOverlayRoute(profileId, destNodePubKeyHex) }
        if (!result.ok) return null
        return extractJsonBool(result.rawJson, "hasPath")
    }

    /**
     * Blocks up to [timeoutMs] waiting for one overlay datagram received by the
     * profile's node. Returns rawJson with payloadBase64 + senderPubKeyHex, or
     * {"ok":true,"timeout":true} when nothing arrived.
     */
    fun pollOverlay(profileId: String, timeoutMs: Int): NativeCoreResult =
        invoke { nativePollOverlay(profileId, timeoutMs) }

    /**
     * Tail of the Go core's internal log (link dials, TLS and handshake errors)
     * used to explain why the node has no live peers.
     */
    fun getLinkLogTail(maxLines: Int = 10): List<String> {
        if (!isNativeLibraryLoaded) return emptyList()
        val raw = try {
            nativeGetLinkLog() ?: return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        }
        val entries = parsed(raw)?.optJSONArray("lines") ?: return emptyList()
        val lines = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            entries.optString(i).lineSequence().filter { it.isNotBlank() }.forEach { lines.add(it) }
        }
        return lines.takeLast(maxLines)
    }

    fun decodeOverlayPayload(rawJson: String): Pair<ByteArray, String>? {
        val payloadB64 = extractJsonField(rawJson, "payloadBase64")
        if (payloadB64.isEmpty()) return null
        return try {
            val bytes = android.util.Base64.decode(payloadB64, android.util.Base64.NO_WRAP)
            bytes to extractJsonField(rawJson, "senderPubKeyHex")
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
