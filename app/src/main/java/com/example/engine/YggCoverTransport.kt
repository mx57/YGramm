package com.example.engine

import android.os.Build
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Runs the peer's TLS handshake on the phone's own TLS stack instead of the core's.
 *
 * The Go core dials `tls://` with crypto/tls and offers no ALPN: on the wire that is a
 * ClientHello naming a whitelisted domain while speaking no HTTP version at all, which is
 * the mismatch a provider enforcing an allow-list RSTs. Here the core is handed a plain
 * loopback stream and the real handshake is performed with the platform stack, which offers
 * `h2, http/1.1` and a BoringSSL record layout like every other app on the device. The
 * overlay is untouched: Yggdrasil still authenticates its own handshake against the far
 * node's key and the message envelope is still sealed on both ends - the proxy relays bytes
 * it cannot give meaning to.
 */
object YggCoverTransport {

    /** Where a covered dial goes once its local endpoint is accepted. */
    data class CoverPlan(
        val realUri: String,
        val host: String,
        val port: Int,
        val sni: String,
        val upgradedFromTcp: Boolean
    )

    @Volatile
    var enabled: Boolean = true

    /**
     * The whitelist profile from the network screen: it decides whether tcp:// is retried on 443
     * and whether a peer outside 443 is dialled at all. It starts matching the screen's default
     * on purpose — a node that booted before the first sync used to dial every port the profile
     * is supposed to hold back.
     */
    @Volatile
    var whitelistMode: Boolean = true

    /** The allowed domain the whitelist profile puts into ClientHello. */
    @Volatile
    var whitelistSni: String = ""

    /** Last handshake failure seen by any session, quoted verbatim for the journal. */
    @Volatile
    var lastError: String? = null

    private val alpn = listOf("h2", "http/1.1")
    private const val HANDSHAKE_TIMEOUT_MS = 8_000

    /** Handshake probes run at once, so checking a full peer list stays under a minute. */
    private const val MAX_CONCURRENT_PROBES = 8

    /** Replaced by tests so the pumping path runs without a real TLS peer. */
    internal var opener: (host: String, port: Int, sni: String, alpn: List<String>) -> Socket =
        { host, port, sni, offered -> openTlsSocket(host, port, sni, offered) }

    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(val plan: CoverPlan, val listener: ServerSocket, val thread: Thread)

    /**
     * Whether the node may dial this peer at all while the whitelist profile is on.
     *
     * A peer survives an allow-list one of two ways: its endpoint already sits on the port the
     * list forwards, or the mask can move the handshake onto it. A dial with neither is traffic
     * the provider drops, and repeating a dropped handshake is what this profile exists to stop
     * producing, so the mode withholds the dial instead of only preferring 443.
     */
    fun dialsPeer(dialUri: String): Boolean {
        if (!whitelistMode) return true
        if (YggNativeCoreBridge.isWhitelistEndpoint(dialUri)) return true
        return planFor(dialUri)?.port == 443
    }

    /**
     * Whether the masked endpoint has to replace this dial instead of supplementing it.
     *
     * The replace case is a `tcp://` peer on a port the allow-list does not forward: the raw dial
     * and the mask address the same peer, and only the mask puts the handshake on 443. Offering
     * both means the node keeps producing the cleartext handshake the provider drops, which is the
     * exact traffic this profile exists to stop. A `tls://` peer is different - its own dial is
     * already 443/TLS, so the mask is a second chance at it rather than the only route.
     */
    fun maskReplacesRawDial(dialUri: String): Boolean =
        whitelistMode && planFor(dialUri)?.upgradedFromTcp == true

    /**
     * The dial the core should use for this peer, or null when the peer cannot be covered.
     *
     * `tls://` keeps its own host and port, so only the handshake moves. A plain `tcp://`
     * peer is retried as TLS on 443 only while the whitelist profile is on, because that is
     * the port an allow-list lets through; outside the profile the cleartext dial is all it
     * has and stays the dial the core gets.
     */
    fun planFor(dialUri: String): CoverPlan? {
        if (!enabled) return null
        val trimmed = dialUri.trim()
        val scheme = trimmed.substringBefore("://").lowercase()
        val authority = trimmed.substringAfter("://").substringBefore("/").substringBefore("?")
        val lastColon = authority.lastIndexOf(':')
        val maybePort = authority.substringAfterLast(':', "")
        val hasPort = lastColon > 0 && maybePort.isNotEmpty() && maybePort.all { it.isDigit() }
        val host = (if (hasPort) authority.substring(0, lastColon) else authority).trim('[', ']')
        val port = when {
            hasPort -> maybePort.toInt()
            scheme == "tls" -> 443
            else -> return null
        }
        val sni = (queryValue(trimmed, "sni")?.takeIf { it.isNotBlank() }
            ?: whitelistSni.trim()).lowercase().trim('.')
            .ifBlank { host.lowercase() }
        if (!isHostname(host) && !host.isIpAddress()) return null
        if (!isHostname(sni)) return null
        return when (scheme) {
            "tls" -> CoverPlan(trimmed, host, port, sni, upgradedFromTcp = false)
            "tcp" -> if (whitelistMode) CoverPlan(trimmed, host, 443, sni, upgradedFromTcp = true) else null
            else -> null
        }
    }

    /** Starts or reuses the local endpoint for this plan; null when the bind failed. */
    fun coverEndpoint(plan: CoverPlan): String? {
        sessions[plan.realUri]?.takeIf { !it.listener.isClosed }?.let {
            return endpointOf(it.listener.localPort)
        }
        val listener = try {
            ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        } catch (e: Exception) {
            lastError = "loopback bind " + e.javaClass.simpleName +
                (e.message?.let { ": $it" } ?: "")
            return null
        }
        val thread = Thread({
            while (!listener.isClosed) {
                val local = try {
                    listener.accept()
                } catch (_: Throwable) {
                    break
                }
                Thread({ pump(plan, local) }, "ygg-cover-${plan.host}").apply {
                    isDaemon = true
                    start()
                }
            }
        }, "ygg-cover-listen-${plan.host}").apply { isDaemon = true }
        sessions[plan.realUri] = Session(plan, listener, thread)
        thread.start()
        return endpointOf(listener.localPort)
    }

    private fun endpointOf(port: Int) = "tcp://127.0.0.1:$port"

    private fun pump(plan: CoverPlan, local: Socket) {
        val remote = try {
            opener(plan.host, plan.port, plan.sni, alpn)
        } catch (e: Throwable) {
            // The cover target is the user's choice, so a refused handshake is a fact worth
            // showing: it means this peer has no TLS listener where the allow-list expects
            // one. The core's own dial is untouched and keeps working on an open network.
            lastError = "${plan.host}:${plan.port} " + e.javaClass.simpleName +
                (e.message?.let { ": $it" } ?: "")
            runCatching { local.close() }
            return
        }
        // Kotlin's copyTo leaves both streams open, which is what is needed here: closing a
        // stream on Java means closing the socket, and the other direction may still have
        // bytes to deliver. Each side is closed explicitly once its own copy is done.
        val out = Thread({
            runCatching { local.inputStream.copyTo(remote.outputStream) }
            runCatching { remote.close() }
        }, "ygg-cover-out").apply { isDaemon = true }
        val inbound = Thread({
            runCatching { remote.inputStream.copyTo(local.outputStream) }
            runCatching { local.close() }
        }, "ygg-cover-in").apply { isDaemon = true }
        out.start()
        inbound.start()
    }

    fun describe(plan: CoverPlan, endpoint: String): String =
        "$endpoint -> tls://${plan.host}:${plan.port} • SNI ${plan.sni} • ALPN ${alpn.joinToString(",")}" +
            if (plan.upgradedFromTcp) " • открытый tcp://-пир поднят на 443" else ""

    /** Live local endpoints, so the UI can say what is actually running. */
    fun activeEndpoints(): Int = sessions.values.count { !it.listener.isClosed }

    fun coveredUri(realUri: String): String? =
        sessions[realUri.trim()]?.takeIf { !it.listener.isClosed }?.let { endpointOf(it.listener.localPort) }

    /** Every live cover link as `real peer URI -> local endpoint`, for a re-dial pass. */
    fun coveredUris(): Map<String, String> = sessions.entries
        .filter { !it.value.listener.isClosed }
        .associate { it.key to endpointOf(it.value.listener.localPort) }

    /** Drops a peer's endpoint so the core is not left offered a dead loopback. */
    fun release(realUri: String) {
        val session = sessions.remove(realUri.trim()) ?: return
        runCatching { session.listener.close() }
        runCatching { session.thread.join(50) }
    }

    fun releaseAll() {
        sessions.keys.toList().forEach { release(it) }
    }

    /** One row of the mask self-test: what the phone's stack achieved against one peer. */
    data class MaskProbe(val uri: String, val accepted: Boolean, val detail: String)

    /**
     * Handshakes every peer through the mask without involving the core.
     *
     * Whether an allow-list lets a covered connection through is a fact about the network and
     * the far node, not about this app, so the honest answer is per peer: reach out to 443 with
     * the profile's SNI and ALPN and report the negotiated protocol or the exact failure. A peer
     * that answers nothing here cannot carry the overlay through a mask, however correct the
     * ClientHello is. Sockets are closed as soon as the handshake ends - the probe holds no
     * session open.
     */
    fun probeMask(uris: List<String>): List<MaskProbe> {
        val targets = uris.map { it.trim() }.filter { it.isNotEmpty() }
        if (targets.isEmpty()) return emptyList()
        if (!enabled) return targets.map { MaskProbe(it, false, "маска выключена") }
        val pool = Executors.newFixedThreadPool(targets.size.coerceAtMost(MAX_CONCURRENT_PROBES))
        return try {
            pool.invokeAll(
                targets.map { uri ->
                    val plan = planFor(uri)
                    Callable {
                        if (plan == null) {
                            MaskProbe(
                                uri, false,
                                "$uri • под маску не берётся: нужен tls://-пир " +
                                    "(tcp:// - только при профиле белого списка)"
                            )
                        } else probeOne(uri, plan)
                    }
                }
            ).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun probeOne(uri: String, plan: CoverPlan): MaskProbe {
        return try {
            val socket = opener(plan.host, plan.port, plan.sni, alpn)
            val negotiated = (socket as? SSLSocket)?.session
                ?.let { "${it.protocol}/${it.cipherSuite}" } ?: "TLS"
            runCatching { socket.close() }
            MaskProbe(uri, true, "${plan.host}:${plan.port} • SNI ${plan.sni} • $negotiated")
        } catch (e: Throwable) {
            lastError = "${plan.host}:${plan.port} " + e.javaClass.simpleName +
                (e.message?.let { ": $it" } ?: "")
            MaskProbe(uri, false, lastError!!)
        }
    }

    internal fun openTlsSocket(host: String, port: Int, sni: String, offeredAlpn: List<String>): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
        // Chain validation is off: a yggdrasil node answers with its own self-signed
        // certificate, and the identity that matters is the node key inside the overlay
        // handshake, not an X509 issuer.
        val ssl = trustAllContext.socketFactory.createSocket(
            raw, host, port, true
        ) as SSLSocket
        // The local port is how a cover handshake is found again in a packet capture: the
        // redirect and the loopback both keep it, so a hello can be attributed to this code
        // instead of guessed at from its shape.
        android.util.Log.i(
            "YggCover",
            "dial $host:$port from local port ${raw.localPort} • sni=$sni • alpn=${offeredAlpn.joinToString(",")}"
        )
        ssl.soTimeout = HANDSHAKE_TIMEOUT_MS
        val params = ssl.sslParameters ?: SSLParameters()
        params.serverNames = listOf(SNIHostName(sni))
        // Chain validation is off, so hostname verification has to go with it: the platform
        // stack would otherwise reject every yggdrasil node for an untrusted self-signed
        // certificate named after somebody else.
        params.endpointIdentificationAlgorithm = null
        // ALPN is only a public API from Android 10 on; below it the hello still names the
        // host but offers no HTTP version, exactly like the core's own handshake.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            params.applicationProtocols = offeredAlpn.toTypedArray()
        }
        ssl.sslParameters = params
        ssl.startHandshake()
        android.util.Log.i(
            "YggCover",
            "handshake done $host:$port from local port ${raw.localPort} • ${ssl.session.protocol}/${ssl.session.cipherSuite}"
        )
        return ssl
    }

    private fun queryValue(uri: String, key: String): String? {
        val query = uri.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val (name, value) = part.split('=', limit = 2).let {
                if (it.size == 2) it[0] to it[1] else it[0] to null
            }
            if (name == key) value else null
        }
    }

    private val hostnamePattern =
        Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    private fun isHostname(value: String): Boolean =
        value.lowercase().matches(hostnamePattern)

    private fun String.isIpAddress(): Boolean =
        matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || lowercase().contains(':')

    private val trustAllContext: SSLContext by lazy {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out java.security.cert.X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun checkServerTrusted(
                    chain: Array<out java.security.cert.X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                    emptyArray()
            }
        )
        SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    }
}
