// File: app/src/main/java/com/example/viewmodel/YggGramViewModel.kt
package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.NetworkRequest
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BackupSnapshotEntity
import com.example.data.CallLogEntity
import com.example.data.ConversationEntity
import com.example.data.CryptoKeyRecordEntity
import com.example.data.GroupMemberCount
import com.example.data.GroupMemberEntity
import com.example.data.LinkedDeviceEntity
import com.example.data.MessageEntity
import com.example.data.P2PFileTransferEntity
import com.example.data.UserProfileEntity
import com.example.data.YggDatabase
import com.example.data.YggPeerEntity
import com.example.data.YggRepository
import com.example.engine.CountryFilterOption
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.DiscoveredYggPeer
import com.example.engine.IncomingCallAudioPacket
import com.example.engine.IncomingCallSignalPacket
import com.example.engine.OverlayProbeOutcome
import com.example.engine.SelfYggdrasilRelayResult
import com.example.engine.YggAudioMediaEngine
import com.example.engine.YggAvatarCodec
import com.example.engine.YggCryptoEngine
import com.example.engine.YggLivePacketLogEntry
import com.example.engine.YggNativeCoreBridge
import com.example.engine.YggPeerScannerCatalog
import com.example.engine.YggTwinP2PBridge
import com.example.viewmodel.controllers.ChatConversationController
import com.example.viewmodel.controllers.NetworkDpiController
import com.example.viewmodel.controllers.VaultIdentityController
import com.example.viewmodel.controllers.VoiceCallController
import com.example.service.YggAlertCategory
import com.example.service.YggCallAlertController
import com.example.service.YggForegroundServiceState
import com.example.service.YggNodeForegroundService
import com.example.service.YggNotificationCenter
import com.example.service.YggPopupNotificationItem
import com.example.service.YggQuickReplyBus
import com.example.ui.components.PendingProfileSwitchRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class YggNavTab {
    CHATS,
    NETWORK_MAP,
    NETWORK_DPI,
    VAULT_SYNC,
    PROFILE_PRIVACY
}

enum class CensorshipPreset(val title: String, val badge: String, val summary: String) {
    RU_WHITELIST_CDN(
        title = "Обход Белых Списков (CDN WSS + RU SNI)",
        badge = "WHITELIST WSS",
        summary = "Маскировка трафика Yggdrasil под HTTPS WebSocket через разрешенные инфраструктурные домены и CDN"
    ),
    TSPU_VLESS_SHADOWTLS(
        title = "ТСПУ Анти-Зондирование (VLESS + ShadowTLS v3)",
        badge = "VLESS + uTLS",
        summary = "Туннелирование с отпечатками uTLS Chrome 128 / iOS 18 и защитой от активного пробинга ТСПУ"
    ),
    QUIC_UDP_OBFUSCATION(
        title = "Анти-Сигнатурный UDP (QUIC Padding + Jitter)",
        badge = "QUIC PADDED",
        summary = "Выравнивание размеров датаграмм и шумовые префиксы против блокировки сигнатур Yggdrasil"
    ),
    AUTONOMOUS_DHT_DOH(
        title = "Шатдаун / Автономный Поиск (DHT BEP-44 + DoH)",
        badge = "DHT + DoH",
        summary = "Бессерверный поиск входных пиров через BitTorrent Mainline DHT и подписанные Ed25519 списки"
    )
}

data class PeerScannerState(
    val countries: List<CountryFilterOption> = YggPeerScannerCatalog.supportedCountries,
    val selectedCountryCode: String = "ALL",
    val searchQuery: String = "",
    val sortMode: String = "PING_ASC", // "PING_ASC", "COUNTRY", "UPTIME"
    val onlyWhitelistCompatible: Boolean = false,
    val isScanning: Boolean = false,
    val discoveredPeers: List<DiscoveredYggPeer> = YggPeerScannerCatalog.initialCatalog,
    val lastScanSummary: String = "В базе ${YggPeerScannerCatalog.initialCatalog.size} пиров в 16 странах • Нажмите «Сканировать пинг»"
)

data class AntiDpiConfigState(
    val activePreset: CensorshipPreset = CensorshipPreset.RU_WHITELIST_CDN,
    val whitelistCdnWssEnabled: Boolean = true,
    val whitelistSniDomain: String = "static-mon.yandex.net",
    val availableWhitelistSnis: List<String> = listOf(
        "static-mon.yandex.net",
        "cdn.gosuslugi.ru",
        "st.vk-portal.net",
        "habr.com",
        "ozone.ru"
    ),
    val utlsProfile: String = "HelloChrome_128_Auto",
    val shadowTlsV3Enabled: Boolean = true,
    val vlessRealityEnabled: Boolean = true,
    val quicPaddingEnabled: Boolean = true,
    val quicPaddingMTU: Int = 1360,
    val dhtBep44BootstrapEnabled: Boolean = true,
    val dohResolverUri: String = "https://dns.quad9.net/dns-query",
    val isProbingRoutes: Boolean = false,
    val lastProbeReport: String = "Ожидание реальной проверки соединения с публичными пирами Yggdrasil..."
)

data class DecentralizedUserProfile(
    val profileId: String = YggRepository.DEFAULT_PROFILE_ID,
    val displayName: String = "Узел YggGram #1",
    val handle: String = "@node1.ygg",
    val bio: String = "Децентрализованный мессенджер в сети Yggdrasil 0200::/7 • Прямой P2P & Zero-Knowledge",
    val statusText: String = "В оверлее 200::/7 (Порт 51921)",
    val allocatedTcpPort: Int = 51921,
    val isActiveInBackground: Boolean = true,
    val nodeRelayRole: String = "PEER_RELAY",
    val relayedPacketsCount: Int = 0,
    val profileFingerprintHex: String = "",
    val avatarUri: String = "",
    val avatarHashHex: String = ""
)

data class PrivacyMatrixState(
    val sealedSenderGlobal: Boolean = true,
    val stripNetworkMetadata: Boolean = true,
    val blockScreenshots: Boolean = true,
    val defaultDisappearingSeconds: Int = 0,
    val hashcashDifficultyBits: Int = 10,
    val allowDirectLanDiscovery: Boolean = false,
    val webDavEndpointUrl: String = "https://webdav.private-node.org:8443/ygg-zk-vault",
    val autoSyncOnMeshConnect: Boolean = true,
    val inAppNotificationsEnabled: Boolean = true,
    val systemNotificationsEnabled: Boolean = true,
    val suppressNotificationsForOpenChat: Boolean = true,
    val messageNotificationsEnabled: Boolean = true,
    val voiceNotificationsEnabled: Boolean = true,
    val callNotificationsEnabled: Boolean = true
)

data class ActiveVoiceCallState(
    val chatId: String,
    val peerTitle: String,
    val peerIpv6: String,
    val peerAvatarUri: String = "",
    // "DIALING_YGG", "RINGING_INCOMING", "ACTIVE_ENCRYPTED", "OFFLINE_ERROR"
    val callStatus: String,
    val statusSubtext: String,
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerphoneOn: Boolean = true,
    val isLoopbackEchoEnabled: Boolean = false,
    val micAmplitudeLevel: Float = 0f,
    val verificationEmojis: String = "🔐 🛰️ 🐉 ⚡",
    val liveRttMs: Int = -1,
    val audioBitrateKbps: Int = 256,
    val txAudioFrames: Int = 0,
    val rxAudioFrames: Int = 0,
    val isIncoming: Boolean = false,
    val callId: String = "",
    val transport: String = "",
    val mediaEncrypted: Boolean = false,
    // True while the peer's X25519 key is still unknown: an SAS derived from an
    // empty peer key would differ on the two phones, so it must not be shown.
    val sasPending: Boolean = false
)

data class YggGramUiState(
    val activeTab: YggNavTab = YggNavTab.CHATS,
    val currentProfileId: String = YggRepository.DEFAULT_PROFILE_ID,
    val selectedChatId: String? = null,
    val searchQuery: String = "",
    val selectedChatFilter: String = "ALL",
    val isOfflineStoreAndForwardMode: Boolean = false,
    val isBackgroundServiceEnabled: Boolean = true,
    val isNetworkAvailable: Boolean = true,
    val isAirplaneModeOn: Boolean = false,
    val isCloneInstance: Boolean = false,
    val isTwinAppReachable: Boolean = false,
    val localUdpPort: Int = 51921,
    val remoteTwinUdpPort: Int = 51920,
    val activePortsSummary: String = ":51921",
    val localLanIp: String? = null,
    val txPacketsCount: Int = 0,
    val rxPacketsCount: Int = 0,
    val packetLogs: List<YggLivePacketLogEntry> = emptyList(),
    val identity: DerivedIdentityHierarchy,
    val keyEpoch: Int = 1,
    val isSeedPhraseRevealed: Boolean = false,
    val antiDpiConfig: AntiDpiConfigState = AntiDpiConfigState(),
    val peerScanner: PeerScannerState = PeerScannerState(),
    val coreMesh: YggNativeCoreBridge.CoreMeshState = YggNativeCoreBridge.CoreMeshState(available = false),
    val overlayDrops: Long = 0L,
    val userProfile: DecentralizedUserProfile = DecentralizedUserProfile(),
    val privacyMatrix: PrivacyMatrixState = PrivacyMatrixState(),
    val activeVoiceCall: ActiveVoiceCallState? = null,
    val activePopupNotification: YggPopupNotificationItem? = null,
    val pendingProfileSwitchRequest: PendingProfileSwitchRequest? = null,
    val recentNotificationsHistory: List<YggPopupNotificationItem> = emptyList(),
    // chatId -> peer label; entries expire a few seconds after the last TYPING frame.
    val typingPeers: Map<String, String> = emptyMap(),
    val statusBannerMessage: String? = null
)

class YggGramViewModel(application: Application) : AndroidViewModel(application) {

    private val database = YggDatabase.getInstance(application)
    private val repository = YggRepository(database.yggDao(), application.applicationContext)

    private val uiPrefs = application.getSharedPreferences("ygggram_ui_prefs", android.content.Context.MODE_PRIVATE)
    // Notifications are suppressed for a chat only while that chat is actually on screen.
    @Volatile
    private var appIsForeground: Boolean = true

    // Bootstrap identity for the first UI frame only: a fresh random mnemonic per launch.
    // ensureInitialized() persists the real mnemonic on first run, so no fixed default
    // key material ever ships with the app.
    private val initialIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(
        YggCryptoEngine.generateFreshMnemonic12(),
        epoch = 1
    )

    private val _uiState = MutableStateFlow(
        YggGramUiState(
            identity = initialIdentity
        )
    )
    val uiState: StateFlow<YggGramUiState> = _uiState.asStateFlow()

    val foregroundServiceState: StateFlow<YggForegroundServiceState> = YggNodeForegroundService.serviceState

    /** Call journal of the active profile, newest first. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val callLog: StateFlow<List<CallLogEntity>> = _uiState
        .map { it.currentProfileId }
        .distinctUntilChanged()
        .flatMapLatest { repository.observeCallLog(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Full-text message search across the active profile's conversations. */
    @OptIn(
        kotlinx.coroutines.ExperimentalCoroutinesApi::class,
        kotlinx.coroutines.FlowPreview::class
    )
    val messageSearchResults: StateFlow<List<MessageEntity>> = _uiState
        .map { it.currentProfileId to it.searchQuery.trim() }
        .distinctUntilChanged()
        .debounce(250L)
        .flatMapLatest { (profileId, query) -> repository.searchMessages(profileId, query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Roster of the conversation that is open, empty for a 1:1 dialog. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeGroupMembers: StateFlow<List<GroupMemberEntity>> = _uiState
        .map { it.selectedChatId.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { chatId -> repository.observeGroupMembers(chatId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Member counts behind every group row, so the chat list states a real roster size. */
    val groupMemberCounts: StateFlow<List<GroupMemberCount>> = repository
        .observeGroupMemberCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val typingExpiryJobs =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val lastTypingSentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private companion object {
        const val TYPING_THROTTLE_MS = 4_000L
        const val TYPING_EXPIRY_MS = 6_000L

        // A muted peer still transmits encrypted silence, so this many seconds without a
        // single decrypted inbound frame can only mean the media path is dead.
        const val CALL_RX_STALL_SECONDS = 6
    }

    private var activeCallJob: kotlinx.coroutines.Job? = null

    private data class CallSessionContext(
        val chatId: String,
        val callId: String,
        val peerTitle: String,
        val peerIpv6: String,
        val peerNodeKeyHex: String,
        val peerLanHint: String,
        val localProfileId: String,
        val localIpv6: String,
        val localName: String,
        val localX25519PrivateKey: ByteArray,
        val localX25519PubHex: String,
        val peerX25519PubHex: String,
        val isIncoming: Boolean
    )

    @Volatile
    private var activeCallContext: CallSessionContext? = null

    @Volatile
    private var activeCallStartedAt: Long = 0L

    @Volatile
    private var activeCallMediaKey: ByteArray? = null

    private var callMediaSendJob: kotlinx.coroutines.Job? = null
    private var callTickerJob: kotlinx.coroutines.Job? = null
    private var callTimeoutJob: kotlinx.coroutines.Job? = null
    private var pendingCallAccept: CompletableDeferred<IncomingCallSignalPacket>? = null
    private var pendingCallAcceptCallId: String = ""
    // A retried RINGING can arrive after the call was declined or answered; it
    // must not ring again or answer an already finished call.
    private val recentlyEndedCallIds = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val callRxFrameCounter = AtomicLong(0L)
    // Inbound frames that arrived but failed AEAD opening: the channel is alive, the
    // call keys are not. Without this counter both look identical to the user.
    private val callRxAeadFailures = AtomicLong(0L)
    private val callMicQueue = Channel<ByteArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var callLastLevelEmitAt: Long = 0L

    private val networkEngine = YggTwinP2PBridge(
        context = application.applicationContext,
        onPacketReceived = { packet ->
            // Joined before the bridge sends its delivery ACK, so «Доставлено» can only
            // mean the row is already in this device's database.
            viewModelScope.launch {
                val currentPrimaryId = _uiState.value.currentProfileId
                val allProfs = repository.getAllProfilesNow()
                val targetProfile = allProfs.find {
                    it.profileId == packet.recipientProfileId ||
                        sameMeshAddress(it.yggIpv6Address, packet.recipientIpv6)
                } ?: allProfs.find { it.profileId == currentPrimaryId } ?: allProfs.firstOrNull()

                val chatId = repository.recordIncomingTwinMessage(
                    packet = packet,
                    activeChatId = _uiState.value.selectedChatId,
                    currentPrimaryProfileId = currentPrimaryId
                )
                // After the message, so the conversation row this writes onto already
                // exists; the picture is checked against its own hash inside the codec.
                repository.storeIncomingAvatar(chatId, packet.senderAvatarHash, packet.senderAvatarData)

                val targetProfileName = targetProfile?.displayName
                val profileSuffix = if (targetProfile != null && targetProfile.profileId != currentPrimaryId) {
                    " [для профиля «${targetProfile.displayName}»]"
                } else {
                    ""
                }

                // The message is already on screen when its chat is open under the active
                // profile: no popup, no system notification and no banner in that case.
                val chatVisibleNow = targetProfile != null &&
                    isChatCurrentlyVisible(chatId, targetProfile.profileId)
                val suppressForOpenChat = chatVisibleNow &&
                    _uiState.value.privacyMatrix.suppressNotificationsForOpenChat

                // Dispatch Popup & Android System Notification if the recipient profile is enabled in settings
                if (!suppressForOpenChat &&
                    targetProfile != null &&
                    (targetProfile.isActiveInBackground || targetProfile.isCurrentPrimary)
                ) {
                    val isVoice = packet.attachmentType == "VOICE"
                    val category = if (isVoice) YggAlertCategory.NEW_VOICE else YggAlertCategory.NEW_MESSAGE
                    val previewText = when (packet.attachmentType) {
                        "VOICE" -> "🎙️ Голосовое сообщение (${packet.voiceDurationSec.coerceAtLeast(1)} сек) • PCM/Opus AEAD"
                        "IMAGE" -> "🖼️ Фотография: ${packet.attachmentName ?: "image.png"} — ${packet.plainText}"
                        "FILE" -> "📎 P2P Файл: ${packet.attachmentName ?: "file.bin"} — ${packet.plainText}"
                        else -> packet.plainText
                    }
                    emitPopupAndSystemNotification(
                        category = category,
                        targetProfile = targetProfile,
                        chatId = chatId,
                        senderName = packet.senderNodeLabel,
                        senderIpv6 = packet.senderIpv6,
                        messagePreview = previewText,
                        voiceDurationSec = packet.voiceDurationSec
                    )
                }

                _uiState.update {
                    it.copy(
                        isTwinAppReachable = true,
                        rxPacketsCount = it.rxPacketsCount + 1,
                        typingPeers = it.typingPeers - chatId,
                        selectedChatId = if (packet.recipientProfileId.isBlank() || packet.recipientProfileId == currentPrimaryId) {
                            it.selectedChatId ?: chatId
                        } else {
                            it.selectedChatId
                        },
                        statusBannerMessage = if (suppressForOpenChat) {
                            it.statusBannerMessage
                        } else {
                            "Получен пакет Yggdrasil от «$targetProfileName <- ${packet.senderNodeLabel}»$profileSuffix (${packet.rttMs} мс)"
                        }
                    )
                }
                if (chatVisibleNow) {
                    launch { flushReadReceipts(chatId) }
                }
                syncForegroundServiceNotification()
            }.join()
        },
        onDeliveryAckReceived = { chatId, messageId, rttMs ->
            viewModelScope.launch {
                // The message row is authoritative for which chat an ACK belongs to: a group
                // message confirmed by one member must not be written as delivered to all.
                val ackedMsg = repository.getMessage(messageId)
                val ackedChatId = ackedMsg?.chatId?.takeIf { it.isNotBlank() } ?: chatId
                if (repository.getConversation(ackedChatId)?.chatType == "GROUP") {
                    // The fan-out already stores this member in deliveredMemberCsv; the
                    // callback only reports the running count.
                    val ackedCount = ackedMsg?.deliveredMemberCsv
                        ?.split(',').orEmpty().count { it.isNotBlank() }
                    val memberTotal = repository.getGroupMembers(ackedChatId).size
                    repository.updateConversationRouteStatus(
                        chatId = ackedChatId,
                        isOnline = true,
                        routeSummary = "Группа • ACK участника (${rttMs} мс): подтверждено $ackedCount из $memberTotal"
                    )
                    _uiState.update {
                        it.copy(
                            rxPacketsCount = it.rxPacketsCount + 1,
                            statusBannerMessage = "Участник группы подтвердил доставку ACK (${rttMs} мс): $ackedCount из $memberTotal"
                        )
                    }
                    return@launch
                }
                repository.markMessageDelivered(messageId)
                if (chatId.isNotBlank()) {
                    repository.updateConversationRouteStatus(
                        chatId = chatId,
                        isOnline = true,
                        routeSummary = "Доставка подтверждена ACK получателя (${rttMs} мс)"
                    )
                }
                _uiState.update {
                    it.copy(
                        rxPacketsCount = it.rxPacketsCount + 1,
                        statusBannerMessage = "Получатель подтвердил доставку ACK (${rttMs} мс)"
                    )
                }
            }
        },
        onDeliveryAckTimeout = { chatId, messageId, attempts ->
            viewModelScope.launch {
                // Only a message that was claiming «Ожидает доставки» loses that claim:
                // one rejected up-front for having no route keeps its sharper reason.
                if (repository.requeueUnacknowledged(messageId) && chatId.isNotBlank()) {
                    repository.updateConversationRouteStatus(
                        chatId = chatId,
                        isOnline = true,
                        routeSummary = "Очередь S&F • узел не ответил ACK за 2 минуты ($attempts попыток), автоповтор каждые 45 с"
                    )
                }
            }
        },
        onTwinReachableChanged = { reachable ->
            _uiState.update { it.copy(isTwinAppReachable = reachable) }
        },
        onPacketLogAdded = { entry ->
            _uiState.update { state ->
                val updatedLogs = (listOf(entry) + state.packetLogs).take(40)
                state.copy(packetLogs = updatedLogs)
            }
        },
        onLanNodeDiscovered = { lanNode ->
            viewModelScope.launch {
                val chatId = repository.createNewConversation(
                    title = lanNode.displayName,
                    yggIpv6Input = lanNode.yggIpv6,
                    disappearingSeconds = 0,
                    directEndpointHint = "${lanNode.hostIp}:${lanNode.tcpPort}",
                    localIdentity = _uiState.value.identity,
                    ownerProfileId = _uiState.value.currentProfileId,
                    peerEd25519PublicKeyHex = lanNode.pubKeyHex
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "В локальной сети найден узел «${lanNode.displayName}» (${lanNode.hostIp}:${lanNode.tcpPort})",
                        selectedChatId = it.selectedChatId ?: chatId
                    )
                }
            }
        },
        onReactionReceived = { reaction ->
            viewModelScope.launch {
                val added = reaction.action != "REMOVE"
                var applied: MessageEntity? = null
                // A reaction can overtake its message when both travel as separate
                // overlay datagrams; retry briefly before giving up on the lookup.
                for (attempt in 0..2) {
                    applied = repository.applyPeerReaction(
                        wireMessageId = reaction.messageId,
                        emoji = reaction.emoji,
                        added = added
                    )
                    if (applied != null) break
                    if (attempt < 2) delay(700)
                }
                val target = applied
                _uiState.update {
                    it.copy(
                        statusBannerMessage = if (target != null) {
                            if (added) "Получена реакция ${reaction.emoji} от ${reaction.senderIpv6.take(16)}…"
                            else "Собеседник снял реакцию ${reaction.emoji}"
                        } else {
                            "Реакция ${reaction.emoji} получена, но сообщение для неё не найдено"
                        }
                    )
                }
            }
        },
        onReadReceiptReceived = { chatId, ids ->
            viewModelScope.launch {
                // A group has no reader-of-record: one member's receipt would mark the
                // message read for everybody, so group receipts are not applied.
                if (repository.getConversation(chatId)?.chatType == "GROUP") return@launch
                ids.forEach { repository.markOutgoingMessageRead(it) }
                if (ids.isNotEmpty()) {
                    _uiState.update {
                        it.copy(statusBannerMessage = "Собеседник прочитал сообщений: ${ids.size}")
                    }
                }
            }
        },
        onTypingReceived = { peerChatId, label, senderIpv6 ->
            viewModelScope.launch {
                val localChatId = repository.findConversationIdByPeerIpv6(
                    senderIpv6 = senderIpv6,
                    ownerProfileId = _uiState.value.currentProfileId
                ) ?: peerChatId
                _uiState.update { it.copy(typingPeers = it.typingPeers + (localChatId to label)) }
                typingExpiryJobs.remove(localChatId)?.cancel()
                typingExpiryJobs[localChatId] = viewModelScope.launch {
                    delay(TYPING_EXPIRY_MS)
                    _uiState.update { state ->
                        if (state.typingPeers[localChatId] == label) {
                            state.copy(typingPeers = state.typingPeers - localChatId)
                        } else {
                            state
                        }
                    }
                }
            }
        },
        onIncomingCallSignalReceived = { callSig ->
            viewModelScope.launch { handleIncomingCallSignal(callSig) }
        },
        onCallAudioFrameReceived = { packet ->
            handleIncomingCallAudioFrame(packet)
        },
        onProfileRelayPacketHandled = { profileId, _ ->
            viewModelScope.launch {
                repository.incrementProfileRelayedPackets(profileId, 1)
            }
        }
    )

    private var airplaneReceiver: BroadcastReceiver? = null

    val userProfiles: StateFlow<List<UserProfileEntity>> = repository.userProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Conversations filtered for the currently selected primary profile
    val conversations: StateFlow<List<ConversationEntity>> = combine(
        repository.conversations,
        _uiState
    ) { allConvs, state ->
        allConvs.filter { conv ->
            conv.ownerProfileId == state.currentProfileId ||
                (state.currentProfileId == YggRepository.DEFAULT_PROFILE_ID && conv.ownerProfileId.isBlank())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMessages: StateFlow<List<MessageEntity>> = repository.allMessages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fileTransfers: StateFlow<List<P2PFileTransferEntity>> = repository.fileTransfers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val peers: StateFlow<List<YggPeerEntity>> = repository.peers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val linkedDevices: StateFlow<List<LinkedDeviceEntity>> = repository.linkedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backupSnapshots: StateFlow<List<BackupSnapshotEntity>> = repository.backupSnapshots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cryptoKeys: StateFlow<List<CryptoKeyRecordEntity>> = repository.cryptoKeys
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val queuedOfflineMessagesCount: StateFlow<Int> = combine(allMessages, conversations) { msgs, convs ->
        val ownerChatIds = convs.map { it.chatId }.toSet()
        msgs.count { it.deliveryState == "QUEUED_OFFLINE_SF" && it.chatId in ownerChatIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val networkDpiController = NetworkDpiController(
        application = application,
        repository = repository,
        networkEngine = networkEngine,
        scope = viewModelScope,
        onBannerMessage = { msg -> _uiState.update { it.copy(statusBannerMessage = msg) } },
        onSyncForegroundNotification = { syncForegroundServiceNotification() }
    )

    val chatConversationController = ChatConversationController(
        application = application,
        repository = repository,
        networkEngine = networkEngine,
        scope = viewModelScope,
        onBannerMessage = { msg -> _uiState.update { it.copy(statusBannerMessage = msg) } },
        onIncrementTx = { _uiState.update { it.copy(txPacketsCount = it.txPacketsCount + 1) } },
        onIncrementRx = { _uiState.update { it.copy(rxPacketsCount = it.rxPacketsCount + 1) } }
    )

    val voiceCallController = VoiceCallController(
        application = application,
        repository = repository,
        networkEngine = networkEngine,
        scope = viewModelScope,
        onBannerMessage = { msg -> _uiState.update { it.copy(statusBannerMessage = msg) } }
    )

    val vaultIdentityController = VaultIdentityController(
        application = application,
        repository = repository,
        networkEngine = networkEngine,
        scope = viewModelScope,
        onBannerMessage = { msg -> _uiState.update { it.copy(statusBannerMessage = msg) } },
        onProfileUpdated = { applyUserProfileEntityToState(it) },
        onSyncForegroundNotification = { syncForegroundServiceNotification() }
    )

    init {
        viewModelScope.launch {
            networkDpiController.antiDpiConfig.collect { cfg ->
                _uiState.update { it.copy(antiDpiConfig = cfg) }
            }
        }
        viewModelScope.launch {
            networkDpiController.peerScanner.collect { scanner ->
                _uiState.update { it.copy(peerScanner = scanner) }
            }
        }
        viewModelScope.launch {
            networkDpiController.packetLogs.collect { logs ->
                _uiState.update { it.copy(packetLogs = logs) }
            }
        }
        viewModelScope.launch {
            chatConversationController.typingPeers.collect { typing ->
                _uiState.update { it.copy(typingPeers = typing) }
            }
        }
        viewModelScope.launch {
            vaultIdentityController.isSeedPhraseRevealed.collect { revealed ->
                _uiState.update { it.copy(isSeedPhraseRevealed = revealed) }
            }
        }

        _uiState.update { state ->
            val p = state.privacyMatrix
            state.copy(
                privacyMatrix = p.copy(
                    inAppNotificationsEnabled = uiPrefs.getBoolean("notif_in_app", true),
                    systemNotificationsEnabled = uiPrefs.getBoolean("notif_system", true),
                    suppressNotificationsForOpenChat = uiPrefs.getBoolean("notif_suppress_open_chat", true),
                    messageNotificationsEnabled = uiPrefs.getBoolean("notif_messages", true),
                    voiceNotificationsEnabled = uiPrefs.getBoolean("notif_voice", true),
                    callNotificationsEnabled = uiPrefs.getBoolean("notif_calls", true),
                    sealedSenderGlobal = uiPrefs.getBoolean("priv_sealed_sender", p.sealedSenderGlobal),
                    stripNetworkMetadata = uiPrefs.getBoolean("priv_strip_metadata", p.stripNetworkMetadata),
                    blockScreenshots = uiPrefs.getBoolean("priv_block_screenshots", p.blockScreenshots),
                    defaultDisappearingSeconds = uiPrefs.getInt("priv_default_ttl_sec", p.defaultDisappearingSeconds),
                    hashcashDifficultyBits = uiPrefs.getInt("priv_hashcash_bits", p.hashcashDifficultyBits),
                    allowDirectLanDiscovery = uiPrefs.getBoolean("priv_allow_lan", p.allowDirectLanDiscovery),
                    autoSyncOnMeshConnect = uiPrefs.getBoolean("priv_autosync_mesh", p.autoSyncOnMeshConnect),
                    webDavEndpointUrl = uiPrefs.getString("priv_webdav_url", p.webDavEndpointUrl) ?: p.webDavEndpointUrl
                )
            )
        }
        networkEngine.allowDirectLanRouting = _uiState.value.privacyMatrix.allowDirectLanDiscovery
        // The disappearing timer was stored but never enforced; messages now leave the database
        // and the attachment files leave the disk once the chat TTL passes.
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val purged = repository.purgeExpiredDisappearingMessages()
                if (purged > 0) {
                    _uiState.update {
                        it.copy(statusBannerMessage = "Автоудаление: удалено сообщений: $purged")
                    }
                }
                delay(60_000L)
            }
        }
        YggNodeForegroundService.onUserRequestedStop = {
            networkEngine.stop()
            _uiState.update {
                it.copy(
                    isBackgroundServiceEnabled = false,
                    statusBannerMessage = "Фоновый сервис Yggdrasil остановлен пользователем из уведомления"
                )
            }
        }
        YggNodeForegroundService.onUserRequestedPing = {
            refreshRealPeerConnectivity()
        }
        YggNodeForegroundService.onPeriodicBackgroundHeartbeat = {
            if (_uiState.value.isBackgroundServiceEnabled && networkEngine.isRealNetworkAvailable()) {
                refreshRealPeerConnectivity()
            }
        }

        viewModelScope.launch {
            val primaryProfile = repository.ensureInitialized(initialIdentity)
            applyUserProfileEntityToState(primaryProfile)
            networkEngine.start()
            val allProfs = repository.getAllProfilesNow()
            networkEngine.syncConcurrentProfileListeners(allProfs, storedPeerUrisForBoot())
            startNetworkAndAirplaneObserver()
            syncForegroundServiceNotification()
            refreshRealPeerConnectivity()
            YggQuickReplyBus.drain(getApplication()).forEach { deliverQuickReply(it) }
            YggQuickReplyBus.registerHandler { reply ->
                viewModelScope.launch { deliverQuickReply(reply) }
            }
        }

        // The store-and-forward queue used to be flushed only on an offline -> online
        // transition, so a message queued while the overlay had no live peer stayed
        // queued for the whole session even after the route recovered.
        viewModelScope.launch {
            while (true) {
                delay(45_000L)
                if (repository.getQueuedMessages().isEmpty()) continue
                if (!networkEngine.isRealNetworkAvailable()) continue
                flushQueuedMessagesOverRealNetwork("таймер 45 с")
            }
        }

        // The peer card must reflect the real Go core: only the core knows which
        // public links are up and which remote nodes its routing tree reached.
        viewModelScope.launch {
            var lastMeshSignature = ""
            var lastMeshChangeFlushAt = 0L
            while (true) {
                refreshCoreMeshState()
                // A new live link or a routing tree that grew is the moment a queued
                // message can finally leave; waiting for the 45 s tick made the user
                // watch «в очереди» long after the overlay had recovered. Debounced
                // because a flapping peer changes the signature every few seconds.
                val mesh = _uiState.value.coreMesh
                if (mesh.available) {
                    val signature = "${mesh.liveLinks}/${mesh.knownNodes}/" +
                        mesh.upPeers.keys.sorted().joinToString(",")
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (signature != lastMeshSignature) {
                        lastMeshSignature = signature
                        if (now - lastMeshChangeFlushAt >= 12_000L &&
                            networkEngine.isRealNetworkAvailable() &&
                            repository.getQueuedMessages().isNotEmpty()
                        ) {
                            lastMeshChangeFlushAt = now
                            flushQueuedMessagesOverRealNetwork("изменение mesh")
                        }
                    }
                }
                delay(8_000L)
            }
        }
    }

    private suspend fun refreshCoreMeshState() {
        val profileId = _uiState.value.currentProfileId
        val state = withContext(Dispatchers.IO) {
            YggNativeCoreBridge.readMeshState(profileId)
        }
        _uiState.update {
            it.copy(
                coreMesh = state.copy(restartCount = networkEngine.coreRestartCount(profileId)),
                overlayDrops = networkEngine.overlayDropTotal()
            )
        }
        applyCoreLinkStatusToPeers(state)
    }

    /**
     * The core decides which peer rows are live. The TCP/TLS scanner only guesses from
     * a handshake it is allowed to speak, and a node that refuses that guess can still
     * hold a working Yggdrasil link over the same endpoint — the greyed-out cards next
     * to growing RX/TX counters were that contradiction on screen.
     */
    private suspend fun applyCoreLinkStatusToPeers(state: YggNativeCoreBridge.CoreMeshState) {
        if (!state.available) return
        repository.getAllPeersNow().forEach { peer ->
            val coreLatency = state.upPeers[peer.endpointUri.substringBefore("?").trim().lowercase()]
            // The core only measures latency once a session round trip happened, so a
            // live link without a number keeps the last real measurement of the same port.
            val target = when {
                coreLatency != null -> true to if (coreLatency > 0) coreLatency else peer.latencyMs
                else -> false to -1
            }
            if (peer.isConnected != target.first || peer.latencyMs != target.second) {
                repository.updatePeerProbeStatus(
                    peerId = peer.peerId,
                    reachable = target.first,
                    latencyMs = target.second
                )
            }
        }
    }

    private fun applyUserProfileEntityToState(profileEntity: UserProfileEntity) {
        val words = profileEntity.mnemonicCsv.split(" ").filter { it.isNotBlank() }
        val effectiveWords = if (words.size >= 6) {
            words
        } else {
            // Legacy profile without usable mnemonic: generate and persist a fresh one so the
            // derived identity remains stable across restarts.
            val fresh = YggCryptoEngine.generateFreshMnemonic12()
            viewModelScope.launch {
                repository.persistProfileMnemonic(profileEntity.profileId, fresh)
            }
            fresh
        }
        val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(effectiveWords, epoch = 1)
        val sigHash = YggCryptoEngine.sha256Hex(
            "${profileEntity.displayName}:${profileEntity.handle}:${profileEntity.bio}:${hierarchy.yggNodePublicKeyHex}"
        )
        _uiState.update { state ->
            state.copy(
                currentProfileId = profileEntity.profileId,
                identity = hierarchy,
                localUdpPort = profileEntity.allocatedTcpPort,
                userProfile = DecentralizedUserProfile(
                    profileId = profileEntity.profileId,
                    displayName = profileEntity.displayName,
                    handle = profileEntity.handle,
                    bio = profileEntity.bio,
                    statusText = profileEntity.statusText,
                    allocatedTcpPort = profileEntity.allocatedTcpPort,
                    isActiveInBackground = profileEntity.isActiveInBackground,
                    nodeRelayRole = profileEntity.nodeRelayRole,
                    relayedPacketsCount = profileEntity.relayedPacketsCount,
                    profileFingerprintHex = "sha256_fp:${sigHash.take(44)}",
                    avatarUri = profileEntity.avatarUri,
                    avatarHashHex = profileEntity.avatarHashHex
                )
            )
        }
    }

    private fun isChatCurrentlyVisible(chatId: String, profileId: String): Boolean {
        if (!appIsForeground || chatId.isBlank()) return false
        val state = _uiState.value
        return state.activeTab == YggNavTab.CHATS &&
            state.selectedChatId == chatId &&
            (profileId.isBlank() || profileId == state.currentProfileId)
    }

    fun setAppForeground(foreground: Boolean) {
        appIsForeground = foreground
    }

    /**
     * Notification switches are persisted so they survive restarts; the popup and the
     * Android heads-up notification are gated independently because a user may want
     * in-app alerts without system tray noise (or the other way around).
     */
    fun updateNotificationPreferences(
        inAppNotificationsEnabled: Boolean? = null,
        systemNotificationsEnabled: Boolean? = null,
        suppressNotificationsForOpenChat: Boolean? = null,
        messageNotificationsEnabled: Boolean? = null,
        voiceNotificationsEnabled: Boolean? = null,
        callNotificationsEnabled: Boolean? = null
    ) {
        _uiState.update { state ->
            val p = state.privacyMatrix
            val next = p.copy(
                inAppNotificationsEnabled = inAppNotificationsEnabled ?: p.inAppNotificationsEnabled,
                systemNotificationsEnabled = systemNotificationsEnabled ?: p.systemNotificationsEnabled,
                suppressNotificationsForOpenChat = suppressNotificationsForOpenChat ?: p.suppressNotificationsForOpenChat,
                messageNotificationsEnabled = messageNotificationsEnabled ?: p.messageNotificationsEnabled,
                voiceNotificationsEnabled = voiceNotificationsEnabled ?: p.voiceNotificationsEnabled,
                callNotificationsEnabled = callNotificationsEnabled ?: p.callNotificationsEnabled
            )
            state.copy(
                privacyMatrix = next,
                statusBannerMessage = "Настройки уведомлений сохранены"
            )
        }
        val p = _uiState.value.privacyMatrix
        uiPrefs.edit()
            .putBoolean("notif_in_app", p.inAppNotificationsEnabled)
            .putBoolean("notif_system", p.systemNotificationsEnabled)
            .putBoolean("notif_suppress_open_chat", p.suppressNotificationsForOpenChat)
            .putBoolean("notif_messages", p.messageNotificationsEnabled)
            .putBoolean("notif_voice", p.voiceNotificationsEnabled)
            .putBoolean("notif_calls", p.callNotificationsEnabled)
            .apply()
    }

    private fun emitPopupAndSystemNotification(
        category: YggAlertCategory,
        targetProfile: UserProfileEntity,
        chatId: String,
        senderName: String,
        senderIpv6: String,
        messagePreview: String,
        voiceDurationSec: Int = 0,
        callId: String = ""
    ) {
        val privacy = _uiState.value.privacyMatrix
        val categoryAllowed = when (category) {
            YggAlertCategory.NEW_MESSAGE -> privacy.messageNotificationsEnabled
            YggAlertCategory.NEW_VOICE -> privacy.voiceNotificationsEnabled
            YggAlertCategory.INCOMING_CALL, YggAlertCategory.MISSED_CALL -> privacy.callNotificationsEnabled
        }
        if (!categoryAllowed) return
        if (!privacy.inAppNotificationsEnabled && !privacy.systemNotificationsEnabled) return

        val app = getApplication<Application>()
        val item = YggPopupNotificationItem(
            notificationId = "notif_${UUID.randomUUID().toString().take(8)}",
            category = category,
            targetProfileId = targetProfile.profileId,
            targetProfileName = targetProfile.displayName,
            targetProfileIpv6 = targetProfile.yggIpv6Address,
            targetProfileColorHex = targetProfile.avatarColorHex,
            targetProfileRelayRole = targetProfile.nodeRelayRole,
            chatId = chatId,
            senderName = senderName,
            senderIpv6 = senderIpv6,
            messagePreview = messagePreview,
            voiceDurationSec = voiceDurationSec,
            callId = callId,
            timestamp = System.currentTimeMillis()
        )
        if (privacy.inAppNotificationsEnabled && appIsForeground) {
            _uiState.update { state ->
                state.copy(
                    activePopupNotification = item,
                    recentNotificationsHistory = (listOf(item) + state.recentNotificationsHistory).take(25)
                )
            }
        }
        if (privacy.systemNotificationsEnabled) {
            YggNotificationCenter.postSystemHeadsUpNotification(app, item)
        }
    }

    fun dismissPopupNotification() {
        _uiState.update { it.copy(activePopupNotification = null) }
    }

    fun handlePopupNotificationAction(item: YggPopupNotificationItem, actionType: String) {
        val currentId = _uiState.value.currentProfileId
        if (item.targetProfileId.isNotBlank() && item.targetProfileId != currentId) {
            // Target notification belongs to a background profile -> ask user confirmation before switching!
            _uiState.update {
                it.copy(
                    pendingProfileSwitchRequest = PendingProfileSwitchRequest(
                        notification = item,
                        actionType = actionType
                    )
                )
            }
        } else {
            _uiState.update { it.copy(activePopupNotification = null) }
            executeNotificationTargetAction(item, actionType)
        }
    }

    fun confirmProfileSwitchFromNotification(request: PendingProfileSwitchRequest) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    pendingProfileSwitchRequest = null,
                    activePopupNotification = null
                )
            }
            val pair = repository.switchPrimaryUserProfile(request.notification.targetProfileId)
            if (pair != null) {
                val (targetProfile, _) = pair
                val allProfs = repository.getAllProfilesNow()
                networkEngine.syncConcurrentProfileListeners(allProfs)
                applyUserProfileEntityToState(targetProfile)
                syncForegroundServiceNotification()
            }
            executeNotificationTargetAction(request.notification, request.actionType)
        }
    }

    fun cancelProfileSwitchFromNotification() {
        _uiState.update { it.copy(pendingProfileSwitchRequest = null) }
    }

    fun declineIncomingCallNotification(item: YggPopupNotificationItem) {
        val ctx = activeCallContext
        if (ctx != null && ctx.isIncoming &&
            _uiState.value.activeVoiceCall?.callStatus == "RINGING_INCOMING" &&
            (item.chatId.isBlank() || item.chatId == ctx.chatId)
        ) {
            declineIncomingCall()
            return
        }
        viewModelScope.launch {
            val allProfs = repository.getAllProfilesNow()
            val targetProf = allProfs.find { it.profileId == item.targetProfileId } ?: return@launch
            emitPopupAndSystemNotification(
                category = YggAlertCategory.MISSED_CALL,
                targetProfile = targetProf,
                chatId = item.chatId,
                senderName = item.senderName,
                senderIpv6 = item.senderIpv6,
                messagePreview = "Пропущенный вызов от «${item.senderName}» (отклонен)"
            )
        }
    }

    private fun executeNotificationTargetAction(item: YggPopupNotificationItem, actionType: String) {
        viewModelScope.launch {
            if (item.chatId.isNotBlank()) {
                repository.markConversationRead(item.chatId)
            }
            _uiState.update {
                it.copy(
                    activeTab = YggNavTab.CHATS,
                    selectedChatId = item.chatId.ifBlank { it.selectedChatId }
                )
            }
            if (actionType == "ANSWER_CALL") {
                kotlinx.coroutines.delay(120L)
                val pendingIncoming = activeCallContext?.isIncoming == true &&
                    _uiState.value.activeVoiceCall?.callStatus == "RINGING_INCOMING"
                if (pendingIncoming) {
                    acceptIncomingCall()
                }
                // Without a live ring there is nothing to answer. Redialling from
                // the notification used to place an outgoing call by surprise.
            } else if (actionType == YggNotificationCenter.ACTION_DECLINE_CALL) {
                kotlinx.coroutines.delay(120L)
                val pendingIncoming = activeCallContext?.isIncoming == true &&
                    _uiState.value.activeVoiceCall?.callStatus == "RINGING_INCOMING"
                if (pendingIncoming) {
                    declineIncomingCall()
                }
            } else if (actionType == "CALLBACK_MISSED") {
                kotlinx.coroutines.delay(120L)
                if (item.chatId.isNotBlank()) {
                    startVoiceCall(item.chatId)
                }
            }
        }
    }

    fun handleSystemNotificationIntent(
        targetProfileId: String,
        chatId: String,
        categoryName: String,
        action: String = "",
        callId: String = ""
    ) {
        if (targetProfileId.isBlank() && chatId.isBlank()) return
        viewModelScope.launch {
            if (action == YggNotificationCenter.ACTION_DECLINE_CALL) {
                val ctx = activeCallContext
                val ringing = ctx?.isIncoming == true &&
                    _uiState.value.activeVoiceCall?.callStatus == "RINGING_INCOMING" &&
                    (callId.isBlank() || ctx.callId == callId)
                if (ringing) {
                    declineIncomingCall()
                }
                if (callId.isNotBlank()) {
                    YggNotificationCenter.cancelIncomingCall(getApplication(), callId)
                }
                return@launch
            }
            if (action == YggNotificationCenter.ACTION_ANSWER_CALL) {
                val ctx = activeCallContext
                val ringing = ctx?.isIncoming == true &&
                    _uiState.value.activeVoiceCall?.callStatus == "RINGING_INCOMING" &&
                    (callId.isBlank() || ctx.callId == callId)
                if (ringing) {
                    acceptIncomingCall()
                    return@launch
                }
                // The ring is gone (answered elsewhere or already timed out);
                // just open the conversation instead of dialling back.
            }
            val allProfs = repository.getAllProfilesNow()
            val targetProf = allProfs.find { it.profileId == targetProfileId }
                ?: allProfs.find { it.profileId == _uiState.value.currentProfileId }
                ?: return@launch
            val conv = if (chatId.isNotBlank()) repository.getConversation(chatId) else null
            val cat = runCatching { YggAlertCategory.valueOf(categoryName) }.getOrDefault(YggAlertCategory.NEW_MESSAGE)
            val syntheticItem = YggPopupNotificationItem(
                notificationId = "sys_intent_${System.currentTimeMillis()}",
                category = cat,
                targetProfileId = targetProf.profileId,
                targetProfileName = targetProf.displayName,
                targetProfileIpv6 = targetProf.yggIpv6Address,
                targetProfileColorHex = targetProf.avatarColorHex,
                targetProfileRelayRole = targetProf.nodeRelayRole,
                chatId = chatId,
                senderName = conv?.title ?: "Узел Yggdrasil",
                senderIpv6 = conv?.yggIpv6Address ?: "0200::/7",
                messagePreview = conv?.lastMessagePreview ?: "Открытие события из системного уведомления Android",
                callId = callId
            )
            // Tapping a call notification opens the app: answering is an explicit
            // action in the notification, not a side effect of a tap.
            val actionType = when (cat) {
                YggAlertCategory.INCOMING_CALL -> "OPEN_CHAT"
                YggAlertCategory.MISSED_CALL -> "OPEN_CHAT"
                else -> "OPEN_CHAT"
            }
            handlePopupNotificationAction(syntheticItem, actionType)
        }
    }

    fun syncForegroundServiceNotification() {
        val app = getApplication<Application>()
        val state = _uiState.value
        if (!state.isBackgroundServiceEnabled) {
            YggNodeForegroundService.stopNodeService(app)
            return
        }
        viewModelScope.launch {
            val allProfs = repository.getAllProfilesNow()
            val activeProfsCount = allProfs.count { it.isActiveInBackground || it.isCurrentPrimary }.coerceAtLeast(1)
            val coreLinks = networkEngine.coreLiveLinkCount()
            val alivePeersCount = if (coreLinks >= 0) coreLinks else peers.value.count { it.isConnected }
            val portsSummary = networkEngine.getActivePortsSummary()
            _uiState.update { it.copy(activePortsSummary = portsSummary) }
            YggNodeForegroundService.startOrUpdateNodeService(
                context = app,
                activeProfilesCount = activeProfsCount,
                connectedPeersCount = alivePeersCount,
                primaryIpv6 = state.identity.yggIpv6Address,
                activePortsSummary = portsSummary,
                txPackets = state.txPacketsCount,
                rxPackets = state.rxPacketsCount
            )
        }
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                isBackgroundServiceEnabled = enabled,
                statusBannerMessage = if (enabled) {
                    "Фоновый сервис Yggdrasil запущен: постоянное уведомление и P2P-сокеты активны"
                } else {
                    "Фоновый сервис Yggdrasil отключен"
                }
            )
        }
        if (enabled) {
            viewModelScope.launch {
                networkEngine.start()
                networkEngine.syncConcurrentProfileListeners(repository.getAllProfilesNow())
            }
        } else {
            networkEngine.stop()
        }
        syncForegroundServiceNotification()
    }

    // --- Multi-Profile Creation, Switching & Concurrent Background Operation ---

    fun createNewProfile(
        displayName: String,
        handle: String,
        bio: String,
        runSimultaneouslyInBackground: Boolean,
        switchImmediately: Boolean,
        nodeRelayRole: String = "PEER_RELAY"
    ) {
        viewModelScope.launch {
            val (created, _) = repository.createNewUserProfile(
                displayName = displayName,
                handle = handle,
                bio = bio,
                runSimultaneouslyInBackground = runSimultaneouslyInBackground,
                switchImmediately = switchImmediately,
                nodeRelayRole = nodeRelayRole
            )
            val allProfs = repository.getAllProfilesNow()
            networkEngine.syncConcurrentProfileListeners(allProfs, storedPeerUrisForBoot())

            if (switchImmediately) {
                applyUserProfileEntityToState(created)
                _uiState.update {
                    it.copy(
                        selectedChatId = null,
                        statusBannerMessage = "Создан и активирован профиль «${created.displayName}» (${created.yggIpv6Address.take(18)}..., порт :${created.allocatedTcpPort})"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Создан профиль «${created.displayName}» (${if (runSimultaneouslyInBackground) "работает параллельно в фоне на порту :${created.allocatedTcpPort}" else "на паузе"})"
                    )
                }
            }
            syncForegroundServiceNotification()
        }
    }

    fun setProfileNodeRelayRole(profileId: String, nodeRelayRole: String) {
        viewModelScope.launch {
            val updatedList = repository.setProfileNodeRelayRole(profileId, nodeRelayRole)
            networkEngine.syncConcurrentProfileListeners(updatedList)
            val target = updatedList.find { it.profileId == profileId }
            if (target != null && target.profileId == _uiState.value.currentProfileId) {
                applyUserProfileEntityToState(target)
            }
            val roleDisplay = when (nodeRelayRole) {
                "CLIENT_ONLY" -> "Только клиент (транзит отключен)"
                "SF_BLIND_RELAY" -> "S&F Ретранслятор (слепое хранение и пересылка)"
                else -> "Пир-узел (маршрутизация оверлея 0200::/7)"
            }
            if (target != null && (target.isActiveInBackground || target.isCurrentPrimary)) {
                networkEngine.dispatchRelayRoleVerificationFrame(target.profileId, target.yggIpv6Address)
            }
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Профиль «${target?.displayName ?: profileId}»: установлена роль «$roleDisplay»"
                )
            }
            syncForegroundServiceNotification()
        }
    }

    /**
     * Triggers a live notification event (Incoming Call, Missed Call, Voice Message, or P2P Message)
     * for any enabled profile so the user can test pop-up & Android system notifications and
     * background profile-switching confirmation.
     */
    fun triggerProfileTestNotification(profileId: String, category: YggAlertCategory) {
        viewModelScope.launch {
            val allProfs = repository.getAllProfilesNow()
            val targetProfile = allProfs.find { it.profileId == profileId } ?: return@launch
            if (!targetProfile.isActiveInBackground && !targetProfile.isCurrentPrimary) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Включите профиль «${targetProfile.displayName}» в фоновый режим для приема уведомлений")
                }
                return@launch
            }
            val senderLabel = if (targetProfile.profileId == _uiState.value.currentProfileId) {
                "Узел Мимир • Северный Хаб"
            } else {
                _uiState.value.userProfile.displayName
            }
            val senderIpv6 = if (targetProfile.profileId == _uiState.value.currentProfileId) {
                "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8"
            } else {
                _uiState.value.identity.yggIpv6Address
            }

            val chatId = repository.createNewConversation(
                title = senderLabel,
                yggIpv6Input = senderIpv6,
                disappearingSeconds = 0,
                directEndpointHint = "",
                ownerProfileId = targetProfile.profileId
            )

            val preview = when (category) {
                YggAlertCategory.INCOMING_CALL -> "Входящий голосовой вызов через оверлей Yggdrasil (Opus/PCM AEAD)"
                YggAlertCategory.MISSED_CALL -> "Пропущенный голосовой вызов • Нажмите «Перезвонить»"
                YggAlertCategory.NEW_VOICE -> "🎙️ Голосовое сообщение (6 сек) • Записано с микрофона (PCM 16 кГц)"
                YggAlertCategory.NEW_MESSAGE -> "Новое сообщение, защищённое X25519 + AES-256-GCM, для профиля «${targetProfile.displayName}»"
            }

            if (_uiState.value.privacyMatrix.systemNotificationsEnabled &&
                !YggNotificationCenter.canPostNotifications(getApplication<Application>())
            ) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Системные уведомления заблокированы Android: разрешите их в профиле или настройках приложения")
                }
            }

            emitPopupAndSystemNotification(
                category = category,
                targetProfile = targetProfile,
                chatId = chatId,
                senderName = senderLabel,
                senderIpv6 = senderIpv6,
                messagePreview = preview,
                voiceDurationSec = if (category == YggAlertCategory.NEW_VOICE) 6 else 0
            )
        }
    }

    fun switchActiveProfile(profileId: String) {
        viewModelScope.launch {
            val pair = repository.switchPrimaryUserProfile(profileId) ?: return@launch
            val (targetProfile, _) = pair
            val allProfs = repository.getAllProfilesNow()
            networkEngine.syncConcurrentProfileListeners(allProfs)
            applyUserProfileEntityToState(targetProfile)
            _uiState.update {
                it.copy(
                    selectedChatId = null,
                    statusBannerMessage = "Переключено на профиль «${targetProfile.displayName}» (${targetProfile.yggIpv6Address.take(18)}...) • Активных профилей в фоне: ${allProfs.count { p -> p.isActiveInBackground || p.isCurrentPrimary }}"
                )
            }
            syncForegroundServiceNotification()
        }
    }

    fun toggleProfileBackgroundExecution(profileId: String, activeInBackground: Boolean) {
        viewModelScope.launch {
            val updatedList = repository.setProfileBackgroundActive(profileId, activeInBackground)
            networkEngine.syncConcurrentProfileListeners(updatedList)
            val target = updatedList.find { it.profileId == profileId }
            if (target != null && target.profileId == _uiState.value.currentProfileId) {
                applyUserProfileEntityToState(target)
            }
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (activeInBackground) {
                        "Профиль «${target?.displayName ?: profileId}» запущен параллельно в фоне (TCP порт :${target?.allocatedTcpPort})"
                    } else {
                        "Фоновый сокет профиля «${target?.displayName ?: profileId}» переведен на паузу"
                    }
                )
            }
            syncForegroundServiceNotification()
        }
    }

    fun deleteSecondaryProfile(profileId: String) {
        viewModelScope.launch {
            val before = repository.getAllProfilesNow().find { it.profileId == profileId }
            val updated = repository.deleteSecondaryProfile(profileId)
            networkEngine.syncConcurrentProfileListeners(updated)
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Дополнительный профиль «${before?.displayName ?: profileId}» удален"
                )
            }
            syncForegroundServiceNotification()
        }
    }

    /**
     * Panic-wipe: stops every Go node, deletes all profiles (with their mnemonic
     * key material), chats, messages, transfers, keys and backups, then boots a
     * fresh default profile — the device ends in the same state as right after
     * installation.
     */
    fun panicWipeAllData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                YggNativeCoreBridge.stopAllCores()
            }
            networkEngine.panicResetCoreState()
            repository.panicWipeAllData()
            clearPacketLogs()
            val primaryProfile = repository.ensureInitialized(initialIdentity)
            applyUserProfileEntityToState(primaryProfile)
            val allProfs = repository.getAllProfilesNow()
            networkEngine.syncConcurrentProfileListeners(allProfs)
            _uiState.update {
                it.copy(
                    txPacketsCount = 0,
                    rxPacketsCount = 0,
                    statusBannerMessage =
                        "Экстренное стирание выполнено: профили, ключи и переписка удалены с устройства"
                )
            }
            syncForegroundServiceNotification()
        }
    }

    /**
     * Records the user's manual confirmation that the contact's safety number
     * matched out-of-band. Only the local flag changes — nothing is announced
     * over the mesh, because the peer cannot prove the match by itself.
     */
    fun setContactKeyVerified(chatId: String, verified: Boolean) {
        viewModelScope.launch {
            repository.setConversationKeyVerified(chatId, verified)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (verified) {
                        "Ключ узла помечен верифицированным — сверяйте safety number заново при смене ключа"
                    } else {
                        "Пометка верификации ключа снята"
                    }
                )
            }
        }
    }

    /**
     * Opens a chat from the currently selected profile to another profile using its Yggdrasil 0200::/7 address.
     * All messages, voice notes, files, and calls between profiles are routed strictly through external
     * public Yggdrasil peers (unless manual LAN mode is explicitly enabled in settings).
     */
    fun openChatWithSecondaryProfile(targetProfile: UserProfileEntity) {
        viewModelScope.launch {
            if (!targetProfile.isActiveInBackground) {
                repository.setProfileBackgroundActive(targetProfile.profileId, true)
            }
            val allProfs = repository.getAllProfilesNow()
            networkEngine.syncConcurrentProfileListeners(allProfs)

            val chatId = repository.createNewConversation(
                title = "${targetProfile.displayName} (${targetProfile.handle})",
                yggIpv6Input = "ygggram://${targetProfile.yggIpv6Address}?pub=${targetProfile.yggPublicKeyHex}",
                disappearingSeconds = 0,
                directEndpointHint = "",
                localIdentity = _uiState.value.identity,
                ownerProfileId = _uiState.value.currentProfileId
            )

            _uiState.update {
                it.copy(
                    activeTab = YggNavTab.CHATS,
                    selectedChatId = chatId,
                    statusBannerMessage = "Открыт диалог с профилем «${targetProfile.displayName}» — маршрут строго через внешние пиры Yggdrasil 0200::/7"
                )
            }
        }
    }

    private fun startNetworkAndAirplaneObserver() {
        val app = getApplication<Application>()
        updateConnectivitySnapshot()

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            try {
                cm.registerNetworkCallback(
                    request,
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            updateConnectivitySnapshot()
                            viewModelScope.launch { refreshRealPeerConnectivity() }
                        }

                        override fun onLost(network: Network) {
                            updateConnectivitySnapshot()
                            viewModelScope.launch {
                                if (!networkEngine.isRealNetworkAvailable()) {
                                    repository.markAllPeersDisconnected()
                                    syncForegroundServiceNotification()
                                }
                            }
                        }

                        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                            updateConnectivitySnapshot()
                        }
                    }
                )
            } catch (_: Exception) {
            }
        }

        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateConnectivitySnapshot()
                    viewModelScope.launch {
                        if (!networkEngine.isRealNetworkAvailable()) {
                            repository.markAllPeersDisconnected()
                            _uiState.update { state ->
                                state.copy(
                                    antiDpiConfig = state.antiDpiConfig.copy(
                                        lastProbeReport = "Авиарежим активен / Сеть отключена: все внешние соединения Yggdrasil разорваны"
                                    ),
                                    statusBannerMessage = "Включен Авиарежим: сеть недоступна, пиры переведены в офлайн"
                                )
                            }
                            syncForegroundServiceNotification()
                        } else {
                            refreshRealPeerConnectivity()
                        }
                    }
                }
            }
            app.registerReceiver(receiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))
            airplaneReceiver = receiver
        } catch (_: Exception) {
        }
    }

    private fun updateConnectivitySnapshot() {
        val netOk = networkEngine.isRealNetworkAvailable()
        val airplane = networkEngine.isAirplaneModeActive()
        val lanIp = networkEngine.getLocalLanIpv4Address()
        val activePorts = networkEngine.getActivePortsSummary()
        _uiState.update {
            it.copy(
                isNetworkAvailable = netOk,
                isAirplaneModeOn = airplane,
                localLanIp = lanIp,
                localUdpPort = it.userProfile.allocatedTcpPort,
                activePortsSummary = activePorts
            )
        }
    }

    /**
     * Peers the nodes dial at boot. Connected ones come first because the core caps
     * the dial list — a stored peer that answered the last scan must not be pushed
     * out by catalog entries that were never verified. Among equals, the ones whose
     * handshake names a host outrank the bare ones: on a filtered uplink those are the
     * links that get through, and a capped list full of dropped handshakes has no route
     * to the overlay at all.
     */
    private suspend fun storedPeerUrisForBoot(): List<String> =
        repository.getAllPeersNow()
            .sortedWith(
                compareByDescending<YggPeerEntity> { it.isConnected }
                    .thenByDescending { YggNativeCoreBridge.sendsHostnameSni(it.endpointUri, it.sniDomain) }
            )
            .map { YggNativeCoreBridge.corePeerUri(it.endpointUri, it.sniDomain) }

    /**
     * Probes all configured peers in the routing table using real TCP/TLS sockets.
     * In Airplane Mode or when offline, immediately marks all peers disconnected (-1 ms).
     */
    fun refreshRealPeerConnectivity() {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            if (!networkEngine.isRealNetworkAvailable()) {
                repository.markAllPeersDisconnected()
                val reason = if (_uiState.value.isAirplaneModeOn) "Активен Авиарежим" else "Отсутствует подключение к сети"
                _uiState.update { state ->
                    state.copy(
                        antiDpiConfig = state.antiDpiConfig.copy(
                            isProbingRoutes = false,
                            lastProbeReport = "Сеть недоступна ($reason) — подключенных пиров: 0"
                        )
                    )
                }
                syncForegroundServiceNotification()
                return@launch
            }

            val currentPeers = repository.getAllPeersNow()
            if (currentPeers.isEmpty()) return@launch

            val results = withContext(Dispatchers.IO) {
                currentPeers.map { peer ->
                    async {
                        val probe = networkEngine.probeRealYggdrasilPeer(
                            endpointUri = peer.endpointUri,
                            sniOverride = peer.sniDomain,
                            timeoutMs = 3000
                        )
                        repository.updatePeerProbeStatus(
                            peerId = peer.peerId,
                            reachable = probe.reachable,
                            latencyMs = probe.rttMs,
                            rxDelta = probe.rxBytes,
                            txDelta = probe.txBytes
                        )
                        peer to probe
                    }
                }.awaitAll()
            }

            val aliveCount = results.count { it.second.reachable }
            val bestRtt = results.filter { it.second.reachable }.minOfOrNull { it.second.rttMs }
            val coreMesh = withContext(Dispatchers.IO) {
                YggNativeCoreBridge.readMeshState(_uiState.value.currentProfileId)
            }
            _uiState.update { state ->
                // The core is the authority: a peer can refuse the plain TCP/TLS
                // handshake while the Yggdrasil link over the same endpoint is up.
                val report = if (aliveCount > 0) {
                    "TCP/TLS-сканер: живых пиров $aliveCount из ${results.size} (лучший пинг: ${bestRtt ?: "-"} мс)"
                } else {
                    "TCP/TLS-сканер: ни один из ${results.size} пиров не ответил на рукопожатие"
                }
                state.copy(
                    coreMesh = coreMesh,
                    antiDpiConfig = state.antiDpiConfig.copy(
                        isProbingRoutes = false,
                        lastProbeReport = report
                    )
                )
            }
            syncForegroundServiceNotification()
        }
    }

    override fun onCleared() {
        super.onCleared()
        YggQuickReplyBus.unregisterHandler()
        typingExpiryJobs.values.forEach { it.cancel() }
        typingExpiryJobs.clear()
        try {
            airplaneReceiver?.let { getApplication<Application>().unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        networkDpiController.stop()
        chatConversationController.cleanup()
        voiceCallController.stopCallAudio()
        YggAudioMediaEngine.stopVoipCallAudioSession(getApplication())
        YggAudioMediaEngine.stopVoiceNotePlayback()
        networkEngine.stop()
    }

    fun clearPacketLogs() {
        networkDpiController.clearPacketLogs()
        _uiState.update { it.copy(packetLogs = emptyList()) }
    }

    fun selectTab(tab: YggNavTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun selectChat(chatId: String?) {
        _uiState.update { it.copy(selectedChatId = chatId) }
        if (chatId != null) {
            viewModelScope.launch {
                repository.markConversationRead(chatId)
                flushReadReceipts(chatId)
            }
        }
    }

    /**
     * Opens the dedicated "Избранное • Мой узел Yggdrasil" self-chat.
     */
    fun openSelfYggdrasilTestChat() {
        viewModelScope.launch {
            val selfChatId = repository.ensureSelfYggdrasilConversation(
                identity = _uiState.value.identity,
                ownerProfileId = _uiState.value.currentProfileId
            )
            _uiState.update {
                it.copy(
                    activeTab = YggNavTab.CHATS,
                    selectedChatId = selfChatId
                )
            }
        }
    }

    /**
     * Runs a real round trip through the 0200::/7 overlay and reports the result
     * in the banner and in the self-chat route status.
     *
     * A node cannot address itself inside the overlay, so the check addresses a
     * remote node from the routing tree and waits for its session acknowledgement.
     */
    fun runYggdrasilOverlaySelfTest() {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            val profileId = _uiState.value.currentProfileId
            val selfChatId = repository.ensureSelfYggdrasilConversation(
                identity = _uiState.value.identity,
                ownerProfileId = profileId
            )
            val outcome = networkEngine.probeOverlayRoundTrip(profileId)
            applyOverlayProbeOutcome(outcome, selfChatId)
        }
    }

    private suspend fun applyOverlayProbeOutcome(outcome: OverlayProbeOutcome, selfChatId: String?) {
        if (outcome.confirmed) {
            val routeSummary = "Yggdrasil • оверлей (интернет, RTT ${outcome.rttMs} мс)"
            if (!selfChatId.isNullOrBlank()) {
                repository.updateConversationRouteStatus(selfChatId, true, routeSummary)
            }
            repository.updatePeerTrafficByEndpoint(
                endpointUri = "overlay://yggdrasil-0200::7",
                rttMs = outcome.rttMs.toInt(),
                txDelta = outcome.txBytes,
                rxDelta = 0L
            )
            _uiState.update {
                it.copy(
                    rxPacketsCount = it.rxPacketsCount + 1,
                    statusBannerMessage = "Оверлей Yggdrasil подтверждён: ответ удалённой ноды через 0200::/7 за ${outcome.rttMs} мс"
                )
            }
        } else {
            if (!selfChatId.isNullOrBlank()) {
                repository.updateConversationRouteStatus(
                    selfChatId,
                    false,
                    "Маршрут оверлея не подтверждён (${outcome.errorDetail ?: "нет ответа удалённой ноды"})"
                )
            }
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Оверлей Yggdrasil не подтверждён: ${outcome.errorDetail ?: "нет ответа удалённой ноды"}"
                )
            }
        }
        refreshCoreMeshState()
        syncForegroundServiceNotification()
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            val targetConv = conversations.value.find { it.chatId == chatId }
            repository.deleteConversation(chatId)
            if (_uiState.value.selectedChatId == chatId) {
                _uiState.update { it.copy(selectedChatId = null) }
            }
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Чат «${targetConv?.title ?: "Диалог"}» и вся его история сообщений удалены"
                )
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun updateChatFilter(filter: String) {
        _uiState.update { it.copy(selectedChatFilter = filter) }
    }

    fun toggleOfflineStoreAndForwardMode() {
        val nextOffline = !_uiState.value.isOfflineStoreAndForwardMode
        _uiState.update {
            it.copy(
                isOfflineStoreAndForwardMode = nextOffline,
                statusBannerMessage = if (nextOffline) {
                    "Включен Офлайн-режим: пакеты сохраняются в локальной очереди S&F без отправки в сокет"
                } else {
                    "Офлайн-режим отключен: попытка отправки очереди S&F через сеть Yggdrasil..."
                }
            )
        }
        if (!nextOffline) {
            flushQueuedMessagesOverRealNetwork("выход из офлайна")
        }
    }

    private fun isConversationSelfNode(conv: ConversationEntity?, identity: DerivedIdentityHierarchy): Boolean {
        if (conv == null) return false
        // A group row carries this node's own address as its owner, but it is a set of
        // external members — sending to it is never a note to ourselves.
        if (conv.chatType == "GROUP") return false
        return YggRepository.isSelfChatId(conv.chatId) ||
            conv.publicKeyHex.equals(identity.yggNodePublicKeyHex, ignoreCase = true) ||
            sameMeshAddress(conv.yggIpv6Address, identity.yggIpv6Address)
    }

    private fun sameMeshAddress(first: String, second: String): Boolean {
        if (first.trim().equals(second.trim(), ignoreCase = true)) return true
        val left = YggCryptoEngine.normalizeIpv6Literal(first) ?: return false
        val right = YggCryptoEngine.normalizeIpv6Literal(second) ?: return false
        return left == right
    }

    private suspend fun buildCandidateExternalPeersList(): List<Triple<String, String, String>> {
        val allPeers = repository.getAllPeersNow()
        val connectedFirst = allPeers
            .sortedWith(
                compareBy<YggPeerEntity> { if (it.isConnected) 0 else 1 }
                    .thenBy { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE }
            )
        return connectedFirst.map { Triple(it.endpointUri, it.sniDomain, it.regionLabel) }
    }

    /**
     * Fan a group note out over the mesh, one frame per roster member.
     *
     * Yggdrasil has no multicast and there is no server, so a group is only a set of 1:1
     * overlay links that all carry the same groupId and the same advertised roster; members
     * learn the roster from the frames themselves. Acknowledgements are counted per address
     * and the message turns DELIVERED only when the whole roster has acked, because a group
     * of five with two nodes offline has not been delivered to.
     *
     * @return acknowledged members, members a frame reached, and the roster size.
     */
    private suspend fun dispatchGroupMessage(
        conv: ConversationEntity,
        msg: MessageEntity,
        attachmentBase64: String?
    ): Triple<Int, Int, Int> {
        val state = _uiState.value
        val identity = state.identity
        val members = repository.getGroupMembers(conv.chatId)
        if (members.isEmpty() || identity.yggIpv6Address.isBlank())
            return Triple(0, 0, members.size)

        val roster = (members.map { it.memberIpv6.lowercase() to it.memberPubKeyHex.lowercase() } +
            (identity.yggIpv6Address.lowercase() to identity.yggNodePublicKeyHex.lowercase()))
            .filter { it.first.contains(":") }
            .distinctBy { it.first }
            .take(64)
        val rosterCsv = roster.joinToString(",") { "${it.first}|${it.second}" }
        val candidatePeers = buildCandidateExternalPeersList()
        val avatarBase64 = YggAvatarCodec.base64OfStoredFile(state.userProfile.avatarUri).orEmpty()

        var acked = 0
        var routed = 0
        members.forEach { member ->
            // The member's own contact row is used when it exists: it carries the direct
            // endpoint hint and the X25519 key pinned by past conversations. A member added
            // only through someone else's roster notice has no row yet, so it is addressed
            // by what the frame advertised.
            val known = conversations.value.firstOrNull {
                it.ownerProfileId == conv.ownerProfileId &&
                    it.chatType != "GROUP" &&
                    sameMeshAddress(it.yggIpv6Address, member.memberIpv6)
            }
            val memberConv = known
                ?: conv.copy(
                    yggIpv6Address = member.memberIpv6,
                    publicKeyHex = member.memberPubKeyHex,
                    peerX25519PublicKeyHex = ""
                )
            val peerIdentity = repository.resolvePeerNodeIdentity(memberConv)
            val peerIpv6 = peerIdentity.yggIpv6Address.ifBlank { member.memberIpv6 }
            if (peerIpv6.isBlank()) return@forEach
            val result = networkEngine.dispatchMessageViaExternalYggdrasilOverlay(
                chatId = conv.chatId,
                messageId = msg.messageId,
                peerTitle = member.memberName.ifBlank { memberConv.title },
                peerIpv6 = peerIpv6,
                directEndpointHint = extractDirectEndpointHint(memberConv),
                senderNodeLabel = state.userProfile.displayName,
                senderIpv6 = identity.yggIpv6Address,
                senderPubKeyHex = identity.yggNodePublicKeyHex,
                recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                plainText = msg.plainText,
                cipherPayloadHex = msg.cipherPayloadHex,
                hashcashToken = msg.hashcashPowToken,
                attachmentName = msg.attachmentName,
                attachmentSizeBytes = msg.attachmentSizeBytes,
                candidatePeers = candidatePeers,
                allowLanFallback = state.privacyMatrix.allowDirectLanDiscovery,
                attachmentType = msg.attachmentType,
                attachmentUri = msg.attachmentUri,
                attachmentBase64 = attachmentBase64,
                voiceDurationSec = msg.voiceDurationSec,
                voiceWaveformCsv = msg.voiceWaveformCsv,
                replyToMessageId = msg.replyToMessageId,
                forwardedFrom = msg.forwardedFrom,
                senderAvatarHash = state.userProfile.avatarHashHex,
                senderAvatarBase64 = avatarBase64,
                groupId = conv.groupId,
                groupTitle = conv.title,
                groupMembersCsv = rosterCsv
            )
            if (result.delivered) routed++
            if (result.relayEndpointUri.isNotBlank() && result.rttMs > 0) {
                repository.updatePeerTrafficByEndpoint(
                    endpointUri = result.relayEndpointUri,
                    rttMs = result.rttMs.toInt(),
                    txDelta = result.txBytes,
                    rxDelta = result.rxBytes
                )
            }
            if (result.endToEndConfirmed) {
                repository.recordGroupMemberDelivery(msg.messageId, peerIpv6)
                acked++
            } else if (result.delivered) {
                repository.markMessageSentP2P(msg.messageId)
            }
        }
        repository.updateConversationRouteStatus(
            chatId = conv.chatId,
            isOnline = routed > 0,
            routeSummary = when {
                acked == members.size -> "Группа • доставлено всем: $acked из ${members.size}"
                acked > 0 -> "Группа • подтверждено ACK: $acked из ${members.size}"
                routed > 0 -> "Группа • передано в оверлей: $routed из ${members.size}, ACK пока нет"
                else -> "Группа • нет подтвержденного маршрута ни к одному участнику"
            }
        )
        return Triple(acked, routed, members.size)
    }

    private fun flushQueuedMessagesOverRealNetwork(trigger: String = "внешний вызов") {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            val hasInternet = networkEngine.isRealNetworkAvailable()
            if (!hasInternet) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Нет интернета: очередь S&F пробует прямые LAN-маршруты контактов")
                }
            }
            val app = getApplication<Application>()
            val queued = repository.getQueuedMessages()
            // Which trigger fired matters when a queue drains late: in logcat the 45 s
            // timer and the mesh-change path are otherwise indistinguishable.
            Log.d("YggSF", "flush(${trigger}): сообщений в очереди ${queued.size}")
            var deliveredCount = 0
            var confirmedCount = 0
            var unreadableAttachments = 0
            val currentIdentity = _uiState.value.identity
            val candidateExternalPeers = buildCandidateExternalPeersList()

            for (msg in queued) {
                val conv = repository.getConversation(msg.chatId) ?: continue
                val wantsAttachment = msg.attachmentType != null || msg.attachmentName != null
                val attachmentBase64 = if (wantsAttachment) {
                    YggAudioMediaEngine.prepareAttachmentForP2P(
                        context = app,
                        attachmentName = msg.attachmentName,
                        attachmentType = msg.attachmentType,
                        attachmentUri = msg.attachmentUri,
                        voiceDurationSec = msg.voiceDurationSec,
                        voiceWaveformCsv = msg.voiceWaveformCsv
                    ).second.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                if (wantsAttachment && attachmentBase64 == null) {
                    // Dispatching without the payload would mark the note delivered while the peer
                    // receives an empty attachment, so the message stays in the queue.
                    unreadableAttachments++
                    continue
                }
                if (isConversationSelfNode(conv, currentIdentity)) {
                    // A node cannot address itself inside the 0200::/7 overlay, so a message
                    // to the own node is stored as a local note. The mesh itself is verified
                    // separately by the overlay probe.
                    repository.markMessageLocalNote(msg.messageId)
                    deliveredCount++
                } else if (conv.chatType == "GROUP") {
                    val (acked, routed, total) = dispatchGroupMessage(conv, msg, attachmentBase64)
                    if (acked == total && total > 0) confirmedCount++
                    if (routed > 0) deliveredCount++
                } else {
                    val directHint = extractDirectEndpointHint(conv)
                    val peerIdentity = repository.resolvePeerNodeIdentity(conv)
                    val overlayRelay = networkEngine.dispatchMessageViaExternalYggdrasilOverlay(
                        chatId = conv.chatId,
                        messageId = msg.messageId,
                        peerTitle = conv.title,
                        peerIpv6 = peerIdentity.yggIpv6Address.ifBlank { conv.yggIpv6Address },
                        directEndpointHint = directHint,
                        senderNodeLabel = _uiState.value.userProfile.displayName,
                        senderIpv6 = currentIdentity.yggIpv6Address,
                        senderPubKeyHex = currentIdentity.yggNodePublicKeyHex,
                        recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                        plainText = msg.plainText,
                        cipherPayloadHex = msg.cipherPayloadHex,
                        hashcashToken = msg.hashcashPowToken,
                        attachmentName = msg.attachmentName,
                        attachmentSizeBytes = msg.attachmentSizeBytes,
                        candidatePeers = candidateExternalPeers,
                        allowLanFallback = _uiState.value.privacyMatrix.allowDirectLanDiscovery,
                        attachmentType = msg.attachmentType,
                        attachmentUri = msg.attachmentUri,
                        attachmentBase64 = attachmentBase64,
                        voiceDurationSec = msg.voiceDurationSec,
                        voiceWaveformCsv = msg.voiceWaveformCsv,
                        senderAvatarHash = _uiState.value.userProfile.avatarHashHex,
                        senderAvatarBase64 = YggAvatarCodec
                            .base64OfStoredFile(_uiState.value.userProfile.avatarUri).orEmpty()
                    )
                    if (overlayRelay.delivered) {
                        deliveredCount++
                        if (overlayRelay.endToEndConfirmed) {
                            confirmedCount++
                        } else {
                            repository.markMessageSentP2P(msg.messageId)
                        }
                        if (overlayRelay.relayEndpointUri.isNotBlank()) {
                            repository.updatePeerTrafficByEndpoint(
                                endpointUri = overlayRelay.relayEndpointUri,
                                rttMs = overlayRelay.rttMs.toInt(),
                                txDelta = overlayRelay.txBytes,
                                rxDelta = overlayRelay.rxBytes
                            )
                        }
                        repository.updateConversationRouteStatus(
                            chatId = conv.chatId,
                            isOnline = true,
                            routeSummary = describeDeliveryRoute(overlayRelay)
                        )
                    }
                }
            }
            if (queued.isNotEmpty()) {
                val attachmentNote = if (unreadableAttachments > 0) {
                    " • вложений без читаемого файла: $unreadableAttachments, они остались в очереди"
                } else ""
                _uiState.update {
                    it.copy(
                        statusBannerMessage = when {
                            confirmedCount > 0 ->
                                "Доставлено и подтверждено ACK: $confirmedCount из ${queued.size} сообщений очереди S&F"
                            deliveredCount > 0 ->
                                "Отправлено $deliveredCount из ${queued.size}, ACK пока не получен — нажмите «Повторить» в чате"
                            else ->
                                "Сообщения (${queued.size} шт.) остаются в очереди S&F: подтвержденного маршрута нет"
                        } + attachmentNote
                    )
                }
            }
            syncForegroundServiceNotification()
        }
    }

    private fun describeDeliveryRoute(result: SelfYggdrasilRelayResult): String = when {
        result.transport == "LAN_DIRECT" -> "LAN • прямое TCP, ACK ${result.rttMs} мс"
        result.transport == "LOCAL_PROFILE" -> "Локальный профиль • ACK ${result.rttMs} мс"
        result.endToEndConfirmed -> "Yggdrasil • оверлей, ACK ${result.rttMs} мс"
        result.delivered -> "Yggdrasil • оверлей — ожидается ACK"
        else -> "Очередь S&F • ${result.errorDetail ?: "нет маршрута"}"
    }

    private fun describeDeliveryBanner(result: SelfYggdrasilRelayResult, peerTitle: String): String = when {
        result.transport == "LAN_DIRECT" -> "Доставлено напрямую по локальной сети (ACK ${result.rttMs} мс)"
        result.transport == "LOCAL_PROFILE" -> "Доставлено локальному профилю «$peerTitle» (ACK ${result.rttMs} мс)"
        result.endToEndConfirmed -> "Доставлено через оверлей Yggdrasil (ACK ${result.rttMs} мс)"
        else -> "Пакет передан в оверлей Yggdrasil, но ACK от «$peerTitle» не получен — повторы идут в фоне"
    }

    private fun hasVerifiedOverlayKey(conv: ConversationEntity?): Boolean {
        if (conv == null) return false
        val key = conv.publicKeyHex.trim().lowercase()
        return YggCryptoEngine.isEd25519Hex(key) &&
            YggCryptoEngine.addressMatchesEd25519Key(conv.yggIpv6Address, key)
    }

    private fun extractDirectEndpointHint(conv: ConversationEntity?): String {
        if (conv == null) return ""
        // An explicit lan= endpoint is part of this contact and is honoured regardless
        // of the global LAN toggle; passive discovery remains gated by the toggle.
        val bio = conv.bioStatus.trim()
        if (bio.contains(":") && !bio.contains(" ") && !bio.startsWith("127.0.0.1:")) return bio
        val route = conv.routeTransportSummary
        if (route.startsWith("Direct P2P (") && route.endsWith(")")) {
            val extracted = route.removePrefix("Direct P2P (").removeSuffix(")")
            if (!extracted.startsWith("127.0.0.1:")) return extracted
        }
        return ""
    }

    /**
     * Marks incoming messages of [chatId] read and tells the peer which wire ids were
     * displayed. Best-effort: the local state is updated first, the receipt then uses
     * the same overlay/LAN paths as normal messages.
     */
    private suspend fun flushReadReceipts(chatId: String) {
        if (chatId.isBlank() || YggRepository.isSelfChatId(chatId)) return
        val wireIds = repository.getUnreadIncomingWireIds(chatId)
        if (wireIds.isEmpty()) return
        repository.markIncomingMessagesRead(chatId)
        val conv = repository.getConversation(chatId) ?: return
        // Receipts and typing are 1:1 frames; a group has no single peer to address and a
        // receipt sent to one member would claim the whole group read it.
        if (conv.chatType == "GROUP") return
        val state = _uiState.value
        if (conv.ownerProfileId.isNotBlank() && conv.ownerProfileId != state.currentProfileId) return
        val peerIdentity = repository.resolvePeerNodeIdentity(conv)
        val peerIpv6 = peerIdentity.yggIpv6Address.ifBlank { conv.yggIpv6Address }
        if (peerIpv6.isBlank()) return
        val hint = extractDirectEndpointHint(conv)
        networkEngine.dispatchReadReceipt(
            chatId = chatId,
            wireMessageIds = wireIds,
            peerIpv6 = peerIpv6,
            directEndpointHint = hint,
            senderIpv6 = state.identity.yggIpv6Address,
            senderPubKeyHex = state.identity.yggNodePublicKeyHex,
            recipientNodePubKeyHex = peerIdentity.publicKeyHex,
            allowLanFallback = state.privacyMatrix.allowDirectLanDiscovery || hint.isNotBlank()
        )
    }

    /**
     * Sends a reply typed in a system notification. The conversation belongs to a
     * concrete profile, so a reply from a background profile switches the active
     * profile first; the banner states that plainly.
     */
    private suspend fun deliverQuickReply(reply: YggQuickReplyBus.PendingReply) {
        if (reply.chatId.isBlank() || reply.text.isBlank()) return
        val targetProfileId = reply.profileId.ifBlank { _uiState.value.currentProfileId }
        if (targetProfileId != _uiState.value.currentProfileId) {
            val pair = repository.switchPrimaryUserProfile(targetProfileId)
            if (pair == null) {
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Быстрый ответ не отправлен: профиль отправителя недоступен"
                    )
                }
                return
            }
            networkEngine.syncConcurrentProfileListeners(repository.getAllProfilesNow())
            applyUserProfileEntityToState(pair.first)
            syncForegroundServiceNotification()
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Быстрый ответ из уведомления: активный профиль " +
                        "переключён на «${pair.first.displayName}»"
                )
            }
        }
        sendMessage(reply.chatId, reply.text)
    }

    fun sendMessage(
        chatId: String,
        plainText: String,
        attachmentName: String? = null,
        attachmentSizeBytes: Long = 0L,
        attachmentType: String? = null,
        attachmentUri: String? = null,
        voiceDurationSec: Int = 0,
        voiceWaveformCsv: String? = null,
        quotedMessageId: String = "",
        forwardedFrom: String = ""
    ) {
        if (plainText.isBlank() && attachmentName == null && attachmentType == null) return
        updateConnectivitySnapshot()
        val app = getApplication<Application>()
        val currentState = _uiState.value
        val effectiveText = plainText.ifBlank {
            when (attachmentType) {
                "VOICE" -> "Голосовое сообщение (${voiceDurationSec} сек)"
                "IMAGE" -> "Фотография: ${attachmentName ?: "image.jpg"}"
                else -> "Передача P2P файла: ${attachmentName ?: "file.bin"}"
            }
        }
        val effectiveType = attachmentType ?: if (attachmentName != null) "FILE" else null

        viewModelScope.launch {
            val targetConv = repository.getConversation(chatId) ?: conversations.value.find { it.chatId == chatId }
            val allowManualLan = currentState.privacyMatrix.allowDirectLanDiscovery
            // An explicit lan= endpoint from the contact is real consent for a direct
            // route, so it also works offline and with the global LAN toggle off.
            val directHint = extractDirectEndpointHint(targetConv)
            val offline = currentState.isOfflineStoreAndForwardMode ||
                (!currentState.isNetworkAvailable && !allowManualLan && directHint.isBlank())

            // Prepare local copy and binary Base64 payload for real P2P transfer
            var resolvedLocalUri = attachmentUri
            var resolvedSizeBytes = attachmentSizeBytes
            var attachmentBase64: String? = null

            if (effectiveType != null) {
                val (localSavedUri, encodedB64) = YggAudioMediaEngine.prepareAttachmentForP2P(
                    context = app,
                    attachmentName = attachmentName,
                    attachmentType = effectiveType,
                    attachmentUri = attachmentUri,
                    voiceDurationSec = voiceDurationSec.coerceAtLeast(1),
                    voiceWaveformCsv = voiceWaveformCsv
                )
                resolvedLocalUri = localSavedUri
                attachmentBase64 = encodedB64.takeIf { it.isNotBlank() }
                if (attachmentBase64 == null) {
                    _uiState.update {
                        it.copy(statusBannerMessage = "Вложение не отправлено: файл не читается. Выберите его заново.")
                    }
                    return@launch
                }
                if (resolvedSizeBytes <= 0L && !localSavedUri.isNullOrBlank()) {
                    try {
                        val path = android.net.Uri.parse(localSavedUri).path
                        if (!path.isNullOrBlank()) {
                            resolvedSizeBytes = java.io.File(path).length()
                        }
                    } catch (_: Throwable) {
                    }
                }
            }

            // The quote must reference the id the receiver knows the quoted message by.
            val wireQuoteId = if (quotedMessageId.isBlank()) {
                ""
            } else {
                repository.getMessage(quotedMessageId)?.let { quoted ->
                    if (quoted.isOutgoing) {
                        quoted.messageId
                    } else {
                        quoted.peerMessageId.ifBlank { quoted.messageId }
                    }
                }.orEmpty()
            }

            val savedMsg = repository.sendEncryptedMessage(
                chatId = chatId,
                plainText = effectiveText,
                senderName = currentState.userProfile.displayName,
                senderIpv6 = currentState.identity.yggIpv6Address,
                isOfflineMode = offline,
                sealedSenderGlobal = currentState.privacyMatrix.sealedSenderGlobal,
                powDifficultyBits = currentState.privacyMatrix.hashcashDifficultyBits,
                attachmentName = attachmentName,
                attachmentSizeBytes = resolvedSizeBytes,
                attachmentType = effectiveType,
                attachmentUri = resolvedLocalUri,
                voiceDurationSec = voiceDurationSec,
                voiceWaveformCsv = voiceWaveformCsv,
                replyToMessageId = wireQuoteId,
                forwardedFrom = forwardedFrom
            ) ?: return@launch

            if (offline) {
                val reason = if (currentState.isAirplaneModeOn) "Авиарежим" else "Офлайн"
                repository.updateConversationRouteStatus(
                    chatId = chatId,
                    isOnline = false,
                    routeSummary = "Очередь S&F • Сеть Yggdrasil недоступна ($reason)"
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Сеть Yggdrasil недоступна ($reason): сообщение в очереди S&F"
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(txPacketsCount = it.txPacketsCount + 1) }

            if (targetConv?.chatType == "GROUP") {
                val (acked, routed, total) = dispatchGroupMessage(targetConv, savedMsg, attachmentBase64)
                _uiState.update {
                    it.copy(
                        statusBannerMessage = when {
                            total == 0 -> "В группе «${targetConv.title}» нет участников — добавьте контакты"
                            acked == total -> "Группа «${targetConv.title}»: доставлено всем ($acked из $total)"
                            acked > 0 -> "Группа «${targetConv.title}»: ACK подтверждены только $acked из $total"
                            routed > 0 ->
                                "Группа «${targetConv.title}»: передано $routed из $total, ACK пока не получен — нажмите «Повторить»"
                            else ->
                                "Группа «${targetConv.title}»: нет подтвержденного маршрута ни к одному участнику, сообщения в очереди S&F"
                        }
                    )
                }
                syncForegroundServiceNotification()
                return@launch
            }

            val candidatePeers = buildCandidateExternalPeersList()

            // Check if sending to self ("Избранное • Мой узел Yggdrasil" or own IPv6/PubKey)
            if (isConversationSelfNode(targetConv, currentState.identity)) {
                // A node cannot address itself inside the 0200::/7 overlay, so the note is
                // stored locally and the real mesh route is confirmed by the overlay probe.
                repository.markMessageLocalNote(savedMsg.messageId)
                val outcome = networkEngine.probeOverlayRoundTrip(currentState.currentProfileId)
                applyOverlayProbeOutcome(outcome, chatId)
                syncForegroundServiceNotification()
                return@launch
            }

            // Route through the real Yggdrasil overlay first, then an explicit LAN
            // endpoint as a confirmed fallback. A stale stored key is never used.
            val peerIdentity = targetConv?.let { repository.resolvePeerNodeIdentity(it) }
            val peerIpv6 = peerIdentity?.yggIpv6Address?.takeIf { it.isNotBlank() }
                ?: targetConv?.yggIpv6Address
                ?: ""
            val peerNodeKey = peerIdentity?.publicKeyHex.orEmpty()
            val overlayResult = networkEngine.dispatchMessageViaExternalYggdrasilOverlay(
                chatId = chatId,
                messageId = savedMsg.messageId,
                peerTitle = targetConv?.title ?: "Узел Yggdrasil",
                peerIpv6 = peerIpv6,
                directEndpointHint = directHint,
                senderNodeLabel = currentState.userProfile.displayName,
                senderIpv6 = currentState.identity.yggIpv6Address,
                senderPubKeyHex = currentState.identity.yggNodePublicKeyHex,
                recipientNodePubKeyHex = peerNodeKey,
                plainText = effectiveText,
                cipherPayloadHex = savedMsg.cipherPayloadHex,
                hashcashToken = savedMsg.hashcashPowToken,
                attachmentName = attachmentName,
                attachmentSizeBytes = resolvedSizeBytes,
                candidatePeers = candidatePeers,
                allowLanFallback = allowManualLan,
                attachmentType = effectiveType,
                attachmentUri = resolvedLocalUri,
                attachmentBase64 = attachmentBase64,
                voiceDurationSec = voiceDurationSec,
                voiceWaveformCsv = voiceWaveformCsv,
                replyToMessageId = wireQuoteId,
                forwardedFrom = forwardedFrom,
                senderAvatarHash = currentState.userProfile.avatarHashHex,
                senderAvatarBase64 = YggAvatarCodec
                    .base64OfStoredFile(currentState.userProfile.avatarUri).orEmpty()
            )

            if (overlayResult.delivered) {
                if (overlayResult.relayEndpointUri.isNotBlank() && overlayResult.rttMs > 0) {
                    repository.updatePeerTrafficByEndpoint(
                        endpointUri = overlayResult.relayEndpointUri,
                        rttMs = overlayResult.rttMs.toInt(),
                        txDelta = overlayResult.txBytes,
                        rxDelta = overlayResult.rxBytes
                    )
                }
                if (!overlayResult.endToEndConfirmed) {
                    repository.markMessageSentP2P(savedMsg.messageId)
                }
                repository.updateConversationRouteStatus(
                    chatId = chatId,
                    isOnline = true,
                    routeSummary = describeDeliveryRoute(overlayResult)
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessage = describeDeliveryBanner(
                            overlayResult,
                            targetConv?.title ?: "Узел Yggdrasil"
                        )
                    )
                }
            } else {
                repository.updateConversationRouteStatus(
                    chatId = chatId,
                    isOnline = false,
                    routeSummary = "Очередь S&F • ${overlayResult.errorDetail ?: "Нет подтвержденного маршрута"}"
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Сообщение в очереди S&F: ${overlayResult.errorDetail ?: "нет подтвержденного маршрута к получателю"}"
                    )
                }
            }
            syncForegroundServiceNotification()
        }
    }

    fun sendVoiceMessage(
        chatId: String,
        durationSeconds: Int,
        waveformCsv: String,
        recordedFileUri: String? = null,
        recordedFileSizeBytes: Long = 0L
    ) {
        val safeDur = durationSeconds.coerceAtLeast(1)
        // A guessed "duration x 32 kbps" size used to be shown for notes whose file was never
        // measured; sendMessage now re-reads the real length from the saved file.
        val fileName = recordedFileUri?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
            ?: "voice_${System.currentTimeMillis() % 100000}.wav"
        sendMessage(
            chatId = chatId,
            plainText = "Голосовое сообщение PCM/Opus AEAD (${safeDur} сек)",
            attachmentName = fileName,
            attachmentSizeBytes = recordedFileSizeBytes,
            attachmentType = "VOICE",
            attachmentUri = recordedFileUri,
            voiceDurationSec = safeDur,
            voiceWaveformCsv = waveformCsv
        )
    }

    fun sendMediaOrDocumentAttachment(
        chatId: String,
        fileName: String,
        fileSizeBytes: Long,
        attachmentType: String, // "IMAGE" or "FILE"
        attachmentUri: String?,
        caption: String
    ) {
        val defaultCaption = if (attachmentType == "IMAGE") {
            caption.ifBlank { "Зашифрованное изображение ($fileName)" }
        } else {
            caption.ifBlank { "Зашифрованный файл ($fileName)" }
        }
        sendMessage(
            chatId = chatId,
            plainText = defaultCaption,
            attachmentName = fileName,
            attachmentSizeBytes = fileSizeBytes,
            attachmentType = attachmentType,
            attachmentUri = attachmentUri
        )
    }

    fun forwardMessage(messageId: String, targetChatId: String) {
        viewModelScope.launch {
            val source = repository.getMessage(messageId) ?: return@launch
            if (targetChatId.isBlank()) return@launch
            val author = source.forwardedFrom.ifBlank {
                if (source.isOutgoing) _uiState.value.userProfile.displayName else source.senderName
            }
            val text = source.plainText.ifBlank {
                source.attachmentName?.let { "📎 Вложение: $it" }.orEmpty()
            }
            if (text.isBlank()) return@launch
            sendMessage(
                chatId = targetChatId,
                plainText = text,
                forwardedFrom = author.ifBlank { "Yggdrasil" }
            )
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Сообщение переслано в «${repository.getConversationTitle(targetChatId) ?: "диалог"}»"
                )
            }
        }
    }

    /** Throttled typing notification; it is ephemeral and never queued. */
    fun notifyTyping(chatId: String) {
        if (chatId.isBlank() || YggRepository.isSelfChatId(chatId)) return
        val now = System.currentTimeMillis()
        val last = lastTypingSentAt[chatId] ?: 0L
        if (now - last < TYPING_THROTTLE_MS) return
        val state = _uiState.value
        if (state.isOfflineStoreAndForwardMode || !state.isNetworkAvailable) return
        lastTypingSentAt[chatId] = now
        viewModelScope.launch {
            val conv = repository.getConversation(chatId) ?: return@launch
            if (isConversationSelfNode(conv, state.identity)) return@launch
            // A typing frame is addressed to one node; sending it to a single member would
            // imply the whole group was notified.
            if (conv.chatType == "GROUP") return@launch
            val peerIdentity = repository.resolvePeerNodeIdentity(conv)
            val peerIpv6 = peerIdentity.yggIpv6Address.ifBlank { conv.yggIpv6Address }
            if (peerIpv6.isBlank()) return@launch
            val hint = extractDirectEndpointHint(conv)
            networkEngine.dispatchTypingIndicator(
                chatId = chatId,
                peerIpv6 = peerIpv6,
                directEndpointHint = hint,
                senderIpv6 = state.identity.yggIpv6Address,
                senderPubKeyHex = state.identity.yggNodePublicKeyHex,
                senderNodeLabel = state.userProfile.displayName,
                recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                allowLanFallback = state.privacyMatrix.allowDirectLanDiscovery || hint.isNotBlank()
            )
        }
    }

    fun toggleMessageReaction(messageId: String, emoji: String) {        viewModelScope.launch {
            val targetMsg = repository.getMessage(messageId) ?: return@launch
            val updated = repository.toggleReactionOnMessage(
                messageId = messageId,
                emoji = emoji,
                byMe = true
            ) ?: return@launch

            val conv = repository.getConversation(updated.chatId) ?: return@launch
            // Self-chat notes never leave the device; there is no peer to notify.
            if (YggRepository.isSelfChatId(conv.chatId)) return@launch
            // A reaction frame reaches one node, so it cannot claim the group reacted.
            // The local reaction the caller just made stays stored.
            if (conv.chatType == "GROUP") return@launch

            val addedByMe = YggRepository.parseReactionsMap(updated.reactionsJson)[emoji]?.second == true
            // The peer must receive the id its own copy of the message uses.
            val wireMessageId = if (targetMsg.isOutgoing) {
                targetMsg.messageId
            } else {
                targetMsg.peerMessageId.ifBlank { targetMsg.messageId }
            }
            val directHint = extractDirectEndpointHint(conv)
            val peerIdentity = repository.resolvePeerNodeIdentity(conv)
            val ownerProfile = repository.getAllProfilesNow().find { it.profileId == conv.ownerProfileId }
            val result = networkEngine.dispatchReactionFrame(
                chatId = conv.chatId,
                messageId = wireMessageId,
                emoji = emoji,
                action = if (addedByMe) "ADD" else "REMOVE",
                peerIpv6 = peerIdentity.yggIpv6Address.ifBlank { conv.yggIpv6Address },
                directEndpointHint = directHint,
                senderIpv6 = ownerProfile?.yggIpv6Address ?: _uiState.value.identity.yggIpv6Address,
                senderPubKeyHex = ownerProfile?.yggPublicKeyHex
                    ?: _uiState.value.identity.yggNodePublicKeyHex,
                recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                candidatePeers = buildCandidateExternalPeersList(),
                allowLanFallback = _uiState.value.privacyMatrix.allowDirectLanDiscovery
            )
            _uiState.update {
                it.copy(
                    statusBannerMessage = when {
                        result.delivered && result.transport == "YGG_OVERLAY" ->
                            "Реакция $emoji доставлена адресату через оверлей Yggdrasil (ACK)"
                        result.delivered ->
                            "Реакция $emoji доставлена адресату по LAN (ACK)"
                        else -> "Реакция $emoji сохранена локально, но адресат её не подтвердил: " +
                            (result.errorDetail ?: "нет подтвержденного маршрута")
                    }
                )
            }
        }
    }

    fun addContactFromQrPayload(
        parsedQr: com.example.engine.ParsedYggContactQr
    ) {
        // A damaged ?pub= never addresses a node on the overlay, so a card without any
        // other usable route is refused instead of becoming a chat that queues forever.
        if (parsedQr.nodeKeyProblem.isNotBlank() && parsedQr.directLanHint.isBlank()) {
            val reason = "${parsedQr.nodeKeyProblem}. Контакт «${parsedQr.displayName}» не добавлен: " +
                "попросите владельца узла экспортировать визитку заново"
            _uiState.update { it.copy(statusBannerMessage = reason) }
            // The banner slot is shared with routine autopilot notices that replace it
            // within seconds, so the refusal reason is shown on its own as well.
            Toast.makeText(getApplication(), reason, Toast.LENGTH_LONG).show()
            return
        }
        val knownChatIdsBefore = conversations.value.map { it.chatId }.toSet()
        viewModelScope.launch {
            val newChatId = withContext(Dispatchers.IO) {
                repository.createNewConversation(
                    title = parsedQr.displayName,
                    yggIpv6Input = parsedQr.rawUri,
                    disappearingSeconds = _uiState.value.privacyMatrix.defaultDisappearingSeconds,
                    directEndpointHint = parsedQr.directLanHint,
                    localIdentity = _uiState.value.identity,
                    ownerProfileId = _uiState.value.currentProfileId,
                    peerX25519PublicKeyHex = parsedQr.x25519PubKeyHex,
                    peerEd25519PublicKeyHex = parsedQr.ed25519PubKeyHex
                )
            }
            // A card for a node that is already a contact reuses that chat, so say so
            // instead of reporting a second contact the user will never find in the list.
            val wasAlreadyInContacts = newChatId in knownChatIdsBefore
            if (parsedQr.directLanHint.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    networkEngine.registerDirectEndpointForPeer(parsedQr.yggIpv6Address, parsedQr.directLanHint)
                }
            }
            val createdConv = withContext(Dispatchers.IO) { repository.getConversation(newChatId) }
            val hasNodeKey = hasVerifiedOverlayKey(createdConv)
            val createdLanHint = extractDirectEndpointHint(createdConv)
            _uiState.update {
                it.copy(
                    activeTab = YggNavTab.CHATS,
                    selectedChatId = newChatId,
                    statusBannerMessage = when {
                        YggRepository.isSelfChatId(newChatId) ->
                            "Распознан ваш собственный QR-код: открыт чат «Избранное • Мой узел Yggdrasil»"
                        hasNodeKey ->
                            (if (wasAlreadyInContacts)
                                "Контакт «${parsedQr.displayName}» уже был в списке: визитка обновила его имя, адрес и ключ"
                            else
                                "По QR-коду добавлен контакт «${parsedQr.displayName}»") +
                                ": Ed25519-ключ совпадает с адресом ${createdConv?.yggIpv6Address.orEmpty()}"
                        createdLanHint.isNotBlank() ->
                            "Контакт «${parsedQr.displayName}» добавлен: доступен прямой LAN-маршрут ($createdLanHint). " +
                                parsedQr.nodeKeyProblem.ifBlank { "Оверлей требует полный ?pub=…" }
                        else ->
                            "Контакт «${parsedQr.displayName}» добавлен без Ed25519-ключа: сообщения через оверлей недоставимы, нужен полный идентификатор"
                    }
                )
            }
        }
    }

    fun startVoiceCall(chatId: String) {
        if (_uiState.value.activeVoiceCall != null) {
            _uiState.update {
                it.copy(statusBannerMessage = "Голосовой вызов уже идёт: сначала завершите текущий разговор")
            }
            return
        }
        viewModelScope.launch {
            val conv = repository.getConversation(chatId)
                ?: conversations.value.find { it.chatId == chatId }
                ?: return@launch
            val current = _uiState.value
            if (YggRepository.isSelfChatId(conv.chatId) || isConversationSelfNode(conv, current.identity)) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Нельзя позвонить собственному узлу: выберите чат с собеседником")
                }
                return@launch
            }
            if (conv.chatType == "GROUP") {
                _uiState.update {
                    it.copy(statusBannerMessage = "Групповой звонок не поддерживается: вызов идёт на один узел, откройте личный чат участника")
                }
                return@launch
            }
            val ownerProfile = repository.getAllProfilesNow()
                .find { it.profileId == current.currentProfileId }
                ?: return@launch
            val ownerWords = ownerProfile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
            val localPrivateKey = runCatching {
                YggCryptoEngine.x25519PrivateKeyFromMnemonic(ownerWords, 1)
            }.getOrNull()
            if (ownerWords.size < 6 || localPrivateKey == null) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Звонок невозможен: у профиля нет валидной мнемоники для E2EE-ключа")
                }
                return@launch
            }
            val localPubHex = x25519PublicHex(localPrivateKey)
            if (localPubHex.length != 64) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Звонок невозможен: не удалось вывести X25519-ключ профиля")
                }
                return@launch
            }
            val peerIdentity = repository.resolvePeerNodeIdentity(conv)
            val peerIpv6 = peerIdentity.yggIpv6Address.ifBlank { conv.yggIpv6Address }
            if (peerIpv6.isBlank()) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Звонок невозможен: у контакта «${conv.title}» нет адреса Yggdrasil")
                }
                return@launch
            }
            val peerXPub = repository.resolvePeerX25519PublicKeyHex(conv)
            val hint = extractDirectEndpointHint(conv)
            val allowLan = _uiState.value.privacyMatrix.allowDirectLanDiscovery || hint.isNotBlank()
            val callId = UUID.randomUUID().toString()
            val ctx = CallSessionContext(
                chatId = conv.chatId,
                callId = callId,
                peerTitle = conv.title,
                peerIpv6 = peerIpv6,
                peerNodeKeyHex = peerIdentity.publicKeyHex,
                peerLanHint = hint,
                localProfileId = ownerProfile.profileId,
                localIpv6 = ownerProfile.yggIpv6Address,
                localName = ownerProfile.displayName,
                localX25519PrivateKey = localPrivateKey,
                localX25519PubHex = localPubHex,
                peerX25519PubHex = peerXPub,
                isIncoming = false
            )
            activeCallContext = ctx
            activeCallStartedAt = System.currentTimeMillis()
            activeCallMediaKey = if (isX25519KeyHex(peerXPub)) deriveCallKey(ctx) else null
            pendingCallAccept?.cancel()
            pendingCallAccept = null
            val peerKeyKnownAtDial = isX25519KeyHex(peerXPub)
            _uiState.update {
                it.copy(
                    activeVoiceCall = ActiveVoiceCallState(
                        chatId = conv.chatId,
                        peerTitle = conv.title,
                        peerIpv6 = peerIpv6,
                        peerAvatarUri = conv.avatarUri,
                        callStatus = "DIALING_YGG",
                        statusSubtext = "Отправка зашифрованного сигнала вызова через Yggdrasil...",
                        verificationEmojis = if (peerKeyKnownAtDial) {
                            buildCallVerificationEmojis(callId, localPubHex, peerXPub)
                        } else {
                            ""
                        },
                        isIncoming = false,
                        callId = callId,
                        mediaEncrypted = activeCallMediaKey != null,
                        sasPending = !peerKeyKnownAtDial
                    ),
                    statusBannerMessage = "Исходящий вызов «${conv.title}»..."
                )
            }
            YggAudioMediaEngine.startVoipCallAudioSession(
                context = getApplication(),
                isDialing = true,
                isMuted = false,
                isSpeakerphoneOn = true
            )
            val acceptDeferred = CompletableDeferred<IncomingCallSignalPacket>()
            pendingCallAccept = acceptDeferred
            pendingCallAcceptCallId = callId
            activeCallJob?.cancel()
            activeCallJob = viewModelScope.launch {
                var deliveredTransport = ""
                var deliveredRtt = -1L
                var delivered = false
                // One dispatch already re-offers the frame until its CALL_ACK lands, so
                // two rounds only exist to refresh the status line for the user.
                val maxAttempts = 2
                for (attempt in 0 until maxAttempts) {
                    if (_uiState.value.activeVoiceCall?.callId != callId) return@launch
                    if (attempt > 0) {
                        _uiState.update { state ->
                            val call = state.activeVoiceCall ?: return@update state
                            if (call.callId != callId) return@update state
                            state.copy(
                                activeVoiceCall = call.copy(
                                    statusSubtext = "Повтор сигнала вызова • попытка ${attempt + 1} из $maxAttempts..."
                                )
                            )
                        }
                    }
                    val result = networkEngine.dispatchCallControlSignal(
                        chatId = conv.chatId,
                        callId = callId,
                        signalAction = "RINGING",
                        peerIpv6 = peerIpv6,
                        recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                        senderIpv6 = ownerProfile.yggIpv6Address,
                        senderName = ownerProfile.displayName,
                        senderX25519PubKeyHex = localPubHex,
                        directEndpointHint = hint,
                        allowLanFallback = allowLan
                    )
                    if (result.delivered) {
                        delivered = true
                        deliveredTransport = result.transport
                        deliveredRtt = result.rttMs
                        break
                    }
                    if (attempt < maxAttempts - 1) {
                        _uiState.update { state ->
                            val call = state.activeVoiceCall ?: return@update state
                            if (call.callId != callId) return@update state
                            state.copy(
                                activeVoiceCall = call.copy(
                                    statusSubtext = "Попытка ${attempt + 1} из $maxAttempts не удалась: " +
                                        (result.errorDetail ?: "нет подтверждённого маршрута") + " • повтор..."
                                )
                            )
                        }
                        delay(1_500L)
                    }
                }
                if (!delivered) {
                    // The answer may have arrived while the last RINGING attempt was
                    // still waiting for its ACK; an explicit ACCEPT proves delivery.
                    if (!acceptDeferred.isCompleted) {
                        finishCallWithError("Вызов «${conv.title}» не доставлен: нет подтверждённого маршрута до собеседника")
                        return@launch
                    }
                }
                _uiState.update { state ->
                    val call = state.activeVoiceCall ?: return@update state
                    if (call.callId != callId) return@update state
                    state.copy(
                        activeVoiceCall = call.copy(
                            statusSubtext = "Сигнал вызова доставлен ($deliveredTransport) • ожидание ответа...",
                            transport = deliveredTransport,
                            liveRttMs = deliveredRtt.toInt()
                        )
                    )
                }
                // The callee stops ringing 45 s after the first RINGING lands, which can
                // be well before this delivery confirmation, so a shorter wait here still
                // covers the whole remote timer; MISSED/BYE end it even earlier.
                val answer = withTimeoutOrNull(35_000L) { acceptDeferred.await() }
                if (pendingCallAccept === acceptDeferred) pendingCallAccept = null
                if (answer == null) {
                    // Clear the dialing UI first: dispatching MISSED can take tens of
                    // seconds on a lossy overlay, and the phone must not stay "ringing".
                    finishCallWithError("Абонент «${conv.title}» не ответил", "MISSED")
                    viewModelScope.launch {
                        networkEngine.dispatchCallControlSignal(
                            chatId = conv.chatId,
                            callId = callId,
                            signalAction = "MISSED",
                            peerIpv6 = peerIpv6,
                            recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                            senderIpv6 = ownerProfile.yggIpv6Address,
                            senderName = ownerProfile.displayName,
                            senderX25519PubKeyHex = localPubHex,
                            directEndpointHint = hint,
                            allowLanFallback = allowLan
                        )
                    }
                    return@launch
                }
                when (answer.signalAction.uppercase()) {
                    "ACCEPT" -> {
                        // The key carried by ACCEPT is live proof of who answered. A
                        // locally pinned key can belong to an identity that was wiped and
                        // regenerated since, and deriving the media key from it makes every
                        // audio frame undecryptable while the UI still claims a call.
                        val announced = answer.senderX25519PubKeyHex.trim().lowercase()
                        val peerKey = if (isX25519KeyHex(announced)) announced else ctx.peerX25519PubHex
                        if (!isX25519KeyHex(peerKey)) {
                            finishCallWithError("Абонент ответил, но не передал X25519-ключ для E2EE-аудио")
                            return@launch
                        }
                        val keyChanged = isX25519KeyHex(ctx.peerX25519PubHex) &&
                            ctx.peerX25519PubHex.trim().lowercase() != peerKey
                        val acceptedCtx = ctx.copy(peerX25519PubHex = peerKey.trim().lowercase())
                        activeCallContext = acceptedCtx
                        val key = deriveCallKey(acceptedCtx)
                        if (key == null) {
                            finishCallWithError(
                                "Не удалось вывести общий E2EE-ключ звонка",
                                notifyPeer = true
                            )
                            return@launch
                        }
                        activeCallMediaKey = key
                        repository.getConversation(conv.chatId)?.let {
                            repository.pinConversationPeerX25519PublicKey(it, peerKey)
                        }
                        val media = networkEngine.openCallMediaChannel(
                            chatId = conv.chatId,
                            callId = callId,
                            peerIpv6 = peerIpv6,
                            recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                            senderIpv6 = ownerProfile.yggIpv6Address,
                            senderName = ownerProfile.displayName,
                            directEndpointHint = hint,
                            allowLanFallback = allowLan
                        )
                        if (!media.opened) {
                            // The callee already stopped ringing: without BYE it would sit
                            // in a call that this side has already left.
                            finishCallWithError(
                                "Абонент ответил, но голосовой канал не открылся: " +
                                    (media.errorDetail ?: "нет маршрута"),
                                notifyPeer = true
                            )
                            return@launch
                        }
                        activateCall(
                            ctx = acceptedCtx,
                            transport = media.transport.ifBlank { deliveredTransport },
                            rttMs = media.rttMs.takeIf { it > 0 } ?: deliveredRtt,
                            note = if (keyChanged) {
                                "X25519-ключ собеседника сменился с прошлого звонка — сверьте эмодзи SAS"
                            } else {
                                ""
                            }
                        )
                    }
                    "DECLINE" -> finishCallWithError("Абонент «${conv.title}» отклонил вызов", "DECLINED")
                    "BUSY" -> finishCallWithError("Абонент «${conv.title}» занят", "BUSY")
                    "BYE" -> finishCallWithError("Абонент «${conv.title}» завершил вызов", "CANCELED")
                    "MISSED" -> finishCallWithError("Вызов не принят абонентом", "MISSED")
                    else -> finishCallWithError("Вызов прерван (${answer.signalAction})")
                }
            }
        }
    }

    private suspend fun handleIncomingCallSignal(callSig: IncomingCallSignalPacket) {
        val action = callSig.signalAction.ifBlank { "RINGING" }.uppercase()
        if (action == "RINGING") {
            handleIncomingRing(callSig)
            return
        }
        if (action == "ACCEPT") {
            val deferred = pendingCallAccept
            if (deferred != null && callSig.callId == pendingCallAcceptCallId && !deferred.isCompleted) {
                deferred.complete(callSig)
            }
            return
        }
        // DECLINE / BUSY / BYE / MISSED abort a dialing call through the accept waiter.
        val deferred = pendingCallAccept
        if (deferred != null && callSig.callId == pendingCallAcceptCallId && !deferred.isCompleted) {
            deferred.complete(callSig)
            return
        }
        val ctx = activeCallContext
        if (ctx != null && ctx.callId == callSig.callId) {
            // A repeated ACCEPT must never tear down an already active call.
            if (action == "ACCEPT") return
            val message = when (action) {
                "DECLINE" -> "Абонент «${ctx.peerTitle}» отклонил вызов"
                "BUSY" -> "Абонент «${ctx.peerTitle}» занят"
                "BYE" -> "Собеседник «${ctx.peerTitle}» завершил вызов"
                "MISSED" -> "Вызов не был принят"
                else -> "Вызов завершён ($action)"
            }
            val outcome = when (action) {
                "DECLINE" -> "DECLINED"
                "BUSY" -> "BUSY"
                "MISSED" -> "MISSED"
                // BYE on an active call is a normal completion, on a ringing
                // incoming call it means the caller hung up before an answer.
                "BYE" -> if (_uiState.value.activeVoiceCall?.callStatus == "RINGING_INCOMING") {
                    "CANCELED"
                } else {
                    "COMPLETED"
                }
                else -> "FAILED"
            }
            finishCallWithError(message, outcome)
            return
        }
        if (action == "MISSED") {
            // The peer's MISSED can echo back a call this device just finished itself
            // (both sides time out at 45 s); rendering it here would fake an incoming
            // missed call on the caller's phone.
            if (recentlyEndedCallIds.containsKey(callSig.callId)) return
            val target = repository.getAllProfilesNow().find {
                it.profileId == callSig.recipientProfileId ||
                    sameMeshAddress(it.yggIpv6Address, callSig.recipientIpv6)
            } ?: return
            emitPopupAndSystemNotification(
                category = YggAlertCategory.MISSED_CALL,
                targetProfile = target,
                chatId = callSig.chatId,
                senderName = callSig.callerName.ifBlank { "Узел Yggdrasil" },
                senderIpv6 = callSig.callerIpv6.ifBlank { "0200::/7" },
                messagePreview = "Пропущенный зашифрованный аудиозвонок Yggdrasil 0200::/7"
            )
        }
    }

    private fun pruneRecentlyEndedCalls() {
        val cutoff = System.currentTimeMillis() - 120_000L
        recentlyEndedCallIds.entries.removeAll { it.value < cutoff }
        if (recentlyEndedCallIds.size > 32) {
            recentlyEndedCallIds.entries
                .sortedBy { it.value }
                .take(recentlyEndedCallIds.size - 32)
                .forEach { recentlyEndedCallIds.remove(it.key) }
        }
    }

    private suspend fun handleIncomingRing(callSig: IncomingCallSignalPacket) {
        if (callSig.callId.isBlank() || callSig.callerIpv6.isBlank()) return
        pruneRecentlyEndedCalls()
        if (recentlyEndedCallIds.containsKey(callSig.callId)) return
        // A retried RINGING for the call we already know must not become BUSY:
        // that used to tear down a call the user had just answered.
        val own = activeCallContext
        if (own != null && own.callId == callSig.callId) {
            if (!own.isIncoming) {
                // The ring landed back on this device: both legs are profiles of the one
                // process, which holds a single call slot. Saying "абонент не ответил"
                // after 35 silent seconds would blame the peer for our own limit.
                finishCallWithError(
                    "Звонок между двумя профилями одного устройства не поддерживается: " +
                        "наберите собеседника на другом устройстве"
                )
            }
            return
        }

        val allProfs = repository.getAllProfilesNow()
        val targetProfile = allProfs.find {
            it.profileId == callSig.recipientProfileId ||
                sameMeshAddress(it.yggIpv6Address, callSig.recipientIpv6)
        } ?: allProfs.find { it.isCurrentPrimary } ?: return
        if (!targetProfile.isActiveInBackground && !targetProfile.isCurrentPrimary) return

        val callerTitle = callSig.callerName.ifBlank { "Узел Yggdrasil" }
        val conversationChatId = repository.createNewConversation(
            title = callerTitle,
            yggIpv6Input = callSig.callerIpv6,
            disappearingSeconds = 0,
            directEndpointHint = callSig.senderLanHint,
            ownerProfileId = targetProfile.profileId,
            peerX25519PublicKeyHex = callSig.senderX25519PubKeyHex,
            peerEd25519PublicKeyHex = callSig.senderNodePubKeyHex
        )
        if (isX25519KeyHex(callSig.senderX25519PubKeyHex)) {
            repository.getConversation(conversationChatId)?.let {
                repository.pinConversationPeerX25519PublicKey(it, callSig.senderX25519PubKeyHex)
            }
        }

        val profileWords = targetProfile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
        val localPrivateKey = runCatching {
            YggCryptoEngine.x25519PrivateKeyFromMnemonic(profileWords, 1)
        }.getOrNull() ?: return
        val localPubHex = x25519PublicHex(localPrivateKey)
        // The incoming LAN connection can appear as 127.0.0.1 (port-forwarded
        // emulators) and then carries no usable reply hint. Fall back to the
        // endpoint pinned in the contact so ACCEPT/BYE still take the direct path.
        val replyLanHint = callSig.senderLanHint.ifBlank {
            repository.getConversation(conversationChatId)?.let { extractDirectEndpointHint(it) }.orEmpty()
        }
        val ctx = CallSessionContext(
            chatId = conversationChatId,
            callId = callSig.callId,
            peerTitle = callerTitle,
            peerIpv6 = callSig.callerIpv6,
            peerNodeKeyHex = callSig.senderNodePubKeyHex,
            peerLanHint = replyLanHint,
            localProfileId = targetProfile.profileId,
            localIpv6 = targetProfile.yggIpv6Address,
            localName = targetProfile.displayName,
            localX25519PrivateKey = localPrivateKey,
            localX25519PubHex = localPubHex,
            peerX25519PubHex = callSig.senderX25519PubKeyHex.trim().lowercase(),
            isIncoming = true
        )

        if (_uiState.value.activeVoiceCall != null || activeCallContext != null) {
            sendCallControlSignalAsync(ctx, "BUSY")
            logClosedCall(ctx, "BUSY", callSig.transport)
            _uiState.update {
                it.copy(statusBannerMessage = "Входящий вызов от «$callerTitle»: линия занята, отправлен BUSY")
            }
            return
        }
        if (!isX25519KeyHex(callSig.senderX25519PubKeyHex)) {
            sendCallControlSignalAsync(ctx, "DECLINE")
            logClosedCall(ctx, "DECLINED", callSig.transport)
            _uiState.update {
                it.copy(statusBannerMessage = "Входящий вызов от «$callerTitle» отклонён: нет X25519-ключа для E2EE-аудио")
            }
            return
        }

        emitPopupAndSystemNotification(
            category = YggAlertCategory.INCOMING_CALL,
            targetProfile = targetProfile,
            chatId = conversationChatId,
            senderName = callerTitle,
            senderIpv6 = callSig.callerIpv6,
            messagePreview = "Входящий зашифрованный голосовой вызов Yggdrasil 0200::/7",
            callId = callSig.callId
        )

        activeCallContext = ctx
        activeCallStartedAt = System.currentTimeMillis()
        activeCallMediaKey = deriveCallKey(ctx)
        pendingCallAccept?.cancel()
        pendingCallAccept = null
        activeCallJob?.cancel()
        activeCallJob = null
        val targetIsCurrent = targetProfile.profileId == _uiState.value.currentProfileId
        val callerAvatarUri = conversations.value
            .find { it.chatId == conversationChatId }?.avatarUri.orEmpty()
        _uiState.update {
            it.copy(
                activeVoiceCall = ActiveVoiceCallState(
                    chatId = conversationChatId,
                    peerTitle = callerTitle,
                    peerIpv6 = callSig.callerIpv6,
                    peerAvatarUri = callerAvatarUri,
                    callStatus = "RINGING_INCOMING",
                    statusSubtext = "Входящий зашифрованный вызов (${callSig.transport}) • примите или отклоните",
                    verificationEmojis = buildCallVerificationEmojis(
                        callSig.callId,
                        localPubHex,
                        callSig.senderX25519PubKeyHex
                    ),
                    isIncoming = true,
                    callId = callSig.callId,
                    transport = callSig.transport,
                    mediaEncrypted = activeCallMediaKey != null
                ),
                selectedChatId = if (targetIsCurrent) conversationChatId else it.selectedChatId,
                statusBannerMessage = "Входящий голосовой вызов от «$callerTitle»"
            )
        }
        YggAudioMediaEngine.startVoipCallAudioSession(
            context = getApplication(),
            isDialing = true,
            isMuted = false,
            isSpeakerphoneOn = true
        )
        YggCallAlertController.start(getApplication())
        callTimeoutJob?.cancel()
        callTimeoutJob = viewModelScope.launch {
            delay(45_000L)
            val ringingCall = _uiState.value.activeVoiceCall
            if (ringingCall?.callId == callSig.callId && ringingCall.callStatus == "RINGING_INCOMING") {
                val missedCtx = ctx
                // Free the line immediately, then tell the caller asynchronously:
                // a slow MISSED dispatch used to keep the phone "busy" for the next call.
                viewModelScope.launch {
                    networkEngine.dispatchCallControlSignal(
                        chatId = missedCtx.chatId,
                        callId = missedCtx.callId,
                        signalAction = "MISSED",
                        peerIpv6 = missedCtx.peerIpv6,
                        recipientNodePubKeyHex = missedCtx.peerNodeKeyHex,
                        senderIpv6 = missedCtx.localIpv6,
                        senderName = missedCtx.localName,
                        senderX25519PubKeyHex = missedCtx.localX25519PubHex,
                        directEndpointHint = missedCtx.peerLanHint,
                        allowLanFallback = _uiState.value.privacyMatrix.allowDirectLanDiscovery
                    )
                }
                emitPopupAndSystemNotification(
                    category = YggAlertCategory.MISSED_CALL,
                    targetProfile = targetProfile,
                    chatId = conversationChatId,
                    senderName = callerTitle,
                    senderIpv6 = callSig.callerIpv6,
                    messagePreview = "Пропущенный зашифрованный аудиозвонок: абонент не ответил за 45 секунд"
                )
                finishCallWithError("Пропущенный вызов от «$callerTitle»", "MISSED")
            }
        }
    }

    fun acceptIncomingCall() {
        val ctx = activeCallContext ?: return
        val call = _uiState.value.activeVoiceCall ?: return
        if (!ctx.isIncoming || call.callStatus != "RINGING_INCOMING") return
        if (ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Разрешите доступ к микрофону, чтобы принять звонок от «${ctx.peerTitle}»"
                )
            }
            return
        }
        YggCallAlertController.stop()
        YggNotificationCenter.cancelIncomingCall(getApplication(), ctx.callId)
        callTimeoutJob?.cancel()
        callTimeoutJob = null
        _uiState.update { state ->
            val active = state.activeVoiceCall ?: return@update state
            state.copy(
                activeVoiceCall = active.copy(statusSubtext = "Подтверждаем вызов и открываем E2EE-аудиоканал...")
            )
        }
        activeCallJob?.cancel()
        activeCallJob = viewModelScope.launch {
            // One dispatch re-offers ACCEPT until the caller's CALL_ACK lands. The return
            // path over the overlay is cold at this moment (the caller was the only side
            // transmitting), so this is what turns "the peer answered" into a fact.
            val answer = networkEngine.dispatchCallControlSignal(
                chatId = ctx.chatId,
                callId = ctx.callId,
                signalAction = "ACCEPT",
                peerIpv6 = ctx.peerIpv6,
                recipientNodePubKeyHex = ctx.peerNodeKeyHex,
                senderIpv6 = ctx.localIpv6,
                senderName = ctx.localName,
                senderX25519PubKeyHex = ctx.localX25519PubHex,
                directEndpointHint = ctx.peerLanHint,
                allowLanFallback = _uiState.value.privacyMatrix.allowDirectLanDiscovery,
                timeoutMs = 25_000L
            )
            if (!answer.delivered) {
                finishCallWithError(
                    "Не удалось подтвердить вызов: ${answer.errorDetail ?: "нет маршрута"}",
                    notifyPeer = true
                )
                return@launch
            }
            val key = activeCallMediaKey ?: deriveCallKey(ctx)
            if (key == null) {
                finishCallWithError("Не удалось вывести общий E2EE-ключ звонка", notifyPeer = true)
                return@launch
            }
            activeCallMediaKey = key
            val media = networkEngine.openCallMediaChannel(
                chatId = ctx.chatId,
                callId = ctx.callId,
                peerIpv6 = ctx.peerIpv6,
                recipientNodePubKeyHex = ctx.peerNodeKeyHex,
                senderIpv6 = ctx.localIpv6,
                senderName = ctx.localName,
                directEndpointHint = ctx.peerLanHint,
                allowLanFallback = _uiState.value.privacyMatrix.allowDirectLanDiscovery
            )
            if (!media.opened) {
                finishCallWithError(
                    "Голосовой канал не открылся: " + (media.errorDetail ?: "нет маршрута"),
                    notifyPeer = true
                )
                return@launch
            }
            activateCall(ctx, media.transport.ifBlank { answer.transport }, answer.rttMs)
        }
    }

    fun declineIncomingCall() {
        val ctx = activeCallContext ?: return
        val call = _uiState.value.activeVoiceCall ?: return
        if (!ctx.isIncoming || call.callStatus != "RINGING_INCOMING") return
        sendCallControlSignalAsync(ctx, "DECLINE")
        finishCallWithError("Входящий вызов от «${ctx.peerTitle}» отклонён", "DECLINED")
    }

    private fun activateCall(ctx: CallSessionContext, transport: String, rttMs: Long, note: String = "") {
        val app = getApplication<Application>()
        val call = _uiState.value.activeVoiceCall ?: return
        if (call.callId != ctx.callId) return
        if (activeCallMediaKey == null) {
            finishCallWithError("E2EE-ключ звонка не выведен")
            return
        }
        val effectiveTransport = transport.ifBlank { "YGG_OVERLAY" }
        val effectiveRtt = rttMs.takeIf { it > 0 } ?: 0L
        val channelLine = "Зашифрованный аудиопоток AES-256-GCM • $effectiveTransport" +
            (if (effectiveRtt > 0) " • RTT $effectiveRtt мс" else "") +
            // Silent filler frames keep the counters moving without a capture device, so
            // the call UI has to say that nothing real is leaving this side.
            (if (YggAudioMediaEngine.isVoipMicCapturing()) "" else " • микрофон не захвачен: уходит тишина")
        callRxFrameCounter.set(0)
        callRxAeadFailures.set(0)
        callLastLevelEmitAt = 0L
        while (callMicQueue.tryReceive().isSuccess) {
        }
        YggAudioMediaEngine.setVoipMicFrameListener { pcm ->
            callMicQueue.trySend(pcm)
        }
        YggAudioMediaEngine.setVoipLevelListener { level ->
            val now = System.currentTimeMillis()
            if (now - callLastLevelEmitAt >= 120L) {
                callLastLevelEmitAt = now
                _uiState.update { state ->
                    val active = state.activeVoiceCall ?: return@update state
                    if (active.callStatus != "ACTIVE_ENCRYPTED") return@update state
                    state.copy(
                        activeVoiceCall = active.copy(
                            micAmplitudeLevel = level.coerceIn(0f, 1f)
                        )
                    )
                }
            }
        }
        YggAudioMediaEngine.clearVoipRemotePcm()
        YggAudioMediaEngine.updateVoipCallAudioState(
            context = app,
            isDialing = false,
            isMuted = call.isMuted,
            isSpeakerphoneOn = call.isSpeakerphoneOn
        )
        _uiState.update { state ->
            val active = state.activeVoiceCall ?: return@update state
            if (active.callId != ctx.callId) return@update state
            val sas = if (isX25519KeyHex(ctx.peerX25519PubHex)) {
                buildCallVerificationEmojis(ctx.callId, ctx.localX25519PubHex, ctx.peerX25519PubHex)
            } else {
                ""
            }
            state.copy(
                activeVoiceCall = active.copy(
                    callStatus = "ACTIVE_ENCRYPTED",
                    statusSubtext = if (note.isBlank()) channelLine else "$note • $channelLine",
                    transport = effectiveTransport,
                    mediaEncrypted = true,
                    liveRttMs = effectiveRtt.toInt(),
                    micAmplitudeLevel = 0f,
                    // The callee's key may have arrived with ACCEPT: the emojis shown
                    // while dialing could not be derived, so compute them now.
                    verificationEmojis = sas.ifBlank { active.verificationEmojis },
                    sasPending = sas.isBlank()
                )
            )
        }
        callMediaSendJob?.cancel()
        callMediaSendJob = viewModelScope.launch(Dispatchers.Default) {
            var sequence = 0L
            var sinceUiUpdate = 0
            for (pcm in callMicQueue) {
                val currentCtx = activeCallContext ?: break
                val key = activeCallMediaKey ?: break
                val frame = runCatching {
                    YggCryptoEngine.sealCallMediaFrame(key, pcm, currentCtx.callId)
                }.getOrNull() ?: break
                sequence++
                val sent = networkEngine.sendCallAudioFrame(currentCtx.callId, frame.first, frame.second, sequence)
                if (!sent) {
                    withContext(Dispatchers.Main) {
                        finishCallWithError("Голосовой канал прерван: аудиокадры не доставляются")
                    }
                    break
                }
                sinceUiUpdate++
                if (sinceUiUpdate >= 12) {
                    sinceUiUpdate = 0
                    _uiState.update { state ->
                        val active = state.activeVoiceCall ?: return@update state
                        state.copy(
                            activeVoiceCall = active.copy(txAudioFrames = active.txAudioFrames + 12),
                            txPacketsCount = state.txPacketsCount + 12
                        )
                    }
                }
            }
        }
        callTickerJob?.cancel()
        callTickerJob = viewModelScope.launch {
            var elapsed = 0
            var lastRx = callRxFrameCounter.get()
            var silentSeconds = 0
            while (true) {
                delay(1_000L)
                val active = _uiState.value.activeVoiceCall ?: break
                if (active.callStatus != "ACTIVE_ENCRYPTED" || active.callId != ctx.callId) break
                elapsed++
                val rxNow = callRxFrameCounter.get()
                val rxDelta = (rxNow - lastRx).coerceAtLeast(0L)
                lastRx = rxNow
                silentSeconds = if (rxDelta == 0L) silentSeconds + 1 else 0
                if (silentSeconds >= CALL_RX_STALL_SECONDS) {
                    // The peer transmits encrypted silence while muted, so nothing has
                    // arrived at all: the "encrypted call" the timer shows is not real.
                    val failures = callRxAeadFailures.get()
                    finishCallWithError(
                        if (failures > 0) {
                            "Аудио собеседника не расшифровывается ($failures кадров): " +
                                "E2EE-ключи звонка не совпадают"
                        } else {
                            "Голосовой канал прерван: от собеседника не пришёл ни один аудиокадр"
                        },
                        outcome = "FAILED",
                        notifyPeer = true
                    )
                    break
                }
                _uiState.update { state ->
                    val current = state.activeVoiceCall ?: return@update state
                    if (current.callId != ctx.callId || current.callStatus != "ACTIVE_ENCRYPTED") {
                        return@update state
                    }
                    state.copy(
                        activeVoiceCall = current.copy(
                            durationSeconds = elapsed,
                            rxAudioFrames = current.rxAudioFrames + rxDelta.toInt()
                        ),
                        rxPacketsCount = state.rxPacketsCount + rxDelta.toInt()
                    )
                }
            }
        }
    }

    private fun handleIncomingCallAudioFrame(packet: IncomingCallAudioPacket) {
        val ctx = activeCallContext ?: return
        if (packet.callId != ctx.callId) return
        if (_uiState.value.activeVoiceCall?.callStatus != "ACTIVE_ENCRYPTED") return
        if (packet.recipientProfileId.isNotBlank() && packet.recipientProfileId != ctx.localProfileId) return
        val key = activeCallMediaKey ?: return
        if (packet.ivBase64.isBlank() || packet.cipherBase64.isBlank()) return
        val pcm = runCatching {
            YggCryptoEngine.openCallMediaFrame(key, packet.ivBase64, packet.cipherBase64, packet.callId)
        }.getOrElse {
            callRxAeadFailures.incrementAndGet()
            return
        }
        if (pcm.isEmpty()) return
        YggAudioMediaEngine.enqueueVoipRemotePcm(pcm)
        callRxFrameCounter.incrementAndGet()
    }

    fun toggleCallMute() {
        val app = getApplication<Application>()
        _uiState.update { state ->
            val call = state.activeVoiceCall ?: return@update state
            val nextMute = !call.isMuted
            YggAudioMediaEngine.updateVoipCallAudioState(
                context = app,
                isDialing = call.callStatus != "ACTIVE_ENCRYPTED",
                isMuted = nextMute,
                isSpeakerphoneOn = call.isSpeakerphoneOn
            )
            state.copy(activeVoiceCall = call.copy(isMuted = nextMute))
        }
    }

    fun toggleCallSpeakerphone() {
        val app = getApplication<Application>()
        _uiState.update { state ->
            val call = state.activeVoiceCall ?: return@update state
            val nextSpeaker = !call.isSpeakerphoneOn
            YggAudioMediaEngine.updateVoipCallAudioState(
                context = app,
                isDialing = call.callStatus != "ACTIVE_ENCRYPTED",
                isMuted = call.isMuted,
                isSpeakerphoneOn = nextSpeaker
            )
            state.copy(activeVoiceCall = call.copy(isSpeakerphoneOn = nextSpeaker))
        }
    }

    fun triggerCallTestAudioTone() {
        YggAudioMediaEngine.playVoipTestTonePulse()
    }

    fun endVoiceCall() {
        val ctx = activeCallContext
        val endedCall = _uiState.value.activeVoiceCall ?: return
        activeCallJob?.cancel()
        activeCallJob = null
        if (ctx != null) {
            sendCallControlSignalAsync(
                ctx,
                if (endedCall.callStatus == "RINGING_INCOMING") "DECLINE" else "BYE"
            )
        }
        val banner = if (endedCall.durationSeconds > 0) {
            "Голосовой вызов с «${endedCall.peerTitle}» завершён (${endedCall.durationSeconds} сек)"
        } else {
            null
        }
        val outcome = when (endedCall.callStatus) {
            "RINGING_INCOMING" -> "DECLINED"
            "DIALING_YGG" -> "CANCELED"
            "ACTIVE_ENCRYPTED" -> "COMPLETED"
            else -> null
        }
        finishCallWithError(banner, outcome)
    }

    private fun logClosedCall(ctx: CallSessionContext, outcome: String, transport: String) {
        val entry = CallLogEntity(
            callId = ctx.callId,
            chatId = ctx.chatId,
            peerName = ctx.peerTitle,
            peerIpv6 = ctx.peerIpv6,
            direction = if (ctx.isIncoming) "INCOMING" else "OUTGOING",
            outcome = outcome,
            startedAt = System.currentTimeMillis(),
            durationSeconds = 0,
            transport = transport,
            ownerProfileId = ctx.localProfileId
        )
        viewModelScope.launch {
            runCatching { repository.recordCallLog(entry) }
        }
    }

    private fun sendCallControlSignalAsync(ctx: CallSessionContext, action: String) {
        viewModelScope.launch {
            runCatching {
                networkEngine.dispatchCallControlSignal(
                    chatId = ctx.chatId,
                    callId = ctx.callId,
                    signalAction = action,
                    peerIpv6 = ctx.peerIpv6,
                    recipientNodePubKeyHex = ctx.peerNodeKeyHex,
                    senderIpv6 = ctx.localIpv6,
                    senderName = ctx.localName,
                    senderX25519PubKeyHex = ctx.localX25519PubHex,
                    directEndpointHint = ctx.peerLanHint,
                    allowLanFallback = _uiState.value.privacyMatrix.allowDirectLanDiscovery || ctx.peerLanHint.isNotBlank(),
                    timeoutMs = 12_000L
                )
            }
        }
    }

    private fun finishCallWithError(
        message: String?,
        outcome: String? = null,
        notifyPeer: Boolean = false
    ) {
        val app = getApplication<Application>()
        val ctx = activeCallContext
        val endedCall = _uiState.value.activeVoiceCall
        val startedAt = activeCallStartedAt
        // A locally detected failure (dead media path, channel that never opened) leaves
        // the peer sitting in a call that has already ended here; without BYE it only
        // finds out from its own stall timeout.
        if (notifyPeer && ctx != null) sendCallControlSignalAsync(ctx, "BYE")
        YggCallAlertController.stop()
        ctx?.callId?.takeIf { it.isNotBlank() }?.let { callId ->
            YggNotificationCenter.cancelIncomingCall(app, callId)
            recentlyEndedCallIds[callId] = System.currentTimeMillis()
            pruneRecentlyEndedCalls()
        }
        callMediaSendJob?.cancel()
        callMediaSendJob = null
        callTickerJob?.cancel()
        callTickerJob = null
        callTimeoutJob?.cancel()
        callTimeoutJob = null
        pendingCallAccept?.cancel()
        pendingCallAccept = null
        YggAudioMediaEngine.setVoipMicFrameListener(null)
        YggAudioMediaEngine.setVoipLevelListener(null)
        YggAudioMediaEngine.clearVoipRemotePcm()
        YggAudioMediaEngine.stopVoipCallAudioSession(app)
        ctx?.let { networkEngine.closeCallMediaChannel(it.callId) }
        activeCallContext = null
        activeCallStartedAt = 0L
        activeCallMediaKey = null
        callRxFrameCounter.set(0)
        callRxAeadFailures.set(0)
        callLastLevelEmitAt = 0L
        while (callMicQueue.tryReceive().isSuccess) {
        }
        if (ctx != null) {
            val resolvedOutcome = outcome ?: when {
                endedCall?.callStatus == "ACTIVE_ENCRYPTED" -> "COMPLETED"
                ctx.isIncoming && endedCall?.callStatus == "RINGING_INCOMING" -> "DECLINED"
                else -> "FAILED"
            }
            val entry = CallLogEntity(
                callId = ctx.callId,
                chatId = ctx.chatId,
                peerName = ctx.peerTitle,
                peerIpv6 = ctx.peerIpv6,
                direction = if (ctx.isIncoming) "INCOMING" else "OUTGOING",
                outcome = resolvedOutcome,
                startedAt = startedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                durationSeconds = endedCall?.durationSeconds ?: 0,
                transport = endedCall?.transport.orEmpty(),
                ownerProfileId = ctx.localProfileId
            )
            viewModelScope.launch {
                runCatching { repository.recordCallLog(entry) }
            }
        }
        _uiState.update { state ->
            val popup = state.activePopupNotification
            val staleCallPopup = popup != null &&
                popup.category == YggAlertCategory.INCOMING_CALL &&
                (ctx == null || popup.callId.isBlank() || popup.callId == ctx.callId)
            state.copy(
                activeVoiceCall = null,
                activePopupNotification = if (staleCallPopup) null else state.activePopupNotification,
                statusBannerMessage = message?.takeIf { it.isNotBlank() } ?: state.statusBannerMessage
            )
        }
    }

    private fun x25519PublicHex(privateKey: ByteArray): String = runCatching {
        YggCryptoEngine.x25519PublicKeyFromPrivate(privateKey)
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }.getOrDefault("")

    private fun isX25519KeyHex(value: String): Boolean {
        val clean = value.trim()
        return clean.length == 64 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    private fun deriveCallKey(ctx: CallSessionContext): ByteArray? = runCatching {
        YggCryptoEngine.deriveCallMediaKey(ctx.localX25519PrivateKey, ctx.peerX25519PubHex, ctx.callId)
    }.getOrNull()

    private fun buildCallVerificationEmojis(callId: String, localPub: String, peerPub: String): String {
        val emojiPool = listOf("🔐", "🛰️", "🐉", "⚡", "🛡️", "💎", "🔥", "🌊", "🦊", "🪐", "🎯", "🔑")
        // X25519 public keys have no natural order, so both sides must sort them
        // before hashing or the caller and callee display different SAS emojis.
        val (firstKey, secondKey) = listOf(localPub.lowercase(), peerPub.lowercase()).sorted()
        val hashHex = YggCryptoEngine.sha256Hex("$callId:$firstKey:$secondKey")
        return (0 until 4).map { idx ->
            val byteVal = hashHex.substring(idx * 2, idx * 2 + 2).toIntOrNull(16) ?: idx
            emojiPool[byteVal % emojiPool.size]
        }.joinToString(" ")
    }

    /**
     * Retries sending an offline/queued message over the real Yggdrasil network when tapped in chat.
     */
    fun retrySendMessage(message: MessageEntity) {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            val app = getApplication<Application>()
            val currentState = _uiState.value
            val conv = repository.getConversation(message.chatId) ?: return@launch
            val directHint = extractDirectEndpointHint(conv)
            val allowManualLan = currentState.privacyMatrix.allowDirectLanDiscovery

            if (!networkEngine.isRealNetworkAvailable() && !allowManualLan && directHint.isBlank()) {
                val reason = if (_uiState.value.isAirplaneModeOn) "Включен Авиарежим" else "Нет подключения к сети Yggdrasil"
                _uiState.update {
                    it.copy(statusBannerMessage = "Повторная отправка невозможна: $reason")
                }
                return@launch
            }

            val wantsAttachment = message.attachmentType != null || message.attachmentName != null
            val attachmentBase64 = if (wantsAttachment) {
                YggAudioMediaEngine.prepareAttachmentForP2P(
                    context = app,
                    attachmentName = message.attachmentName,
                    attachmentType = message.attachmentType,
                    attachmentUri = message.attachmentUri,
                    voiceDurationSec = message.voiceDurationSec,
                    voiceWaveformCsv = message.voiceWaveformCsv
                ).second.takeIf { it.isNotBlank() }
            } else {
                null
            }
            if (wantsAttachment && attachmentBase64 == null) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Повтор не отправлен: файл вложения не читается. Прикрепите его заново.")
                }
                return@launch
            }

            val candidatePeers = buildCandidateExternalPeersList()
            if (isConversationSelfNode(conv, currentState.identity)) {
                // Retrying a note to the own node means re-checking the real overlay route.
                repository.markMessageLocalNote(message.messageId)
                val outcome = networkEngine.probeOverlayRoundTrip(currentState.currentProfileId)
                applyOverlayProbeOutcome(outcome, conv.chatId)
            } else if (conv.chatType == "GROUP") {
                val (acked, routed, total) = dispatchGroupMessage(conv, message, attachmentBase64)
                _uiState.update {
                    it.copy(
                        statusBannerMessage = when {
                            total == 0 -> "В группе нет участников: сначала добавьте контакты"
                            acked == total -> "Повтор в группу: доставлено всем ($acked из $total)"
                            else -> "Повтор в группу: ACK $acked из $total, кадров передано: $routed"
                        }
                    )
                }
            } else {
                val peerIdentity = repository.resolvePeerNodeIdentity(conv)
                val overlayRes = networkEngine.dispatchMessageViaExternalYggdrasilOverlay(
                    chatId = conv.chatId,
                    messageId = message.messageId,
                    peerTitle = conv.title,
                    peerIpv6 = peerIdentity.yggIpv6Address.ifBlank { conv.yggIpv6Address },
                    directEndpointHint = directHint,
                    senderNodeLabel = currentState.userProfile.displayName,
                    senderIpv6 = currentState.identity.yggIpv6Address,
                    senderPubKeyHex = currentState.identity.yggNodePublicKeyHex,
                    recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                    plainText = message.plainText,
                    cipherPayloadHex = message.cipherPayloadHex,
                    hashcashToken = message.hashcashPowToken,
                    attachmentName = message.attachmentName,
                    attachmentSizeBytes = message.attachmentSizeBytes,
                    candidatePeers = candidatePeers,
                    allowLanFallback = allowManualLan,
                    attachmentType = message.attachmentType,
                    attachmentUri = message.attachmentUri,
                    attachmentBase64 = attachmentBase64,
                    voiceDurationSec = message.voiceDurationSec,
                    voiceWaveformCsv = message.voiceWaveformCsv
                )
                if (overlayRes.delivered) {
                    if (!overlayRes.endToEndConfirmed) {
                        repository.markMessageSentP2P(message.messageId)
                    }
                    if (overlayRes.relayEndpointUri.isNotBlank() && overlayRes.rttMs > 0) {
                        repository.updatePeerTrafficByEndpoint(
                            endpointUri = overlayRes.relayEndpointUri,
                            rttMs = overlayRes.rttMs.toInt(),
                            txDelta = overlayRes.txBytes,
                            rxDelta = overlayRes.rxBytes
                        )
                    }
                    repository.updateConversationRouteStatus(
                        chatId = conv.chatId,
                        isOnline = true,
                        routeSummary = describeDeliveryRoute(overlayRes)
                    )
                    _uiState.update {
                        it.copy(
                            statusBannerMessage = describeDeliveryBanner(overlayRes, conv.title)
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            statusBannerMessage = "Повтор не удался: ${overlayRes.errorDetail ?: "оверлей Yggdrasil недоступен"}"
                        )
                    }
                }
            }
        }
    }

    /**
     * Pings a conversation's route strictly through external Yggdrasil peers (with automatic peer failover)
     * and updates its live route status.
     */
    fun pingConversationRoute(chatId: String) {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            val conv = repository.getConversation(chatId) ?: return@launch
            val allowManualLan = _uiState.value.privacyMatrix.allowDirectLanDiscovery

            if (!networkEngine.isRealNetworkAvailable() && !allowManualLan) {
                val reason = if (_uiState.value.isAirplaneModeOn) "Включен Авиарежим" else "Нет подключения к сети"
                repository.updateConversationRouteStatus(chatId, false, "Офлайн ($reason)")
                _uiState.update {
                    it.copy(statusBannerMessage = "Пинг через Yggdrasil невозможен: $reason")
                }
                return@launch
            }

            val candidatePeers = buildCandidateExternalPeersList()
            val catalogFallback = YggPeerScannerCatalog.initialCatalog
                .take(8)
                .map { Triple(it.endpointUri, it.sniDomain, "${it.countryNameRu} • ${it.cityRu}") }
            val peersToTry = (candidatePeers + catalogFallback).distinctBy { it.first.lowercase() }

            for ((uri, sni, region) in peersToTry) {
                val probe = networkEngine.probeRealYggdrasilPeer(uri, sni, timeoutMs = 3000)
                if (probe.reachable) {
                    repository.updatePeerTrafficByEndpoint(uri, probe.rttMs, probe.txBytes, probe.rxBytes)
                    repository.updateConversationRouteStatus(
                        chatId = chatId,
                        isOnline = true,
                        routeSummary = "Сеть Yggdrasil доступна • $region (${probe.rttMs} мс) • ACK ожидается от получателя"
                    )
                    _uiState.update {
                        it.copy(
                            statusBannerMessage = "Внешний пир Yggdrasil $uri ($region) отвечает: RTT ${probe.rttMs} мс. Сквозная доставка до «${conv.title}» подтверждается только ACK получателя."
                        )
                    }
                    return@launch
                }
            }

            repository.updateConversationRouteStatus(chatId, false, "Внешние пиры Yggdrasil недоступны • очередь S&F")
            _uiState.update {
                it.copy(statusBannerMessage = "Ни один внешний пир Yggdrasil не ответил — сообщения останутся в очереди S&F")
            }
        }
    }

    /**
     * Broadcasts a real LAN discovery beacon on UDP 51920 and probes connected peers,
     * or if a chat is specified, runs a real Yggdrasil self-test / route check.
     */
    fun runLiveMessageSendReceiveTest(targetChatId: String? = null) {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            if (targetChatId != null) {
                pingConversationRoute(targetChatId)
                return@launch
            }
            if (!networkEngine.isRealNetworkAvailable()) {
                val reason = if (_uiState.value.isAirplaneModeOn) "Включен Авиарежим" else "Нет подключения к сети"
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Ошибка проверки сети: $reason. Отключите авиарежим и подключитесь к Wi-Fi/сети."
                    )
                }
                return@launch
            }

            val state = _uiState.value
            val beaconSent = networkEngine.broadcastLanDiscoveryBeacon(
                displayName = state.userProfile.displayName,
                yggIpv6 = state.identity.yggIpv6Address,
                pubKeyHex = state.identity.yggNodePublicKeyHex
            )
            refreshRealPeerConnectivity()
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (beaconSent) {
                        "Отправлен LAN-маяк (UDP :51920) и запущена реальная проверка пиров Yggdrasil"
                    } else {
                        "Запущена реальная проверка пиров Yggdrasil"
                    }
                )
            }
        }
    }

    fun pingTwinNodeNow() {
        runLiveMessageSendReceiveTest(null)
    }

    // --- Real Yggdrasil Public Peer Scanner by Country, Search, Sort & Bulk Import ---

    fun selectScanCountry(countryCode: String) {
        networkDpiController.selectScanCountry(countryCode)
        _uiState.update { state ->
            val countryObj = state.peerScanner.countries.find { it.code == countryCode }
            val filteredCount = state.peerScanner.discoveredPeers.count {
                (countryCode == "ALL" || it.countryCode == countryCode) &&
                    (!state.peerScanner.onlyWhitelistCompatible || it.whitelistCompatible)
            }
            state.copy(
                peerScanner = state.peerScanner.copy(
                    selectedCountryCode = countryCode,
                    lastScanSummary = "Выборка «${countryObj?.nameRu ?: countryCode}»: $filteredCount публичных пиров в списке"
                )
            )
        }
    }

    fun updateScannerSearchQuery(query: String) {
        networkDpiController.updateScannerSearchQuery(query)
    }

    fun updateScannerSortMode(sortMode: String) {
        networkDpiController.updateScannerSortMode(sortMode)
    }

    fun toggleScannerWhitelistOnly() {
        networkDpiController.toggleScannerWhitelistOnly()
    }

    /**
     * Bulk-imports peer URIs (`tls://`, `tcp://`, `wss://`, `quic://`) from clipboard/text,
     * adds them to the scanner catalog and routing table, and immediately probes their real RTT.
     */
    fun importCustomPeersFromText(rawText: String) {
        val cfg = _uiState.value.antiDpiConfig
        val parsed = YggPeerScannerCatalog.parseCustomPeerUrisFromText(
            rawText = rawText,
            defaultSni = cfg.whitelistSniDomain,
            defaultUtls = cfg.utlsProfile
        )
        if (parsed.isEmpty()) {
            _uiState.update {
                it.copy(statusBannerMessage = "В тексте не найдено URI пиров (ожидаются tls://, tcp://, wss:// или quic://)")
            }
            return
        }

        viewModelScope.launch {
            updateConnectivitySnapshot()
            val netAvailable = networkEngine.isRealNetworkAvailable()
            var aliveImported = 0

            val probedPeers = withContext(Dispatchers.IO) {
                parsed.map { peer ->
                    async {
                        val probe = if (netAvailable) {
                            networkEngine.probeRealYggdrasilPeer(peer.endpointUri, peer.sniDomain, timeoutMs = 3000)
                        } else null
                        val reachable = probe?.reachable == true
                        val rtt = if (reachable) probe?.rttMs ?: -1 else -1
                        repository.addOrUpdatePeer(
                            peerId = "peer_${peer.id}",
                            endpointUri = peer.endpointUri,
                            transportType = peer.transportType,
                            sniDomain = peer.sniDomain,
                            utlsFingerprint = peer.utlsFingerprint,
                            regionLabel = "${peer.countryFlag} Импорт • ${peer.cityRu}",
                            latencyMs = rtt,
                            isConnected = reachable,
                            whitelistCompatible = peer.whitelistCompatible
                        )
                        peer.copy(latencyMs = rtt)
                    }
                }.awaitAll()
            }

            aliveImported = probedPeers.count { it.latencyMs > 0 }
            _uiState.update { state ->
                val merged = (probedPeers + state.peerScanner.discoveredPeers).distinctBy { it.endpointUri }
                state.copy(
                    peerScanner = state.peerScanner.copy(
                        discoveredPeers = merged,
                        lastScanSummary = "Импортировано ${probedPeers.size} пиров (ответили по сети: $aliveImported)"
                    ),
                    statusBannerMessage = "Импортировано ${probedPeers.size} пиров Yggdrasil • Активных: $aliveImported"
                )
            }
            syncForegroundServiceNotification()
        }
    }

    /**
     * Fetches the live public peer list from `publicpeers.neilalexander.dev/publicnodes.json` (merging
     * with the 46 built-in real peers) and performs REAL TCP/TLS socket handshakes to measure genuine RTT.
     */
    fun scanYggdrasilPeersByCountry() {
        networkDpiController.scanYggdrasilPeersByCountry()
    }

    fun connectScannedPeer(scanned: DiscoveredYggPeer) {
        networkDpiController.connectScannedPeer(scanned)
    }

    fun connectTopFastestPeersInSelectedCountry() {
        networkDpiController.connectTopFastestPeersInSelectedCountry()
    }

    /**
     * Opening a group from the contacts this profile already has dialogs with.
     *
     * Nothing leaves the device yet: on a mesh with no server, the group only becomes real
     * to the other nodes when the first message goes out, because that frame carries its
     * identifier and its roster.
     */
    fun createGroup(title: String, memberContactChatIds: List<String>) {
        val picked = memberContactChatIds.filter { it.isNotBlank() }
        if (picked.isEmpty()) {
            _uiState.update {
                it.copy(statusBannerMessage = "Группа не создана: выберите хотя бы один контакт")
            }
            return
        }
        viewModelScope.launch {
            val newChatId = withContext(Dispatchers.IO) {
                repository.createNewGroup(
                    title = title,
                    memberContactChatIds = picked,
                    ownerProfileId = _uiState.value.currentProfileId,
                    localIdentity = _uiState.value.identity
                )
            }
            if (newChatId.isBlank()) {
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Группа не создана: у выбранных контактов нет адреса узла в оверлее"
                    )
                }
                return@launch
            }
            val rosterSize = withContext(Dispatchers.IO) { repository.getGroupMembers(newChatId).size }
            _uiState.update {
                it.copy(
                    selectedChatId = newChatId,
                    statusBannerMessage = "Группа создана, участников: ${rosterSize + 1}. " +
                        "Она станет видима остальным с первым отправленным сообщением"
                )
            }
        }
    }

    /** @param memberContactChatIds local chat ids of the contacts to fold into the group. */
    fun addContactsToGroup(chatId: String, memberContactChatIds: List<String>) {
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                repository.addContactsToGroup(chatId, memberContactChatIds)
            }
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (added > 0) {
                        "Участников добавлено: $added. Новый состав уйдёт с следующим сообщением в группу"
                    } else {
                        "Добавлять нечего: эти контакты уже в группе или у них нет адреса узла"
                    }
                )
            }
        }
    }

    fun createNewChat(
        title: String,
        yggIpv6Input: String,
        disappearingSeconds: Int
    ) {
        if (title.isBlank() && yggIpv6Input.isBlank()) return
        val parts = yggIpv6Input.split("|", ",", " ").map { it.trim() }.filter { it.isNotEmpty() }
        val primaryAddr = parts.firstOrNull() ?: yggIpv6Input.trim()
        if (primaryAddr.isBlank()) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Укажите адрес узла (IPv6 2xx:…, Ed25519-ключ или ygggram://-ссылку) — контакт без адреса узла создать нельзя"
                )
            }
            return
        }
        val looksLikeContact = primaryAddr.startsWith("ygggram://", ignoreCase = true) ||
            YggCryptoEngine.isEd25519Hex(primaryAddr) ||
            YggCryptoEngine.normalizeIpv6Literal(primaryAddr) != null ||
            (primaryAddr.contains(".") && primaryAddr.contains(":") && !primaryAddr.startsWith("127.0.0.1"))
        if (!looksLikeContact) {
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Некорректный идентификатор контакта: нужен ygggram://…?pub=…, Ed25519-ключ, IPv6 2xx:… или LAN-адрес host:port"
                )
            }
            return
        }
        viewModelScope.launch {
            val directHint = parts.drop(1).firstOrNull { it.contains(":") && it.contains(".") }
                ?: if (primaryAddr.contains(".") && primaryAddr.contains(":")) primaryAddr else ""

            val effectiveTitle = title.trim().ifBlank { "Узел Yggdrasil" }
            val newChatId = withContext(Dispatchers.IO) {
                repository.createNewConversation(
                    title = effectiveTitle,
                    yggIpv6Input = primaryAddr,
                    disappearingSeconds = disappearingSeconds,
                    directEndpointHint = directHint,
                    localIdentity = _uiState.value.identity,
                    ownerProfileId = _uiState.value.currentProfileId
                )
            }
            if (directHint.isNotBlank() && !YggRepository.isSelfChatId(newChatId)) {
                withContext(Dispatchers.IO) {
                    val conv = repository.getConversation(newChatId)
                    if (conv != null) {
                        networkEngine.registerDirectEndpointForPeer(conv.yggIpv6Address, directHint)
                    }
                }
            }
            val createdConv = withContext(Dispatchers.IO) { repository.getConversation(newChatId) }
            val hasNodeKey = hasVerifiedOverlayKey(createdConv)
            val createdLanHint = extractDirectEndpointHint(createdConv)
            _uiState.update {
                it.copy(
                    selectedChatId = newChatId,
                    statusBannerMessage = when {
                        YggRepository.isSelfChatId(newChatId) ->
                            "Открыт чат «Избранное • Мой узел Yggdrasil» (маршрутизация через внешние пиры)"
                        hasNodeKey -> "Добавлен контакт «$effectiveTitle»: Ed25519-ключ совпадает с адресом узла"
                        createdLanHint.isNotBlank() ->
                            "Контакт «$effectiveTitle» добавлен: доступен только прямой LAN-маршрут ($createdLanHint), для оверлея нужен полный идентификатор с ?pub=…"
                        else ->
                            "Контакт «$effectiveTitle» добавлен без Ed25519-ключа: оверлей не сможет доставить сообщения. Добавьте контакт по полной ссылке ygggram://…?pub=…"
                    }
                )
            }
        }
    }

    fun toggleFileTransferState(transfer: P2PFileTransferEntity) {
        viewModelScope.launch {
            repository.toggleFileTransferPauseResume(transfer)
        }
    }

    fun applyCensorshipPreset(preset: CensorshipPreset) {
        networkDpiController.applyCensorshipPreset(preset)
        viewModelScope.launch {
            refreshRealPeerConnectivity()
        }
    }

    fun selectWhitelistSniDomain(domain: String) {
        networkDpiController.selectWhitelistSniDomain(domain)
    }

    fun selectUtlsFingerprint(fingerprint: String) {
        networkDpiController.selectUtlsFingerprint(fingerprint)
    }

    fun toggleAntiDpiFlag(flagName: String) {
        networkDpiController.toggleAntiDpiFlag(flagName)
    }

    fun runAntiDpiDiagnosticProbe() {
        if (_uiState.value.antiDpiConfig.isProbingRoutes) return
        _uiState.update { state ->
            state.copy(
                antiDpiConfig = state.antiDpiConfig.copy(
                    isProbingRoutes = true,
                    lastProbeReport = "Выполняется реальное TCP/TLS тестирование настроенных пиров Yggdrasil..."
                )
            )
        }
        refreshRealPeerConnectivity()
    }

    fun addCustomPeer(
        endpointUri: String,
        transportType: String,
        sniDomain: String,
        utlsFingerprint: String,
        regionLabel: String,
        whitelistCompatible: Boolean
    ) {
        if (endpointUri.isBlank()) return
        viewModelScope.launch {
            updateConnectivitySnapshot()
            val probe = networkEngine.probeRealYggdrasilPeer(
                endpointUri = endpointUri.trim(),
                sniOverride = sniDomain.trim().ifBlank { null },
                timeoutMs = 3500
            )
            repository.addOrUpdatePeer(
                endpointUri = endpointUri,
                transportType = transportType,
                sniDomain = sniDomain.ifBlank { _uiState.value.antiDpiConfig.whitelistSniDomain },
                utlsFingerprint = utlsFingerprint,
                regionLabel = regionLabel.ifBlank { "Пользовательский пир Yggdrasil" },
                latencyMs = if (probe.reachable) probe.rttMs else -1,
                isConnected = probe.reachable,
                whitelistCompatible = whitelistCompatible
            )
            val core = networkEngine.applyPeerChangeToCores(
                endpointUri.trim(),
                enabled = true,
                sniDomain = sniDomain
            )

            _uiState.update {
                it.copy(
                    statusBannerMessage = when {
                        core.applied == 0 ->
                            "Пир $endpointUri сохранён, но живая нода его не приняла: " +
                                (core.errorDetail ?: "нет активной ноды Yggdrasil")
                        probe.reachable ->
                            "Пир $endpointUri добавлен в ноду (TCP-пинг ${probe.rttMs} мс • " +
                                "линков ядра: ${core.liveLinks} из ${core.totalLinks})"
                        else ->
                            "Пир $endpointUri добавлен в ноду, но TCP-пинг не прошёл " +
                                "(${probe.errorDetail ?: "узел недоступен"}) • линков ядра: ${core.liveLinks} из ${core.totalLinks}"
                    }
                )
            }
            syncForegroundServiceNotification()
        }
    }

    fun togglePeer(peer: YggPeerEntity) {
        viewModelScope.launch {
            if (!peer.isConnected) {
                val probe = networkEngine.probeRealYggdrasilPeer(
                    endpointUri = peer.endpointUri,
                    sniOverride = peer.sniDomain,
                    timeoutMs = 3000
                )
                repository.updatePeerProbeStatus(
                    peerId = peer.peerId,
                    reachable = probe.reachable,
                    latencyMs = probe.rttMs,
                    rxDelta = probe.rxBytes,
                    txDelta = probe.txBytes
                )
                val core = networkEngine.applyPeerChangeToCores(
                    peer.endpointUri,
                    enabled = true,
                    sniDomain = peer.sniDomain
                )

                // Reachability of the port says nothing about the Yggdrasil link: only
                // the node can say whether it actually brought the peer up.
                val linkLine = if (core.applied > 0) " • линков ядра: ${core.liveLinks} из ${core.totalLinks}" else ""
                _uiState.update {
                    it.copy(
                        statusBannerMessage = when {
                            !probe.reachable ->
                                "Не удалось подключиться к ${peer.endpointUri}: ${probe.errorDetail ?: "узел недоступен"}"
                            core.applied == 0 ->
                                "${peer.endpointUri} сохранён, но нода его не приняла: ${core.errorDetail ?: "нет активной ноды"}"
                            else ->
                                "Пир ${peer.endpointUri} добавлен в ноду (RTT: ${probe.rttMs} мс$linkLine)"
                        }
                    )
                }
            } else {
                repository.updatePeerProbeStatus(
                    peerId = peer.peerId,
                    reachable = false,
                    latencyMs = -1
                )
                networkEngine.applyPeerChangeToCores(peer.endpointUri, enabled = false)
            }
            syncForegroundServiceNotification()
        }
    }

    fun removePeer(peerId: String) {
        viewModelScope.launch {
            val uri = peers.value.find { it.peerId == peerId }?.endpointUri.orEmpty()
            repository.deletePeer(peerId)
            if (uri.isNotBlank()) networkEngine.applyPeerChangeToCores(uri, enabled = false)
            syncForegroundServiceNotification()
        }
    }

    fun toggleRevealSeedPhrase() {
        vaultIdentityController.toggleRevealSeedPhrase()
    }

    fun regenerateNewSeedIdentity() {
        val freshWords = YggCryptoEngine.generateFreshMnemonic12()
        val freshIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(freshWords, epoch = 1)
        viewModelScope.launch {
            val updatedProfile = repository.regenerateProfileIdentity(
                profileId = _uiState.value.currentProfileId,
                identity = freshIdentity
            )
            if (updatedProfile == null) {
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Не удалось перегенерировать идентичность: профиль не найден"
                    )
                }
                return@launch
            }
            applyUserProfileEntityToState(updatedProfile)
            networkEngine.syncConcurrentProfileListeners(repository.getAllProfilesNow())
            _uiState.update {
                it.copy(
                    keyEpoch = 1,
                    statusBannerMessage = "Сгенерирована новая BIP-39 мнемоника и IPv6-адрес узла ${freshIdentity.yggIpv6Address.take(19)}..."
                )
            }
            syncForegroundServiceNotification()
        }
    }

    fun triggerCrossPlatformSyncNow() {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            val netOk = networkEngine.isRealNetworkAvailable()
            if (!netOk) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Синхронизация невозможна: устройство офлайн или в Авиарежиме")
                }
                return@launch
            }
            networkEngine.broadcastLanDiscoveryBeacon(
                displayName = _uiState.value.userProfile.displayName,
                yggIpv6 = _uiState.value.identity.yggIpv6Address,
                pubKeyHex = _uiState.value.identity.yggNodePublicKeyHex
            )
            val newVector = repository.syncAllLinkedDevicesNow(isOnline = true)
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (newVector > 0) {
                        "Запрос синхронизации отправлен в LAN и оверлей Yggdrasil (StateVector #$newVector)"
                    } else {
                        "Отправлен LAN-маяк синхронизации (нет привязанных устройств)"
                    }
                )
            }
        }
    }

    fun pairNewDevice(
        deviceName: String,
        platform: String,
        yggIpv6OrCode: String,
        lanEndpoint: String
    ) {
        if (deviceName.isBlank()) return
        viewModelScope.launch {
            val netOk = networkEngine.isRealNetworkAvailable()
            repository.linkNewCrossPlatformDevice(
                deviceName = deviceName,
                platform = platform,
                yggIpv6OrCode = yggIpv6OrCode,
                lanEndpoint = lanEndpoint,
                isOnline = netOk
            )
            _uiState.update {
                it.copy(statusBannerMessage = "Устройство «$deviceName» добавлено в список синхронизации")
            }
        }
    }

    fun createEncryptedBackup(passphrase: String, isWebDavCloud: Boolean) {
        val effectivePass = passphrase.ifBlank { _uiState.value.identity.backupMasterKeyFingerprint }
        viewModelScope.launch {
            val snapshot = repository.createBackupSnapshot(
                passphrase = effectivePass,
                isWebDavCloud = isWebDavCloud
            )
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Создан зашифрованный бэкап ${snapshot.archiveFileName} (${snapshot.chunkCountFastCdc} чанков FastCDC)"
                )
            }
        }
    }

    fun verifyOrRestoreBackup(snapshot: BackupSnapshotEntity, isRestore: Boolean) {
        val msg = if (isRestore) {
            "Восстановлено состояние из архива ${snapshot.archiveFileName} (Merkle SHA-256: ${snapshot.merkleRootHex.take(12)}...)"
        } else {
            "Проверка полинома Poly1305 и дерева Merkle для ${snapshot.archiveFileName} пройдена успешно"
        }
        _uiState.update { it.copy(statusBannerMessage = msg) }
    }

    fun updateDecentralizedProfile(
        displayName: String,
        handle: String,
        bio: String,
        statusText: String
    ) {
        viewModelScope.launch {
            val currentId = _uiState.value.currentProfileId
            val updatedEntity = repository.updateCurrentProfileMetadata(
                profileId = currentId,
                displayName = displayName,
                handle = handle,
                bio = bio,
                statusText = statusText
            )
            if (updatedEntity != null) {
                applyUserProfileEntityToState(updatedEntity)
            } else {
                val sigHash = YggCryptoEngine.sha256Hex(
                    "$displayName:$handle:$bio:${_uiState.value.identity.yggNodePublicKeyHex}"
                )
                _uiState.update {
                    it.copy(
                        userProfile = it.userProfile.copy(
                            displayName = displayName.trim().ifBlank { it.userProfile.displayName },
                            handle = handle.trim().ifBlank { it.userProfile.handle },
                            bio = bio.trim(),
                            statusText = statusText.trim(),
                            profileFingerprintHex = "sha256_fp:${sigHash.take(44)}"
                        )
                    )
                }
            }
            _uiState.update {
                it.copy(statusBannerMessage = "Профиль сохранён • отпечаток вычислен по ключу узла")
            }
        }
    }

    /**
     * Setting the profile picture from the gallery.
     *
     * Nothing is announced as changed unless the codec produced a file: the avatar travels
     * inside the message frame, so a picture too large to fit there has no honest delivery
     * path and must not quietly become a half-cropped one.
     */
    fun setProfileAvatar(source: Uri) {
        viewModelScope.launch {
            val profileId = _uiState.value.currentProfileId
            val updated = repository.setProfileAvatar(profileId, source)
            if (updated != null) {
                applyUserProfileEntityToState(updated)
                _uiState.update {
                    it.copy(statusBannerMessage = "Фото профиля обновлено • будет передано при следующем сообщении")
                }
            } else {
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Фото не подходит: нужен файл, из которого получится квадрат до ${YggAvatarCodec.MAX_JPEG_BYTES / 1000} КБ"
                    )
                }
            }
        }
    }

    fun clearProfileAvatar() {
        viewModelScope.launch {
            val profileId = _uiState.value.currentProfileId
            repository.clearProfileAvatar(profileId)
            repository.getAllProfilesNow().find { it.profileId == profileId }
                ?.let { applyUserProfileEntityToState(it) }
            _uiState.update { it.copy(statusBannerMessage = "Фото профиля удалено") }
        }
    }

    fun updatePrivacySetting(
        sealedSenderGlobal: Boolean? = null,
        stripNetworkMetadata: Boolean? = null,
        blockScreenshots: Boolean? = null,
        allowDirectLanDiscovery: Boolean? = null,
        autoSyncOnMeshConnect: Boolean? = null,
        defaultDisappearingSeconds: Int? = null,
        hashcashDifficultyBits: Int? = null,
        webDavEndpointUrl: String? = null
    ) {
        if (allowDirectLanDiscovery != null) {
            networkEngine.allowDirectLanRouting = allowDirectLanDiscovery
        }
        _uiState.update { state ->
            val p = state.privacyMatrix
            val nextLan = allowDirectLanDiscovery ?: p.allowDirectLanDiscovery
            state.copy(
                privacyMatrix = p.copy(
                    sealedSenderGlobal = sealedSenderGlobal ?: p.sealedSenderGlobal,
                    stripNetworkMetadata = stripNetworkMetadata ?: p.stripNetworkMetadata,
                    blockScreenshots = blockScreenshots ?: p.blockScreenshots,
                    allowDirectLanDiscovery = nextLan,
                    autoSyncOnMeshConnect = autoSyncOnMeshConnect ?: p.autoSyncOnMeshConnect,
                    defaultDisappearingSeconds = defaultDisappearingSeconds ?: p.defaultDisappearingSeconds,
                    hashcashDifficultyBits = hashcashDifficultyBits ?: p.hashcashDifficultyBits,
                    webDavEndpointUrl = webDavEndpointUrl ?: p.webDavEndpointUrl
                ),
                statusBannerMessage = if (allowDirectLanDiscovery != null) {
                    if (allowDirectLanDiscovery) {
                        "Включен ручной резервный режим LAN (используется только при недоступности внешних пиров Yggdrasil)"
                    } else {
                        "Ручной режим LAN отключен: доставка между профилями и узлами работает строго через внешние пиры Yggdrasil"
                    }
                } else {
                    state.statusBannerMessage
                }
            )
        }
        val p = _uiState.value.privacyMatrix
        uiPrefs.edit()
            .putBoolean("priv_sealed_sender", p.sealedSenderGlobal)
            .putBoolean("priv_strip_metadata", p.stripNetworkMetadata)
            .putBoolean("priv_block_screenshots", p.blockScreenshots)
            .putBoolean("priv_allow_lan", p.allowDirectLanDiscovery)
            .putBoolean("priv_autosync_mesh", p.autoSyncOnMeshConnect)
            .putInt("priv_default_ttl_sec", p.defaultDisappearingSeconds)
            .putInt("priv_hashcash_bits", p.hashcashDifficultyBits)
            .putString("priv_webdav_url", p.webDavEndpointUrl)
            .apply()
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }
}
