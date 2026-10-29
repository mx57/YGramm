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
        // Public links the far side completed the overlay handshake on. This is the only
        // number that means «the node is in the network»; see [readCoreConfirmedPeers].
        val liveLinks: Int = 0,
        // Sockets the core currently holds to peers, handshaked or not. Kept separate
        // because a TLS port that answers is not a route.
        val transportLinks: Int = 0,
        val totalLinks: Int = 0,
        val knownNodes: Int = 0,
        val sessionCount: Int = 0,
        val restartCount: Int = 0,
        // Endpoint (query stripped, lowercased) -> link latency in ms for the links the core
        // confirmed. The TCP scanner cannot know this: a node can refuse a plain handshake
        // while the Yggdrasil link over the same endpoint carries traffic, and the scanner
        // used to grey out exactly those peers.
        val livePeers: Map<String, Int> = emptyMap(),
        // What the node managed to bind for incoming links, attempt by attempt. A scheme the
        // kernel refused is shown with its error, never as an active transport.
        val listeners: List<CoreListener> = emptyList()
    )

    /**
     * One listen endpoint the core tried, with the result it got.
     *
     * tls, wss and quic all want 443 and only the first one to bind keeps it, so the list is
     * shown as attempts rather than as a set of active transports.
     */
    data class CoreListener(
        val scheme: String,
        val uri: String,
        val bound: Boolean,
        val address: String,
        val error: String,
        val recordedAtMs: Long
    ) {
        /** Строка отчёта: бинд или отказ с причиной, как их вернул kernel. */
        fun describeRu(): String = if (bound) {
            "$scheme слушает $address"
        } else {
            "$scheme не слушает: ${error.ifBlank { "порт занят или прав не хватает" }}"
        }
    }

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
    private external fun nativeGetListeners(profileId: String): String?

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

    fun getListenersJson(profileId: String): NativeCoreResult =
        invoke { nativeGetListeners(profileId) }

    /**
     * The endpoints this node reports as listening.
     *
     * Nothing here is inferred: the answer comes from the core's own bind attempts, so a scheme
     * the kernel refused appears with its error instead of being shown as active because the
     * profile asked for it.
     */
    fun readListeners(profileId: String): List<CoreListener> {
        val result = getListenersJson(profileId)
        if (!result.ok) return emptyList()
        return readCoreListeners(result.rawJson)
    }

    internal fun readCoreListeners(rawJson: String): List<CoreListener> {
        val listeners = try {
            JSONObject(rawJson).optJSONArray("listeners")
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val result = ArrayList<CoreListener>(listeners.length())
        for (i in 0 until listeners.length()) {
            val entry = listeners.optJSONObject(i) ?: continue
            result.add(
                CoreListener(
                    scheme = entry.optString("scheme").lowercase(),
                    uri = entry.optString("uri"),
                    bound = entry.optBoolean("bound", false),
                    address = entry.optString("addr"),
                    error = entry.optString("error"),
                    recordedAtMs = entry.optLong("recordedAt", 0L)
                )
            )
        }
        return result
    }

    /**
     * Reads the live mesh state of a profile's core. This is the authoritative
     * source for "is the node really connected": the Kotlin side can only see
     * TCP/TLS reachability of a peer list, while the core knows which remote nodes
     * its routing tree reached and which links finished the overlay handshake.
     */
    fun readMeshState(profileId: String): CoreMeshState {
        val result = getPeersJson(profileId)
        if (!result.ok) return CoreMeshState(available = false)
        val raw = result.rawJson
        val livePeers = readCoreConfirmedPeers(raw)
        return CoreMeshState(
            available = true,
            liveLinks = livePeers.size,
            transportLinks = extractJsonInt(raw, "upCount", 0),
            totalLinks = extractJsonInt(raw, "total", 0),
            knownNodes = extractJsonInt(raw, "knownNodes", 0),
            sessionCount = extractJsonInt(raw, "sessionCount", 0),
            livePeers = livePeers,
            listeners = run {
                val listenersResult = getListenersJson(profileId)
                if (listenersResult.ok) readCoreListeners(listenersResult.rawJson) else emptyList()
            }
        )
    }

    /**
     * Every dial the running core holds, whether or not the socket behind it ever answered.
     *
     * This is the list the whitelist profile actually has to change. The database's probe flag
     * cannot stand in for it: on an allow-listed uplink no probe answers, so a pass keyed on it
     * parks and restores nothing while the node goes on dialling the exact cleartext endpoints
     * the profile was switched on to stop. Like [readCoreConfirmedPeers], the URI is taken
     * without the `maxbackoff` query the core appends.
     */
    internal fun readConfiguredPeerUris(rawJson: String): Set<String> {
        val peers = try {
            JSONObject(rawJson).optJSONArray("peers")
        } catch (_: Exception) {
            null
        } ?: return emptySet()
        val result = HashSet<String>(peers.length())
        for (i in 0 until peers.length()) {
            val peer = peers.optJSONObject(i) ?: continue
            val endpoint = peer.optString("URI").substringBefore("?").trim().lowercase()
            if (endpoint.isNotBlank()) result.add(endpoint)
        }
        return result
    }

    /**
     * The peers this node really has an overlay link with.
     *
     * `Up` alone is not enough. yggdrasil-go (src/core/api.go, v0.5.14) sets it the moment a
     * connection object exists and fills `Key`, `Root` and `Latency` only from the ironwood
     * debug table, which the far side joins after it completes the overlay handshake. Counting
     * `Up` reported 14 "live links" on a node whose routing tree knew one node, had zero
     * sessions and never got a handshake back from any peer - sockets to ports that answer
     * TLS are not routes.
     *
     * The URI the core reports carries the `maxbackoff` query it appended, so it is stripped
     * here to match the endpoint exactly as the database stores it.
     */
    internal fun readCoreConfirmedPeers(rawJson: String): Map<String, Int> {
        val peers = try {
            JSONObject(rawJson).optJSONArray("peers")
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val result = HashMap<String, Int>(peers.length())
        for (i in 0 until peers.length()) {
            val peer = peers.optJSONObject(i) ?: continue
            if (!peer.optBoolean("Up", false)) continue
            // ed25519.PublicKey marshals to a base64 string; a peer with no key behind the
            // socket never shook hands, so it is not a link.
            val key = peer.opt("Key")
            if (key !is String || key.isBlank()) continue
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
     * endpoint here: the port has to be written into the URI, because a scheme name proves
     * nothing about it - `wss://relay:9999` rides a port the whitelist drops just as `tcp://` does.
     */
    fun isWhitelistEndpoint(endpointUri: String): Boolean {
        val uri = endpointUri.trim().substringBefore('?')
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
     * Public links finished with an overlay handshake, as reported by the core.
     * -1 when the core cannot be queried at all. Callers treat anything above zero as
     * «the node can reach the mesh», so counting sockets here would let a node holding
     * only unanswered connections claim a route it does not have.
     */
    fun livePeerCount(profileId: String): Int {
        val result = getPeersJson(profileId)
        if (!result.ok) return -1
        return readCoreConfirmedPeers(result.rawJson).size
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
