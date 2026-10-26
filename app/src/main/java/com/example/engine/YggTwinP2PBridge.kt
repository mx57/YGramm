// File: app/src/main/java/com/example/engine/YggTwinP2PBridge.kt
package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.example.data.UserProfileEntity
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IncomingTwinPacket(
    val targetChatId: String,
    val messageId: String = "",
    val recipientProfileId: String = "",
    val recipientIpv6: String = "",
    val senderNodeLabel: String,
    val senderIpv6: String,
    val senderPubKeyHex: String = "",
    val plainText: String,
    val cipherPayloadHex: String,
    val hashcashToken: String,
    val attachmentName: String?,
    val attachmentSizeBytes: Long,
    val attachmentType: String? = null,
    val attachmentUri: String? = null,
    val attachmentBase64: String? = null,
    // Set when the bytes came through the piece stream and this device verified the Merkle root
    // itself; an inline payload leaves it empty and the receiver hashes the file it saved.
    val streamedAttachmentRoot: String = "",
    val voiceDurationSec: Int = 0,
    val voiceWaveformCsv: String? = null,
    // Wire id of the message this one quotes, and the original author for forwards.
    val replyToMessageId: String = "",
    val forwardedFrom: String = "",
    // The sender's own picture, so a contact's face arrives on the same authenticated
    // overlay path as their messages instead of from a server nobody controls.
    // data is only present on the first message after the sender changes it.
    val senderAvatarHash: String = "",
    val senderAvatarData: String = "",
    // Group identity and roster, advertised by every group message. A member learns who
    // else is in the group from the messages themselves, so a group needs no separate
    // sync channel and no server: one frame from any member is enough to rebuild it.
    val groupId: String = "",
    val groupTitle: String = "",
    // "ipv6|ed25519hex,ipv6|ed25519hex" — empty key when the sender only knows the address.
    val groupMembersCsv: String = "",
    val rttMs: Long,
    val timestamp: Long
)

data class IncomingReactionPacket(
    val chatId: String,
    val messageId: String,
    val emoji: String,
    val senderIpv6: String,
    val recipientProfileId: String = "",
    val action: String = "ADD" // "ADD" or "REMOVE"
)

data class ReactionDispatchResult(
    val delivered: Boolean,
    val endToEndConfirmed: Boolean = false,
    val transport: String = "",
    val rttMs: Long = -1L,
    val errorDetail: String? = null
)

/**
 * A request to erase one message from this device. [proofEnvelope] is the AEAD proof that the
 * requester authored that message; the repository refuses the request when it does not verify.
 */
data class IncomingDeletePacket(
    val chatId: String,
    val wireMessageId: String,
    val deleteId: String,
    val senderIpv6: String,
    val proofEnvelope: String
)

data class DeleteDispatchResult(
    val delivered: Boolean,
    val endToEndConfirmed: Boolean = false,
    val transport: String = "",
    val rttMs: Long = -1L,
    val errorDetail: String? = null
)

/**
 * One frame of a streamed large-file transfer. Each piece is its own AEAD message keyed by the
 * conversation and bound to its index, so a piece that does not decrypt is dropped rather than
 * written; the transfer only completes when the Merkle root over the pieces actually written
 * matches the root the sender declared up front.
 */
data class IncomingFilePieceFrame(
    val chatId: String,
    val transferId: String,
    val senderIpv6: String,
    val senderEd25519PubHex: String
)

data class FileStreamDispatchResult(
    val delivered: Boolean,
    val piecesSent: Int = 0,
    val piecesTotal: Int = 0,
    val bytesTransferred: Long = 0L,
    val merkleRootHex: String = "",
    val errorDetail: String? = null
)

/**
 * Live state of one streamed transfer. Both ends report it: the sender counts acknowledged
 * pieces, the receiver counts written ones, so the progress bar and the transfer ledger show
 * bytes that really moved instead of an estimate.
 */
data class FileTransferProgress(
    val chatId: String,
    val transferId: String,
    val fileName: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val elapsedMs: Long,
    val merkleRootHex: String,
    val isOutgoing: Boolean,
    // "STREAMING" while pieces move, "VERIFIED" after the Merkle root matched, "FAILED" otherwise.
    val state: String
)

data class IncomingCallSignalPacket(
    val chatId: String,
    val recipientProfileId: String = "",
    val recipientIpv6: String = "",
    val callerName: String,
    val callerIpv6: String,
    // "RINGING", "ACCEPT", "DECLINE", "BUSY", "BYE", "MISSED"
    val signalAction: String = "RINGING",
    val callId: String = "",
    val senderX25519PubKeyHex: String = "",
    val senderNodePubKeyHex: String = "",
    val transport: String = "",
    // "ip:port" of the caller's listener when the signal arrived over LAN; used to
    // route ACCEPT/DECLINE and the media channel back without a second lookup.
    val senderLanHint: String = ""
)

data class IncomingCallAudioPacket(
    val chatId: String,
    val callId: String,
    val senderIpv6: String,
    val recipientProfileId: String,
    val ivBase64: String,
    val cipherBase64: String,
    val seq: Long,
    val senderNodePubKeyHex: String = ""
)

data class CallSignalDispatchResult(
    val delivered: Boolean,
    val transport: String = "",
    val rttMs: Long = -1L,
    val errorDetail: String? = null
)

data class CallMediaChannelResult(
    val opened: Boolean,
    val transport: String = "",
    val rttMs: Long = -1L,
    val errorDetail: String? = null
)

data class YggLivePacketLogEntry(
    val id: String,
    val timeFormatted: String,
    val direction: String, // "TX -> YGG", "RX <- YGG", "ACK <- PEER", "TX -> LAN", "ERR"
    val summary: String,
    val details: String
)

data class PeerProbeResult(
    val endpointUri: String,
    // True only when the endpoint answered the handshake it was asked to answer —
    // an open port that never replies cannot carry a Yggdrasil link.
    val reachable: Boolean,
    val rttMs: Int,
    val protocolUsed: String,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val errorDetail: String? = null
)

data class SelfYggdrasilRelayResult(
    val delivered: Boolean,
    val relayEndpointUri: String,
    val relayRegionLabel: String,
    val rttMs: Long,
    val txBytes: Long,
    val rxBytes: Long,
    val errorDetail: String? = null,
    // true only when an explicit receiver ACK was observed (overlay session ACK, loopback
    // profile ACK or LAN-direct ACK); relay-only hops are not delivery confirmations.
    val endToEndConfirmed: Boolean = false,
    // "YGG_OVERLAY", "LAN_DIRECT" or "LOCAL_PROFILE"; empty when nothing was delivered.
    val transport: String = "",
    val deliveredToProfileId: String = ""
)

/**
 * Result of a real overlay round trip: the profile's node addressed a remote node
 * from its routing tree and the remote node answered over the 0200::/7 overlay.
 */
data class OverlayProbeOutcome(
    val confirmed: Boolean,
    val rttMs: Long,
    val remoteKeyHex: String,
    val multiHop: Boolean,
    val livePeers: Int,
    val knownNodes: Int,
    val txBytes: Long,
    val errorDetail: String? = null
)

/**
 * What the running node actually did with a peer change: how many live cores accepted
 * it and the link counts afterwards. Only the node knows whether a link came up.
 */
data class PeerApplyOutcome(
    val applied: Int,
    val liveLinks: Int,
    val totalLinks: Int,
    val errorDetail: String? = null
)

data class DiscoveredLanNode(
    val displayName: String,
    val yggIpv6: String,
    val pubKeyHex: String,
    val hostIp: String,
    val tcpPort: Int,
    val lastSeenMs: Long
)

/**
 * Real network transport engine for YggGram (Userspace Yggdrasil + Multi-Profile P2P):
 * 1. Strictly checks physical connectivity & Airplane Mode via ConnectivityManager + Settings.Global.
 * 2. Performs genuine TCP / TLS / WSS handshakes and Yggdrasil `meta\x00\x00` wire header exchange
 *    with public Yggdrasil peers to measure real RTT.
 * 3. Routes self-messages ("Избранное • Мой узел Yggdrasil") strictly through external public Yggdrasil
 *    peers over the internet (NEVER via 127.0.0.1 loopback) to honestly verify Yggdrasil mesh connectivity.
 * 4. Supports simultaneous multi-profile execution: binds dedicated TCP listeners for each active profile
 *    (`51921..51935`) so 2+ profiles can run concurrently in the background and exchange real encrypted
 *    messages, voice recordings, images, files, reactions, and VoIP call frames.
 */
class YggTwinP2PBridge(
    private val context: Context? = null,
    private val onPacketReceived: suspend (IncomingTwinPacket) -> Unit,
    private val onDeliveryAckReceived: suspend (chatId: String, messageId: String, rttMs: Long, ackSenderPubKeyHex: String) -> Unit,
    private val onDeliveryAckTimeout: suspend (chatId: String, messageId: String, attempts: Int) -> Unit = { _, _, _ -> },
    private val onTwinReachableChanged: (Boolean) -> Unit,
    private val onPacketLogAdded: (YggLivePacketLogEntry) -> Unit,
    private val onLanNodeDiscovered: (DiscoveredLanNode) -> Unit = {},
    private val onReactionReceived: suspend (IncomingReactionPacket) -> Unit = {},
    private val onDeleteForEveryoneReceived: suspend (IncomingDeletePacket) -> Unit = {},
    /**
     * Gives the AEAD key for one streamed file transfer: the repository derives it from the
     * conversation's pinned peer X25519 key and the local profile's own private key, so a piece
     * from any other node simply fails to decrypt and is refused.
     */
    private val resolveFileTransferKey: suspend (profileId: String, chatId: String, transferId: String) -> ByteArray? = { _, _, _ -> null },
    /**
     * Finds the local dialog an inbound stream belongs to. The chatId inside the frame is the
     * sender's own row and means nothing on this device, and without the local dialog there is
     * no pinned peer key to verify the pieces against.
     */
    private val resolveIncomingDialogChatId: suspend (profileId: String, senderPubKeyHex: String, senderIpv6: String) -> String? = { _, _, _ -> null },
    private val onFileTransferProgress: suspend (FileTransferProgress) -> Unit = {},
    private val onReadReceiptReceived: suspend (chatId: String, wireMessageIds: List<String>) -> Unit = { _, _ -> },
    private val onTypingReceived: suspend (chatId: String, senderNodeLabel: String, senderIpv6: String) -> Unit = { _, _, _ -> },
    private val onIncomingCallSignalReceived: suspend (IncomingCallSignalPacket) -> Unit = {},
    private val onCallAudioFrameReceived: suspend (IncomingCallAudioPacket) -> Unit = {},
    private val onProfileRelayPacketHandled: suspend (profileId: String, role: String) -> Unit = { _, _ -> }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tcpServerSocket: ServerSocket? = null
    private var lanDiscoverySocket: DatagramSocket? = null

    // The primary listener is kept alive by its own job: without it a single closed
    // ServerSocket — a transient accept error, the OS resetting the socket — left the node
    // unable to receive anything until the app was restarted.
    private var tcpListenerJob: Job? = null

    // @Volatile: read from the listener loop on the IO dispatcher, written by start/stop.
    @Volatile
    private var listenersRequested = false

    // Concurrent secondary profile TCP listeners: profileId -> Pair(ServerSocket, Job)
    private val secondaryProfileSockets = ConcurrentHashMap<String, Pair<ServerSocket, Job>>()
    private val activeProfilePortMap = ConcurrentHashMap<String, Int>() // profileId -> bound port
    private val ipv6ToProfileIdMap = ConcurrentHashMap<String, String>() // yggIpv6.lowercase() -> profileId
    private val profileRelayRoleMap = ConcurrentHashMap<String, String>() // profileId -> nodeRelayRole

    var localTcpPort: Int = 51921
        private set

    val localUdpPort: Int
        get() = localTcpPort

    val lanDiscoveryUdpPort: Int = 51920

    private val packetCounter = AtomicLong(1L)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // The last round trip this node actually measured from the peer. An internet overlay route
    // answers in tens of seconds while a LAN frame returns in milliseconds, so every stream
    // budget is derived from what the wire really costs instead of a fixed guess.
    private val observedOverlayAckRttMs = AtomicLong(0L)

    // Known direct IP:Port endpoints discovered via LAN multicast or explicit contact URI (only used when allowDirectLanRouting = true)
    private val directEndpointTable = ConcurrentHashMap<String, InetSocketAddress>()

    // Call signalling: control frames wait for a CALL_ACK on the same transport;
    // media frames are fire-and-forget AEAD datagrams.
    private val pendingCallAcks = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Long>>()
    private val outgoingCallMediaSessions = ConcurrentHashMap<String, CallMediaSession>()

    // Large attachments are split into overlay-sized chunks: one Yggdrasil datagram
    // carries at most ~64 KB, so an inline Base64 image can never fit as one frame.
    private val incomingAttachmentAssemblies = ConcurrentHashMap<String, AttachmentAssembly>()
    private val completedAttachmentPayloads = ConcurrentHashMap<String, Pair<String, Long>>()
    private val pendingChunkAcks = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Long>>()

    // Streamed large files. Inbound: one open .part file per transfer plus the digests of the
    // pieces actually written, which are what the Merkle root is re-checked against. Outbound:
    // the highest piece count the peer has acknowledged and the coroutine waiting on it.
    private val incomingFileStreams = ConcurrentHashMap<String, IncomingFileStream>()
    private val completedFileStreams = ConcurrentHashMap<String, CompletedFileStream>()
    private val outgoingStreamHave = ConcurrentHashMap<String, Int>()
    // A stream of a real file runs for minutes, so the queue happily launches a second attempt
    // for the same message while the first is still going. Both then wait on the one ack-waiter
    // keyed by this transfer and each steals the other's acknowledgement, so neither window ever
    // completes. One attempt per transfer runs at a time.
    private val runningFileStreams = ConcurrentHashMap.newKeySet<String>()
    private val outgoingStreamVerified = ConcurrentHashMap<String, Boolean>()
    private val outgoingStreamRefusal = ConcurrentHashMap<String, String>()
    private val streamAckWaiters = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Int>>()

    private data class CallMediaSession(
        val callId: String,
        val chatId: String,
        val senderProfileId: String,
        val senderIpv6: String,
        val senderName: String,
        val recipientIpv6: String,
        val recipientKeyHex: String,
        val transport: String,
        val socket: Socket? = null,
        val writer: PrintWriter? = null
    )

    private class AttachmentAssembly(
        val transferId: String,
        val totalChunks: Int,
        val totalBase64Chars: Int,
        val attachmentSha256: String,
        val attachmentName: String?,
        val attachmentType: String?,
        val startedAtMs: Long
    ) {
        val chunks = ConcurrentHashMap<Int, String>()
    }

    private class CompletedFileStream(
        val localUri: String,
        val merkleRootHex: String,
        val fileName: String,
        val totalBytes: Long,
        val completedAtMs: Long
    )

    /**
     * One inbound streamed file. Pieces are accepted strictly in index order, so the resume
     * point is a single counter and the digest list is already in leaf order for the tree.
     */
    private class IncomingFileStream(
        val transferId: String,
        val chatId: String,
        val fileName: String,
        val fileType: String,
        val totalBytes: Long,
        val pieceBytes: Int,
        val pieceCount: Int,
        val declaredRoot: String,
        val key: ByteArray,
        val partFile: File,
        val startedAtMs: Long
    ) {
        // Pieces are accepted in whatever order the overlay delivers them, exactly like a
        // torrent: one lost datagram must not throw away the thirty-two that arrived after it.
        // `nextIndex` is therefore the cumulative prefix the sender resumes from, not a cursor.
        val have = BooleanArray(pieceCount)
        val digests = arrayOfNulls<ByteArray>(pieceCount)
        var nextIndex = 0
        var writtenBytes = 0L
        // -1 until a hole has already been reported for the current prefix, so a window of
        // out-of-order pieces answers once instead of once per piece.
        var holeAckedFor = -1
        // The prefix the sender was last told about. Pieces arrive out of order, so the prefix
        // jumps by whatever the hole filled in — an absolute window boundary can be skipped
        // entirely and the sender would sit on a timeout for nothing.
        var lastAckedPrefix = 0
        // Pieces that failed to open. One is a bit in transit; a whole window is the wrong key.
        var rejects = 0
    }

    private companion object {
        // Base64 chars per chunk. Values are Base64-encoded again by the wire frame,
        // so 18k chars become a ~25k datagram — below any plausible session MTU.
        const val ATTACHMENT_CHUNK_CHARS = 18_000
        // Attachments larger than this are always sent through chunk frames.
        const val ATTACHMENT_INLINE_MAX_CHARS = 20_000
        const val ATTACHMENT_CHUNK_WINDOW = 4
        const val ATTACHMENT_CHUNK_ACK_TIMEOUT_MS = 6_000L
        const val CHUNK_ASSEMBLY_TTL_MS = 10 * 60 * 1000L

        // Pieces in flight before the sender waits for the peer's cumulative acknowledgement.
        // A mesh round trip is seconds, not milliseconds, so the window has to be wide enough
        // that one acknowledgement pays for a meaningful slice of the file.
        const val FILE_PIECE_WINDOW = 32
        const val FILE_PIECE_ACK_TIMEOUT_MS = 9_000L
        // Consecutive windows with no forward progress abort the stream with a real reason.
        // The peer now accepts pieces out of order, so a window only counts as stalled when the
        // hole at its front survived a re-send — eight of those means the route is truly broken.
        const val FILE_STREAM_MAX_STALLED_WINDOWS = 8
        const val FILE_STREAM_TTL_MS = 45 * 60 * 1000L
        const val FILE_COMPLETED_LOOKUP_TTL_MS = 20 * 60 * 1000L

        const val CORE_WATCHDOG_INTERVAL_MS = 15_000L
        const val CORE_RETRY_MIN_BACKOFF_MS = 20_000L
        const val CORE_RETRY_MAX_BACKOFF_MS = 300_000L
        const val CORE_START_GRACE_MS = 45_000L
        const val CORE_DEATH_STRIKES_BEFORE_RESTART = 2

        // Spacing between re-offers of one call-control signal on a cold overlay route.
        // Shorter than the path-discovery round trip it is waiting for, longer than the
        // single-slot coalescing cache can be saturated by.
        const val CALL_SIGNAL_RETRY_MS = 1_800L
    }

    @Volatile
    var allowDirectLanRouting: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // The listener is the only thing that makes a peer discoverable in the local
            // network, so the toggle has to open and close it instead of merely changing
            // how frames received anyway are counted.
            scope.launch { applyLanDiscoveryState() }
        }

    private val yggdrasilTlsSocketFactory: SSLSocketFactory by lazy {
        val trustAllYggPeerCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllYggPeerCerts, SecureRandom())
        sslContext.socketFactory
    }

    fun isRealNetworkAvailable(): Boolean {
        val ctx = context ?: return true
        return try {
            val airplaneMode = Settings.Global.getInt(
                ctx.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return !airplaneMode
            val activeNet = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNet) ?: return false

            val hasPhysicalTransport =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

            if (airplaneMode && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return false
            }
            hasPhysicalTransport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    fun isAirplaneModeActive(): Boolean {
        val ctx = context ?: return false
        return try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Exception) {
            false
        }
    }

    fun getLocalLanIpv4Address(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.")) return ip
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun registerDirectEndpointForPeer(yggIpv6OrKey: String, hostPort: String) {
        val clean = hostPort.trim()
        if (!clean.contains(":")) return
        val host = clean.substringBeforeLast(":").removeSurrounding("[", "]")
        val port = clean.substringAfterLast(":").toIntOrNull() ?: 51921
        if (host.isNotBlank() && port in 1..65535) {
            directEndpointTable[yggIpv6OrKey.lowercase()] = InetSocketAddress.createUnresolved(host, port)
        }
    }

    fun getActivePortsSummary(): String {
        val ports = (listOf(localTcpPort) + activeProfilePortMap.values).distinct().sorted()
        return ports.joinToString(", ") { ":$it" }
    }

    /**
     * Synchronizes background TCP listeners for all user profiles that have `isActiveInBackground = true`.
     * Allows 2 or more profiles to run simultaneously in the background and communicate over real TCP sockets!
     */
    fun syncConcurrentProfileListeners(
        profiles: List<UserProfileEntity>,
        persistedPeerUris: List<String> = emptyList()
    ) {
        scope.launch {
            ipv6ToProfileIdMap.clear()
            profileRelayRoleMap.clear()
            pubKeyToProfileIdMap.clear()
            profiles.forEach { prof ->
                ipv6ToProfileIdMap[prof.yggIpv6Address.lowercase()] = prof.profileId
                profileRelayRoleMap[prof.profileId] = prof.nodeRelayRole
                profileIdToPubKeyMap[prof.profileId] = prof.yggPublicKeyHex
                pubKeyToProfileIdMap[prof.yggPublicKeyHex.trim().lowercase()] = prof.profileId
            }
            // Re-apply what the live cores reported: a stored row can carry a blank or
            // legacy address, and dropping the node's own address here made the bridge
            // stop recognising frames addressed to itself.
            profiles.forEach { prof ->
                coreIdentityByProfileId[prof.profileId]?.let { (coreKeyHex, coreAddress) ->
                    if (coreKeyHex.isNotBlank()) {
                        profileIdToPubKeyMap[prof.profileId] = coreKeyHex
                        pubKeyToProfileIdMap[coreKeyHex] = prof.profileId
                    }
                    if (coreAddress.isNotBlank()) ipv6ToProfileIdMap[coreAddress] = prof.profileId
                }
            }

            // Close sockets for profiles no longer active in background
            val activeIds = profiles.filter { it.isActiveInBackground || it.isCurrentPrimary }.map { it.profileId }.toSet()
            val toStop = secondaryProfileSockets.keys.filter { it !in activeIds }
            for (id in toStop) {
                secondaryProfileSockets.remove(id)?.let { (srv, job) ->
                    job.cancel()
                    try {
                        srv.close()
                    } catch (_: Exception) {
                    }
                }
                activeProfilePortMap.remove(id)
            }

            // Primary profile uses `localTcpPort`
            val primaryProf = profiles.find { it.isCurrentPrimary } ?: profiles.firstOrNull()
            if (primaryProf != null) {
                // The promoted primary is now served by the main listener; drop its stale secondary socket
                secondaryProfileSockets.remove(primaryProf.profileId)?.let { (srv, job) ->
                    job.cancel()
                    try {
                        srv.close()
                    } catch (_: Exception) {
                    }
                }
                activeProfilePortMap[primaryProf.profileId] = localTcpPort
                directEndpointTable.remove(primaryProf.yggIpv6Address.lowercase())
            }

            // Bind dedicated TCP server sockets for all additional active profiles
            for (prof in profiles) {
                if (prof.profileId == primaryProf?.profileId) continue
                directEndpointTable.remove(prof.yggIpv6Address.lowercase())
                if (!prof.isActiveInBackground) {
                    continue
                }
                val existing = secondaryProfileSockets[prof.profileId]
                if (existing != null && !existing.first.isClosed) {
                    val boundPort = existing.first.localPort
                    activeProfilePortMap[prof.profileId] = boundPort
                    continue
                }

                var boundServer: ServerSocket? = null
                val preferredPorts = listOf(prof.allocatedTcpPort) + (51922..51938).toList()
                for (candidate in preferredPorts.distinct()) {
                    if (candidate == localTcpPort || activeProfilePortMap.values.contains(candidate)) continue
                    try {
                        val srv = ServerSocket()
                        srv.reuseAddress = true
                        srv.bind(InetSocketAddress("0.0.0.0", candidate))
                        boundServer = srv
                        break
                    } catch (_: Exception) {
                    }
                }

                if (boundServer != null) {
                    val actualPort = boundServer.localPort
                    activeProfilePortMap[prof.profileId] = actualPort

                    val acceptJob = launch {
                        while (isActive && !boundServer.isClosed) {
                            try {
                                val client = boundServer.accept()
                                launch {
                                    handleIncomingTcpConnection(client, recipientProfileId = prof.profileId, recipientIpv6 = prof.yggIpv6Address)
                                }
                            } catch (_: Exception) {
                                // Transient accept error: wait instead of spinning on accept().
                                // A closed socket leaves the loop, and the next profile sync
                                // re-binds it.
                                delay(300)
                            }
                        }
                    }
                    secondaryProfileSockets[prof.profileId] = boundServer to acceptJob
                    logPacket(
                        direction = "SYS • MULTI",
                        summary = "Профиль «${prof.displayName}» активен параллельно на TCP :$actualPort",
                        details = "IPv6: ${prof.yggIpv6Address} • Готов к приему P2P-сообщений, файлов и звонков"
                    )
                }
            }

            // One real Yggdrasil node per profile — inter-profile traffic must
            // traverse the internet overlay, so every profile's node stays up
            // even while another profile is in the foreground.
            // Stored peers outrank catalog guesses: a node dialled only from the
            // hardcoded list ignored the verified peers in the database and came up
            // with zero live links. The catalog stays as the first-run fallback, and a
            // node only dials the first sixteen of them, so the ones whose hello names
            // a host go first — those are the links a filtering provider is least likely
            // to drop, and a fallback that reaches nothing is worth less than a slower
            // one that reaches the overlay at all.
            val dialCandidates = persistedPeerUris.filter { it.isNotBlank() }
                .ifEmpty {
                    YggPeerScannerCatalog.initialCatalog
                        .sortedByDescending {
                            YggNativeCoreBridge.sendsHostnameSni(it.endpointUri, it.sniDomain)
                        }
                        .map { YggNativeCoreBridge.corePeerUri(it.endpointUri, it.sniDomain) }
                }
            // Under the whitelist profile a peer outside 443 cannot be reached, and dialling it
            // anyway is the traffic the profile exists to stop. The count is logged instead of
            // guessed: an overlay that comes up with zero links has to say which rule emptied it.
            val defaultPeerUris = if (YggCoverTransport.whitelistMode) {
                val allowed = dialCandidates.filter { YggCoverTransport.dialsPeer(it) }
                if (allowed.size < dialCandidates.size) {
                    logPacket(
                        direction = "SYS • WHITELIST",
                        summary = "Режим белых списков: ${dialCandidates.size - allowed.size} пиров " +
                            "не обзваниваются — до них нет пути через 443",
                        details = "Линков под профиль осталось: ${allowed.size}",
                        mirror = true
                    )
                }
                allowed
            } else {
                dialCandidates
            }
            val listedProfileIds = profiles.map { it.profileId }.toSet()
            for (prof in profiles) {
                synchronized(nativeCoreStartLock) {
                    ensureNativeCoreForProfile(prof.profileId, prof.mnemonicCsv, defaultPeerUris)
                }
            }
            for (runningId in initializedNativeProfiles.toList()) {
                if (runningId !in listedProfileIds) {
                    stopNativeCoreForProfile(runningId)
                }
            }
        }
    }

    fun start() {
        listenersRequested = true
        pruneStalePartFiles()
        scope.launch {
            if (tcpListenerJob?.isActive == true) return@launch
            tcpListenerJob = launch { runPrimaryTcpListener() }

            if (YggNativeCoreBridge.isNativeLibraryLoaded) {
                logPacket(
                    direction = "SYS • CORE",
                    summary = "Мост работает в режиме «только оверлей Yggdrasil»",
                    details = "Межпрофильные и P2P-сообщения доставляются через интернет нодами 0200::/7 (JNI-ядро на каждый профиль)"
                )
                if (coreWatchdogJob?.isActive != true) {
                    coreWatchdogJob = launch { coreWatchdogLoop() }
                }
            }

            launch {
                applyLanDiscoveryState()
            }
        }
    }

    /**
     * Removing the piece scratch left by a previous run.
     *
     * An inbound stream lives only in this process, so a «.part» file found at startup belongs to
     * nothing that can still be resumed: the sender re-opens the transfer with FILE_BEGIN, which
     * starts the file over. Without this sweep an interrupted large file stayed on disk forever —
     * no message row points at it, so the orphan purge deliberately ignores «.part» files.
     */
    private fun pruneStalePartFiles() {
        val dir = context?.filesDir?.let { File(it, "ygg_attachments") } ?: return
        val live = incomingFileStreams.values.mapTo(mutableSetOf()) { it.partFile.name }
        val children = dir.listFiles { file: File ->
            file.name.startsWith("stream_") && file.name.endsWith(".part") && !live.contains(file.name)
        } ?: return
        var freed = 0L
        children.forEach { file ->
            val size = file.length()
            if (file.delete()) freed += size
        }
        if (freed > 0L) {
            logPacket(
                direction = "STREAM",
                summary = "Удалены осколки незавершённых приёмов предыдущего запуска",
                details = "Освобождено ${freed / 1024} КБ • файлов ${children.size}",
                mirror = true
            )
        }
    }

    /**
     * Binding and accepting on the main P2P socket, and re-binding whenever the socket dies.
     *
     * A closed ServerSocket used to end the accept loop for good: the node kept its overlay
     * links and reported itself online while answering nothing on TCP. The loop now treats a
     * dead listener as a normal event — back off, bind again, keep serving — and only stops
     * when the app asked for it.
     */
    private suspend fun CoroutineScope.runPrimaryTcpListener() {
        while (isActive && listenersRequested) {
            var boundServer: ServerSocket? = null
            for (candidatePort in 51921..51930) {
                if (!listenersRequested) return
                try {
                    val srv = ServerSocket()
                    srv.reuseAddress = true
                    srv.bind(InetSocketAddress("0.0.0.0", candidatePort))
                    boundServer = srv
                    localTcpPort = candidatePort
                    break
                } catch (_: Exception) {
                }
            }
            tcpServerSocket = boundServer

            if (boundServer == null) {
                // No port was free at this moment — another instance is starting, or the
                // radio is still down. Say so, and try again instead of going silent.
                logPacket(
                    direction = "ERR • P2P",
                    summary = "Основной TCP-сокет P2P не открыт: все порты 51921-51930 недоступны",
                    details = "Приём по TCP отсутствует; повторная привязка через 15 с"
                )
                delay(15_000)
                continue
            }

            val lanIp = getLocalLanIpv4Address() ?: "0.0.0.0"
            logPacket(
                direction = "SYS • P2P",
                summary = "Открыт основной TCP-сокет P2P на $lanIp:$localTcpPort",
                details = "Фоновый сервис Yggdrasil готов к приему пакетов, файлов, голосовых и звонков"
            )

            while (isActive && listenersRequested && !boundServer.isClosed) {
                try {
                    val client = boundServer.accept()
                    launch {
                        handleIncomingTcpConnection(client, recipientProfileId = "", recipientIpv6 = "")
                    }
                } catch (_: Exception) {
                    // A transient accept error must not become a hot spin against the kernel.
                    delay(300)
                }
            }

            if (!isActive || !listenersRequested) return
            logPacket(
                direction = "ERR • P2P",
                summary = "Основной TCP-сокет закрылся: перепривязка портов",
                details = "Приём P2P был прерван; повторная привязка через 2 с"
            )
            delay(2_000)
        }
    }

    // One real Yggdrasil node (JNI core) per active profile; messaging goes
    // strictly through the 0200::/7 overlay, never via loopback shortcuts.
    private val initializedNativeProfiles = ConcurrentHashMap.newKeySet<String>()
    private val nativeCoreStartLock = Any()
    private val overlayRecvJobs = ConcurrentHashMap<String, Job>()
    private val overlayDropCounts = ConcurrentHashMap<String, AtomicLong>()

    // Core watchdog: a node that died after a successful init (or whose init
    // failed, e.g. no network at boot) is restarted from these parameters.
    private val nativeCoreParams = ConcurrentHashMap<String, Pair<String, List<String>>>()
    private val coreRestartCounts = ConcurrentHashMap<String, Int>()
    private val coreNextRetryAt = ConcurrentHashMap<String, Long>()
    private val coreRetryBackoffMs = ConcurrentHashMap<String, Long>()
    private val coreStartAttemptAt = ConcurrentHashMap<String, Long>()
    private val coreDeathStrikes = ConcurrentHashMap<String, Int>()
    private var coreWatchdogJob: Job? = null
    private val pendingOverlayAcks = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Long>>()

    /**
     * Avatar advertisement bookkeeping.
     *
     * [advertisedAvatarHashes] holds, per chat, the last picture the recipient has been
     * CONFIRMED to hold — an ACK, nothing less. Until that confirmation arrives every
     * message to the chat repeats the bytes, so a torn or undelivered frame cannot leave
     * a contact permanently faceless. [pendingAvatarAdverts] maps an in-flight message to
     * what it carried, so whichever transport finally returns the ACK can record it.
     */
    private val advertisedAvatarHashes = ConcurrentHashMap<String, String>()
    private val pendingAvatarAdverts = ConcurrentHashMap<String, Pair<String, String>>()

    /**
     * Base64url of a JPEG capped at [YggAvatarCodec.MAX_JPEG_BYTES], plus the frame's own
     * overhead. Anything longer did not come from this codec and is dropped before it can
     * displace the message it rode in on.
     */
    private fun avatarDataOf(frame: Map<String, String>): String =
        frame["senderAvatarData"].orEmpty().takeIf {
            it.length <= YggAvatarCodec.MAX_JPEG_BYTES * 2 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
        }.orEmpty()

    /**
     * A roster is merged from whatever a peer advertises, so it is capped and shape-
     * checked: one frame from an unfamiliar node must not be able to plant thousands of
     * phantom members in a chat list.
     */
    private fun rosterOf(frame: Map<String, String>): String =
        frame["groupMembers"].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.contains(':') }
            .distinct()
            .take(64)
            .joinToString(",")

    /**
     * A group id ends up inside a primary key, so it is shape-checked and bounded: an
     * unfamiliar peer must not be able to mint an arbitrarily long identifier.
     */
    private fun groupIdOf(frame: Map<String, String>): String =
        frame["groupId"].orEmpty().trim().lowercase()
            .takeIf { it.length <= 64 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
            .orEmpty()

    private fun markAvatarAdvertised(messageId: String) {
        pendingAvatarAdverts.remove(messageId)?.let { (chatId, hash) ->
            advertisedAvatarHashes[chatId] = hash
        }
    }
    // Reaction ACKs must never be confused with message ACKs: a reaction frame carries
    // the id of the message it reacts to, and completing a message ACK with it would
    // falsely upgrade that message to DELIVERED.
    private val pendingReactionAcks = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Long>>()
    // A delete is keyed by its own event id, exactly like a reaction: the ACK for a delete of
    // message X must not be read as the delivery ACK of message X.
    private val pendingDeleteAcks = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Long>>()
    private val profileIdToPubKeyMap = ConcurrentHashMap<String, String>() // profileId -> Ed25519 node pubkey hex
    private val pubKeyToProfileIdMap = ConcurrentHashMap<String, String>() // Ed25519 node pubkey hex -> profileId
    // What the running node reported about itself, kept across profile syncs: the stored
    // profile row can lag behind the live identity.
    private val coreIdentityByProfileId = ConcurrentHashMap<String, Pair<String, String>>() // profileId -> (Ed25519 hex, IPv6)

    /**
     * Resolves the Ed25519 node key the overlay must address.
     *
     * The mesh routes by node key, so a key that belongs to a local profile always wins
     * over the one stored in the conversation: the latter can still be a legacy
     * placeholder, which would silently route the frame to a node that does not exist.
     */
    private fun resolveRecipientNodeKey(peerIpv6: String, storedKeyHex: String): String {
        val addr = peerIpv6.trim().lowercase()
        val key = storedKeyHex.trim().lowercase()
        ipv6ToProfileIdMap[addr]?.let { profileId ->
            profileIdToPubKeyMap[profileId]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        }
        if (key.isNotBlank()) {
            pubKeyToProfileIdMap[key]?.let { profileId ->
                profileIdToPubKeyMap[profileId]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
            }
        }
        // Outside the local profile table a key must reproduce the peer's overlay
        // address; otherwise it is a legacy placeholder the mesh cannot route to.
        if (key.isBlank()) return ""
        return if (YggCryptoEngine.normalizeIpv6Literal(addr) != null) {
            key.takeIf { YggCryptoEngine.addressMatchesEd25519Key(addr, it) }.orEmpty()
        } else {
            key
        }
    }

    // Sender retries can deliver the same wire frame more than once (the ACK may be
    // lost even when the message arrived), so keep recently seen frame ids to
    // deduplicate incoming MSG frames before they reach the message store.
    private val recentIncomingOverlayMessages = ConcurrentHashMap<String, Long>()

    private fun pruneRecentIncomingOverlayMessages() {
        if (recentIncomingOverlayMessages.size < 2048) return
        val cutoff = System.currentTimeMillis() - 10 * 60_000L
        recentIncomingOverlayMessages.entries.removeIf { it.value < cutoff }
    }

    /**
     * Boots (once per profile) a real Yggdrasil Go core node for [profileId] with its own keys,
     * joins the configured Yggdrasil peers and starts the overlay receive loop.
     * Best-effort: when the native library is absent the Kotlin transport keeps working.
     */
    fun ensureNativeCoreForProfile(
        profileId: String,
        mnemonicCsv: String,
        peerUris: List<String>
    ) {
        if (!YggNativeCoreBridge.isNativeLibraryLoaded) return
        if (profileId.isBlank() || mnemonicCsv.isBlank()) return
        if (!initializedNativeProfiles.add(profileId)) return
        scope.launch {
            val filesDir = context?.filesDir?.absolutePath
            if (filesDir == null) {
                initializedNativeProfiles.remove(profileId)
                return@launch
            }
            // Go HMACs the raw string; Kotlin derives identity from words joined
            // by single spaces. Normalize here so both sides always reach the same key.
            val normalizedMnemonic = mnemonicCsv.split(" ").filter { it.isNotBlank() }.joinToString(" ")
            val uniqueUris = peerUris.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(16)
            nativeCoreParams[profileId] = normalizedMnemonic to uniqueUris
            coreStartAttemptAt[profileId] = System.currentTimeMillis()
            val initResult = YggNativeCoreBridge.initCore(
                profileId,
                normalizedMnemonic,
                filesDir
            )
            if (!initResult.ok) {
                initializedNativeProfiles.remove(profileId)
                scheduleCoreRetryBackoff(profileId)
                logPacket(
                    direction = "ERR • CORE",
                    summary = "Нода Yggdrasil профиля не инициализирована — watchdog повторит попытку",
                    details = "profile=$profileId • ${initResult.error}"
                )
                return@launch
            }
            coreRetryBackoffMs.remove(profileId)
            coreNextRetryAt.remove(profileId)
            coreDeathStrikes.remove(profileId)
            // The running node's real key/address are authoritative for routing; if the
            // stored identity drifted (legacy mnemonic formatting), correct the maps so
            // frames addressed to the live node are recognized.
            val corePubKeyHex = YggNativeCoreBridge.extractJsonField(initResult.rawJson, "publicKeyHex").lowercase()
            val coreIpv6 = YggNativeCoreBridge.extractJsonField(initResult.rawJson, "ipv6Address").lowercase()
            if (corePubKeyHex.isNotBlank()) {
                val advertisedKey = profileIdToPubKeyMap[profileId]
                if (advertisedKey != null && !advertisedKey.equals(corePubKeyHex, ignoreCase = true)) {
                    logPacket(
                        direction = "SYS • CORE",
                        summary = "Ключ ноды профиля исправлен по данным ядра",
                        details = "profile=$profileId • ожидался ${advertisedKey.take(16)}…, ядро использует ${corePubKeyHex.take(16)}…"
                    )
                }
                profileIdToPubKeyMap[profileId] = corePubKeyHex
                if (coreIpv6.isNotBlank()) {
                    ipv6ToProfileIdMap[coreIpv6] = profileId
                }
                coreIdentityByProfileId[profileId] = corePubKeyHex to coreIpv6
            }
            var joinedPeers = 0
            val dials = uniqueUris.flatMap { coreDialsFor(it) }
            for (dialUri in dials) {
                val added = YggNativeCoreBridge.addPeer(profileId, dialUri)
                if (added.ok) joinedPeers++
            }
            logPacket(
                direction = "SYS • CORE",
                summary = "Нода Yggdrasil профиля активна в сети 0200::/7 (JNI, Go)",
                details = "profile=$profileId • Пиров подключено: $joinedPeers из ${dials.size} • " +
                    coverTransportSummary() +
                    " • Доставка сообщений — только через интернет-оверлей Yggdrasil"
            )
            startOverlayRecvLoop(profileId)
        }
    }

    /**
     * Public links the running nodes finished an overlay handshake on: 0 means the node has
     * no route to the overlay, -1 that no core is running to ask. The DB peer flags say
     * neither, and an open socket to a peer says less than nothing.
     */
    fun coreLiveLinkCount(): Int {
        var best = -1
        for (profileId in initializedNativeProfiles.toList()) {
            val mesh = YggNativeCoreBridge.readMeshState(profileId)
            if (mesh.available) best = maxOf(best, mesh.liveLinks)
        }
        return best
    }

    /**
     * The dial list as the core receives it.
     *
     * Outside the whitelist profile a peer gets both its own URI and, when the mask is on, a
     * loopback endpoint whose TLS handshake comes from the phone's stack: the covered link is an
     * extra chance against an allow-list, not a replacement, so a node reachable only over
     * cleartext tcp:// keeps working on an unfiltered uplink. Under the profile the two routes
     * stop being the same offer - a tcp:// peer outside 443 has no path the filter forwards
     * except the mask, so only the masked endpoint is dialled and the cleartext handshake that
     * gets dropped is not produced at all. If even the local endpoint cannot be bound the raw
     * dial is returned: a link that might work beats a dial list the node never tries.
     */
    private fun coreDialsFor(peerUri: String): List<String> {
        val dial = peerUri.trim()
        if (dial.isBlank()) return emptyList()
        val plan = YggCoverTransport.planFor(dial) ?: return listOf(dial)
        val endpoint = YggCoverTransport.coverEndpoint(plan) ?: return listOf(dial)
        return if (YggCoverTransport.maskReplacesRawDial(dial)) listOf(endpoint)
        else listOf(dial, endpoint)
    }

    private fun coverTransportSummary(): String {
        if (!YggCoverTransport.enabled) return "Маска TLS со стека телефона выключена"
        val error = YggCoverTransport.lastError
        return "Маска TLS со стека телефона: ${YggCoverTransport.activeEndpoints()} эндпоинтов" +
            if (error.isNullOrBlank()) "" else " • последняя ошибка рукопожатия: $error"
    }

    /**
     * Rebuilds only the cover links of the running nodes after the switch changed. The
     * peer's own dial is left alone: switching the mask off must not drop the connection that
     * already carried traffic, and switching it on must not renumber the whole dial list.
     */
    suspend fun refreshCoverLinks(peerUris: List<String>): Int = withContext(Dispatchers.IO) {
        val profiles = initializedNativeProfiles.toList()
        YggCoverTransport.coveredUris().forEach { (realUri, endpoint) ->
            profiles.forEach { YggNativeCoreBridge.removePeer(it, endpoint) }
            YggCoverTransport.release(realUri)
        }
        var added = 0
        if (YggCoverTransport.enabled) {
            for (uri in peerUris) {
                val extra = coreDialsFor(uri).filterNot { it == uri.trim() }
                if (extra.isEmpty()) continue
                for (profileId in profiles) {
                    extra.forEach { if (YggNativeCoreBridge.addPeer(profileId, it).ok) added++ }
                }
            }
            profiles.forEach { YggNativeCoreBridge.retryPeers(it) }
        }
        logPacket(
            direction = "SYS • CORE",
            summary = if (YggCoverTransport.enabled) "Маска TLS со стека телефона переприменена"
            else "Маска TLS со стека телефона снята",
            details = coverTransportSummary() + " • cover-линков добавлено: $added"
        )
        added
    }

    /**
     * Applies a peer add/remove to every running node. Persisting the Room row alone
     * leaves the live node dialling its old list until the app restarts, so «подключить
     * пир» used to claim success while no new link ever appeared and calls had no route.
     */
    suspend fun applyPeerChangeToCores(
        peerUri: String,
        enabled: Boolean,
        sniDomain: String = ""
    ): PeerApplyOutcome = withContext(Dispatchers.IO) {
        val uri = peerUri.trim()
        val profiles = initializedNativeProfiles.toList()
        if (uri.isBlank() || profiles.isEmpty()) {
            return@withContext PeerApplyOutcome(
                applied = 0,
                liveLinks = 0,
                totalLinks = 0,
                errorDetail = if (uri.isBlank()) "Пустой адрес пира" else "Нода Yggdrasil не запущена"
            )
        }
        // What the core is actually told to dial, including the SNI it will put in the
        // ClientHello. Removing still matches without it: yggdrasil identifies a link by
        // scheme/host/port and ignores the query.
        val dialUri = YggNativeCoreBridge.corePeerUri(uri, sniDomain)
        // Under the whitelist profile the first entry can be the masked endpoint rather than the
        // peer's own URI - the cleartext dial is the one the filter drops, so it is not offered.
        val dials = coreDialsFor(dialUri).ifEmpty { listOf(dialUri) }
        val primaryDial = dials.first()
        var applied = 0
        var live = 0
        var total = 0
        var failure: String? = null
        for (profileId in profiles) {
            val res = if (enabled) {
                YggNativeCoreBridge.addPeer(profileId, primaryDial)
            } else {
                YggNativeCoreBridge.removePeer(profileId, primaryDial)
            }
            // "peer is already configured" is the state the caller asked for, not a
            // failure. The autopilot re-adds its best peer on every pass, so reporting
            // it as «пир не добавлен» filled the journal with false alarms while the
            // link was up the whole time.
            val alreadyConfigured = res.error.contains("already configured", ignoreCase = true)
            if (res.ok || alreadyConfigured) {
                applied++
            } else {
                failure = res.error.ifBlank { "ядро отклонило пир" }
            }
            // The extra cover link is best-effort and never decides the outcome: the peer the
            // user toggled is the one reported, and a cover endpoint that was simply never
            // added must not turn a successful removal into an error line. Removal takes both
            // spellings of the peer, including a raw dial the node added before the profile
            // was switched on.
            if (enabled) {
                dials.filterNot { it == primaryDial }
                    .forEach { YggNativeCoreBridge.addPeer(profileId, it) }
            } else {
                if (primaryDial != dialUri) {
                    YggNativeCoreBridge.removePeer(profileId, dialUri)
                }
                YggCoverTransport.coveredUri(dialUri)
                    ?.let { YggNativeCoreBridge.removePeer(profileId, it) }
            }
            val mesh = YggNativeCoreBridge.readMeshState(profileId)
            live = maxOf(live, mesh.liveLinks)
            total = maxOf(total, mesh.totalLinks)
        }
        if (!enabled) YggCoverTransport.release(dialUri)
        if (enabled && applied > 0) {
            // The core dials new peers on its own backoff timer; interrupt it so the
            // link the user just asked for is attempted now rather than minutes later.
            profiles.forEach { YggNativeCoreBridge.retryPeers(it) }
        }
        logPacket(
            direction = if (applied > 0) "SYS • CORE" else "ERR • CORE",
            summary = when {
                applied == 0 -> if (enabled) "Пир не добавлен в работающую ноду" else "Пир не удалён из работающей ноды"
                enabled -> "Пир добавлен в работающую ноду Yggdrasil"
                else -> "Пир удалён из работающей ноды Yggdrasil"
            },
            details = "$dialUri • линков ядра с рукопожатием: $live из $total" +
                (failure?.let { " • $it" } ?: "")
        )
        PeerApplyOutcome(applied, live, total, failure)
    }

    fun stopNativeCoreForProfile(profileId: String) {
        nativeCoreParams.remove(profileId)
        coreRestartCounts.remove(profileId)
        coreRetryBackoffMs.remove(profileId)
        coreNextRetryAt.remove(profileId)
        coreStartAttemptAt.remove(profileId)
        coreDeathStrikes.remove(profileId)
        if (!initializedNativeProfiles.remove(profileId)) return
        overlayRecvJobs.remove(profileId)?.cancel()
        scope.launch {
            val stopped = YggNativeCoreBridge.stopCore(profileId)
            logPacket(
                direction = "SYS • CORE",
                summary = "Нода Yggdrasil профиля остановлена",
                details = "profile=$profileId • ok=${stopped.ok}"
            )
        }
    }

    private fun startOverlayRecvLoop(profileId: String) {
        if (overlayRecvJobs[profileId]?.isActive == true) return
        val job = scope.launch {
            while (isActive && initializedNativeProfiles.contains(profileId)) {
                val pollResult = try {
                    YggNativeCoreBridge.pollOverlay(profileId, 300)
                } catch (_: Throwable) {
                    continue
                }
                if (!pollResult.ok) {
                    if (pollResult.error.contains("not initialized")) {
                        initializedNativeProfiles.remove(profileId)
                        scheduleCoreRetryBackoff(profileId)
                        break
                    }
                    continue
                }
                // An idle poll is not a lost frame: the core answers {"ok":true,"timeout":true}
                // when nothing arrived, and counting that as loss buried the real drops
                // under thousands of phantom ones.
                if (pollResult.rawJson.contains("\"timeout\":true")) continue
                val decoded = YggNativeCoreBridge.decodeOverlayPayload(pollResult.rawJson)
                if (decoded == null) {
                    noteOverlayDrop(profileId, "payload оверлея не раскодирован")
                    continue
                }
                val (payload, senderPubKeyHex) = decoded
                try {
                    handleOverlayFrame(profileId, payload, senderPubKeyHex)
                } catch (t: Throwable) {
                    noteOverlayDrop(
                        profileId,
                        "обработка кадра упала: ${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}"
                    )
                }
            }
            // Self-cleanup so a watchdog re-init can start a fresh recv loop.
            overlayRecvJobs.remove(profileId, coroutineContext[Job])
        }
        overlayRecvJobs[profileId] = job
    }

    /**
     * Frames the node received but the app could not use. Without this the drops are
     * invisible: every counter keeps counting only what survived parsing, so a link
     * that loses half its traffic still looks perfectly healthy.
     */
    internal fun noteOverlayDrop(profileId: String, reason: String) {
        val counter = overlayDropCounts.getOrPut(profileId) { AtomicLong() }
        val total = counter.incrementAndGet()
        // A broken link would flood the journal one line per frame; the first drop and
        // then every tenth carry the running number the operator actually needs.
        if (total == 1L || total % 10L == 0L) {
            logPacket(
                direction = "ERR • YGG",
                summary = "Кадр оверлея потерян — всего потерь: $total",
                details = "profile=$profileId • $reason"
            )
        }
    }

    fun coreRestartCount(profileId: String): Int = coreRestartCounts[profileId] ?: 0

    /**
     * Frames the nodes received but the app could not use, summed over profiles.
     * The RX counter only counts what survived parsing; without this number a link
     * that loses traffic looks identical to one that does not.
     */
    fun overlayDropTotal(): Long = overlayDropCounts.values.sumOf { it.get() }

    /**
     * Drops every in-memory core bookkeeping so a panic-wipe leaves no profile
     * that the watchdog could silently bring back. The watchdog loop itself
     * stays alive and simply finds an empty parameter map.
     */
    fun panicResetCoreState() {
        nativeCoreParams.clear()
        coreRestartCounts.clear()
        coreNextRetryAt.clear()
        coreRetryBackoffMs.clear()
        coreStartAttemptAt.clear()
        coreDeathStrikes.clear()
        overlayRecvJobs.values.forEach { it.cancel() }
        overlayRecvJobs.clear()
        overlayDropCounts.clear()
        initializedNativeProfiles.clear()
    }

    private fun scheduleCoreRetryBackoff(profileId: String) {
        val prev = coreRetryBackoffMs[profileId] ?: 0L
        val next = if (prev <= 0L) {
            CORE_RETRY_MIN_BACKOFF_MS
        } else {
            (prev * 2).coerceAtMost(CORE_RETRY_MAX_BACKOFF_MS)
        }
        coreRetryBackoffMs[profileId] = next
        coreNextRetryAt[profileId] = System.currentTimeMillis() + next
    }

    /**
     * The Go node runs in-process, so its disappearance from the core's engine
     * map (failed init, stop, native error) is the honest death signal. The
     * watchdog restarts such a node with capped backoff and exposes the exact
     * restart count to the UI.
     */
    private suspend fun coreWatchdogLoop() {
        while (true) {
            delay(CORE_WATCHDOG_INTERVAL_MS)
            currentCoroutineContext().ensureActive()
            for ((profileId, params) in nativeCoreParams) {
                if (initializedNativeProfiles.contains(profileId)) {
                    // A node that just started may not be in the engine map yet.
                    if (System.currentTimeMillis() - (coreStartAttemptAt[profileId] ?: 0L) < CORE_START_GRACE_MS) continue
                    if (YggNativeCoreBridge.readMeshState(profileId).available) {
                        coreDeathStrikes.remove(profileId)
                        // A dead reader is invisible everywhere else: the node reports
                        // live links while incoming frames pile up unread in the core.
                        if (overlayRecvJobs[profileId]?.isActive != true) {
                            logPacket(
                                direction = "ERR • YGG",
                                summary = "Приёмник оверлея профиля не работал — перезапущен",
                                details = "profile=$profileId • потерь кадров на сейчас: ${overlayDropCounts[profileId]?.get() ?: 0L}"
                            )
                            startOverlayRecvLoop(profileId)
                        }
                        continue
                    }
                    // One failed query can be a JNI hiccup; only a second consecutive
                    // failure is treated as death, because restarting a live node drops
                    // every overlay session it holds.
                    val strikes = (coreDeathStrikes[profileId] ?: 0) + 1
                    coreDeathStrikes[profileId] = strikes
                    if (strikes < CORE_DEATH_STRIKES_BEFORE_RESTART) continue
                    coreDeathStrikes.remove(profileId)
                    initializedNativeProfiles.remove(profileId)
                    overlayRecvJobs.remove(profileId)?.cancel()
                    val count = (coreRestartCounts[profileId] ?: 0) + 1
                    coreRestartCounts[profileId] = count
                    logPacket(
                        direction = "SYS • CORE",
                        summary = "Ядро ноды профиля пропало — перезапуск №$count",
                        details = "profile=$profileId"
                    )
                    synchronized(nativeCoreStartLock) {
                        ensureNativeCoreForProfile(profileId, params.first, params.second)
                    }
                } else {
                    val nextAt = coreNextRetryAt[profileId] ?: continue
                    if (System.currentTimeMillis() < nextAt) continue
                    synchronized(nativeCoreStartLock) {
                        ensureNativeCoreForProfile(profileId, params.first, params.second)
                    }
                }
            }
        }
    }

    /**
     * Public peer links flap, so the node is sometimes left without a live peer
     * even though the network is up. Interrupts the core's reconnect backoff
     * and waits briefly for a peer to come back before the send is rejected.
     */
    private suspend fun awaitLivePeer(profileId: String, waitMs: Long = 15_000L): Int {
        var live = YggNativeCoreBridge.livePeerCount(profileId)
        if (live > 0) return live
        YggNativeCoreBridge.retryPeers(profileId)
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            delay(1500L)
            YggNativeCoreBridge.retryPeers(profileId)
            live = YggNativeCoreBridge.livePeerCount(profileId)
            if (live > 0) return live
        }
        return live
    }

    private fun sendOverlayFrame(senderProfileId: String, destPubKeyHex: String, frame: String, kind: String): Boolean {
        if (destPubKeyHex.isBlank()) return false
        val result = YggNativeCoreBridge.sendOverlay(
            senderProfileId,
            destPubKeyHex,
            frame.toByteArray(StandardCharsets.UTF_8)
        )
        if (!result.ok) {
            logPacket(
                direction = "ERR • TX",
                summary = "Отправка $kind через оверлей Yggdrasil не удалась",
                details = "dest=${destPubKeyHex.take(16)}... • ${result.error}"
            )
        }
        return result.ok
    }

    /**
     * Streams a large Base64 attachment as ACKed chunk datagrams. Returns true only
     * when every chunk was explicitly confirmed by the recipient node.
     */
    private suspend fun sendAttachmentChunksOverOverlay(
        senderProfileId: String,
        recipientKeyHex: String,
        transferId: String,
        chatId: String,
        recipientIpv6: String,
        senderIpv6: String,
        senderPubKeyHex: String,
        senderNodeLabel: String,
        attachmentBase64: String,
        attachmentName: String?,
        attachmentType: String?
    ): Boolean {
        val totalChars = attachmentBase64.length
        val chunkCount = (totalChars + ATTACHMENT_CHUNK_CHARS - 1) / ATTACHMENT_CHUNK_CHARS
        val attachmentSha = YggCryptoEngine.sha256Hex(attachmentBase64)

        fun buildFrame(index: Int): String {
            val start = index * ATTACHMENT_CHUNK_CHARS
            val end = (start + ATTACHMENT_CHUNK_CHARS).coerceAtMost(totalChars)
            return encodeWireFrame(
                mapOf(
                    "type" to "ATTACH_CHUNK",
                    "transferId" to transferId,
                    "messageId" to transferId,
                    "chunkIndex" to index.toString(),
                    "chunkCount" to chunkCount.toString(),
                    "chunkData" to attachmentBase64.substring(start, end),
                    "totalBase64Chars" to totalChars.toString(),
                    "attachmentSha256" to attachmentSha,
                    "attachmentName" to (attachmentName ?: ""),
                    "attachmentType" to (attachmentType ?: ""),
                    "chatId" to chatId,
                    "recipientIpv6" to recipientIpv6,
                    "senderIpv6" to senderIpv6,
                    "senderPubKeyHex" to senderPubKeyHex,
                    "senderNodeLabel" to senderNodeLabel,
                    "sentAt" to System.currentTimeMillis().toString()
                )
            )
        }

        var confirmed = 0
        var cursor = 0
        while (cursor < chunkCount) {
            val batchEnd = (cursor + ATTACHMENT_CHUNK_WINDOW).coerceAtMost(chunkCount)
            val awaited = mutableListOf<Pair<Int, kotlinx.coroutines.CompletableDeferred<Long>>>()
            for (index in cursor until batchEnd) {
                val deferred = kotlinx.coroutines.CompletableDeferred<Long>()
                pendingChunkAcks["$transferId:$index"] = deferred
                if (sendOverlayFrame(senderProfileId, recipientKeyHex, buildFrame(index), "CHUNK ${index + 1}/$chunkCount")) {
                    awaited += index to deferred
                } else {
                    pendingChunkAcks.remove("$transferId:$index", deferred)
                }
            }
            var batchComplete = true
            for ((index, deferred) in awaited) {
                var ack = kotlinx.coroutines.withTimeoutOrNull(ATTACHMENT_CHUNK_ACK_TIMEOUT_MS) { deferred.await() }
                if (ack == null) {
                    val retryDeferred = kotlinx.coroutines.CompletableDeferred<Long>()
                    pendingChunkAcks["$transferId:$index"] = retryDeferred
                    if (sendOverlayFrame(senderProfileId, recipientKeyHex, buildFrame(index), "CHUNK ${index + 1}/$chunkCount")) {
                        ack = kotlinx.coroutines.withTimeoutOrNull(ATTACHMENT_CHUNK_ACK_TIMEOUT_MS) { retryDeferred.await() }
                    }
                }
                pendingChunkAcks.remove("$transferId:$index")
                if (ack == null) {
                    batchComplete = false
                } else {
                    confirmed++
                }
            }
            if (!batchComplete) {
                for (index in cursor until chunkCount) pendingChunkAcks.remove("$transferId:$index")
                logPacket(
                    direction = "ERR • ATTACH",
                    summary = "Чанк ${confirmed + 1}/$chunkCount вложения не подтвержден",
                    details = "transferId=${transferId.take(12)} • «${attachmentName ?: "файл"}» • остановка передачи"
                )
                return false
            }
            cursor = batchEnd
        }
        logPacket(
            direction = "TX -> YGG",
            summary = "Вложение «${attachmentName ?: "файл"}» передано чанками ($confirmed/$chunkCount)",
            details = "transferId=${transferId.take(12)} • ${totalChars / 1024} KB Base64 • все чанки подтверждены ACK"
        )
        return confirmed == chunkCount
    }

    // ── Streamed large-file transport ────────────────────────────────────────
    // A file above the inline ceiling never becomes one Base64 blob: it travels as
    // separately encrypted pieces that the receiver writes straight into a .part file at
    // their own offsets. Nothing here holds more than one piece in memory, the peer can
    // restart a broken run at the piece it already has, and the whole file is only
    // accepted once the Merkle root over the written pieces equals the declared one.

    /** The app's own readable copy of an attachment, or null when the Uri is not one. */
    private fun streamedSourceFile(attachmentUri: String?): File? {
        val raw = attachmentUri?.takeIf { it.startsWith("file://") } ?: return null
        val path = try {
            android.net.Uri.parse(raw).path
        } catch (_: Throwable) {
            null
        } ?: return null
        val file = File(path)
        return if (file.isFile && file.canRead() && file.length() > 0L) file else null
    }

    private fun pieceLengthOf(totalBytes: Long, pieceBytes: Int, index: Int): Int {
        val offset = index.toLong() * pieceBytes
        return (totalBytes - offset).coerceAtMost(pieceBytes.toLong()).toInt()
    }

    private fun readPieceAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray? {
        if (length <= 0) return null
        raf.seek(offset)
        val buffer = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = raf.read(buffer, filled, length - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.takeIf { filled == length }
    }

    private fun streamAckFrame(
        kind: String,
        transferId: String,
        haveThrough: Int,
        sentAt: String,
        rootVerified: Boolean? = null,
        refusalReason: String? = null
    ): String = encodeWireFrame(
        buildMap {
            put("type", "ACK")
            put("fileStreamAck", kind)
            put("transferId", transferId)
            put("haveThrough", haveThrough.toString())
            put("sentAt", sentAt)
            rootVerified?.let { put("rootVerified", if (it) "1" else "0") }
            refusalReason?.let { put("streamRefusal", it) }
        }
    )

    /**
     * How long a stream frame waits for its acknowledgement. A fixed number is wrong twice: on a
     * LAN it idles for minutes, on an internet overlay route (measured at tens of seconds here)
     * it abandons a peer that was about to answer. The budget follows the round trip this node
     * actually measured, with headroom and hard bounds.
     */
    private fun streamAckTimeout(minMs: Long): Long =
        (observedOverlayAckRttMs.get() * 4L).coerceIn(minMs, 120_000L)

    /**
     * Registers the waiter before the frame goes out, so an acknowledgement that arrives
     * immediately is never missed. Returns the peer's cumulative piece count, or -1 when the
     * frame was not sent or nothing was confirmed inside the budget.
     */
    private suspend fun askStreamAck(
        transferId: String,
        frame: String,
        kind: String,
        send: () -> Boolean,
        timeoutMs: Long
    ): Int {
        val waiter = kotlinx.coroutines.CompletableDeferred<Int>()
        streamAckWaiters[transferId] = waiter
        outgoingStreamHave.remove(transferId)
        if (!send()) {
            streamAckWaiters.remove(transferId, waiter)
            return -1
        }
        val reported = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { waiter.await() }
        streamAckWaiters.remove(transferId, waiter)
        return reported ?: outgoingStreamHave[transferId] ?: -1
    }

    /**
     * Streams one file to the peer through the overlay. Returns only after every piece was
     * acknowledged and the peer confirmed the Merkle root, so the caller can honestly mark the
     * message delivered; any other outcome comes back with the reason and the piece count lost.
     *
     * A second attempt for a transfer that is already running is refused here rather than
     * started: the two would fight over the single acknowledgement slot of this transfer.
     */
    private suspend fun streamFileOverOverlay(
        senderProfileId: String,
        recipientKeyHex: String,
        transferId: String,
        chatId: String,
        sourceFile: File,
        attachmentName: String?,
        attachmentType: String?,
        peerIpv6: String,
        senderIpv6: String,
        senderPubKeyHex: String,
        senderNodeLabel: String,
        send: (String, String) -> Boolean
    ): FileStreamDispatchResult {
        if (!runningFileStreams.add(transferId)) {
            logPacket(
                direction = "SYS • STREAM",
                summary = "Повторная попытка не запущена: этот файл уже передаётся",
                details = "«${attachmentName ?: sourceFile.name}» • transferId=${transferId.take(12)} • сообщение останется в очереди S&F"
            )
            return FileStreamDispatchResult(
                delivered = false,
                bytesTransferred = 0L,
                errorDetail = "файл уже передаётся этой очередью — повторная попытка не началась"
            )
        }
        try {
            return streamFilePieces(
                senderProfileId = senderProfileId,
                recipientKeyHex = recipientKeyHex,
                transferId = transferId,
                chatId = chatId,
                sourceFile = sourceFile,
                attachmentName = attachmentName,
                attachmentType = attachmentType,
                peerIpv6 = peerIpv6,
                senderIpv6 = senderIpv6,
                senderPubKeyHex = senderPubKeyHex,
                senderNodeLabel = senderNodeLabel,
                send = send
            )
        } finally {
            runningFileStreams.remove(transferId)
        }
    }

    private suspend fun streamFilePieces(
        senderProfileId: String,
        recipientKeyHex: String,
        transferId: String,
        chatId: String,
        sourceFile: File,
        attachmentName: String?,
        attachmentType: String?,
        peerIpv6: String,
        senderIpv6: String,
        senderPubKeyHex: String,
        senderNodeLabel: String,
        send: (String, String) -> Boolean
    ): FileStreamDispatchResult {
        val pieceBytes = YggCryptoEngine.FILE_PIECE_BYTES
        val name = attachmentName ?: sourceFile.name
        val totalBytes = sourceFile.length()
        val streamMb = YggCryptoEngine.MAX_P2P_STREAM_BYTES / (1024 * 1024)
        val streamStartedAt = System.currentTimeMillis()
        fun elapsedMs(): Long = System.currentTimeMillis() - streamStartedAt

        fun failed(reason: String): FileStreamDispatchResult {
            outgoingStreamHave.remove(transferId)
            outgoingStreamVerified.remove(transferId)
            val detail = outgoingStreamRefusal.remove(transferId) ?: reason
            logPacket(
                direction = "ERR • STREAM",
                summary = "Потоковая передача не выполнена: $detail",
                details = "«$name» • ${totalBytes / 1024} КБ • transferId=${transferId.take(12)}"
            )
            scope.launch {
                onFileTransferProgress(
                    FileTransferProgress(chatId, transferId, name, 0L, totalBytes, elapsedMs(), "", true, "FAILED")
                )
            }
            return FileStreamDispatchResult(false, bytesTransferred = totalBytes, errorDetail = detail)
        }

        if (totalBytes > YggCryptoEngine.MAX_P2P_STREAM_BYTES) {
            return failed("файл ${totalBytes / (1024 * 1024)} МБ больше предела потока $streamMb МБ")
        }
        val key = resolveFileTransferKey(senderProfileId, chatId, transferId)
            ?: return failed("нет ключа X25519 собеседника — шифровать нечем")
        val profile = withContext(Dispatchers.IO) {
            YggCryptoEngine.pieceProfileOf(sourceFile, pieceBytes)
        } ?: return failed("файл не читается целиком — контрольные суммы построить нечем")
        val pieceCount = profile.pieceCount

        val beginFrame = encodeWireFrame(
            mapOf(
                "type" to "FILE_BEGIN",
                "transferId" to transferId,
                "chatId" to chatId,
                "attachmentName" to name,
                "attachmentType" to (attachmentType ?: ""),
                "totalBytes" to totalBytes.toString(),
                "pieceBytes" to pieceBytes.toString(),
                "pieceCount" to pieceCount.toString(),
                "merkleRoot" to profile.merkleRootHex,
                "recipientIpv6" to peerIpv6,
                "senderIpv6" to senderIpv6,
                "senderPubKeyHex" to senderPubKeyHex,
                "senderNodeLabel" to senderNodeLabel,
                "sentAt" to System.currentTimeMillis().toString()
            )
        )
        val resumeFrom = askStreamAck(
            transferId = transferId,
            frame = beginFrame,
            kind = "FILE_BEGIN",
            send = { send(beginFrame, "FILE_BEGIN") },
            timeoutMs = streamAckTimeout(FILE_PIECE_ACK_TIMEOUT_MS * 2)
        )
        if (resumeFrom < 0) return failed("адресат не подтвердил начало потока — проверьте маршрут")

        logPacket(
            direction = "TX -> STREAM",
            summary = "Поток файла «$name»: ${pieceCount} кусков по ${pieceBytes / 1024} КБ",
            details = "Merkle SHA-256 ${profile.merkleRootHex.take(16)}… • собеседник продолжит с куска $resumeFrom"
        )

        var cursor = resumeFrom.coerceIn(0, pieceCount)
        var stalledWindows = 0
        var wireBytes = 0L
        RandomAccessFile(sourceFile, "r").use { raf ->
            while (cursor < pieceCount) {
                val windowStart = cursor
                val windowEnd = (cursor + FILE_PIECE_WINDOW).coerceAtMost(pieceCount)
                var windowError: String? = null
                // The window leaves inside the acknowledgement request, so the waiter is already
                // registered when the first piece goes out and a fast ACK can never be missed.
                val sendWindow: () -> Boolean = {
                    var accepted = true
                    for (index in windowStart until windowEnd) {
                        val length = pieceLengthOf(totalBytes, pieceBytes, index)
                        val plain = readPieceAt(raf, index.toLong() * pieceBytes, length)
                        if (plain == null) {
                            windowError = "кусок $index не читается в файле"
                            accepted = false
                            break
                        }
                        val (ivBase64, cipherBase64) = YggCryptoEngine.sealFilePiece(key, plain, transferId, index)
                        val pieceFrame = encodeWireFrame(
                            mapOf(
                                "type" to "FILE_PIECE",
                                "transferId" to transferId,
                                "chatId" to chatId,
                                "pieceIndex" to index.toString(),
                                "pieceBytes" to pieceBytes.toString(),
                                "iv" to ivBase64,
                                "ct" to cipherBase64,
                                "senderIpv6" to senderIpv6,
                                "senderPubKeyHex" to senderPubKeyHex,
                                "sentAt" to (System.currentTimeMillis()).toString()
                            )
                        )
                        wireBytes += pieceFrame.length
                        if (!send(pieceFrame, "FILE_PIECE ${index + 1}/$pieceCount")) {
                            windowError = "оверлей отклонил кусок ${index + 1}/$pieceCount"
                            accepted = false
                            break
                        }
                    }
                    accepted
                }
                val acked = askStreamAck(
                    transferId = transferId,
                    frame = "",
                    kind = "FILE_PIECE",
                    send = sendWindow,
                    timeoutMs = streamAckTimeout(FILE_PIECE_ACK_TIMEOUT_MS)
                )
                windowError?.let { return failed(it) }
                if (acked > cursor) {
                    stalledWindows = 0
                    cursor = acked.coerceAtMost(pieceCount)
                } else {
                    stalledWindows++
                    if (stalledWindows >= FILE_STREAM_MAX_STALLED_WINDOWS) {
                        return failed("куски $windowStart..${windowEnd - 1} не подтверждены адресатом за $stalledWindows попыток")
                    }
                }
                scope.launch {
                    onFileTransferProgress(
                        FileTransferProgress(
                            chatId = chatId,
                            transferId = transferId,
                            fileName = name,
                            doneBytes = (cursor.toLong() * pieceBytes).coerceAtMost(totalBytes),
                            totalBytes = totalBytes,
                            elapsedMs = elapsedMs(),
                            merkleRootHex = profile.merkleRootHex,
                            isOutgoing = true,
                            state = "STREAMING"
                        )
                    )
                }
            }
        }

        val doneFrame = encodeWireFrame(
            mapOf(
                "type" to "FILE_DONE",
                "transferId" to transferId,
                "chatId" to chatId,
                "merkleRoot" to profile.merkleRootHex,
                "senderIpv6" to senderIpv6,
                "senderPubKeyHex" to senderPubKeyHex,
                "sentAt" to System.currentTimeMillis().toString()
            )
        )
        val finalHave = askStreamAck(
            transferId = transferId,
            frame = doneFrame,
            kind = "FILE_DONE",
            send = { send(doneFrame, "FILE_DONE") },
            timeoutMs = streamAckTimeout(FILE_PIECE_ACK_TIMEOUT_MS * 2)
        )
        val verified = outgoingStreamVerified.remove(transferId) == true
        if (finalHave < 0 || !verified) {
            return failed("адресат не подтвердил целостность файла: Merkle корень не совпал или приём прерван")
        }
        outgoingStreamHave.remove(transferId)
        outgoingStreamRefusal.remove(transferId)
        scope.launch {
            onFileTransferProgress(
                FileTransferProgress(chatId, transferId, name, totalBytes, totalBytes, elapsedMs(), profile.merkleRootHex, true, "VERIFIED")
            )
        }
        logPacket(
            direction = "TX -> STREAM",
            summary = "Файл «$name» передан потоком: $pieceCount кусков подтверждены адресатом",
            details = "Merkle SHA-256 ${profile.merkleRootHex.take(24)}… • ${totalBytes / 1024} КБ файла • ${wireBytes / 1024} КБ в эфир"
        )
        return FileStreamDispatchResult(
            delivered = true,
            piecesSent = pieceCount,
            piecesTotal = pieceCount,
            bytesTransferred = totalBytes,
            merkleRootHex = profile.merkleRootHex
        )
    }

    private fun pruneFileStreams() {
        val cutoff = System.currentTimeMillis() - FILE_STREAM_TTL_MS
        incomingFileStreams.entries.toList().forEach { entry ->
            if (entry.value.startedAtMs < cutoff) {
                incomingFileStreams.remove(entry.key)
                entry.value.partFile.delete()
            }
        }
        val completedCutoff = System.currentTimeMillis() - FILE_COMPLETED_LOOKUP_TTL_MS
        completedFileStreams.entries.toList().forEach { entry ->
            if (entry.value.completedAtMs < completedCutoff) {
                completedFileStreams.remove(entry.key)
                try {
                    android.net.Uri.parse(entry.value.localUri).path?.let { File(it).delete() }
                } catch (_: Throwable) {
                }
            }
        }
    }

    /**
     * Opens (or resumes) one inbound stream. The answer is the piece count already on disk:
     * the sender continues there, so an interrupted run costs the re-sent tail, not the file.
     */
    private suspend fun handleFileBegin(
        profileId: String,
        senderPubKeyHex: String,
        map: Map<String, String>,
        reply: (String) -> Unit
    ) {
        val transferId = map["transferId"]?.takeIf { it.isNotBlank() } ?: return
        val declaredRoot = map["merkleRoot"]?.trim()?.lowercase().orEmpty()
        val totalBytes = map["totalBytes"]?.toLongOrNull() ?: -1L
        val pieceBytes = map["pieceBytes"]?.toIntOrNull() ?: YggCryptoEngine.FILE_PIECE_BYTES
        val pieceCount = map["pieceCount"]?.toIntOrNull() ?: 0
        val sentAt = map["sentAt"] ?: System.currentTimeMillis().toString()
        pruneFileStreams()

        val existing = incomingFileStreams[transferId]
        if (existing != null && existing.declaredRoot == declaredRoot && existing.totalBytes == totalBytes) {
            // A re-offered BEGIN is also the sender saying "my last answer never reached me".
            // Let it report the hole once more, and count the new window from where it resumes.
            existing.holeAckedFor = -1
            existing.lastAckedPrefix = existing.nextIndex
            reply(streamAckFrame("begin", transferId, existing.nextIndex, sentAt))
            return
        }

        // The sender's chatId names a row on its own device. Here the dialog is whoever
        // authenticated the session, because that is the conversation holding the pinned key.
        val chatId = resolveIncomingDialogChatId(
            profileId,
            senderPubKeyHex,
            map["senderIpv6"].orEmpty()
        )

        fun refuse(reason: String) {
            // The sender is told why in the same frame, so it stops at once instead of
            // re-offering a window this device will never accept.
            reply(streamAckFrame("refused", transferId, 0, sentAt, refusalReason = reason))
            logPacket(
                direction = "ERR • STREAM",
                summary = "Входящий поток отклонён: $reason",
                details = "«${map["attachmentName"] ?: "файл"}» • transferId=${transferId.take(12)}"
            )
            scope.launch {
                onFileTransferProgress(
                    FileTransferProgress(
                        chatId = chatId.orEmpty(),
                        transferId = transferId,
                        fileName = map["attachmentName"] ?: "файл",
                        doneBytes = 0L,
                        totalBytes = totalBytes.coerceAtLeast(0L),
                        elapsedMs = 0L,
                        merkleRootHex = declaredRoot,
                        isOutgoing = false,
                        state = "FAILED"
                    )
                )
            }
        }
        if (chatId.isNullOrBlank()) return refuse("на этом устройстве нет диалога с данной нодой — поток не принят")
        if (declaredRoot.length != 64 || !declaredRoot.all { it in '0'..'9' || it in 'a'..'f' }) {
            return refuse("обещанный Merkle корень некорректен")
        }
        if (totalBytes <= 0 || totalBytes > YggCryptoEngine.MAX_P2P_STREAM_BYTES) {
            return refuse("размер файла вне предела потока")
        }
        if (pieceBytes <= 0 || pieceBytes > YggCryptoEngine.FILE_PIECE_BYTES) {
            return refuse("размер куска больше разрешённого")
        }
        if (pieceCount <= 0 || pieceCount.toLong() * pieceBytes < totalBytes ||
            (pieceCount - 1).toLong() * pieceBytes >= totalBytes
        ) {
            return refuse("число кусков не сходится с размером файла")
        }
        val storage = this.context ?: return refuse("нет хранилища приложения")
        val attachDir = File(storage.filesDir, "ygg_attachments").apply { mkdirs() }
        val usable = attachDir.usableSpace
        if (usable < totalBytes + 8L * 1024 * 1024) {
            return refuse("на устройстве свободно ${usable / (1024 * 1024)} МБ — меньше, чем нужно файлу")
        }
        val key = resolveFileTransferKey(profileId, chatId, transferId)
            ?: return refuse("для этого диалога нет ключа X25519 — куски нечем проверить")

        val safeName = (map["attachmentName"]?.takeIf { it.isNotBlank() } ?: "file")
            .replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        val partFile = File(attachDir, "stream_${transferId.take(24)}.part")
        incomingFileStreams.remove(transferId)
        if (partFile.exists()) partFile.delete()
        val stream = IncomingFileStream(
            transferId = transferId,
            chatId = chatId,
            fileName = safeName,
            fileType = map["attachmentType"] ?: "",
            totalBytes = totalBytes,
            pieceBytes = pieceBytes,
            pieceCount = pieceCount,
            declaredRoot = declaredRoot,
            key = key,
            partFile = partFile,
            startedAtMs = System.currentTimeMillis()
        )
        incomingFileStreams[transferId] = stream
        logPacket(
            direction = "RX <- STREAM",
            summary = "Принят поток файла «$safeName» (${totalBytes / 1024} КБ, $pieceCount кусков)",
            details = "Ключ проверки — X25519 этого диалога • корень ${declaredRoot.take(16)}…"
        )
        reply(streamAckFrame("begin", transferId, 0, sentAt))
    }

    private suspend fun handleFilePiece(
        map: Map<String, String>,
        reply: (String) -> Unit
    ) {
        val transferId = map["transferId"]?.takeIf { it.isNotBlank() } ?: return
        val index = map["pieceIndex"]?.toIntOrNull() ?: return
        val sentAt = map["sentAt"] ?: System.currentTimeMillis().toString()
        val stream = incomingFileStreams[transferId] ?: return
        // Nothing is acknowledged for an unknown transfer: the sender's window times out and it
        // restarts with FILE_BEGIN, which is exactly the recovery path for a lost stream state.
        if (index < 0 || index >= stream.pieceCount) {
            reply(streamAckFrame("piece", transferId, stream.nextIndex, sentAt))
            return
        }
        if (stream.have[index]) {
            // Already on disk and already authenticated once. Stay silent: answering a duplicate
            // with the unchanged prefix would complete the sender's wait immediately and make it
            // count a window it is about to fill as stalled. The sender learns the real position
            // from the next boundary acknowledgement or from its own FILE_BEGIN resume.
            return
        }
        if (index > stream.nextIndex && stream.holeAckedFor != stream.nextIndex) {
            // A piece beyond the prefix means something before it is missing. Report the prefix
            // once per hole instead of letting the sender burn a whole window timeout on it.
            stream.holeAckedFor = stream.nextIndex
            reply(streamAckFrame("piece", transferId, stream.nextIndex, sentAt))
        }
        val plain = try {
            YggCryptoEngine.openFilePiece(
                key = stream.key,
                ivBase64 = map["iv"] ?: "",
                cipherBase64 = map["ct"] ?: "",
                transferId = transferId,
                index = index
            )
        } catch (_: Throwable) {
            stream.rejects++
            logPacket(
                direction = "ERR • STREAM",
                summary = "Кусок $index не расшифрован ($stream.rejects из ${stream.pieceCount}) — прошу заново",
                details = "transferId=${transferId.take(12)} • бит по дороге или письмо шло не от этого собеседника"
            )
            if (stream.rejects >= FILE_PIECE_WINDOW) {
                // A whole window of bad pieces is not transit noise, it is the wrong key.
                // Keeping the stream would only let the sender pour bytes into a file that
                // can never be verified, so it is dropped and the sender is told why.
                stream.partFile.delete()
                incomingFileStreams.remove(transferId)
                reply(
                    streamAckFrame(
                        "refused",
                        transferId,
                        stream.nextIndex,
                        sentAt,
                        refusalReason = "куски не расшифровываются — файл принят не будет"
                    )
                )
            } else {
                reply(streamAckFrame("piece", transferId, stream.nextIndex, sentAt))
            }
            return
        }
        if (plain.size != pieceLengthOf(stream.totalBytes, stream.pieceBytes, index)) {
            stream.partFile.delete()
            incomingFileStreams.remove(transferId)
            reply(
                streamAckFrame(
                    "refused",
                    transferId,
                    index,
                    sentAt,
                    refusalReason = "длина куска $index не сходится с размером файла"
                )
            )
            return
        }
        val wrote = withContext(Dispatchers.IO) {
            try {
                RandomAccessFile(stream.partFile, "rw").use { raf ->
                    raf.seek(index.toLong() * stream.pieceBytes)
                    raf.write(plain)
                }
                true
            } catch (_: Throwable) {
                false
            }
        }
        if (!wrote) {
            reply(
                streamAckFrame(
                    "refused",
                    transferId,
                    stream.nextIndex,
                    sentAt,
                    refusalReason = "кусок $index не записан на диск"
                )
            )
            return
        }
        stream.digests[index] = YggCryptoEngine.sha256Bytes(plain)
        stream.have[index] = true
        stream.writtenBytes += plain.size
        if (index == stream.nextIndex) {
            while (stream.nextIndex < stream.pieceCount && stream.have[stream.nextIndex]) stream.nextIndex++
        }
        val complete = stream.nextIndex >= stream.pieceCount
        // The sender waits on a cumulative position, not on per-piece receipts, so it is answered
        // once a whole window of new prefix has landed, on completion, and once per hole.
        val reportPrefix = complete || stream.nextIndex - stream.lastAckedPrefix >= FILE_PIECE_WINDOW
        if (reportPrefix) {
            stream.lastAckedPrefix = stream.nextIndex
            stream.holeAckedFor = -1
            reply(streamAckFrame("piece", transferId, stream.nextIndex, sentAt))
        }
        // One progress row per piece would put thousands of coroutines on the queue for a
        // large file, so the bar only advances together with a window acknowledgement.
        if (reportPrefix) {
            val doneBytes = stream.writtenBytes
            val elapsed = System.currentTimeMillis() - stream.startedAtMs
            scope.launch {
                onFileTransferProgress(
                    FileTransferProgress(
                        chatId = stream.chatId,
                        transferId = transferId,
                        fileName = stream.fileName,
                        doneBytes = doneBytes,
                        totalBytes = stream.totalBytes,
                        elapsedMs = elapsed,
                        merkleRootHex = stream.declaredRoot,
                        isOutgoing = false,
                        state = "STREAMING"
                    )
                )
            }
        }
    }

    private suspend fun handleFileDone(
        map: Map<String, String>,
        reply: (String) -> Unit
    ) {
        val transferId = map["transferId"]?.takeIf { it.isNotBlank() } ?: return
        val sentAt = map["sentAt"] ?: System.currentTimeMillis().toString()
        val declared = map["merkleRoot"]?.trim()?.lowercase().orEmpty()
        val stream = incomingFileStreams[transferId]
        if (stream == null) {
            val already = completedFileStreams[transferId]
            if (already != null && already.merkleRootHex == declared) {
                reply(streamAckFrame("done", transferId, already.totalBytes.toInt(), sentAt, true))
            }
            return
        }
        val orderedDigests = stream.digests.filterNotNull()
        val verified = declared == stream.declaredRoot &&
            stream.nextIndex == stream.pieceCount &&
            orderedDigests.size == stream.pieceCount &&
            YggCryptoEngine.merkleRootOfPieceDigests(orderedDigests) == stream.declaredRoot &&
            stream.partFile.length() == stream.totalBytes
        if (!verified) {
            logPacket(
                direction = "ERR • STREAM",
                summary = "Поток «${stream.fileName}» отброшен: контрольная сумма не сошлась",
                details = "целых кусков ${orderedDigests.size}/${stream.pieceCount} • " +
                    "подтверждено подряд ${stream.nextIndex}/${stream.pieceCount} • ${stream.writtenBytes}/${stream.totalBytes} Б"
            )
            incomingFileStreams.remove(transferId)
            stream.partFile.delete()
            reply(streamAckFrame("done", transferId, stream.nextIndex, sentAt, false))
            val failedBytes = stream.writtenBytes
            val totalBytesForReport = stream.totalBytes
            val root = stream.declaredRoot
            val name = stream.fileName
            val owner = stream.chatId
            val elapsed = System.currentTimeMillis() - stream.startedAtMs
            scope.launch {
                onFileTransferProgress(
                    FileTransferProgress(owner, transferId, name, failedBytes, totalBytesForReport, elapsed, root, false, "FAILED")
                )
            }
            return
        }
        val finalFile = File(
            stream.partFile.parentFile,
            "rx_${System.currentTimeMillis()}_${stream.fileName}"
        )
        val moved = withContext(Dispatchers.IO) {
            if (stream.partFile.renameTo(finalFile)) true
            else runCatching {
                stream.partFile.copyTo(finalFile, overwrite = true)
                stream.partFile.delete()
                true
            }.getOrDefault(false)
        }
        incomingFileStreams.remove(transferId)
        if (!moved) {
            reply(streamAckFrame("done", transferId, stream.nextIndex, sentAt, false))
            return
        }
        val uri = android.net.Uri.fromFile(finalFile).toString()
        completedFileStreams[transferId] = CompletedFileStream(
            localUri = uri,
            merkleRootHex = stream.declaredRoot,
            fileName = stream.fileName,
            totalBytes = stream.totalBytes,
            completedAtMs = System.currentTimeMillis()
        )
        logPacket(
            direction = "RX <- STREAM",
            summary = "Файл «${stream.fileName}» принят целиком: Merkle корень совпал",
            details = "${stream.totalBytes / 1024} КБ • ${stream.pieceCount} кусков • SHA-256 ${stream.declaredRoot.take(24)}…"
        )
        reply(streamAckFrame("done", transferId, stream.pieceCount, sentAt, true))
        val owner = stream.chatId
        val name = stream.fileName
        val root = stream.declaredRoot
        val bytes = stream.totalBytes
        val elapsed = System.currentTimeMillis() - stream.startedAtMs
        scope.launch {
            onFileTransferProgress(
                FileTransferProgress(owner, transferId, name, bytes, bytes, elapsed, root, false, "VERIFIED")
            )
        }
    }

    private suspend fun awaitCompletedStream(transferId: String): CompletedFileStream? {
        if (transferId.isBlank()) return null
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            completedFileStreams[transferId]?.let { return it }
            delay(250L)
        }
        return completedFileStreams[transferId]
    }

    private suspend fun handleOverlayFrame(profileId: String, payload: ByteArray, senderPubKeyHex: String) {
        val map = decodeWireFrame(String(payload, StandardCharsets.UTF_8))
        val senderIpv6 = map["senderIpv6"] ?: ""
        when (map["type"]) {
            "TYPING" -> {
                onTwinReachableChanged(true)
                val chatId = map["chatId"] ?: ""
                if (chatId.isNotBlank()) {
                    onTypingReceived(
                        chatId,
                        map["senderNodeLabel"]?.takeIf { it.isNotBlank() } ?: "Собеседник",
                        senderIpv6
                    )
                }
            }
            "MSG" -> {
                onTwinReachableChanged(true)
                val sentAt = map["sentAt"]?.toLongOrNull() ?: System.currentTimeMillis()
                val targetIpv6 = map["recipientIpv6"] ?: ""
                val resolvedProfileId = profileId.ifBlank {
                    ipv6ToProfileIdMap[targetIpv6.lowercase()] ?: ""
                }
                val wireMessageId = map["messageId"] ?: ""
                pruneRecentIncomingOverlayMessages()
                val seenBefore = wireMessageId.isNotBlank() &&
                    recentIncomingOverlayMessages.putIfAbsent(wireMessageId, System.currentTimeMillis()) != null
                val ackFrame = encodeWireFrame(
                    mapOf(
                        "type" to "ACK",
                        "messageId" to wireMessageId,
                        "chatId" to (map["chatId"] ?: ""),
                        "sentAt" to sentAt.toString()
                    )
                )
                if (seenBefore) {
                    // The sender's retry raced with a lost ACK: confirm again but
                    // do not insert the message a second time.
                    sendOverlayFrame(profileId, senderPubKeyHex, ackFrame, "ACK")
                    logPacket(
                        direction = "RX <- YGG",
                        summary = "Дубликат пакета «${wireMessageId.take(12)}» пропущен (ретрай отправителя)",
                        details = "Повторный ACK отправлен ноде ${senderPubKeyHex.take(16)}..."
                    )
                    return
                }
                // The core knows the session's real source key; the frame field is only
                // a claim and must not override it.
                val sessionSenderKey = senderPubKeyHex.trim().lowercase()
                val wireSenderKey = map["senderPubKeyHex"]?.trim()?.lowercase().orEmpty()
                val trustedSenderKey = when {
                    YggCryptoEngine.isEd25519Hex(sessionSenderKey) -> sessionSenderKey
                    YggCryptoEngine.isEd25519Hex(wireSenderKey) -> wireSenderKey
                    else -> ""
                }
                var inlineAttachment = map["attachmentBase64"]?.takeIf { it.isNotEmpty() }
                if (inlineAttachment == null && map["attachmentChunked"] == "1") {
                    inlineAttachment = awaitAssembledAttachment(wireMessageId)
                    if (inlineAttachment == null) {
                        // No ACK: the sender keeps the message queued instead of marking
                        // an attachment-less envelope as delivered. The retry re-sends
                        // the chunks and this MSG frame.
                        logPacket(
                            direction = "ERR • ATTACH",
                            summary = "Вложение ещё не собрано — сообщение не подтверждено",
                            details = "messageId=${wireMessageId.take(12)} • получено ${incomingAttachmentAssemblies[wireMessageId]?.chunks?.size ?: 0} чанков"
                        )
                        recentIncomingOverlayMessages.remove(wireMessageId)
                        return
                    }
                }
                // A streamed file arrived piece by piece before this frame; the MSG only names the
                // transfer. Until the last piece is verified there is nothing to acknowledge with,
                // so the message stays unconfirmed and the sender's queue keeps it.
                var streamedFile = if (map["attachmentChunked"] == "2") awaitCompletedStream(wireMessageId) else null
                if (map["attachmentChunked"] == "2" && streamedFile == null) {
                    logPacket(
                        direction = "ERR • STREAM",
                        summary = "Поток файла ещё не дописан — сообщение не подтверждено",
                        details = "messageId=${wireMessageId.take(12)} • получено ${incomingFileStreams[wireMessageId]?.nextIndex ?: 0} кусков"
                    )
                    recentIncomingOverlayMessages.remove(wireMessageId)
                    return
                }
                val incoming = IncomingTwinPacket(
                    targetChatId = map["chatId"] ?: "",
                    messageId = wireMessageId,
                    recipientProfileId = resolvedProfileId,
                    recipientIpv6 = targetIpv6,
                    senderNodeLabel = map["senderNodeLabel"] ?: "Пир Yggdrasil",
                    senderIpv6 = senderIpv6,
                    senderPubKeyHex = trustedSenderKey,
                    plainText = map["plainText"] ?: "",
                    cipherPayloadHex = map["cipherPayloadHex"] ?: "",
                    hashcashToken = map["hashcashToken"] ?: "",
                    attachmentName = map["attachmentName"]?.takeIf { it.isNotEmpty() },
                    attachmentSizeBytes = streamedFile?.totalBytes ?: (map["attachmentSizeBytes"]?.toLongOrNull() ?: 0L),
                    attachmentType = map["attachmentType"]?.takeIf { it.isNotEmpty() },
                    attachmentUri = streamedFile?.localUri ?: map["attachmentUri"]?.takeIf { it.isNotEmpty() },
                    attachmentBase64 = inlineAttachment,
                    streamedAttachmentRoot = streamedFile?.merkleRootHex ?: "",
                    voiceDurationSec = map["voiceDurationSec"]?.toIntOrNull() ?: 0,
                    voiceWaveformCsv = map["voiceWaveformCsv"]?.takeIf { it.isNotEmpty() },
                    replyToMessageId = map["replyToMessageId"].orEmpty(),
                    forwardedFrom = map["forwardedFrom"].orEmpty(),
                    senderAvatarHash = map["senderAvatarHash"].orEmpty().trim().lowercase(),
                    senderAvatarData = avatarDataOf(map),
                    groupId = groupIdOf(map),
                    groupTitle = map["groupTitle"].orEmpty().trim().take(80),
                    groupMembersCsv = rosterOf(map),
                    rttMs = (System.currentTimeMillis() - sentAt).coerceAtLeast(1L),
                    timestamp = map["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
                )
                logPacket(
                    direction = "RX <- YGG",
                    summary = "Принят пакет от «${incoming.senderNodeLabel}» через оверлей Yggdrasil (интернет)",
                    details = "Получатель: $targetIpv6 • RTT: ${incoming.rttMs} мс • AEAD: ${incoming.cipherPayloadHex.take(24)}..."
                )
                onProfileRelayPacketHandled(profileId, "RX_OVERLAY")
                onPacketReceived(incoming)
                // Only after the message is in the local database: an ACK sent earlier tells
                // the sender «Доставлено» for a packet this device may still lose on insert.
                sendOverlayFrame(profileId, senderPubKeyHex, ackFrame, "ACK")
            }
            "ACK" -> {
                if (map["attachmentChunkAck"] == "1") {
                    val transferId = map["transferId"] ?: ""
                    val chunkIndex = map["chunkIndex"]?.toIntOrNull() ?: -1
                    if (transferId.isNotBlank() && chunkIndex >= 0) {
                        pendingChunkAcks.remove("$transferId:$chunkIndex")?.complete(1L)
                    }
                    return
                }
                if (map["fileStreamAck"] != null) {
                    val transferId = map["transferId"] ?: ""
                    if (transferId.isBlank()) return
                    // sentAt is this device's own stamp echoed back, so the difference is a real
                    // measured round trip — the budget for the next piece window is taken from it.
                    map["sentAt"]?.toLongOrNull()?.let {
                        observedOverlayAckRttMs.set((System.currentTimeMillis() - it).coerceAtLeast(1L))
                    }
                    if (map["fileStreamAck"] == "refused") {
                        // The peer named its reason in the same frame; repeating a window this
                        // device already turned down would only delay the honest banner.
                        outgoingStreamRefusal[transferId] = map["streamRefusal"] ?: "адресат отклонил поток"
                        streamAckWaiters.remove(transferId)?.complete(-1)
                        return
                    }
                    // Cumulative: the peer names the piece count it has on disk, which confirms
                    // every earlier piece too, so one acknowledgement covers the whole window.
                    val have = map["haveThrough"]?.toIntOrNull() ?: -1
                    if (have >= 0) {
                        val best = maxOf(outgoingStreamHave[transferId] ?: 0, have)
                        outgoingStreamHave[transferId] = best
                        map["rootVerified"]?.let { outgoingStreamVerified[transferId] = it == "1" }
                        streamAckWaiters.remove(transferId)?.complete(best)
                    }
                    return
                }
                val messageId = map["messageId"] ?: ""
                val reactionId = map["reactionId"] ?: ""
                val deleteId = map["deleteId"] ?: ""
                val sentAt = map["sentAt"]?.toLongOrNull() ?: 0L
                val rtt = if (sentAt > 0) (System.currentTimeMillis() - sentAt).coerceAtLeast(1L) else 1L
                observedOverlayAckRttMs.set(rtt)
                onTwinReachableChanged(true)
                if (deleteId.isNotBlank()) {
                    pendingDeleteAcks.remove(deleteId)?.complete(rtt)
                    logPacket(
                        direction = "ACK <- YGG",
                        summary = "Адресат подтвердил приём запроса удаления (RTT: $rtt мс)",
                        details = "deleteId=${deleteId.take(12)} • messageId=${messageId.take(12)} • От ноды ${senderPubKeyHex.take(16)}...",
                        mirror = true
                    )
                } else if (reactionId.isNotBlank()) {
                    pendingReactionAcks.remove(reactionId)?.complete(rtt)
                    logPacket(
                        direction = "ACK <- YGG",
                        summary = "Реакция подтверждена через оверлей Yggdrasil (RTT: $rtt мс)",
                        details = "reactionId=${reactionId.take(12)} • messageId=${messageId.take(12)} • От ноды ${senderPubKeyHex.take(16)}..."
                    )
                } else {
                    pendingOverlayAcks.remove(messageId)?.complete(rtt)
                    markAvatarAdvertised(messageId)
                    onDeliveryAckReceived(map["chatId"] ?: "", messageId, rtt, senderPubKeyHex)
                    logPacket(
                        direction = "ACK <- YGG",
                        summary = "ACK получен через оверлей Yggdrasil (RTT: $rtt мс)",
                        details = "messageId=${messageId.take(12)} • От ноды ${senderPubKeyHex.take(16)}..."
                    )
                }
            }
            "REACTION" -> {
                val targetMessageId = map["messageId"] ?: ""
                val reactionId = map["reactionId"] ?: ""
                val emoji = map["emoji"] ?: ""
                val action = map["action"]?.uppercase().let {
                    if (it == "REMOVE") "REMOVE" else "ADD"
                }
                val targetIpv6 = map["recipientIpv6"] ?: ""
                val resolvedProfileId = profileId.ifBlank {
                    ipv6ToProfileIdMap[targetIpv6.lowercase()] ?: ""
                }
                // ACK through the same overlay session, echoing the reaction event id so
                // the sender can prove this exact reaction reached the recipient.
                val ackFrame = encodeWireFrame(
                    mapOf(
                        "type" to "ACK",
                        "messageId" to targetMessageId,
                        "reactionId" to reactionId,
                        "chatId" to (map["chatId"] ?: ""),
                        "sentAt" to (map["sentAt"] ?: System.currentTimeMillis().toString())
                    )
                )
                sendOverlayFrame(profileId, senderPubKeyHex, ackFrame, "ACK")
                pruneRecentIncomingOverlayMessages()
                val seenBefore = reactionId.isNotBlank() &&
                    recentIncomingOverlayMessages.putIfAbsent("rct:$reactionId", System.currentTimeMillis()) != null
                if (seenBefore) {
                    logPacket(
                        direction = "RX <- YGG",
                        summary = "Дубликат реакции «${reactionId.take(12)}» пропущен (ретрай отправителя)",
                        details = "Повторный ACK отправлен ноде ${senderPubKeyHex.take(16)}..."
                    )
                } else {
                    logPacket(
                        direction = "RX <- YGG",
                        summary = if (action == "REMOVE") {
                            "Принято снятие реакции $emoji через оверлей Yggdrasil"
                        } else {
                            "Принята реакция $emoji через оверлей Yggdrasil"
                        },
                        details = "Сообщение: ${targetMessageId.take(16)}... • От узла $senderIpv6 • ACK отправлен"
                    )
                    onReactionReceived(
                        IncomingReactionPacket(
                            chatId = map["chatId"] ?: "",
                            messageId = targetMessageId,
                            emoji = emoji,
                            senderIpv6 = senderIpv6,
                            recipientProfileId = resolvedProfileId,
                            action = action
                        )
                    )
                }
            }
            "MSG_DELETE" -> {
                val targetMessageId = map["messageId"] ?: ""
                val deleteId = map["deleteId"] ?: ""
                val proofEnvelope = map["proof"] ?: ""
                // The ACK only says this device received the request; whether it erases the
                // copy is decided by the proof check in the repository.
                val ackFrame = encodeWireFrame(
                    mapOf(
                        "type" to "ACK",
                        "messageId" to targetMessageId,
                        "deleteId" to deleteId,
                        "chatId" to (map["chatId"] ?: ""),
                        "sentAt" to (map["sentAt"] ?: System.currentTimeMillis().toString())
                    )
                )
                sendOverlayFrame(profileId, senderPubKeyHex, ackFrame, "ACK")
                pruneRecentIncomingOverlayMessages()
                val seenBefore = deleteId.isNotBlank() &&
                    recentIncomingOverlayMessages.putIfAbsent("del:$deleteId", System.currentTimeMillis()) != null
                if (seenBefore) {
                    logPacket(
                        direction = "RX <- YGG",
                        summary = "Дубликат запроса удаления «${deleteId.take(12)}» пропущен (ретрай отправителя)",
                        details = "Повторный ACK отправлен ноде ${senderPubKeyHex.take(16)}...",
                        mirror = true
                    )
                } else {
                    logPacket(
                        direction = "RX <- YGG",
                        summary = "Принят запрос удалить сообщение через оверлей Yggdrasil",
                        details = "Сообщение: ${targetMessageId.take(16)}... • От узла $senderIpv6 • ACK отправлен",
                        mirror = true
                    )
                    onDeleteForEveryoneReceived(
                        IncomingDeletePacket(
                            chatId = map["chatId"] ?: "",
                            wireMessageId = targetMessageId,
                            deleteId = deleteId,
                            senderIpv6 = senderIpv6,
                            proofEnvelope = proofEnvelope
                        )
                    )
                }
            }
            "READ_RECEIPT" -> {
                onTwinReachableChanged(true)
                val chatId = map["chatId"] ?: ""
                val ids = map["messageIds"].orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                if (ids.isNotEmpty()) {
                    logPacket(
                        direction = "RX <- YGG",
                        summary = "Получена квитанция о прочтении ${ids.size} сообщ.",
                        details = "Диалог ${chatId.take(16)}... • От ноды ${senderPubKeyHex.take(16)}..."
                    )
                    onReadReceiptReceived(chatId, ids)
                }
            }
            "FILE_BEGIN" -> {
                onTwinReachableChanged(true)
                handleFileBegin(profileId, senderPubKeyHex, map) { frame ->
                    sendOverlayFrame(profileId, senderPubKeyHex, frame, "STREAM_ACK")
                }
            }
            "FILE_PIECE" -> {
                onTwinReachableChanged(true)
                handleFilePiece(map) { frame ->
                    sendOverlayFrame(profileId, senderPubKeyHex, frame, "STREAM_ACK")
                }
            }
            "FILE_DONE" -> {
                onTwinReachableChanged(true)
                handleFileDone(map) { frame ->
                    sendOverlayFrame(profileId, senderPubKeyHex, frame, "STREAM_ACK")
                }
            }
            "ATTACH_CHUNK" -> {
                onTwinReachableChanged(true)
                val transferId = map["transferId"] ?: return
                val chunkIndex = map["chunkIndex"]?.toIntOrNull() ?: -1
                val chunkCount = map["chunkCount"]?.toIntOrNull() ?: 0
                val chunkData = map["chunkData"] ?: ""
                if (chunkIndex < 0 || chunkCount <= 0 || chunkData.isEmpty()) return
                pruneAttachmentAssemblies()
                val assembly = incomingAttachmentAssemblies.getOrPut(transferId) {
                    AttachmentAssembly(
                        transferId = transferId,
                        totalChunks = chunkCount,
                        totalBase64Chars = map["totalBase64Chars"]?.toIntOrNull() ?: 0,
                        attachmentSha256 = map["attachmentSha256"] ?: "",
                        attachmentName = map["attachmentName"]?.takeIf { it.isNotEmpty() },
                        attachmentType = map["attachmentType"]?.takeIf { it.isNotEmpty() },
                        startedAtMs = System.currentTimeMillis()
                    )
                }
                assembly.chunks.putIfAbsent(chunkIndex, chunkData)
                val ackFrame = encodeWireFrame(
                    mapOf(
                        "type" to "ACK",
                        "attachmentChunkAck" to "1",
                        "transferId" to transferId,
                        "chunkIndex" to chunkIndex.toString(),
                        "sentAt" to (map["sentAt"] ?: System.currentTimeMillis().toString())
                    )
                )
                sendOverlayFrame(profileId, senderPubKeyHex, ackFrame, "CHUNK_ACK")
                if (assembly.chunks.size == assembly.totalChunks) {
                    finalizeIncomingAttachment(assembly, map["senderIpv6"] ?: senderIpv6)
                }
            }
            "CALL_SIGNAL" -> {
                onTwinReachableChanged(true)
                val action = map["signalAction"] ?: "AUDIO"
                val callId = map["callId"] ?: ""
                val targetIpv6 = map["recipientIpv6"] ?: ""
                val resolvedProfileId = profileId.ifBlank {
                    ipv6ToProfileIdMap[targetIpv6.lowercase()] ?: ""
                }
                if (action == "AUDIO") {
                    // Audio frames are continuous: no per-frame ACK, otherwise the
                    // ACK traffic would double the stream for no benefit.
                    if (callId.isNotBlank()) {
                        onCallAudioFrameReceived(
                            IncomingCallAudioPacket(
                                chatId = map["chatId"] ?: "",
                                callId = callId,
                                senderIpv6 = senderIpv6,
                                recipientProfileId = resolvedProfileId,
                                ivBase64 = map["iv"] ?: "",
                                cipherBase64 = map["ct"] ?: "",
                                seq = map["seq"]?.toLongOrNull() ?: 0L,
                                senderNodePubKeyHex = senderPubKeyHex.trim().lowercase()
                            )
                        )
                    }
                } else {
                    val ackFrame = encodeWireFrame(
                        mapOf(
                            "type" to "CALL_ACK",
                            "callId" to callId,
                            "signalAction" to action,
                            "sentAt" to (map["sentAt"] ?: System.currentTimeMillis().toString())
                        )
                    )
                    sendOverlayFrame(profileId, senderPubKeyHex, ackFrame, "CALL_ACK")
                    // A cold overlay route keeps only the last pending datagram per
                    // destination, so back-to-back copies collapse into one. Re-offering
                    // the ACK spread over a couple of seconds is what actually survives
                    // path discovery and stops the caller from retransmitting RINGING.
                    scope.launch {
                        for (delayMs in listOf(400L, 2_000L)) {
                            kotlinx.coroutines.delay(delayMs)
                            sendOverlayFrame(profileId, senderPubKeyHex, ackFrame, "CALL_ACK (повтор)")
                        }
                    }
                    logPacket(
                        direction = "RX <- VOIP",
                        summary = when (action) {
                            "RINGING" -> "Входящий вызов VoIP от «${map["senderNodeLabel"] ?: "Узел Yggdrasil"}»"
                            "ACCEPT" -> "Абонент принял голосовой вызов"
                            "DECLINE" -> "Абонент отклонил голосовой вызов"
                            "BUSY" -> "Абонент занят"
                            "BYE" -> "Абонент завершил голосовой вызов"
                            "MISSED" -> "Пропущенный вызов VoIP"
                            else -> "Сигнал VoIP: $action"
                        },
                        details = "callId=${callId.take(12)} • $senderIpv6 • оверлей Yggdrasil"
                    )
                    onIncomingCallSignalReceived(
                        IncomingCallSignalPacket(
                            chatId = map["chatId"] ?: "",
                            recipientProfileId = resolvedProfileId,
                            recipientIpv6 = targetIpv6,
                            callerName = map["senderNodeLabel"] ?: "Узел Yggdrasil",
                            callerIpv6 = senderIpv6,
                            signalAction = action,
                            callId = callId,
                            senderX25519PubKeyHex = map["senderX25519PubKeyHex"] ?: "",
                            senderNodePubKeyHex = senderPubKeyHex.trim().lowercase(),
                            transport = "YGG_OVERLAY"
                        )
                    )
                }
            }
            "CALL_ACK" -> {
                val callId = map["callId"] ?: ""
                val action = map["signalAction"] ?: ""
                // The arrival time of this device's clock is what the sender turns into a
                // round trip; the echoed `sentAt` belongs to the other party's clock and
                // subtracting across two unsynchronised clocks produced a meaningless RTT.
                pendingCallAcks.remove("$callId:$action")?.complete(System.currentTimeMillis())
            }
        }
    }

    private fun finalizeIncomingAttachment(assembly: AttachmentAssembly, senderIpv6: String) {
        val ordered = StringBuilder(assembly.totalBase64Chars.coerceAtLeast(0))
        for (index in 0 until assembly.totalChunks) {
            val chunk = assembly.chunks[index] ?: return
            ordered.append(chunk)
        }
        val assembled = ordered.toString()
        if (assembly.totalBase64Chars > 0 && assembled.length != assembly.totalBase64Chars) {
            logPacket(
                direction = "ERR • ATTACH",
                summary = "Вложение отклонено: длина не совпадает",
                details = "transferId=${assembly.transferId.take(12)} • ожидалось ${assembly.totalBase64Chars} символов, получено ${assembled.length}"
            )
            incomingAttachmentAssemblies.remove(assembly.transferId)
            return
        }
        if (assembly.attachmentSha256.isNotBlank() &&
            !YggCryptoEngine.sha256Hex(assembled).equals(assembly.attachmentSha256, ignoreCase = true)
        ) {
            logPacket(
                direction = "ERR • ATTACH",
                summary = "Вложение отклонено: SHA-256 не совпадает",
                details = "transferId=${assembly.transferId.take(12)} • целостность нарушена"
            )
            incomingAttachmentAssemblies.remove(assembly.transferId)
            return
        }
        completedAttachmentPayloads[assembly.transferId] = assembled to System.currentTimeMillis()
        incomingAttachmentAssemblies.remove(assembly.transferId)
        logPacket(
            direction = "RX <- YGG",
            summary = "Вложение собрано из ${assembly.totalChunks} чанков (${assembled.length / 1024} KB Base64)",
            details = "transferId=${assembly.transferId.take(12)} • SHA-256 подтвержден • от $senderIpv6"
        )
    }

    private suspend fun awaitAssembledAttachment(transferId: String): String? {
        if (transferId.isBlank()) return null
        val deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline) {
            // The payload is kept after the first read: a retried MSG whose ACK was lost
            // must resolve the same attachment again instead of stalling the queue.
            completedAttachmentPayloads[transferId]?.let { return it.first }
            delay(200L)
        }
        return null
    }

    private fun pruneAttachmentAssemblies() {
        val cutoff = System.currentTimeMillis() - CHUNK_ASSEMBLY_TTL_MS
        incomingAttachmentAssemblies.entries.removeAll { it.value.startedAtMs < cutoff }
        completedAttachmentPayloads.entries.removeAll { it.value.second < cutoff }
        while (completedAttachmentPayloads.size > 12) {
            val oldest = completedAttachmentPayloads.entries.minByOrNull { it.value.second } ?: break
            completedAttachmentPayloads.remove(oldest.key, oldest.value)
        }
    }

    /** Opens the LAN discovery listener while direct LAN exchange is allowed, closes it otherwise. */
    private suspend fun applyLanDiscoveryState() {
        if (!allowDirectLanRouting) {
            lanDiscoverySocket?.close()
            lanDiscoverySocket = null
            return
        }
        if (lanDiscoverySocket?.isClosed == false) return
        startLanDiscoveryListener()
    }

    private suspend fun startLanDiscoveryListener() {
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", lanDiscoveryUdpPort))
                broadcast = true
            }
        } catch (_: Exception) {
            null
        } ?: return
        lanDiscoverySocket = sock

        val buf = ByteArray(4096)
        while (scope.isActive && !sock.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val senderIp = pkt.address?.hostAddress ?: continue
                val localIp = getLocalLanIpv4Address()
                if (senderIp == localIp || senderIp == "127.0.0.1") continue
                val raw = String(pkt.data, 0, pkt.length, StandardCharsets.UTF_8)
                handleIncomingLanDiscoveryPacket(raw, senderIp)
            } catch (_: Exception) {
            }
        }
    }

    private fun isLoopbackAddress(ip: String): Boolean =
        ip.startsWith("127.") || ip == "::1" || ip.equals("0:0:0:0:0:0:0:1", ignoreCase = true)

    private suspend fun handleIncomingTcpConnection(
        socket: Socket,
        recipientProfileId: String,
        recipientIpv6: String
    ) {
        try {
            socket.soTimeout = 10000
            val remoteIp = socket.inetAddress?.hostAddress ?: "unknown"
            // A direct LAN peer is opt-in. Loopback stays open because the profile-to-profile
            // transfers and the on-device tests run over it.
            if (!allowDirectLanRouting && !isLoopbackAddress(remoteIp)) {
                logPacket(
                    direction = "RX <- LAN",
                    summary = "Прямое LAN-соединение от $remoteIp отклонено",
                    details = "Обмен в локальной сети выключен в настройках приватности; доставка идёт только через оверлей Yggdrasil"
                )
                return
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
            // One connection may carry many frames: the call media channel keeps a
            // single socket open and streams encrypted AUDIO frames over it.
            while (true) {
            val line = reader.readLine() ?: break
            val map = decodeWireFrame(line)

            when (map["type"]) {
                "TYPING" -> {
                    onTwinReachableChanged(true)
                    val chatId = map["chatId"] ?: ""
                    if (chatId.isNotBlank()) {
                        onTypingReceived(
                            chatId,
                            map["senderNodeLabel"]?.takeIf { it.isNotBlank() }
                                ?: "Собеседник ($remoteIp)",
                            map["senderIpv6"] ?: ""
                        )
                    }
                }
                "PING" -> {
                    onTwinReachableChanged(true)
                    val pong = encodeWireFrame(
                        mapOf(
                            "type" to "PONG",
                            "sentAt" to (map["sentAt"] ?: System.currentTimeMillis().toString())
                        )
                    )
                    writer.println(pong)
                }
                "MSG" -> {
                    onTwinReachableChanged(true)
                    val sentAt = map["sentAt"]?.toLongOrNull() ?: System.currentTimeMillis()
                    val externalRtt = map["externalRttMs"]?.toLongOrNull()
                    val rtt = externalRtt ?: (System.currentTimeMillis() - sentAt).coerceAtLeast(1L)
                    val routedViaPeer = map["routedViaYggPeer"].orEmpty()
                    val routedViaRegion = map["routedViaRegion"].orEmpty()
                    val msgId = map["messageId"] ?: ""
                    val senderIpv6 = map["senderIpv6"] ?: ""
                    val targetIpv6 = map["recipientIpv6"]?.takeIf { it.isNotBlank() } ?: recipientIpv6
                    val resolvedProfileId = recipientProfileId.ifBlank {
                        ipv6ToProfileIdMap[targetIpv6.lowercase()] ?: ""
                    }
                    val senderPort = map["senderTcpPort"]?.toIntOrNull() ?: 51921
                    if (allowDirectLanRouting && senderIpv6.isNotBlank() && remoteIp != "unknown" && remoteIp != "127.0.0.1") {
                        directEndpointTable[senderIpv6.lowercase()] = InetSocketAddress.createUnresolved(remoteIp, senderPort)
                    }
                    pruneRecentIncomingOverlayMessages()
                    val alreadySeen = msgId.isNotBlank() &&
                        recentIncomingOverlayMessages.putIfAbsent(msgId, System.currentTimeMillis()) != null

                    var lanAttachment = map["attachmentBase64"]?.takeIf { it.isNotEmpty() }
                    if (lanAttachment == null && map["attachmentChunked"] == "1") {
                        // Older senders may reference overlay chunks in a LAN frame; wait
                        // for the assembly instead of ACKing an attachment-less message.
                        lanAttachment = awaitAssembledAttachment(msgId)
                        if (lanAttachment == null) {
                            logPacket(
                                direction = "ERR • ATTACH",
                                summary = "Вложение ещё не собрано — LAN-пакет не подтвержден",
                                details = "messageId=${msgId.take(12)} • получено ${incomingAttachmentAssemblies[msgId]?.chunks?.size ?: 0} чанков"
                            )
                            if (msgId.isNotBlank()) recentIncomingOverlayMessages.remove(msgId)
                            return
                        }
                    }

                    // A LAN frame may point at a stream the overlay already filled: the bytes
                    // landed in this app's storage either way, so the message resolves to them.
                    var lanStreamFile = if (map["attachmentChunked"] == "2") awaitCompletedStream(msgId) else null
                    if (map["attachmentChunked"] == "2" && lanStreamFile == null) {
                        logPacket(
                            direction = "ERR • STREAM",
                            summary = "Поток файла ещё не дописан — LAN-пакет не подтвержден",
                            details = "messageId=${msgId.take(12)} • получено ${incomingFileStreams[msgId]?.nextIndex ?: 0} кусков"
                        )
                        if (msgId.isNotBlank()) recentIncomingOverlayMessages.remove(msgId)
                        return
                    }

                    val incoming = IncomingTwinPacket(
                        targetChatId = map["chatId"] ?: "",
                        messageId = msgId,
                        recipientProfileId = resolvedProfileId,
                        recipientIpv6 = targetIpv6,
                        senderNodeLabel = map["senderNodeLabel"] ?: "Пир Yggdrasil ($remoteIp)",
                        senderIpv6 = senderIpv6,
                        senderPubKeyHex = map["senderPubKeyHex"] ?: "",
                        plainText = map["plainText"] ?: "",
                        cipherPayloadHex = map["cipherPayloadHex"] ?: "",
                        hashcashToken = map["hashcashToken"] ?: "",
                        attachmentName = map["attachmentName"]?.takeIf { it.isNotEmpty() },
                        attachmentSizeBytes = lanStreamFile?.totalBytes ?: (map["attachmentSizeBytes"]?.toLongOrNull() ?: 0L),
                        rttMs = rtt,
                        timestamp = map["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis(),
                        attachmentType = map["attachmentType"]?.takeIf { it.isNotEmpty() },
                        attachmentUri = lanStreamFile?.localUri ?: map["attachmentUri"]?.takeIf { it.isNotEmpty() },
                        attachmentBase64 = lanAttachment,
                        streamedAttachmentRoot = lanStreamFile?.merkleRootHex ?: "",
                        voiceDurationSec = map["voiceDurationSec"]?.toIntOrNull() ?: 0,
                        voiceWaveformCsv = map["voiceWaveformCsv"]?.takeIf { it.isNotEmpty() },
                        replyToMessageId = map["replyToMessageId"].orEmpty(),
                        forwardedFrom = map["forwardedFrom"].orEmpty(),
                        senderAvatarHash = map["senderAvatarHash"].orEmpty().trim().lowercase(),
                        senderAvatarData = avatarDataOf(map),
                        groupId = groupIdOf(map),
                        groupTitle = map["groupTitle"].orEmpty().trim().take(80),
                        groupMembersCsv = rosterOf(map)
                    )

                    val ackFrame = encodeWireFrame(
                        mapOf(
                            "type" to "ACK",
                            "messageId" to msgId,
                            "chatId" to incoming.targetChatId,
                            "sentAt" to sentAt.toString()
                        )
                    )
                    val viaInfo = if (routedViaPeer.isNotBlank()) {
                        "через внешний пир Yggdrasil $routedViaPeer ($routedViaRegion, RTT $rtt мс)"
                    } else {
                        "($remoteIp, RTT $rtt мс)"
                    }
                    if (alreadySeen) {
                        // The sender retried because the ACK was lost: confirm again,
                        // but do not show the message twice.
                        writer.println(ackFrame)
                        logPacket(
                            direction = "RX <- YGG",
                            summary = "Дубликат пакета «${msgId.take(12)}» пропущен (ретрай отправителя)",
                            details = "Повторный ACK отправлен на $remoteIp:$senderPort"
                        )
                    } else {
                        logPacket(
                            direction = "RX <- YGG",
                            summary = "Принят пакет от «${incoming.senderNodeLabel}» $viaInfo",
                            details = "Получатель: $targetIpv6 • AEAD: ${incoming.cipherPayloadHex.take(24)}... • Отправлен крипто-ACK"
                        )
                        onPacketReceived(incoming)
                        // Ack only once the packet is persisted, as on the overlay path.
                        writer.println(ackFrame)
                    }
                }
                "REACTION" -> {
                    onTwinReachableChanged(true)
                    val chatId = map["chatId"] ?: ""
                    val messageId = map["messageId"] ?: ""
                    val reactionId = map["reactionId"] ?: ""
                    val emoji = map["emoji"] ?: "👍"
                    val action = map["action"]?.uppercase().let {
                        if (it == "REMOVE") "REMOVE" else "ADD"
                    }
                    val senderIpv6 = map["senderIpv6"] ?: ""
                    val targetIpv6 = map["recipientIpv6"]?.takeIf { it.isNotBlank() } ?: recipientIpv6
                    val resolvedProfileId = recipientProfileId.ifBlank {
                        ipv6ToProfileIdMap[targetIpv6.lowercase()] ?: ""
                    }
                    writer.println(
                        encodeWireFrame(
                            mapOf(
                                "type" to "ACK",
                                "messageId" to messageId,
                                "reactionId" to reactionId
                            )
                        )
                    )
                    pruneRecentIncomingOverlayMessages()
                    val seenBefore = reactionId.isNotBlank() &&
                        recentIncomingOverlayMessages.putIfAbsent("rct:$reactionId", System.currentTimeMillis()) != null
                    if (seenBefore) {
                        logPacket(
                            direction = "RX <- YGG",
                            summary = "Дубликат реакции «${reactionId.take(12)}» пропущен (ретрай отправителя)",
                            details = "Повторный ACK отправлен на $remoteIp:${map["senderTcpPort"] ?: 51921}"
                        )
                    } else {
                        logPacket(
                            direction = "RX <- YGG",
                            summary = if (action == "REMOVE") {
                                "Принято снятие реакции $emoji от узла $senderIpv6"
                            } else {
                                "Принята реакция $emoji от узла $senderIpv6"
                            },
                            details = "Сообщение: ${messageId.take(16)}... • LAN $remoteIp • ACK отправлен"
                        )
                        onReactionReceived(
                            IncomingReactionPacket(
                                chatId = chatId,
                                messageId = messageId,
                                emoji = emoji,
                                senderIpv6 = senderIpv6,
                                recipientProfileId = resolvedProfileId,
                                action = action
                            )
                        )
                    }
                }
                "FILE_BEGIN", "FILE_PIECE", "FILE_DONE" -> {
                    onTwinReachableChanged(true)
                    // The same piece state machine serves a direct socket as the overlay:
                    // which transport carried a frame never changes what the bytes mean.
                    val reply: (String) -> Unit = { frame -> writer.println(frame) }
                    when (map["type"]) {
                        "FILE_BEGIN" -> handleFileBegin(
                            recipientProfileId,
                            map["senderPubKeyHex"].orEmpty(),
                            map,
                            reply
                        )
                        "FILE_PIECE" -> handleFilePiece(map, reply)
                        else -> handleFileDone(map, reply)
                    }
                }
                "MSG_DELETE" -> {
                    onTwinReachableChanged(true)
                    val messageId = map["messageId"] ?: ""
                    val deleteId = map["deleteId"] ?: ""
                    val proofEnvelope = map["proof"] ?: ""
                    val senderIpv6 = map["senderIpv6"] ?: ""
                    writer.println(
                        encodeWireFrame(
                            mapOf(
                                "type" to "ACK",
                                "messageId" to messageId,
                                "deleteId" to deleteId
                            )
                        )
                    )
                    pruneRecentIncomingOverlayMessages()
                    val seenBefore = deleteId.isNotBlank() &&
                        recentIncomingOverlayMessages.putIfAbsent("del:$deleteId", System.currentTimeMillis()) != null
                    if (seenBefore) {
                        logPacket(
                            direction = "RX <- YGG",
                            summary = "Дубликат запроса удаления «${deleteId.take(12)}» пропущен (ретрай отправителя)",
                            details = "Повторный ACK отправлен на $remoteIp:${map["senderTcpPort"] ?: 51921}",
                            mirror = true
                        )
                    } else {
                        logPacket(
                            direction = "RX <- YGG",
                            summary = "Принят запрос удалить сообщение от узла $senderIpv6",
                            details = "Сообщение: ${messageId.take(16)}... • LAN $remoteIp • ACK отправлен",
                            mirror = true
                        )
                        onDeleteForEveryoneReceived(
                            IncomingDeletePacket(
                                chatId = map["chatId"] ?: "",
                                wireMessageId = messageId,
                                deleteId = deleteId,
                                senderIpv6 = senderIpv6,
                                proofEnvelope = proofEnvelope
                            )
                        )
                    }
                }
                "READ_RECEIPT" -> {
                    onTwinReachableChanged(true)
                    val chatId = map["chatId"] ?: ""
                    val ids = map["messageIds"].orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    if (ids.isNotEmpty()) {
                        logPacket(
                            direction = "RX <- PEER",
                            summary = "Получена квитанция о прочтении ${ids.size} сообщ. по LAN",
                            details = "Диалог ${chatId.take(16)}... • LAN $remoteIp"
                        )
                        onReadReceiptReceived(chatId, ids)
                    }
                }
                "CALL_SIGNAL" -> {
                    onTwinReachableChanged(true)
                    // A call channel is long-lived and can be idle while the peer is muted,
                    // so the control-connection read timeout must not close it.
                    try {
                        socket.soTimeout = 0
                    } catch (_: Exception) {
                    }
                    val signalAction = map["signalAction"] ?: "AUDIO"
                    val callId = map["callId"] ?: ""
                    val callerName = map["senderNodeLabel"] ?: map["callerName"] ?: "Узел Yggdrasil ($remoteIp)"
                    val callerIpv6 = map["senderIpv6"] ?: map["callerIpv6"] ?: ""
                    val callerPort = map["senderTcpPort"]?.toIntOrNull() ?: 51921
                    val callerLanHint = if (remoteIp.isNotBlank() && remoteIp != "unknown" && remoteIp != "127.0.0.1") {
                        "$remoteIp:$callerPort"
                    } else {
                        ""
                    }
                    val chatId = map["chatId"] ?: ""
                    val targetIpv6 = map["recipientIpv6"]?.takeIf { it.isNotBlank() } ?: recipientIpv6
                    val resolvedProfileId = recipientProfileId.ifBlank {
                        ipv6ToProfileIdMap[targetIpv6.lowercase()] ?: ""
                    }
                    // A direct socket answers for whichever device happens to listen on
                    // that port, and a stale LAN registration can point a call at an
                    // unrelated node. ACKing a signal meant for someone else told the
                    // caller their call had been delivered to a phone that never rang.
                    if (!isCallAddressedToLocalNode(targetIpv6)) {
                        if (signalAction != "AUDIO") {
                            logPacket(
                                direction = "ERR • VOIP",
                                summary = "Сигнал вызова адресован не этому узлу — отброшен",
                                details = "$signalAction для $targetIpv6 • LAN ${remoteIp} • профиль ${resolvedProfileId.ifBlank { "чужой" }}"
                            )
                        }
                    } else if (signalAction == "AUDIO") {
                        if (callId.isNotBlank()) {
                            onCallAudioFrameReceived(
                                IncomingCallAudioPacket(
                                    chatId = chatId,
                                    callId = callId,
                                    senderIpv6 = callerIpv6.ifBlank { remoteIp },
                                    recipientProfileId = resolvedProfileId,
                                    ivBase64 = map["iv"] ?: "",
                                    cipherBase64 = map["ct"] ?: "",
                                    seq = map["seq"]?.toLongOrNull() ?: 0L,
                                    senderNodePubKeyHex = map["senderPubKeyHex"] ?: ""
                                )
                            )
                        }
                    } else {
                        writer.println(
                            encodeWireFrame(
                                mapOf(
                                    "type" to "CALL_ACK",
                                    "callId" to callId,
                                    "signalAction" to signalAction,
                                    "status" to "OK"
                                )
                            )
                        )
                        logPacket(
                            direction = "RX <- VOIP",
                            summary = when (signalAction) {
                                "RINGING" -> "Входящий вызов VoIP от «$callerName» ($callerIpv6)"
                                "ACCEPT" -> "Абонент принял голосовой вызов"
                                "DECLINE" -> "Абонент отклонил голосовой вызов"
                                "BUSY" -> "Абонент занят"
                                "BYE" -> "Абонент завершил голосовой вызов"
                                "MISSED" -> "Пропущенный вызов VoIP от «$callerName» ($callerIpv6)"
                                else -> "Сигнал VoIP: $signalAction"
                            },
                            details = "callId=${callId.take(12)} • LAN ${remoteIp} • профиль ${resolvedProfileId.ifBlank { "primary" }}"
                        )
                        onIncomingCallSignalReceived(
                            IncomingCallSignalPacket(
                                chatId = chatId,
                                recipientProfileId = resolvedProfileId,
                                recipientIpv6 = targetIpv6,
                                callerName = callerName,
                                callerIpv6 = callerIpv6,
                                signalAction = signalAction,
                                callId = callId,
                                senderX25519PubKeyHex = map["senderX25519PubKeyHex"] ?: "",
                                senderNodePubKeyHex = map["senderPubKeyHex"] ?: "",
                                transport = "LAN_DIRECT",
                                senderLanHint = callerLanHint
                            )
                        )
                    }
                }
                "RELAY_TRANSIT" -> {
                    val targetIpv6 = map["recipientIpv6"]?.takeIf { it.isNotBlank() } ?: recipientIpv6
                    val resolvedProfileId = recipientProfileId.ifBlank {
                        ipv6ToProfileIdMap[targetIpv6.lowercase()] ?: ""
                    }
                    val role = (if (resolvedProfileId.isNotBlank()) profileRelayRoleMap[resolvedProfileId] else null)
                        ?: "PEER_RELAY"
                    if (role == "CLIENT_ONLY") {
                        writer.println(encodeWireFrame(mapOf("type" to "RELAY_REJECTED", "role" to role)))
                        logPacket(
                            direction = "DROP • RELAY",
                            summary = "Транзитный пакет отклонен: профиль настроен как «Только клиент»",
                            details = "Целевой узел $targetIpv6 не ретранслирует чужой трафик (CLIENT_ONLY)"
                        )
                    } else {
                        writer.println(encodeWireFrame(mapOf("type" to "RELAY_ACK", "role" to role)))
                        logPacket(
                            direction = "RELAY <-> YGG",
                            summary = if (role == "SF_BLIND_RELAY") {
                                // Nothing in this build verifies a hashcash stamp on the way in,
                                // so the log must not imply that the PoW was checked.
                                "Принят и ретранслирован слепой S&F конверт (Hashcash PoW не проверяется)"
                            } else {
                                "Ретранслирован транзитный оверлейный пакет Yggdrasil 0200::/7"
                            },
                            details = "Роль профиля: $role • Профиль: ${resolvedProfileId.ifBlank { "primary" }}"
                        )
                        if (resolvedProfileId.isNotBlank()) {
                            onProfileRelayPacketHandled(resolvedProfileId, role)
                        }
                    }
                }
            }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleIncomingLanDiscoveryPacket(raw: String, senderIp: String) {
        if (!allowDirectLanRouting) return
        val map = decodeWireFrame(raw)
        if (map["type"] == "LAN_ANNOUNCE") {
            val yggIpv6 = map["yggIpv6"] ?: return
            val pubKey = map["pubKey"] ?: ""
            val name = map["displayName"] ?: "Узел YGramm ($senderIp)"
            val port = map["tcpPort"]?.toIntOrNull() ?: 51921
            directEndpointTable[yggIpv6.lowercase()] = InetSocketAddress.createUnresolved(senderIp, port)
            val node = DiscoveredLanNode(
                displayName = name,
                yggIpv6 = yggIpv6,
                pubKeyHex = pubKey,
                hostIp = senderIp,
                tcpPort = port,
                lastSeenMs = System.currentTimeMillis()
            )
            onTwinReachableChanged(true)
            logPacket(
                direction = "RX <- LAN",
                summary = "Обнаружен узел в локальной сети: $name ($senderIp:$port)",
                details = "Yggdrasil IPv6: $yggIpv6"
            )
            onLanNodeDiscovered(node)
        }
    }

    suspend fun broadcastLanDiscoveryBeacon(
        displayName: String,
        yggIpv6: String,
        pubKeyHex: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isRealNetworkAvailable()) {
            logPacket(
                direction = "ERR • NET",
                summary = "LAN-поиск невозможен: сеть отключена или активен Авиарежим",
                details = "Проверьте подключение к Wi-Fi или мобильной сети"
            )
            return@withContext false
        }
        return@withContext try {
            val payload = encodeWireFrame(
                mapOf(
                    "type" to "LAN_ANNOUNCE",
                    "displayName" to displayName,
                    "yggIpv6" to yggIpv6,
                    "pubKey" to pubKeyHex,
                    "tcpPort" to localTcpPort.toString()
                )
            )
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            DatagramSocket().use { ds ->
                ds.broadcast = true
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val pkt = DatagramPacket(bytes, bytes.size, broadcastAddr, lanDiscoveryUdpPort)
                ds.send(pkt)
            }
            logPacket(
                direction = "TX -> LAN",
                summary = "Отправлен широковещательный маяк LAN Discovery (UDP :$lanDiscoveryUdpPort)",
                details = "Анонс узла $yggIpv6 на порт TCP :$localTcpPort"
            )
            true
        } catch (e: Exception) {
            logPacket(
                direction = "ERR • LAN",
                summary = "Ошибка отправки LAN-маяка: ${e.message ?: "UDP error"}",
                details = "Порт $lanDiscoveryUdpPort"
            )
            false
        }
    }

    internal fun buildYggdrasilMetaHandshakeBytes(ed25519PubKeyHex: String): ByteArray {
        val magic = byteArrayOf('m'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 0, 0)
        val rawPub = ed25519PubKeyHex.chunked(2)
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()
        val pubBytes = ByteArray(32)
        if (rawPub.isNotEmpty()) {
            System.arraycopy(rawPub, 0, pubBytes, 0, rawPub.size.coerceAtMost(32))
        }
        val payloadSize = 16 + pubBytes.size
        val payload = ByteBuffer.allocate(payloadSize)
            .putShort(0)
            .putShort(2)
            .putShort(0)
            .putShort(1)
            .putShort(2)
            .putShort(5)
            .putShort(2)
            .putShort(pubBytes.size.toShort())
            .put(pubBytes)
            .array()
        return ByteBuffer.allocate(magic.size + 2 + payload.size)
            .put(magic)
            .putShort(payload.size.toShort())
            .put(payload)
            .array()
    }

    suspend fun probeRealYggdrasilPeer(
        endpointUri: String,
        sniOverride: String? = null,
        timeoutMs: Int = 3200
    ): PeerProbeResult = withContext(Dispatchers.IO) {
        val host = YggPeerScannerCatalog.extractHostFromUri(endpointUri)
        val port = YggPeerScannerCatalog.extractPortFromUri(endpointUri)

        // Allow probing concurrent secondary profile ports on 127.0.0.1 if port != own primary port
        val isLocalConcurrentProfile = (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) &&
            activeProfilePortMap.values.contains(port)

        if (!isLocalConcurrentProfile && !isRealNetworkAvailable()) {
            val reason = if (isAirplaneModeActive()) "Активен Авиарежим" else "Нет подключения к сети"
            return@withContext PeerProbeResult(
                endpointUri = endpointUri,
                reachable = false,
                rttMs = -1,
                protocolUsed = "OFFLINE",
                errorDetail = reason
            )
        }

        if (host.isBlank() || (!isLocalConcurrentProfile && (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)))) {
            return@withContext PeerProbeResult(
                endpointUri = endpointUri,
                reachable = false,
                rttMs = -1,
                protocolUsed = "INVALID",
                errorDetail = "Локальный адрес запрещен для внешнего пира"
            )
        }

        val isTls = endpointUri.startsWith("tls://", ignoreCase = true) ||
            endpointUri.startsWith("wss://", ignoreCase = true) ||
            endpointUri.startsWith("quic://", ignoreCase = true) ||
            endpointUri.startsWith("shadowtls://", ignoreCase = true) ||
            endpointUri.startsWith("vless+tls://", ignoreCase = true)

        val startNs = System.nanoTime()
        var txCount = 0L
        var rxCount = 0L
        // Only a node that answers the Yggdrasil handshake can carry traffic. An open port
        // cannot, and a peer added on that promise sits down in the dial list forever.
        var answered = false
        var handshakeFailure: String? = null
        try {
            Socket().use { rawSocket ->
                rawSocket.soTimeout = timeoutMs
                rawSocket.connect(InetSocketAddress(host, port), timeoutMs)
                if (isTls) {
                    val sslSocket = yggdrasilTlsSocketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
                    sslSocket.soTimeout = timeoutMs
                    val effectiveSni = sniOverride?.takeIf { it.isNotBlank() } ?: host
                    try {
                        val params = sslSocket.sslParameters ?: SSLParameters()
                        params.serverNames = listOf(SNIHostName(effectiveSni))
                        sslSocket.sslParameters = params
                    } catch (_: Throwable) {
                    }
                    try {
                        sslSocket.startHandshake()
                        val metaBytes = buildYggdrasilMetaHandshakeBytes("")
                        sslSocket.outputStream.write(metaBytes)
                        sslSocket.outputStream.flush()
                        txCount += metaBytes.size
                        val replyBuf = ByteArray(64)
                        val readLen = try {
                            sslSocket.inputStream.read(replyBuf)
                        } catch (_: Throwable) {
                            0
                        }
                        if (readLen > 0) {
                            rxCount += readLen
                            answered = true
                        }
                    } catch (e: Throwable) {
                        // The TCP socket connected but TLS was refused: report the zero bytes that
                        // actually crossed the wire instead of inventing a handshake transcript.
                        handshakeFailure = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                    }
                } else if (isLocalConcurrentProfile) {
                    val ping = encodeWireFrame(mapOf("type" to "PING", "sentAt" to System.currentTimeMillis().toString()))
                    val writer = PrintWriter(OutputStreamWriter(rawSocket.getOutputStream(), StandardCharsets.UTF_8), true)
                    val reader = BufferedReader(InputStreamReader(rawSocket.getInputStream(), StandardCharsets.UTF_8))
                    writer.println(ping)
                    txCount += ping.toByteArray(StandardCharsets.UTF_8).size
                    val pong = reader.readLine()
                    if (pong != null) {
                        rxCount += pong.toByteArray(StandardCharsets.UTF_8).size
                        answered = true
                    }
                } else {
                    val metaBytes = buildYggdrasilMetaHandshakeBytes("")
                    rawSocket.outputStream.write(metaBytes)
                    rawSocket.outputStream.flush()
                    txCount += metaBytes.size
                    val replyBuf = ByteArray(64)
                    val readLen = try {
                        rawSocket.inputStream.read(replyBuf)
                    } catch (_: Throwable) {
                        0
                    }
                    if (readLen > 0) {
                        rxCount += readLen
                        answered = true
                    }
                }
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            val silence = if (answered) null else
                "Порт открыт, но ответа на handshake Yggdrasil нет за ${timeoutMs} мс — это не узел Yggdrasil"
            PeerProbeResult(
                endpointUri = endpointUri,
                reachable = answered && handshakeFailure == null,
                rttMs = elapsedMs,
                protocolUsed = if (isTls) "TLS" else "TCP",
                rxBytes = rxCount,
                txBytes = txCount,
                errorDetail = handshakeFailure ?: silence
            )
        } catch (e: Throwable) {
            PeerProbeResult(
                endpointUri = endpointUri,
                reachable = false,
                rttMs = -1,
                protocolUsed = if (isTls) "TLS" else "TCP",
                errorDetail = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            )
        }
    }

    /**
     * Real overlay round trip for the profile's own Yggdrasil node.
     *
     * A node cannot address itself inside the 0200::/7 overlay: the ironwood
     * pathfinder keeps no route to its own key and the encrypted session layer
     * refuses to build a session with itself, so a self-addressed datagram never
     * leaves the node. The probe therefore addresses a remote node taken from the
     * routing tree and waits for its session acknowledgement - the encrypted layer
     * creates the session only after that answer arrives, so a confirmed probe
     * proves that the datagram travelled the overlay and the answer came back.
     */
    suspend fun probeOverlayRoundTrip(
        profileId: String,
        timeoutMs: Long = 20_000L
    ): OverlayProbeOutcome = withContext(Dispatchers.IO) {
        if (!isRealNetworkAvailable()) {
            val reason = if (isAirplaneModeActive()) "Активен Авиарежим" else "Нет подключения к сети"
            logPacket(
                direction = "ERR • YGG",
                summary = "Зонд оверлея отменён: $reason",
                details = "Для проверки маршрута 0200::/7 нужна реальная сеть"
            )
            return@withContext OverlayProbeOutcome(
                confirmed = false,
                rttMs = -1L,
                remoteKeyHex = "",
                multiHop = false,
                livePeers = 0,
                knownNodes = 0,
                txBytes = 0L,
                errorDetail = reason
            )
        }

        if (profileId.isBlank() || !initializedNativeProfiles.contains(profileId)) {
            logPacket(
                direction = "ERR • CORE",
                summary = "Собственная нода Yggdrasil не активна",
                details = "Проверка маршрута требует работающей ноды профиля в сети 0200::/7"
            )
            return@withContext OverlayProbeOutcome(
                confirmed = false,
                rttMs = -1L,
                remoteKeyHex = "",
                multiHop = false,
                livePeers = 0,
                knownNodes = 0,
                txBytes = 0L,
                errorDetail = "Нода Yggdrasil профиля не активна"
            )
        }

        val livePeers = awaitLivePeer(profileId)
        if (livePeers <= 0) {
            logPacket(
                direction = "ERR • CORE",
                summary = "Нода без живых пиров — маршрут через оверлей не проверить",
                details = "Подключите хотя бы один живой пир во вкладке «Сеть и ТСПУ»"
            )
            return@withContext OverlayProbeOutcome(
                confirmed = false,
                rttMs = -1L,
                remoteKeyHex = "",
                multiHop = false,
                livePeers = 0,
                knownNodes = 0,
                txBytes = 0L,
                errorDetail = "Нода Yggdrasil без живых пиров"
            )
        }

        logPacket(
            direction = "TX -> YGG",
            summary = "Зонд оверлея: датаграмма отправлена удалённой ноде из routing tree",
            details = "Маршрут: нода → пиры 0200::/7 → удалённая нода → ответ • живых пиров: $livePeers"
        )

        val result = YggNativeCoreBridge.probeOverlay(profileId, timeoutMs.toInt())
        if (!result.ok) {
            val error = result.error.ifBlank { "ядро не подтвердило круговой маршрут" }
            logPacket(
                direction = "ERR • YGG",
                summary = "Зонд оверлея не подтверждён",
                details = error
            )
            return@withContext OverlayProbeOutcome(
                confirmed = false,
                rttMs = -1L,
                remoteKeyHex = "",
                multiHop = false,
                livePeers = livePeers,
                knownNodes = YggNativeCoreBridge.extractJsonInt(result.rawJson, "knownNodes", 0),
                txBytes = 0L,
                errorDetail = error
            )
        }

        val raw = result.rawJson
        val rtt = YggNativeCoreBridge.extractJsonInt(raw, "rttMs", -1).toLong()
        val remoteKeyHex = YggNativeCoreBridge.extractJsonField(raw, "remoteKeyHex")
        val multiHop = YggNativeCoreBridge.extractJsonBool(raw, "multiHop", false)
        val knownNodes = YggNativeCoreBridge.extractJsonInt(raw, "knownNodes", 0)
        val txBytes = YggNativeCoreBridge.extractJsonInt(raw, "txBytes", 0).toLong()

        logPacket(
            direction = "ACK <- YGG",
            summary = "Зонд оверлея подтверждён: ответ удалённой ноды через 0200::/7 за $rtt мс",
            details = "Удалённая нода ${remoteKeyHex.take(16)}... • ${
                if (multiHop) "маршрут через оверлей" else "прямой пир"
            } • известных узлов в дереве: $knownNodes"
        )
        return@withContext OverlayProbeOutcome(
            confirmed = true,
            rttMs = rtt,
            remoteKeyHex = remoteKeyHex,
            multiHop = multiHop,
            livePeers = livePeers,
            knownNodes = knownNodes,
            txBytes = txBytes,
            errorDetail = null
        )
    }

    /**
     * Routes an outgoing message (between two profiles or to any Yggdrasil 0200::/7 node)
     * through the REAL Yggdrasil overlay over the internet: the sender's own node dispatches
     * the datagram to the recipient node's Ed25519 key. No loopback (127.0.0.1), no LAN
     * shortcuts, no simulated peer transit — the recipient ACKs through the same overlay.
     */
    suspend fun dispatchMessageViaExternalYggdrasilOverlay(
        chatId: String,
        messageId: String,
        peerTitle: String,
        peerIpv6: String,
        directEndpointHint: String = "",
        senderNodeLabel: String,
        senderIpv6: String,
        senderPubKeyHex: String = "",
        recipientNodePubKeyHex: String = "",
        plainText: String,
        cipherPayloadHex: String,
        hashcashToken: String,
        attachmentName: String?,
        attachmentSizeBytes: Long,
        candidatePeers: List<Triple<String, String, String>>,
        allowLanFallback: Boolean = allowDirectLanRouting,
        attachmentType: String? = null,
        attachmentUri: String? = null,
        attachmentBase64: String? = null,
        voiceDurationSec: Int = 0,
        voiceWaveformCsv: String? = null,
        replyToMessageId: String = "",
        forwardedFrom: String = "",
        senderAvatarHash: String = "",
        senderAvatarBase64: String = "",
        groupId: String = "",
        groupTitle: String = "",
        groupMembersCsv: String = ""
    ): SelfYggdrasilRelayResult = withContext(Dispatchers.IO) {
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.lowercase()] ?: ""
        val targetProfileId = ipv6ToProfileIdMap[peerIpv6.trim().lowercase()]
            ?: recipientNodePubKeyHex.trim().lowercase().takeIf { it.isNotBlank() }?.let { pubKeyToProfileIdMap[it] }

        if (targetProfileId != null && !initializedNativeProfiles.contains(targetProfileId)) {
            val err = "Целевой профиль $peerIpv6 остановлен в фоновом режиме"
            logPacket(
                direction = "ERR • YGG",
                summary = "Целевой профиль «$peerTitle» не активен в фоне",
                details = "Сообщение переведено в очередь Store-and-Forward до включения профиля"
            )
            return@withContext SelfYggdrasilRelayResult(
                delivered = false,
                relayEndpointUri = "",
                relayRegionLabel = "",
                rttMs = -1L,
                txBytes = 0L,
                rxBytes = 0L,
                errorDetail = err
            )
        }

        val hasInternet = isRealNetworkAvailable()
        val senderCoreReady = senderProfileId.isNotBlank() && initializedNativeProfiles.contains(senderProfileId)
        // The overlay addresses packets by the recipient node's Ed25519 public key.
        val recipientKeyHex = resolveRecipientNodeKey(peerIpv6, recipientNodePubKeyHex)
        val senderNodeKey = profileIdToPubKeyMap[senderProfileId]?.trim()?.lowercase().orEmpty()
        val isOwnNodeKey = recipientKeyHex.isNotBlank() && recipientKeyHex.equals(senderNodeKey, ignoreCase = true)
        val isLocalProfileTarget = targetProfileId != null && initializedNativeProfiles.contains(targetProfileId)
        val directAddress = resolveDirectSocketAddress(
            peerIpv6 = peerIpv6,
            directHint = directEndpointHint,
            senderIpv6 = senderIpv6,
            allowPassiveLan = allowLanFallback
        )

        if (isOwnNodeKey) {
            val err = "Получатель использует тот же ключ ноды, что и отправитель. Оверлей не может адресовать узел сам себе — задайте устройствам разные мнемоники."
            logPacket(
                direction = "ERR • YGG",
                summary = "Отправка на собственный ключ ноды невозможна",
                details = "Ключ ${recipientKeyHex.take(16)}... совпадает с ключом активного профиля отправителя"
            )
            return@withContext SelfYggdrasilRelayResult(
                delivered = false,
                relayEndpointUri = "",
                relayRegionLabel = "",
                rttMs = -1L,
                txBytes = 0L,
                rxBytes = 0L,
                errorDetail = err
            )
        }

        if (recipientKeyHex.isBlank() && directAddress == null) {
            val err = "Неизвестен Ed25519-ключ ноды получателя: добавьте контакт по полной ссылке ygggram://…?pub=… или включите LAN-поиск, чтобы получить ключ от узла в локальной сети"
            logPacket(
                direction = "ERR • YGG",
                summary = "Ключ ноды получателя неизвестен — доставка невозможна",
                details = "Сеть Yggdrasil адресует пакеты Ed25519-ключом ноды, а LAN-маршрут для этого контакта не задан"
            )
            return@withContext SelfYggdrasilRelayResult(
                delivered = false,
                relayEndpointUri = "",
                relayRegionLabel = "",
                rttMs = -1L,
                txBytes = 0L,
                rxBytes = 0L,
                errorDetail = err
            )
        }

        val overlayPossible = hasInternet && senderCoreReady && recipientKeyHex.isNotBlank() && !isOwnNodeKey
        var livePeers = 0
        var coreLogTail = ""
        if (overlayPossible) {
            // Do not stall an available LAN fallback behind the full peer wait.
            livePeers = awaitLivePeer(senderProfileId, waitMs = if (directAddress != null) 4000L else 15_000L)
            if (livePeers <= 0) {
                coreLogTail = YggNativeCoreBridge.getLinkLogTail(8).joinToString(" | ") { it }
            }
        }
        val overlayReady = overlayPossible && livePeers > 0

        when {
            overlayReady -> logPacket(
                direction = "SYS • CORE",
                summary = "Нода отправителя держит живой пиринг ($livePeers) — маршрут 0200::/7 доступен",
                details = "Пакет уйдет в интернет-оверлей Yggdrasil к «$peerTitle»"
            )
            directAddress != null -> logPacket(
                direction = "SYS • LAN",
                summary = "Оверлей Yggdrasil недоступен — используется прямой LAN-маршрут",
                details = "Причина: ${when {
                    !hasInternet -> if (isAirplaneModeActive()) "Авиарежим" else "нет интернета"
                    !senderCoreReady -> "нода отправителя не активна"
                    recipientKeyHex.isBlank() -> "ключ получателя неизвестен"
                    else -> "нет живых пиров Yggdrasil"
                }} • ${directAddress.hostString}:${directAddress.port}"
            )
            else -> {
                val diagTail = coreLogTail.takeLast(240)
                val reason = when {
                    !hasInternet -> if (isAirplaneModeActive()) "Активен Авиарежим" else "Нет подключения к интернету"
                    recipientKeyHex.isBlank() -> "Неизвестен Ed25519-ключ ноды получателя"
                    !senderCoreReady -> "Нода Yggdrasil отправителя не активна"
                    livePeers <= 0 -> if (diagTail.isBlank()) "Нода Yggdrasil без живых пиров"
                    else "Нода без пиров • лог ядра: $diagTail"
                    else -> "LAN-маршрут к получателю не задан"
                }
                logPacket(
                    direction = "ERR • CORE",
                    summary = "Доставка невозможна: $reason",
                    details = "Сообщение останется в очереди S&F до появления реального маршрута"
                )
                return@withContext SelfYggdrasilRelayResult(
                    delivered = false,
                    relayEndpointUri = "",
                    relayRegionLabel = "",
                    rttMs = -1L,
                    txBytes = 0L,
                    rxBytes = 0L,
                    errorDetail = reason
                )
            }
        }

        // A file above the inline ceiling is never turned into one Base64 blob: the caller hands
        // over no payload and the bytes travel as separately encrypted pieces, each acknowledged
        // by the peer and resumable from the first piece it does not have yet.
        val streamSource = if (attachmentBase64.isNullOrBlank() && !attachmentName.isNullOrBlank()) {
            streamedSourceFile(attachmentUri)
        } else {
            null
        }
        var streamedOverlayOk = false
        if (streamSource != null) {
            if (!overlayReady) {
                val err = "Файл «$attachmentName» (${streamSource.length() / (1024 * 1024)} МБ) передаётся " +
                    "потоком по кускам, а для этого нужен оверлей Yggdrasil — он сейчас не готов"
                logPacket(
                    direction = "ERR • STREAM",
                    summary = "Поток файла не начат: оверлей недоступен",
                    details = "«$attachmentName» • ${streamSource.length() / 1024} КБ • сообщение остаётся в очереди S&F"
                )
                return@withContext SelfYggdrasilRelayResult(
                    delivered = false,
                    relayEndpointUri = "",
                    relayRegionLabel = "",
                    rttMs = -1L,
                    txBytes = 0L,
                    rxBytes = 0L,
                    errorDetail = err
                )
            }
            val stream = streamFileOverOverlay(
                senderProfileId = senderProfileId,
                recipientKeyHex = recipientKeyHex,
                transferId = messageId,
                chatId = chatId,
                sourceFile = streamSource,
                attachmentName = attachmentName,
                attachmentType = attachmentType,
                peerIpv6 = peerIpv6,
                senderIpv6 = senderIpv6,
                senderPubKeyHex = senderPubKeyHex,
                senderNodeLabel = senderNodeLabel
            ) { frame, kind -> sendOverlayFrame(senderProfileId, recipientKeyHex, frame, kind) }
            if (!stream.delivered) {
                return@withContext SelfYggdrasilRelayResult(
                    delivered = false,
                    relayEndpointUri = "",
                    relayRegionLabel = "",
                    rttMs = -1L,
                    txBytes = 0L,
                    rxBytes = 0L,
                    errorDetail = stream.errorDetail
                )
            }
            streamedOverlayOk = true
        }
        if (streamSource == null && attachmentBase64.isNullOrBlank() && !attachmentName.isNullOrBlank()) {
            // A MSG that names a file nobody can open would still be ACKed as delivered, so the
            // note stays in the queue with the reason instead.
            val err = "Вложение «$attachmentName» не передаётся: локальный файл не найден по адресу " +
                "(${attachmentUri?.take(48) ?: "пусто"}) — сообщение остаётся в очереди S&F"
            logPacket(
                direction = "ERR • ATTACH",
                summary = "Вложение не отправлено: файл недоступен на устройстве",
                details = "messageId=${messageId.take(12)} • «$attachmentName»"
            )
            return@withContext SelfYggdrasilRelayResult(
                delivered = false,
                relayEndpointUri = "",
                relayRegionLabel = "",
                rttMs = -1L,
                txBytes = 0L,
                rxBytes = 0L,
                errorDetail = err
            )
        }

        // A single Yggdrasil datagram cannot carry an inline Base64 attachment larger
        // than the ~64 KB MTU. Large attachments travel first as AEAD-adjacent chunk
        // frames (each acknowledged), then the MSG frame references the assembled blob.
        val attachmentNeedsChunks = !attachmentBase64.isNullOrBlank() &&
            attachmentBase64!!.length > ATTACHMENT_INLINE_MAX_CHARS
        var chunkedOverlayConfirmed = false
        if (overlayReady && attachmentNeedsChunks) {
            chunkedOverlayConfirmed = sendAttachmentChunksOverOverlay(
                senderProfileId = senderProfileId,
                recipientKeyHex = recipientKeyHex,
                transferId = messageId,
                chatId = chatId,
                recipientIpv6 = peerIpv6,
                senderIpv6 = senderIpv6,
                senderPubKeyHex = senderPubKeyHex,
                senderNodeLabel = senderNodeLabel,
                attachmentBase64 = attachmentBase64!!,
                attachmentName = attachmentName,
                attachmentType = attachmentType
            )
            if (!chunkedOverlayConfirmed && directAddress == null) {
                val err = "Вложение не передано: оверлей не подтвердил чанки, LAN-маршрут отсутствует"
                logPacket(
                    direction = "ERR • ATTACH",
                    summary = "Крупное вложение не доставлено через оверлей",
                    details = "messageId=${messageId.take(12)} • ${attachmentBase64.length / 1024} KB Base64 • сообщение остается в очереди S&F"
                )
                return@withContext SelfYggdrasilRelayResult(
                    delivered = false,
                    relayEndpointUri = "",
                    relayRegionLabel = "",
                    rttMs = -1L,
                    txBytes = 0L,
                    rxBytes = 0L,
                    errorDetail = err
                )
            }
        }

        val now = System.currentTimeMillis()
        val senderPort = activeProfilePortMap[senderProfileId] ?: localTcpPort
        // Repeat the bytes until the recipient ACKs having seen this exact hash, then go
        // back to sending nothing but the hash itself — a changed picture must not cost
        // 12 KB on every message forever, and an unconfirmed one must not be forgotten.
        val avatarRidesAlong = senderAvatarHash.isNotBlank() &&
            senderAvatarBase64.isNotBlank() &&
            advertisedAvatarHashes[chatId] != senderAvatarHash
        val messageFields = linkedMapOf(
            "type" to "MSG",
            "chatId" to chatId,
            "messageId" to messageId,
            "recipientIpv6" to peerIpv6,
            "senderNodeLabel" to senderNodeLabel,
            "senderIpv6" to senderIpv6,
            "senderPubKeyHex" to senderPubKeyHex,
            "senderTcpPort" to senderPort.toString(),
            "cipherPayloadHex" to cipherPayloadHex,
            "hashcashToken" to hashcashToken,
            "attachmentName" to (attachmentName ?: ""),
            "attachmentSizeBytes" to attachmentSizeBytes.toString(),
            "attachmentType" to (attachmentType ?: ""),
            "attachmentUri" to (attachmentUri ?: ""),
            "attachmentBase64" to (if (chunkedOverlayConfirmed || streamedOverlayOk) "" else (attachmentBase64 ?: "")),
            "attachmentChunked" to when {
                streamedOverlayOk -> "2"
                chunkedOverlayConfirmed -> "1"
                else -> "0"
            },
            "voiceDurationSec" to voiceDurationSec.toString(),
            "voiceWaveformCsv" to (voiceWaveformCsv ?: ""),
            "replyToMessageId" to replyToMessageId,
            "forwardedFrom" to forwardedFrom,
            "senderAvatarHash" to senderAvatarHash,
            "senderAvatarData" to (if (avatarRidesAlong) senderAvatarBase64 else ""),
            "groupId" to groupId,
            "groupTitle" to groupTitle,
            "groupMembers" to groupMembersCsv,
            "sentAt" to now.toString(),
            "timestamp" to now.toString()
        )
        val wirePayload = encodeWireFrame(messageFields)
        // A TCP LAN frame is not limited by the ~64 KB overlay datagram, so the direct
        // path always carries the attachment inline even after the overlay chunks were
        // confirmed. Otherwise a LAN-delivered MSG would reference chunks the recipient
        // never received over that transport.
        val lanWirePayload = if (chunkedOverlayConfirmed && !attachmentBase64.isNullOrBlank()) {
            encodeWireFrame(
                messageFields + mapOf(
                    "attachmentBase64" to attachmentBase64,
                    "attachmentChunked" to "0"
                )
            )
        } else {
            wirePayload
        }
        val totalOutboundBytes = wirePayload.length.toLong() + (attachmentBase64?.length ?: 0)

        var ackDeferred: kotlinx.coroutines.CompletableDeferred<Long>? = null
        if (avatarRidesAlong) pendingAvatarAdverts[messageId] = chatId to senderAvatarHash
        if (overlayReady) {
            logPacket(
                direction = "TX -> YGG",
                summary = "Пакет направляется в интернет-оверлей Yggdrasil к «$peerTitle»",
                details = "Отправитель: $senderIpv6 → получатель: $peerIpv6 • AEAD: ${cipherPayloadHex.take(24)}... • PoW: $hashcashToken"
            )
            val deferred = kotlinx.coroutines.CompletableDeferred<Long>()
            ackDeferred = deferred
            pendingOverlayAcks[messageId] = deferred
            if (sendOverlayFrame(senderProfileId, recipientKeyHex, wirePayload, "MSG")) {
                onTwinReachableChanged(true)
                // Do not make an explicit LAN route wait the full overlay budget: a slow
                // or still-converging overlay path must not delay confirmed LAN delivery.
                val ackWaitMs = if (directAddress != null) 3500L else 8000L
                val ackRtt = kotlinx.coroutines.withTimeoutOrNull(ackWaitMs) { deferred.await() }
                if (ackRtt != null) {
                    pendingOverlayAcks.remove(messageId, deferred)
                    logPacket(
                        direction = "ACK <- YGG",
                        summary = "Сквозная доставка через оверлей Yggdrasil подтверждена за $ackRtt мс",
                        details = "«$peerTitle» подтвердил прием AEAD-конверта • TX: $totalOutboundBytes B"
                    )
                    return@withContext SelfYggdrasilRelayResult(
                        delivered = true,
                        relayEndpointUri = "overlay://yggdrasil-0200::7",
                        relayRegionLabel = "Интернет • оверлей Yggdrasil",
                        rttMs = ackRtt,
                        txBytes = totalOutboundBytes,
                        // shortcut: the ACK datagram size is only known inside handleOverlayFrame and
                        // is not plumbed here; a fixed 128 used to inflate the peer traffic counter.
                        rxBytes = 0L,
                        endToEndConfirmed = true,
                        transport = "YGG_OVERLAY"
                    )
                }
            } else {
                pendingOverlayAcks.remove(messageId, deferred)
                ackDeferred = null
                logPacket(
                    direction = "ERR • YGG",
                    summary = "Оверлей Yggdrasil не принял пакет для «$peerTitle»",
                    details = "Нет маршрута к ключу ${recipientKeyHex.take(16)}..."
                )
            }
        }

        // Direct LAN fallback. An explicit lan= endpoint comes from the contact and is
        // user consent, so it works even without internet and with the global toggle off.
        if (directAddress != null) {
            logPacket(
                direction = "TX -> LAN",
                summary = "Пакет направляется напрямую по LAN к «$peerTitle»",
                details = "${directAddress.hostString}:${directAddress.port} • AEAD: ${cipherPayloadHex.take(24)}..."
            )
            val lanRtt = sendDirectFrameAndAwaitAck(
                targetSocket = directAddress,
                wirePayload = lanWirePayload,
                chatId = chatId,
                messageId = messageId,
                peerTitle = peerTitle,
                connectTimeoutMs = if (overlayReady) 2500 else 4500,
                readTimeoutMs = if (overlayReady) 3000 else 7000
            )
            if (lanRtt != null) {
                markAvatarAdvertised(messageId)
                ackDeferred?.let {
                    it.complete(lanRtt)
                    pendingOverlayAcks.remove(messageId, it)
                }
                val transport = if (isLocalProfileTarget) "LOCAL_PROFILE" else "LAN_DIRECT"
                return@withContext SelfYggdrasilRelayResult(
                    delivered = true,
                    relayEndpointUri = if (transport == "LAN_DIRECT") "lan://${directAddress.hostString}:${directAddress.port}" else "",
                    relayRegionLabel = if (transport == "LAN_DIRECT") "Локальная сеть • прямое TCP" else "Локальный профиль устройства",
                    rttMs = lanRtt,
                    txBytes = totalOutboundBytes,
                    rxBytes = 0L,
                    endToEndConfirmed = true,
                    transport = transport
                )
            }
        }

        val pendingAck = ackDeferred
        if (pendingAck == null) {
            val reason = when {
                !hasInternet -> (if (isAirplaneModeActive()) "Активен Авиарежим" else "Нет подключения к интернету") +
                    if (directAddress != null) "; LAN-маршрут не ответил ACK" else ""
                recipientKeyHex.isBlank() -> "Неизвестен Ed25519-ключ ноды получателя"
                !senderCoreReady -> "Нода Yggdrasil отправителя не активна"
                livePeers <= 0 -> "Нода Yggdrasil без живых пиров" +
                    if (directAddress != null) "; LAN-маршрут не ответил ACK" else ""
                else -> "Оверлей Yggdrasil не принял пакет, LAN-маршрут не ответил ACK"
            }
            return@withContext SelfYggdrasilRelayResult(
                delivered = false,
                relayEndpointUri = "",
                relayRegionLabel = "",
                rttMs = -1L,
                txBytes = 0L,
                rxBytes = 0L,
                errorDetail = reason
            )
        }

        // Path/session convergence on 0200::/7 is asynchronous (bloom/path lookup plus
        // the ironwood encrypted-layer init take tens of seconds after a cold start,
        // and public-peer links flap), so a single WriteTo is routinely dropped.
        // Keep retrying both transports until an actual ACK arrives.
        scope.launch {
            var attempt = 1
            val retryDeadline = System.currentTimeMillis() + 112_000L
            while (isActive && System.currentTimeMillis() < retryDeadline) {
                delay(8000L)
                if (pendingAck.isCompleted) break
                attempt++
                var lanConfirmed = false
                if (directAddress != null) {
                    lanConfirmed = sendDirectFrameAndAwaitAck(
                        targetSocket = directAddress,
                        wirePayload = lanWirePayload,
                        chatId = chatId,
                        messageId = messageId,
                        peerTitle = peerTitle,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2500
                    ) != null
                    if (lanConfirmed) {
                        markAvatarAdvertised(messageId)
                        pendingAck.complete(1L)
                    }
                }
                if (lanConfirmed) break
                val resent = sendOverlayFrame(senderProfileId, recipientKeyHex, wirePayload, "MSG")
                logPacket(
                    direction = "TX -> YGG",
                    summary = "Повторная отправка №$attempt в оверлей к «$peerTitle»",
                    details = "Маршрут и сессия Yggdrasil сходятся асинхронно • ok=$resent • messageId=${messageId.take(12)}"
                )
            }
            pendingOverlayAcks.remove(messageId, pendingAck)
            pendingAvatarAdverts.remove(messageId)
            if (!pendingAck.isCompleted) {
                logPacket(
                    direction = "ERR • YGG",
                    summary = "ACK от «$peerTitle» не получен за 2 минуты — сообщение возвращено в очередь",
                    details = "messageId=${messageId.take(12)} • выполнено попыток: $attempt • линки ядра " +
                        "переподключены, автоповтор от 45 с до 15 мин"
                )
                // A path entry outlives the links it was learned on: the frame is then
                // forwarded into a dead path and vanishes with no error anywhere. Put the
                // message back in the queue and interrupt the dial backoff, so the next
                // drain runs over refreshed links instead of the same stale path.
                YggNativeCoreBridge.retryPeers(senderProfileId)
                onDeliveryAckTimeout(chatId, messageId, attempt)
            }
        }

        // WriteTo succeeding is not delivery: without a path entry the pathfinder only
        // starts a rumour lookup and the frame is dropped if that lookup fails. Say so
        // instead of promoting the message to «Ожидает доставки».
        if (YggNativeCoreBridge.hasOverlayRoute(senderProfileId, recipientKeyHex) == false) {
            logPacket(
                direction = "ERR • YGG",
                summary = "Маршрут к узлу «$peerTitle» в оверлее не найден",
                details = "Пакет запущен в поиск маршрута • ACK не получен • messageId=${messageId.take(12)}"
            )
            return@withContext SelfYggdrasilRelayResult(
                delivered = false,
                relayEndpointUri = "",
                relayRegionLabel = "",
                rttMs = -1L,
                txBytes = totalOutboundBytes,
                rxBytes = 0L,
                errorDetail = "Узел получателя не виден в оверлее: маршрута к его Ed25519-ключу нет, поиск маршрута ответа не дал"
            )
        }

        logPacket(
            direction = "TX -> YGG",
            summary = "Пакет ушёл по найденному маршруту; ACK от «$peerTitle» еще не получен",
            details = "Статус: ожидается ACK • повторы идут в фоне"
        )
        return@withContext SelfYggdrasilRelayResult(
            delivered = true,
            relayEndpointUri = "overlay://yggdrasil-0200::7",
            relayRegionLabel = "Интернет • оверлей Yggdrasil",
            rttMs = 0L,
            txBytes = totalOutboundBytes,
            rxBytes = 0L,
            endToEndConfirmed = false,
            transport = "YGG_OVERLAY"
        )
    }

    /**
     * Sends an encrypted P2P message over a direct socket (used by unit tests or when manual LAN mode is enabled).
     */
    suspend fun dispatchOutgoingMessageThroughYggdrasil(
        chatId: String,
        messageId: String,
        peerTitle: String,
        peerIpv6: String,
        directEndpointHint: String = "",
        senderNodeLabel: String,
        senderIpv6: String,
        senderPubKeyHex: String = "",
        plainText: String,
        cipherPayloadHex: String,
        hashcashToken: String,
        attachmentName: String?,
        attachmentSizeBytes: Long,
        attachmentType: String? = null,
        attachmentUri: String? = null,
        attachmentBase64: String? = null,
        voiceDurationSec: Int = 0,
        voiceWaveformCsv: String? = null,
        replyToMessageId: String = "",
        forwardedFrom: String = "",
        senderAvatarHash: String = "",
        senderAvatarBase64: String = "",
        groupId: String = "",
        groupTitle: String = "",
        groupMembersCsv: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (directEndpointHint.isNotBlank() && allowDirectLanRouting) {
            registerDirectEndpointForPeer(peerIpv6, directEndpointHint)
        }

        val targetSocketAddress = resolveTargetSocketAddress(peerIpv6, directEndpointHint, senderIpv6)
        val isTargetLocalActiveProfile = ipv6ToProfileIdMap.containsKey(peerIpv6.lowercase()) && targetSocketAddress != null

        if (!isTargetLocalActiveProfile && !isRealNetworkAvailable()) {
            logPacket(
                direction = "ERR • NET",
                summary = "Отправка невозможна: устройство офлайн / Авиарежим",
                details = "Сообщение $messageId переведено в очередь ожидания сети"
            )
            return@withContext false
        }

        if (targetSocketAddress == null) {
            logPacket(
                direction = "TX -> QUEUE",
                summary = "Маршрут к узлу $peerIpv6 не найден в активных сокетах",
                details = "Для доставки требуется активный пир Yggdrasil"
            )
            return@withContext false
        }

        val now = System.currentTimeMillis()
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.lowercase()]
        val senderPort = (if (senderProfileId != null) activeProfilePortMap[senderProfileId] else null) ?: localTcpPort
        logPacket(
            direction = "TX -> LAN",
            summary = "Подключение к «$peerTitle» (${targetSocketAddress.hostString}:${targetSocketAddress.port})",
            details = "AEAD: ${cipherPayloadHex.take(24)}... • PoW: $hashcashToken"
        )

        return@withContext try {
            Socket().use { socket ->
                socket.soTimeout = 7000
                socket.connect(targetSocketAddress, 4500)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))

                val wirePayload = encodeWireFrame(
                    mapOf(
                        "type" to "MSG",
                        "chatId" to chatId,
                        "messageId" to messageId,
                        "recipientIpv6" to peerIpv6,
                        "senderNodeLabel" to senderNodeLabel,
                        "senderIpv6" to senderIpv6,
                        "senderPubKeyHex" to senderPubKeyHex,
                        "senderTcpPort" to senderPort.toString(),
                        "cipherPayloadHex" to cipherPayloadHex,
                        "hashcashToken" to hashcashToken,
                        "attachmentName" to (attachmentName ?: ""),
                        "attachmentSizeBytes" to attachmentSizeBytes.toString(),
                        "attachmentType" to (attachmentType ?: ""),
                        "attachmentUri" to (attachmentUri ?: ""),
                        "attachmentBase64" to (attachmentBase64 ?: ""),
                        "voiceDurationSec" to voiceDurationSec.toString(),
                        "voiceWaveformCsv" to (voiceWaveformCsv ?: ""),
                        "replyToMessageId" to replyToMessageId,
                        "forwardedFrom" to forwardedFrom,
                        "senderAvatarHash" to senderAvatarHash,
                        "senderAvatarData" to (
                            if (senderAvatarHash.isNotBlank() && senderAvatarBase64.isNotBlank() &&
                                advertisedAvatarHashes[chatId] != senderAvatarHash
                            ) senderAvatarBase64 else ""
                            ),
                        "groupId" to groupId,
                        "groupTitle" to groupTitle,
                        "groupMembers" to groupMembersCsv,
                        "sentAt" to now.toString(),
                        "timestamp" to now.toString()
                    )
                )
                writer.println(wirePayload)

                val responseLine = reader.readLine()
                if (responseLine != null) {
                    val ackMap = decodeWireFrame(responseLine)
                    if (ackMap["type"] == "ACK" && ackMap["messageId"] == messageId) {
                        val rtt = (System.currentTimeMillis() - now).coerceAtLeast(1L)
                        onTwinReachableChanged(true)
                        onDeliveryAckReceived(chatId, messageId, rtt, peerIpv6)
                        if (senderAvatarHash.isNotBlank() && senderAvatarBase64.isNotBlank()) {
                            advertisedAvatarHashes[chatId] = senderAvatarHash
                        }
                        logPacket(
                            direction = "ACK <- PEER",
                            summary = "Подтверждена реальная доставка пакета ${messageId.take(10)} (RTT: $rtt мс)",
                            details = "Узел ${targetSocketAddress.hostString}:${targetSocketAddress.port} верифицировал AEAD пакет"
                        )
                        return@use true
                    }
                }
                false
            }
        } catch (e: Exception) {
            logPacket(
                direction = "ERR • TX",
                summary = "Узел «$peerTitle» недоступен (${e.javaClass.simpleName})",
                details = "Адрес: ${targetSocketAddress.hostString}:${targetSocketAddress.port} — пакет сохранен в очереди S&F"
            )
            false
        }
    }

    /**
     * Delivers one reaction event to the peer. The message id on the wire is the id the
     * message has on the receiver (local id of our outgoing message, or the stored
     * peerMessageId of an incoming one), so the receiver can always resolve it.
     * Overlay Yggdrasil is tried first with a retry, then an explicit/direct LAN route.
     * Only an ACK echoing this reaction's event id counts as confirmed delivery.
     */
    suspend fun dispatchReactionFrame(
        chatId: String,
        messageId: String,
        emoji: String,
        action: String,
        peerIpv6: String,
        directEndpointHint: String,
        senderIpv6: String,
        senderPubKeyHex: String = "",
        recipientNodePubKeyHex: String = "",
        candidatePeers: List<Triple<String, String, String>> = emptyList(),
        allowLanFallback: Boolean = allowDirectLanRouting
    ): ReactionDispatchResult = withContext(Dispatchers.IO) {
        if (directEndpointHint.isNotBlank()) {
            registerDirectEndpointForPeer(peerIpv6, directEndpointHint)
        }
        val normalizedAction = if (action.equals("REMOVE", ignoreCase = true)) "REMOVE" else "ADD"
        val actionLabel = if (normalizedAction == "REMOVE") "Снятие реакции $emoji" else "Реакция $emoji"
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.trim().lowercase()] ?: ""
        val recipientKeyHex = resolveRecipientNodeKey(peerIpv6, recipientNodePubKeyHex)
        val directAddress = resolveDirectSocketAddress(
            peerIpv6 = peerIpv6,
            directHint = directEndpointHint,
            senderIpv6 = senderIpv6,
            allowPassiveLan = allowLanFallback
        )
        val hasInternet = isRealNetworkAvailable()
        val senderCoreReady = senderProfileId.isNotBlank() && initializedNativeProfiles.contains(senderProfileId)
        val senderPort = activeProfilePortMap[senderProfileId] ?: localTcpPort
        val reactionId = "rct_${java.util.UUID.randomUUID()}"
        val wirePayload = encodeWireFrame(
            mapOf(
                "type" to "REACTION",
                "reactionId" to reactionId,
                "chatId" to chatId,
                "messageId" to messageId,
                "emoji" to emoji,
                "action" to normalizedAction,
                "senderIpv6" to senderIpv6,
                "senderPubKeyHex" to senderPubKeyHex,
                "senderTcpPort" to senderPort.toString(),
                "recipientIpv6" to peerIpv6,
                "sentAt" to System.currentTimeMillis().toString()
            )
        )

        var overlayPossible = hasInternet && senderCoreReady && recipientKeyHex.isNotBlank()
        var livePeers = 0
        if (overlayPossible) {
            livePeers = awaitLivePeer(senderProfileId, waitMs = if (directAddress != null) 3500L else 6000L)
            if (livePeers <= 0) overlayPossible = false
        }
        if (overlayPossible) {
            logPacket(
                direction = "TX -> YGG",
                summary = "$actionLabel направлен в оверлей Yggdrasil",
                details = "К получателю $peerIpv6 • reactionId=${reactionId.take(12)} • Живых пиров: $livePeers"
            )
            val deferred = kotlinx.coroutines.CompletableDeferred<Long>()
            pendingReactionAcks[reactionId] = deferred
            for (attempt in 1..2) {
                if (!sendOverlayFrame(senderProfileId, recipientKeyHex, wirePayload, "REACTION")) break
                val ackRtt = kotlinx.coroutines.withTimeoutOrNull(
                    if (directAddress != null) 2500L else 4000L
                ) { deferred.await() }
                if (ackRtt != null) {
                    pendingReactionAcks.remove(reactionId, deferred)
                    onTwinReachableChanged(true)
                    logPacket(
                        direction = "ACK <- YGG",
                        summary = "$actionLabel доставлена через оверлей Yggdrasil (ACK, RTT: $ackRtt мс)",
                        details = "Узел $peerIpv6 подтвердил событие ${reactionId.take(12)} по сообщению ${messageId.take(16)}..."
                    )
                    return@withContext ReactionDispatchResult(
                        delivered = true,
                        endToEndConfirmed = true,
                        transport = "YGG_OVERLAY",
                        rttMs = ackRtt
                    )
                }
            }
            pendingReactionAcks.remove(reactionId, deferred)
        }

        if (directAddress != null) {
            logPacket(
                direction = "TX -> LAN",
                summary = "$actionLabel направлена напрямую по LAN",
                details = "${directAddress.hostString}:${directAddress.port} • reactionId=${reactionId.take(12)}"
            )
            try {
                val startedAt = System.currentTimeMillis()
                var lanRtt: Long? = null
                Socket().use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(directAddress, 3500)
                    val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    writer.println(wirePayload)
                    val reply = reader.readLine()?.let { decodeWireFrame(it) }
                    if (reply?.get("type") == "ACK" &&
                        reply["messageId"] == messageId &&
                        reply["reactionId"].orEmpty() == reactionId
                    ) {
                        lanRtt = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                    }
                }
                val confirmedRtt = lanRtt
                if (confirmedRtt != null) {
                    onTwinReachableChanged(true)
                    logPacket(
                        direction = "ACK <- PEER",
                        summary = "$actionLabel доставлена по LAN и подтверждена ACK",
                        details = "Адресат: $peerIpv6 • ${directAddress.hostString}:${directAddress.port} • reactionId=${reactionId.take(12)}"
                    )
                    return@withContext ReactionDispatchResult(
                        delivered = true,
                        endToEndConfirmed = true,
                        transport = "LAN_DIRECT",
                        rttMs = confirmedRtt
                    )
                }
            } catch (e: Exception) {
                logPacket(
                    direction = "ERR • REACTION",
                    summary = "$actionLabel не доставлена по LAN (${e.javaClass.simpleName})",
                    details = "Адресат $peerIpv6 • ${directAddress.hostString}:${directAddress.port}"
                )
            }
        }

        val reason = when {
            !hasInternet && directAddress == null -> "Нет подключения к интернету, LAN-маршрут к получателю не задан"
            recipientKeyHex.isBlank() && directAddress == null ->
                "Неизвестен Ed25519-ключ получателя: добавьте контакт по полной ссылке ygggram://…?pub=… или задайте lan=…"
            !senderCoreReady && directAddress == null -> "Нода Yggdrasil отправителя не активна"
            !overlayPossible && livePeers <= 0 && directAddress == null -> "Нода Yggdrasil без живых пиров"
            else -> "Оверлей Yggdrasil и LAN-маршрут не подтвердили доставку ACK"
        }
        logPacket(
            direction = "ERR • REACTION",
            summary = "$actionLabel не доставлена",
            details = "Причина: $reason • адресат $peerIpv6 • reactionId=${reactionId.take(12)}"
        )
        ReactionDispatchResult(
            delivered = false,
            endToEndConfirmed = false,
            transport = "",
            rttMs = -1L,
            errorDetail = reason
        )
    }

    /**
     * Asks one node to erase the copy of [messageId] it holds. [proofEnvelope] is the AEAD proof
     * of authorship produced for that node; without it the receiving device has no way to tell
     * the request from a stranger guessing a message id, so the call is refused here as well.
     * Delivery is confirmed by that node's ACK, keyed by [deleteId].
     */
    suspend fun dispatchDeleteFrame(
        chatId: String,
        messageId: String,
        deleteId: String,
        proofEnvelope: String,
        peerIpv6: String,
        directEndpointHint: String,
        senderIpv6: String,
        senderPubKeyHex: String = "",
        recipientNodePubKeyHex: String = "",
        candidatePeers: List<Triple<String, String, String>> = emptyList(),
        allowLanFallback: Boolean = allowDirectLanRouting
    ): DeleteDispatchResult = withContext(Dispatchers.IO) {
        if (proofEnvelope.isBlank()) {
            return@withContext DeleteDispatchResult(
                delivered = false,
                errorDetail = "Нет ключа собеседника: доказать авторство сообщения нечем"
            )
        }
        if (directEndpointHint.isNotBlank()) {
            registerDirectEndpointForPeer(peerIpv6, directEndpointHint)
        }
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.trim().lowercase()] ?: ""
        val recipientKeyHex = resolveRecipientNodeKey(peerIpv6, recipientNodePubKeyHex)
        val directAddress = resolveDirectSocketAddress(
            peerIpv6 = peerIpv6,
            directHint = directEndpointHint,
            senderIpv6 = senderIpv6,
            allowPassiveLan = allowLanFallback
        )
        val hasInternet = isRealNetworkAvailable()
        val senderCoreReady = senderProfileId.isNotBlank() && initializedNativeProfiles.contains(senderProfileId)
        val senderPort = activeProfilePortMap[senderProfileId] ?: localTcpPort
        val wirePayload = encodeWireFrame(
            mapOf(
                "type" to "MSG_DELETE",
                "deleteId" to deleteId,
                "chatId" to chatId,
                "messageId" to messageId,
                "proof" to proofEnvelope,
                "senderIpv6" to senderIpv6,
                "senderPubKeyHex" to senderPubKeyHex,
                "senderTcpPort" to senderPort.toString(),
                "recipientIpv6" to peerIpv6,
                "sentAt" to System.currentTimeMillis().toString()
            )
        )

        var overlayPossible = hasInternet && senderCoreReady && recipientKeyHex.isNotBlank()
        var livePeers = 0
        if (overlayPossible) {
            livePeers = awaitLivePeer(senderProfileId, waitMs = if (directAddress != null) 3500L else 6000L)
            if (livePeers <= 0) overlayPossible = false
        }
        if (overlayPossible) {
            logPacket(
                direction = "TX -> YGG",
                summary = "Запрос удалить сообщение направлен в оверлей Yggdrasil",
                details = "К получателю $peerIpv6 • deleteId=${deleteId.take(12)} • Живых пиров: $livePeers",
                mirror = true
            )
            val deferred = kotlinx.coroutines.CompletableDeferred<Long>()
            pendingDeleteAcks[deleteId] = deferred
            for (attempt in 1..2) {
                if (!sendOverlayFrame(senderProfileId, recipientKeyHex, wirePayload, "MSG_DELETE")) break
                val ackRtt = kotlinx.coroutines.withTimeoutOrNull(
                    if (directAddress != null) 2500L else 4000L
                ) { deferred.await() }
                if (ackRtt != null) {
                    pendingDeleteAcks.remove(deleteId, deferred)
                    onTwinReachableChanged(true)
                    logPacket(
                        direction = "ACK <- YGG",
                        summary = "Запрос удаления подтверждён адресатом через оверлей Yggdrasil (RTT: $ackRtt мс)",
                        details = "Узел $peerIpv6 подтвердил приём события ${deleteId.take(12)} по сообщению ${messageId.take(16)}...",
                        mirror = true
                    )
                    return@withContext DeleteDispatchResult(
                        delivered = true,
                        endToEndConfirmed = true,
                        transport = "YGG_OVERLAY",
                        rttMs = ackRtt
                    )
                }
            }
            pendingDeleteAcks.remove(deleteId, deferred)
        }

        if (directAddress != null) {
            logPacket(
                direction = "TX -> LAN",
                summary = "Запрос удалить сообщение направлен напрямую по LAN",
                details = "${directAddress.hostString}:${directAddress.port} • deleteId=${deleteId.take(12)}"
            )
            try {
                val startedAt = System.currentTimeMillis()
                var lanRtt: Long? = null
                Socket().use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(directAddress, 3500)
                    val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    writer.println(wirePayload)
                    val reply = reader.readLine()?.let { decodeWireFrame(it) }
                    if (reply?.get("type") == "ACK" &&
                        reply["messageId"] == messageId &&
                        reply["deleteId"].orEmpty() == deleteId
                    ) {
                        lanRtt = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                    }
                }
                val confirmedRtt = lanRtt
                if (confirmedRtt != null) {
                    onTwinReachableChanged(true)
                    logPacket(
                        direction = "ACK <- PEER",
                        summary = "Запрос удаления подтверждён адресатом по LAN (ACK)",
                        details = "Адресат: $peerIpv6 • ${directAddress.hostString}:${directAddress.port} • deleteId=${deleteId.take(12)}"
                    )
                    return@withContext DeleteDispatchResult(
                        delivered = true,
                        endToEndConfirmed = true,
                        transport = "LAN_DIRECT",
                        rttMs = confirmedRtt
                    )
                }
            } catch (e: Exception) {
                logPacket(
                    direction = "ERR • DELETE",
                    summary = "Запрос удаления не доставлен по LAN (${e.javaClass.simpleName})",
                    details = "Адресат $peerIpv6 • ${directAddress.hostString}:${directAddress.port}"
                )
            }
        }

        val reason = when {
            !hasInternet && directAddress == null -> "Нет подключения к интернету, LAN-маршрут к получателю не задан"
            recipientKeyHex.isBlank() && directAddress == null ->
                "Неизвестен Ed25519-ключ получателя: добавьте контакт по полной ссылке ygggram://…?pub=… или задайте lan=…"
            !senderCoreReady && directAddress == null -> "Нода Yggdrasil отправителя не активна"
            !overlayPossible && livePeers <= 0 && directAddress == null -> "Нода Yggdrasil без живых пиров"
            else -> "Оверлей Yggdrasil и LAN-маршрут не подтвердили доставку ACK"
        }
        logPacket(
            direction = "ERR • DELETE",
            summary = "Запрос удаления не подтверждён",
            details = "Причина: $reason • адресат $peerIpv6 • deleteId=${deleteId.take(12)}"
        )
        DeleteDispatchResult(
            delivered = false,
            endToEndConfirmed = false,
            transport = "",
            rttMs = -1L,
            errorDetail = reason
        )
    }

    /**
     * Sends read receipts for the given wire message ids. Receipts are best-effort:
     * no ACK is awaited, because a lost receipt must never delay the chat UI.
     */
    suspend fun dispatchReadReceipt(
        chatId: String,
        wireMessageIds: List<String>,
        peerIpv6: String,
        directEndpointHint: String,
        senderIpv6: String,
        senderPubKeyHex: String = "",
        recipientNodePubKeyHex: String = "",
        allowLanFallback: Boolean = allowDirectLanRouting
    ): Boolean = withContext(Dispatchers.IO) {
        val ids = wireMessageIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (chatId.isBlank() || ids.isEmpty() || peerIpv6.isBlank()) return@withContext false
        if (directEndpointHint.isNotBlank()) {
            registerDirectEndpointForPeer(peerIpv6, directEndpointHint)
        }
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.trim().lowercase()] ?: ""
        val recipientKeyHex = resolveRecipientNodeKey(peerIpv6, recipientNodePubKeyHex)
        val directAddress = resolveDirectSocketAddress(
            peerIpv6 = peerIpv6,
            directHint = directEndpointHint,
            senderIpv6 = senderIpv6,
            allowPassiveLan = allowLanFallback
        )
        val payload = encodeWireFrame(
            mapOf(
                "type" to "READ_RECEIPT",
                "chatId" to chatId,
                "messageIds" to ids.joinToString(","),
                "senderIpv6" to senderIpv6,
                "senderPubKeyHex" to senderPubKeyHex,
                "recipientIpv6" to peerIpv6,
                "sentAt" to System.currentTimeMillis().toString()
            )
        )
        var transport = ""
        val senderCoreReady = senderProfileId.isNotBlank() &&
            initializedNativeProfiles.contains(senderProfileId)
        // A status frame has no ACK, so a silently dropped overlay frame would leave the
        // peer stuck on "delivered". Prefer the endpoint that already carries messages.
        if (directAddress != null) {
            runCatching {
                Socket().use { socket ->
                    socket.soTimeout = 3000
                    socket.connect(directAddress, 2500)
                    PrintWriter(
                        OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                        true
                    ).println(payload)
                }
                transport = "LAN_DIRECT"
            }
        }
        if (transport.isBlank() && isRealNetworkAvailable() && senderCoreReady && recipientKeyHex.isNotBlank()) {
            val livePeers = awaitLivePeer(senderProfileId, waitMs = 1500L)
            if (livePeers > 0 && sendOverlayFrame(senderProfileId, recipientKeyHex, payload, "READ_RECEIPT")) {
                transport = "YGG_OVERLAY"
            }
        }
        if (transport.isNotBlank()) {
            logPacket(
                direction = if (transport == "YGG_OVERLAY") "TX -> YGG" else "TX -> LAN",
                summary = "Квитанция о прочтении ${ids.size} сообщ. отправлена ($transport)",
                details = "Диалог ${chatId.take(16)}... • Получатель $peerIpv6"
            )
        }
        transport.isNotBlank()
    }

    /**
     * Ephemeral typing notification. It gets no ACK by design: a lost indicator is
     * harmless, and the sender throttles repeats, so it must never enter the S&F queue.
     */
    suspend fun dispatchTypingIndicator(
        chatId: String,
        peerIpv6: String,
        directEndpointHint: String,
        senderIpv6: String,
        senderPubKeyHex: String = "",
        senderNodeLabel: String = "",
        recipientNodePubKeyHex: String = "",
        allowLanFallback: Boolean = allowDirectLanRouting
    ): Boolean = withContext(Dispatchers.IO) {
        if (chatId.isBlank() || peerIpv6.isBlank()) return@withContext false
        if (directEndpointHint.isNotBlank()) {
            registerDirectEndpointForPeer(peerIpv6, directEndpointHint)
        }
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.trim().lowercase()] ?: ""
        val recipientKeyHex = resolveRecipientNodeKey(peerIpv6, recipientNodePubKeyHex)
        val directAddress = resolveDirectSocketAddress(
            peerIpv6 = peerIpv6,
            directHint = directEndpointHint,
            senderIpv6 = senderIpv6,
            allowPassiveLan = allowLanFallback
        )
        val payload = encodeWireFrame(
            mapOf(
                "type" to "TYPING",
                "chatId" to chatId,
                "senderNodeLabel" to senderNodeLabel,
                "senderIpv6" to senderIpv6,
                "senderPubKeyHex" to senderPubKeyHex,
                "recipientIpv6" to peerIpv6,
                "sentAt" to System.currentTimeMillis().toString()
            )
        )
        val senderCoreReady = senderProfileId.isNotBlank() &&
            initializedNativeProfiles.contains(senderProfileId)
        if (directAddress != null) {
            if (runCatching {
                    Socket().use { socket ->
                        socket.soTimeout = 1500
                        socket.connect(directAddress, 1500)
                        PrintWriter(
                            OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                            true
                        ).println(payload)
                    }
                }.isSuccess
            ) {
                return@withContext true
            }
        }
        if (isRealNetworkAvailable() && senderCoreReady && recipientKeyHex.isNotBlank()) {
            if (sendOverlayFrame(senderProfileId, recipientKeyHex, payload, "TYPING")) {
                return@withContext true
            }
        }
        false
    }

    /**
     * Dispatches one call-control frame (RINGING, ACCEPT, DECLINE, BUSY, BYE, MISSED).
     * The call works through the local profile loopback listener, an explicit LAN
     * endpoint, or the Yggdrasil overlay — whichever actually returns CALL_ACK.
     * [timeoutMs] bounds the whole signal, not a single attempt: the overlay path
     * re-offers the frame until the ACK arrives or the deadline passes.
     */
    suspend fun dispatchCallControlSignal(
        chatId: String,
        callId: String,
        signalAction: String,
        peerIpv6: String,
        recipientNodePubKeyHex: String,
        senderIpv6: String,
        senderName: String,
        senderX25519PubKeyHex: String,
        directEndpointHint: String = "",
        allowLanFallback: Boolean = allowDirectLanRouting,
        timeoutMs: Long = 18_000L
    ): CallSignalDispatchResult = withContext(Dispatchers.IO) {
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.lowercase()] ?: ""
        val recipientKeyHex = resolveRecipientNodeKey(peerIpv6, recipientNodePubKeyHex)
        val directAddress = resolveDirectSocketAddress(
            peerIpv6 = peerIpv6,
            directHint = directEndpointHint,
            senderIpv6 = senderIpv6,
            allowPassiveLan = allowLanFallback
        )
        val frame = buildCallSignalFrame(
            signalAction = signalAction,
            callId = callId,
            chatId = chatId,
            peerIpv6 = peerIpv6,
            senderIpv6 = senderIpv6,
            senderName = senderName,
            senderPubKeyHex = profileIdToPubKeyMap[senderProfileId]?.trim().orEmpty(),
            senderX25519PubKeyHex = senderX25519PubKeyHex
        )

        val directIsLocalProfile = ipv6ToProfileIdMap[peerIpv6.trim().lowercase()] != null
        suspend fun sendDirect(): CallSignalDispatchResult? {
            val target = directAddress ?: return null
            val rtt = sendDirectCallFrameAndAwaitAck(target, frame, callId, signalAction) ?: return null
            val transport = if (directIsLocalProfile) "LOCAL_PROFILE" else "LAN_DIRECT"
            logPacket(
                direction = "TX -> VOIP",
                summary = "Сигнал $signalAction доставлен через ${
                    if (transport == "LOCAL_PROFILE") "локальный профиль" else "LAN ${target.hostString}:${target.port}"
                } (RTT $rtt мс)",
                details = "callId=${callId.take(12)} • подтвержден CALL_ACK"
            )
            return CallSignalDispatchResult(true, transport, rtt, null)
        }

        // A reachable LAN/local endpoint answers in milliseconds. Try it before the
        // overlay so ACCEPT/BYE for a call that arrived over LAN are not delayed by
        // overlay session setup (previously up to 8 seconds).
        if (directAddress != null) {
            sendDirect()?.let { return@withContext it }
        }

        val senderCoreReady = senderProfileId.isNotBlank() && initializedNativeProfiles.contains(senderProfileId)
        if (recipientKeyHex.isNotBlank() && senderCoreReady && isRealNetworkAvailable()) {
            val livePeers = awaitLivePeer(senderProfileId, waitMs = 8_000L)
            if (livePeers > 0) {
                // ironwood keeps exactly one pending datagram per destination while it
                // looks up the path and opens the encrypted session, so a cold overlay
                // route silently coalesces a burst: only the last frame survives and the
                // first round trip can take seconds. One deferred therefore stays
                // registered for the whole signal while the frame is re-offered on a slow
                // cadence — whichever copy finally reaches the peer, its CALL_ACK confirms
                // the signal, instead of an early ACK timing out against a later attempt.
                val ackKey = "$callId:$signalAction"
                val deferred = kotlinx.coroutines.CompletableDeferred<Long>()
                pendingCallAcks[ackKey] = deferred
                val deadline = System.currentTimeMillis() + timeoutMs
                val firstOfferAt = System.currentTimeMillis()
                var rtt: Long? = null
                while (rtt == null && System.currentTimeMillis() < deadline) {
                    // The local core rejecting the frame is permanent for this payload
                    // (oversized, dead node), so retrying it would only spam the log.
                    if (!sendOverlayFrame(senderProfileId, recipientKeyHex, frame, "CALL $signalAction")) break
                    val ackedAt = kotlinx.coroutines.withTimeoutOrNull(CALL_SIGNAL_RETRY_MS) { deferred.await() }
                    if (ackedAt != null) rtt = (ackedAt - firstOfferAt).coerceAtLeast(1L)
                }
                pendingCallAcks.remove(ackKey, deferred)
                if (rtt != null) {
                    logPacket(
                        direction = "TX -> VOIP",
                        summary = "Сигнал $signalAction доставлен через оверлей Yggdrasil (RTT $rtt мс)",
                        details = "callId=${callId.take(12)} • CALL_ACK от ноды ${recipientKeyHex.take(16)}..."
                    )
                    return@withContext CallSignalDispatchResult(true, "YGG_OVERLAY", rtt, null)
                }
            }
        }

        val reason = when {
            recipientKeyHex.isBlank() && directAddress == null ->
                "Нет маршрута: неизвестен Ed25519-ключ ноды и LAN-адрес собеседника"
            directAddress == null && !senderCoreReady ->
                "Нода отправителя не активна в фоне"
            else -> "Собеседник не подтвердил сигнал $signalAction"
        }
        logPacket(
            direction = "ERR • VOIP",
            summary = "Сигнал $signalAction не доставлен",
            details = "callId=${callId.take(12)} • $reason"
        )
        CallSignalDispatchResult(false, "", -1L, reason)
    }

    private fun buildCallSignalFrame(
        signalAction: String,
        callId: String,
        chatId: String,
        peerIpv6: String,
        senderIpv6: String,
        senderName: String,
        senderPubKeyHex: String,
        senderX25519PubKeyHex: String,
        iv: String = "",
        ct: String = "",
        seq: Long = 0L
    ): String {
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.trim().lowercase()] ?: ""
        val senderPort = activeProfilePortMap[senderProfileId] ?: localTcpPort
        return encodeWireFrame(
            mapOf(
                "type" to "CALL_SIGNAL",
                "signalAction" to signalAction,
                "callId" to callId,
                "chatId" to chatId,
                "recipientIpv6" to peerIpv6,
                "senderIpv6" to senderIpv6,
                "senderNodeLabel" to senderName,
                "senderPubKeyHex" to senderPubKeyHex,
                "senderX25519PubKeyHex" to senderX25519PubKeyHex,
                "senderTcpPort" to senderPort.toString(),
                "iv" to iv,
                "ct" to ct,
                "seq" to seq.toString(),
                "sentAt" to System.currentTimeMillis().toString()
            )
        )
    }

    private suspend fun sendDirectCallFrameAndAwaitAck(
        targetSocket: InetSocketAddress,
        frame: String,
        callId: String,
        signalAction: String
    ): Long? = try {
        val startedAt = System.currentTimeMillis()
        Socket().use { socket ->
            socket.soTimeout = 3_000
            socket.connect(targetSocket, 2_000)
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            writer.println(frame)
            val reply = reader.readLine()?.let { decodeWireFrame(it) }
            val echoedAction = reply?.get("signalAction").orEmpty()
            val ackedThisCall = reply?.get("type") == "CALL_ACK" &&
                reply["callId"] == callId &&
                (echoedAction.isBlank() || echoedAction == signalAction)
            if (ackedThisCall) (System.currentTimeMillis() - startedAt).coerceAtLeast(1L) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Opens the media path used for encrypted PCM both ways. A direct socket is kept
     * open for the whole call; the overlay path sends one datagram per 40 ms frame.
     */
    suspend fun openCallMediaChannel(
        chatId: String,
        callId: String,
        peerIpv6: String,
        recipientNodePubKeyHex: String,
        senderIpv6: String,
        senderName: String,
        directEndpointHint: String = "",
        allowLanFallback: Boolean = allowDirectLanRouting
    ): CallMediaChannelResult = withContext(Dispatchers.IO) {
        closeCallMediaChannel(callId)
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.lowercase()] ?: ""
        val recipientKeyHex = resolveRecipientNodeKey(peerIpv6, recipientNodePubKeyHex)
        val senderPubKeyHex = profileIdToPubKeyMap[senderProfileId]?.trim().orEmpty()
        val directAddress = resolveDirectSocketAddress(
            peerIpv6 = peerIpv6,
            directHint = directEndpointHint,
            senderIpv6 = senderIpv6,
            allowPassiveLan = allowLanFallback
        )
        if (directAddress != null) {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(directAddress, 2_500)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                outgoingCallMediaSessions[callId] = CallMediaSession(
                    callId = callId,
                    chatId = chatId,
                    senderProfileId = senderProfileId,
                    senderIpv6 = senderIpv6,
                    senderName = senderName,
                    recipientIpv6 = peerIpv6,
                    recipientKeyHex = recipientKeyHex,
                    transport = if (ipv6ToProfileIdMap[peerIpv6.trim().lowercase()] != null) "LOCAL_PROFILE" else "LAN_DIRECT",
                    socket = socket,
                    writer = writer
                )
                logPacket(
                    direction = "TX -> VOIP",
                    summary = "Голосовой канал открыт: ${directAddress.hostString}:${directAddress.port}",
                    details = "callId=${callId.take(12)} • PCM 16 кГц mono • AES-256-GCM"
                )
                return@withContext CallMediaChannelResult(
                    true,
                    outgoingCallMediaSessions[callId]?.transport.orEmpty(),
                    // Zero means "this channel adds no measurement": the caller keeps the
                    // RTT of the signal that actually round-tripped. A TCP handshake is
                    // sub-millisecond on a LAN, and reporting it as 1 мс was a made-up number.
                    0L,
                    null
                )
            } catch (e: Exception) {
                logPacket(
                    direction = "ERR • VOIP",
                    summary = "Прямой голосовой канал не открылся",
                    details = "${directAddress.hostString}:${directAddress.port} • ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
        val senderCoreReady = senderProfileId.isNotBlank() && initializedNativeProfiles.contains(senderProfileId)
        if (recipientKeyHex.isNotBlank() && senderCoreReady) {
            outgoingCallMediaSessions[callId] = CallMediaSession(
                callId = callId,
                chatId = chatId,
                senderProfileId = senderProfileId,
                senderIpv6 = senderIpv6,
                senderName = senderName,
                recipientIpv6 = peerIpv6,
                recipientKeyHex = recipientKeyHex,
                transport = "YGG_OVERLAY"
            )
            logPacket(
                direction = "TX -> VOIP",
                summary = "Голосовой канал открыт через оверлей Yggdrasil",
                details = "callId=${callId.take(12)} • AES-256-GCM, кадры по 40 мс"
            )
            return@withContext CallMediaChannelResult(true, "YGG_OVERLAY", 0L, null)
        }
        CallMediaChannelResult(false, "", -1L, "Нет доступного голосового маршрута к собеседнику")
    }

    fun sendCallAudioFrame(callId: String, iv: String, ct: String, seq: Long): Boolean {
        val session = outgoingCallMediaSessions[callId] ?: return false
        val frame = buildCallSignalFrame(
            signalAction = "AUDIO",
            callId = session.callId,
            chatId = session.chatId,
            peerIpv6 = session.recipientIpv6,
            senderIpv6 = session.senderIpv6,
            senderName = session.senderName,
            senderPubKeyHex = profileIdToPubKeyMap[session.senderProfileId]?.trim().orEmpty(),
            senderX25519PubKeyHex = "",
            iv = iv,
            ct = ct,
            seq = seq
        )
        return try {
            val writer = session.writer
            if (writer != null) {
                writer.println(frame)
                if (writer.checkError()) {
                    closeCallMediaChannel(callId)
                    false
                } else {
                    true
                }
            } else {
                val ok = sendOverlayFrame(session.senderProfileId, session.recipientKeyHex, frame, "CALL AUDIO")
                if (!ok) closeCallMediaChannel(callId)
                ok
            }
        } catch (_: Exception) {
            closeCallMediaChannel(callId)
            false
        }
    }

    fun closeCallMediaChannel(callId: String) {
        outgoingCallMediaSessions.remove(callId)?.let { session ->
            try {
                session.writer?.close()
            } catch (_: Throwable) {
            }
            try {
                session.socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Sends a real TCP RELAY_TRANSIT frame to the specified profile's bound socket to verify
     * its configured nodeRelayRole (CLIENT_ONLY vs PEER_RELAY vs SF_BLIND_RELAY).
     */
    suspend fun dispatchRelayRoleVerificationFrame(
        targetProfileId: String,
        targetProfileIpv6: String
    ): Boolean = withContext(Dispatchers.IO) {
        val port = activeProfilePortMap[targetProfileId] ?: localTcpPort
        return@withContext try {
            Socket().use { s ->
                s.soTimeout = 2000
                s.connect(InetSocketAddress("127.0.0.1", port), 1500)
                val writer = PrintWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                writer.println(
                    encodeWireFrame(
                        mapOf(
                            "type" to "RELAY_TRANSIT",
                            "recipientIpv6" to targetProfileIpv6,
                            "sentAt" to System.currentTimeMillis().toString()
                        )
                    )
                )
                val reply = reader.readLine() ?: return@use false
                val decoded = decodeWireFrame(reply)
                decoded["type"] == "RELAY_ACK"
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveTargetSocketAddress(
        peerIpv6: String,
        directHint: String,
        senderIpv6: String = ""
    ): InetSocketAddress? = resolveDirectSocketAddress(
        peerIpv6 = peerIpv6,
        directHint = directHint,
        senderIpv6 = senderIpv6,
        allowPassiveLan = allowDirectLanRouting
    )

    /**
     * Resolves an OS-level TCP route to a peer. An explicit `lan=` endpoint from the
     * contact is treated as user consent and is honoured even when the global LAN
     * toggle is off; passively discovered endpoints still require the toggle.
     * 0200::/7 overlay addresses are intentionally not returned: they are userspace
     * addresses, the OS socket stack cannot route them, and pretending otherwise
     * produced "sent" logs without delivery.
     */
    private fun resolveDirectSocketAddress(
        peerIpv6: String,
        directHint: String,
        senderIpv6: String,
        allowPassiveLan: Boolean
    ): InetSocketAddress? {
        val peer = peerIpv6.trim()
        val isSameIdentity = senderIpv6.isNotBlank() && peer.equals(senderIpv6, ignoreCase = true)

        // If the peer belongs to an active local profile, its loopback listener is a real route.
        val localProfileId = ipv6ToProfileIdMap[peer.lowercase()]
        if (localProfileId != null && !isSameIdentity) {
            activeProfilePortMap[localProfileId]?.let { return resolvedEndpoint("127.0.0.1", it) }
        }

        val cleanHint = directHint.trim()
        if (cleanHint.contains(":")) {
            val h = cleanHint.substringBeforeLast(":").removeSurrounding("[", "]")
            val p = cleanHint.substringAfterLast(":").toIntOrNull()
            if (h.isNotBlank() && p != null && !dialsOurOwnListener(h, p, senderIpv6)) {
                resolvedEndpoint(h, p)?.let { return it }
            }
        }

        if (allowPassiveLan) {
            directEndpointTable[peer.lowercase()]?.let { registered ->
                if (!isSameIdentity) return resolvedEndpoint(registered.hostString, registered.port) ?: registered
            }
        }

        // Legacy/manual LAN entry stored directly as "host:port".
        val literal = peer.removeSurrounding("[", "]")
        if (literal.contains(".") && literal.contains(":")) {
            val h = literal.substringBeforeLast(":")
            val p = literal.substringAfterLast(":").toIntOrNull() ?: 51921
            if (h.isNotBlank() && p in 1..65535 && !dialsOurOwnListener(h, p, senderIpv6)) {
                return resolvedEndpoint(h, p)
            }
        }
        return null
    }

    /**
     * True when a call frame names an address owned by one of this device's profiles.
     * Compared through the canonical IPv6 form because a contact can carry the same
     * address written differently from the profile record.
     */
    private fun isCallAddressedToLocalNode(ipv6: String): Boolean {
        val canonical = YggCryptoEngine.normalizeIpv6Literal(ipv6) ?: return false
        if (ipv6ToProfileIdMap.keys.any { YggCryptoEngine.normalizeIpv6Literal(it) == canonical }) return true
        // A profile row can hold no usable address while its node key is correct, and an
        // overlay address is nothing but that key written differently.
        return profileIdToPubKeyMap.values.any { YggCryptoEngine.addressMatchesEd25519Key(ipv6, it) }
    }

    /**
     * A LAN hint that terminates on the socket the *sending* profile is listening on —
     * two app instances behind the same NAT address both advertise 10.0.2.15:51921 —
     * points back at ourselves. Dialling it would let us ACK our own call signal and
     * report a delivered call the peer never heard about, so such hints are dropped and
     * the overlay carries the signal instead. Another local profile's port is a real
     * route, so only the sender's own port counts as self.
     */
    private fun dialsOurOwnListener(host: String, port: Int, senderIpv6: String): Boolean {
        val senderProfileId = ipv6ToProfileIdMap[senderIpv6.trim().lowercase()].orEmpty()
        if (port != (activeProfilePortMap[senderProfileId] ?: localTcpPort)) return false
        if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) return true
        val target = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return true
        }
        return NetworkInterface.getNetworkInterfaces().asSequence().any { nic ->
            nic.inetAddresses.asSequence().any { it.address.contentEquals(target.address) }
        }
    }

    /**
     * `Socket.connect` rejects unresolved endpoints with UnknownHostException, so every
     * address that reaches a send path has to be resolved here. Literal IPs resolve
     * instantly; a hostname that cannot be resolved yields no route at all instead of a
     * socket error that would look like a peer outage. Called only on Dispatchers.IO.
     */
    private fun resolvedEndpoint(host: String, port: Int): InetSocketAddress? {
        if (port !in 1..65535) return null
        val address = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        } ?: return null
        return InetSocketAddress(address, port)
    }

    /**
     * Sends one frame over a real TCP socket and returns the RTT only when the peer
     * answers with an ACK for exactly this message id. The ACK is what upgrades the
     * message to DELIVERED; a successful socket write alone proves nothing.
     */
    private suspend fun sendDirectFrameAndAwaitAck(
        targetSocket: InetSocketAddress,
        wirePayload: String,
        chatId: String,
        messageId: String,
        peerTitle: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Long? {
        val startedAt = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.soTimeout = readTimeoutMs
                socket.connect(targetSocket, connectTimeoutMs)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                writer.println(wirePayload)
                val responseLine = reader.readLine()
                val ackMap = responseLine?.let { decodeWireFrame(it) }
                if (ackMap?.get("type") == "ACK" && ackMap["messageId"] == messageId) {
                    val rtt = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                    onTwinReachableChanged(true)
                    // This socket proves nothing about who answered, so the frame carries no
                    // sender key and a group message is not attributed to a member here.
                    onDeliveryAckReceived(chatId, messageId, rtt, "")
                    logPacket(
                        direction = "ACK <- PEER",
                        summary = "Доставка подтверждена ACK через ${targetSocket.hostString}:${targetSocket.port} (RTT: $rtt мс)",
                        details = "Узел «$peerTitle» верифицировал AEAD-пакет ${messageId.take(12)}"
                    )
                    rtt
                } else {
                    logPacket(
                        direction = "ERR • ACK",
                        summary = "Узел «$peerTitle» не подтвердил пакет",
                        details = "Ответ без ACK: ${responseLine?.take(80) ?: "нет ответа"} • ${targetSocket.hostString}:${targetSocket.port}"
                    )
                    null
                }
            }
        } catch (e: Exception) {
            logPacket(
                direction = "ERR • TX",
                summary = "Прямое подключение к «$peerTitle» не удалось (${e.javaClass.simpleName})",
                details = "${targetSocket.hostString}:${targetSocket.port} • ${e.message ?: "нет деталей"}"
            )
            null
        }
    }

    private fun encodeWireFrame(fields: Map<String, String>): String {
        return fields.entries.joinToString("|") { (k, v) ->
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(v.toByteArray(StandardCharsets.UTF_8))
            "$k=$b64"
        }
    }

    private fun decodeWireFrame(line: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        line.trim().split("|").forEach { token ->
            val idx = token.indexOf('=')
            if (idx > 0) {
                val key = token.substring(0, idx)
                val b64 = token.substring(idx + 1)
                try {
                    val value = String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8)
                    result[key] = value
                } catch (_: Exception) {
                }
            }
        }
        return result
    }

    private fun logPacket(
        direction: String,
        summary: String,
        details: String,
        mirror: Boolean = false
    ) {
        val id = "pkt_${packetCounter.getAndIncrement()}"
        if (direction.startsWith("ERR") || direction.contains("STREAM") || mirror) {
            // The on-screen journal disappears with the process, but a refused send or a stalled
            // file stream has to be findable in `adb logcat` on a device nobody is looking at.
            android.util.Log.w("YGG-SEND", "$direction • $summary • $details")
        }
        val entry = YggLivePacketLogEntry(
            id = id,
            timeFormatted = timeFormat.format(Date()),
            direction = direction,
            summary = summary,
            details = details
        )
        onPacketLogAdded(entry)
    }

    fun stop() {
        // Cancel the self-healing loop before closing the socket, otherwise the loop would
        // treat the shutdown close as a failure and bind a new listener behind stop().
        listenersRequested = false
        tcpListenerJob?.cancel()
        tcpListenerJob = null
        // The cover endpoints exist only to feed the running nodes; with them left bound, a
        // restarted bridge would keep loopback listeners nobody dials any more.
        YggCoverTransport.releaseAll()
        try {
            tcpServerSocket?.close()
            tcpServerSocket = null
            lanDiscoverySocket?.close()
            lanDiscoverySocket = null
            secondaryProfileSockets.values.forEach { (srv, job) ->
                job.cancel()
                try {
                    srv.close()
                } catch (_: Exception) {
                }
            }
            secondaryProfileSockets.clear()
            outgoingCallMediaSessions.keys.toList().forEach { closeCallMediaChannel(it) }
            pendingCallAcks.values.forEach { it.cancel() }
            pendingCallAcks.clear()
            pendingChunkAcks.values.forEach { it.cancel() }
            pendingChunkAcks.clear()
            overlayRecvJobs.values.forEach { it.cancel() }
            overlayRecvJobs.clear()
            if (YggNativeCoreBridge.isNativeLibraryLoaded) {
                val stopped = YggNativeCoreBridge.stopAllCores()
                initializedNativeProfiles.clear()
                if (stopped.ok) {
                    logPacket(
                        direction = "SYS • CORE",
                        summary = "Ноды Yggdrasil всех профилей остановлены",
                        details = "Все overlay-соединения ядер закрыты вместе с сокетами моста"
                    )
                }
            }
        } catch (_: Exception) {
        }
    }
}
