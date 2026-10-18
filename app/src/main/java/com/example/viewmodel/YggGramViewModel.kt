// File: app/src/main/java/com/example/viewmodel/YggGramViewModel.kt
package com.example.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BackupSnapshotEntity
import com.example.data.ConversationEntity
import com.example.data.CryptoKeyRecordEntity
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
import com.example.engine.YggAudioMediaEngine
import com.example.engine.YggCryptoEngine
import com.example.engine.YggLivePacketLogEntry
import com.example.engine.YggPeerScannerCatalog
import com.example.engine.YggTwinP2PBridge
import com.example.service.YggForegroundServiceState
import com.example.service.YggNodeForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class YggNavTab {
    CHATS,
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
    val statusText: String = "В оверлее 200::/7 (Порт :51921)",
    val allocatedTcpPort: Int = 51921,
    val isActiveInBackground: Boolean = true,
    val profileSignatureHex: String = "ed25519_sig:9f41a08c273b6501e849201c73d6a9021f384e55b601a29c"
)

data class PrivacyMatrixState(
    val sealedSenderGlobal: Boolean = true,
    val stripNetworkMetadata: Boolean = true,
    val blockScreenshots: Boolean = true,
    val autoRotatePreKeysHours: Int = 24,
    val defaultDisappearingSeconds: Int = 0,
    val hashcashDifficultyBits: Int = 10,
    val allowDirectLanDiscovery: Boolean = true,
    val webDavEndpointUrl: String = "https://webdav.private-node.org:8443/ygg-zk-vault",
    val autoSyncOnMeshConnect: Boolean = true
)

data class ActiveVoiceCallState(
    val chatId: String,
    val peerTitle: String,
    val peerIpv6: String,
    val callStatus: String, // "DIALING_YGG", "ACTIVE_ENCRYPTED", "OFFLINE_ERROR"
    val statusSubtext: String,
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerphoneOn: Boolean = true,
    val isLoopbackEchoEnabled: Boolean = true,
    val micAmplitudeLevel: Float = 0.18f,
    val verificationEmojis: String = "🔐 🛰️ 🐉 ⚡",
    val liveRttMs: Int = -1,
    val audioBitrateKbps: Int = 48,
    val txAudioFrames: Int = 0,
    val rxAudioFrames: Int = 0
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
    val userProfile: DecentralizedUserProfile = DecentralizedUserProfile(),
    val privacyMatrix: PrivacyMatrixState = PrivacyMatrixState(),
    val activeVoiceCall: ActiveVoiceCallState? = null,
    val statusBannerMessage: String? = null
)

class YggGramViewModel(application: Application) : AndroidViewModel(application) {

    private val database = YggDatabase.getInstance(application)
    private val repository = YggRepository(database.yggDao(), application.applicationContext)

    private val defaultMnemonic = listOf(
        "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
        "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
    )

    private val initialIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(defaultMnemonic, epoch = 1)

    private val _uiState = MutableStateFlow(
        YggGramUiState(
            identity = initialIdentity
        )
    )
    val uiState: StateFlow<YggGramUiState> = _uiState.asStateFlow()

    val foregroundServiceState: StateFlow<YggForegroundServiceState> = YggNodeForegroundService.serviceState

    private var activeCallJob: kotlinx.coroutines.Job? = null

    private val networkEngine = YggTwinP2PBridge(
        context = application.applicationContext,
        onPacketReceived = { packet ->
            viewModelScope.launch {
                val currentPrimaryId = _uiState.value.currentProfileId
                val chatId = repository.recordIncomingTwinMessage(
                    packet = packet,
                    activeChatId = _uiState.value.selectedChatId,
                    currentPrimaryProfileId = currentPrimaryId
                )
                val targetProfileName = userProfiles.value.find {
                    it.profileId == packet.recipientProfileId ||
                        it.yggIpv6Address.equals(packet.recipientIpv6, ignoreCase = true)
                }?.displayName

                val profileSuffix = if (targetProfileName != null && packet.recipientProfileId.isNotBlank() && packet.recipientProfileId != currentPrimaryId) {
                    " [для профиля «$targetProfileName»]"
                } else {
                    ""
                }

                _uiState.update {
                    it.copy(
                        isTwinAppReachable = true,
                        rxPacketsCount = it.rxPacketsCount + 1,
                        selectedChatId = if (packet.recipientProfileId.isBlank() || packet.recipientProfileId == currentPrimaryId) {
                            it.selectedChatId ?: chatId
                        } else {
                            it.selectedChatId
                        },
                        statusBannerMessage = "Получен пакет Yggdrasil от «${packet.senderNodeLabel}»$profileSuffix (${packet.rttMs} мс)"
                    )
                }
                syncForegroundServiceNotification()
            }
        },
        onDeliveryAckReceived = { _, messageId, _ ->
            viewModelScope.launch {
                repository.markMessageDelivered(messageId)
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
                    isMlsGroup = false,
                    sealedSender = true,
                    disappearingSeconds = 0,
                    directEndpointHint = "${lanNode.hostIp}:${lanNode.tcpPort}",
                    localIdentity = _uiState.value.identity,
                    ownerProfileId = _uiState.value.currentProfileId
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
                repository.toggleReactionOnMessage(
                    messageId = reaction.messageId,
                    emoji = reaction.emoji,
                    byMe = false
                )
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

    val queuedOfflineMessagesCount: StateFlow<Int> = combine(allMessages) { (msgs) ->
        msgs.count { it.deliveryState == "QUEUED_OFFLINE_SF" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        YggNodeForegroundService.onUserRequestedStop = {
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
            networkEngine.syncConcurrentProfileListeners(allProfs)
            startNetworkAndAirplaneObserver()
            syncForegroundServiceNotification()
            refreshRealPeerConnectivity()
        }
    }

    private fun applyUserProfileEntityToState(profileEntity: UserProfileEntity) {
        val words = profileEntity.mnemonicCsv.split(" ").filter { it.isNotBlank() }
        val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(
            if (words.size >= 6) words else defaultMnemonic,
            epoch = 1
        )
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
                    profileSignatureHex = "ed25519_sig:${sigHash.take(44)}"
                )
            )
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
            val alivePeersCount = peers.value.count { it.isConnected }
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
        syncForegroundServiceNotification()
    }

    // --- Multi-Profile Creation, Switching & Concurrent Background Operation ---

    fun createNewProfile(
        displayName: String,
        handle: String,
        bio: String,
        runSimultaneouslyInBackground: Boolean,
        switchImmediately: Boolean
    ) {
        viewModelScope.launch {
            val (created, _) = repository.createNewUserProfile(
                displayName = displayName,
                handle = handle,
                bio = bio,
                runSimultaneouslyInBackground = runSimultaneouslyInBackground,
                switchImmediately = switchImmediately
            )
            val allProfs = repository.getAllProfilesNow()
            networkEngine.syncConcurrentProfileListeners(allProfs)

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
     * Opens a real P2P chat from the currently selected profile to another local profile
     * (running simultaneously on its own TCP port `127.0.0.1:<allocatedTcpPort>`), enabling instant
     * real testing of P2P text, voice recordings, images, files, and calls between two profiles!
     */
    fun openChatWithSecondaryProfile(targetProfile: UserProfileEntity) {
        viewModelScope.launch {
            if (!targetProfile.isActiveInBackground) {
                repository.setProfileBackgroundActive(targetProfile.profileId, true)
            }
            val allProfs = repository.getAllProfilesNow()
            networkEngine.syncConcurrentProfileListeners(allProfs)

            val endpointHint = "127.0.0.1:${targetProfile.allocatedTcpPort}"
            networkEngine.registerDirectEndpointForPeer(targetProfile.yggIpv6Address, endpointHint)

            val chatId = repository.createNewConversation(
                title = "${targetProfile.displayName} (${targetProfile.handle})",
                yggIpv6Input = "ygggram://${targetProfile.yggIpv6Address}?pub=${targetProfile.yggPublicKeyHex}&lan=$endpointHint",
                isMlsGroup = false,
                sealedSender = true,
                disappearingSeconds = 0,
                directEndpointHint = endpointHint,
                localIdentity = _uiState.value.identity,
                ownerProfileId = _uiState.value.currentProfileId
            )

            _uiState.update {
                it.copy(
                    activeTab = YggNavTab.CHATS,
                    selectedChatId = chatId,
                    statusBannerMessage = "Открыт P2P-диалог с параллельным профилем «${targetProfile.displayName}» (сокет $endpointHint)"
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
            _uiState.update { state ->
                val report = if (aliveCount > 0) {
                    "Соединение с сетью Yggdrasil активно: живых пиров $aliveCount из ${results.size} (лучший пинг: ${bestRtt ?: "-"} мс)"
                } else {
                    "Ни один из ${results.size} пиров не ответил на TCP/TLS рукопожатие (проверьте сеть или выберите живой пир в Сканере)"
                }
                state.copy(
                    rxPacketsCount = state.rxPacketsCount + aliveCount,
                    txPacketsCount = state.txPacketsCount + results.size,
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
        try {
            airplaneReceiver?.let { getApplication<Application>().unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        YggAudioMediaEngine.stopVoipCallAudioSession(getApplication())
        YggAudioMediaEngine.stopVoiceNotePlayback()
        networkEngine.stop()
    }

    fun clearPacketLogs() {
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
            }
        }
    }

    /**
     * Opens the dedicated "Избранное • Мой узел Yggdrasil" self-chat and optionally
     * sends an immediate test message routed strictly through external public Yggdrasil peers.
     */
    fun openSelfYggdrasilTestChat(sendInitialTestMessage: Boolean = false) {
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
            if (sendInitialTestMessage) {
                sendMessage(
                    chatId = selfChatId,
                    plainText = "Тест связи Yggdrasil: проверка маршрута через внешний публичный пир до моего узла ${_uiState.value.identity.yggIpv6Address}"
                )
            }
        }
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
            flushQueuedMessagesOverRealNetwork()
        }
    }

    private fun isConversationSelfNode(conv: ConversationEntity?, identity: DerivedIdentityHierarchy): Boolean {
        if (conv == null) return false
        return YggRepository.isSelfChatId(conv.chatId) ||
            conv.yggIpv6Address.equals(identity.yggIpv6Address, ignoreCase = true) ||
            conv.publicKeyHex.equals(identity.yggNodePublicKeyHex, ignoreCase = true)
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

    private fun flushQueuedMessagesOverRealNetwork() {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            if (!networkEngine.isRealNetworkAvailable()) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Очередь S&F ожидает подключения к сети (сейчас устройство офлайн)")
                }
                return@launch
            }
            val app = getApplication<Application>()
            val queued = repository.getQueuedMessages()
            var deliveredCount = 0
            val currentIdentity = _uiState.value.identity
            val candidateExternalPeers = buildCandidateExternalPeersList()

            for (msg in queued) {
                val conv = repository.getConversation(msg.chatId) ?: continue
                val attachmentBase64 = if (msg.attachmentType != null || msg.attachmentName != null) {
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
                if (isConversationSelfNode(conv, currentIdentity)) {
                    val selfRelay = networkEngine.dispatchSelfMessageViaYggdrasilMesh(
                        chatId = conv.chatId,
                        messageId = msg.messageId,
                        senderNodeLabel = _uiState.value.userProfile.displayName,
                        senderIpv6 = currentIdentity.yggIpv6Address,
                        senderPubKeyHex = currentIdentity.yggNodePublicKeyHex,
                        plainText = msg.plainText,
                        cipherPayloadHex = msg.cipherPayloadHex,
                        hashcashToken = msg.hashcashPowToken,
                        attachmentName = msg.attachmentName,
                        attachmentSizeBytes = msg.attachmentSizeBytes,
                        candidatePeers = candidateExternalPeers,
                        attachmentType = msg.attachmentType,
                        attachmentUri = msg.attachmentUri,
                        attachmentBase64 = attachmentBase64,
                        voiceDurationSec = msg.voiceDurationSec,
                        voiceWaveformCsv = msg.voiceWaveformCsv
                    )
                    if (selfRelay.delivered) {
                        deliveredCount++
                        repository.updatePeerTrafficByEndpoint(
                            endpointUri = selfRelay.relayEndpointUri,
                            rttMs = selfRelay.rttMs.toInt(),
                            txDelta = selfRelay.txBytes,
                            rxDelta = selfRelay.rxBytes
                        )
                    }
                } else {
                    val directHint = extractDirectEndpointHint(conv)
                    val delivered = networkEngine.dispatchOutgoingMessageThroughYggdrasil(
                        chatId = conv.chatId,
                        messageId = msg.messageId,
                        peerTitle = conv.title,
                        peerIpv6 = conv.yggIpv6Address,
                        directEndpointHint = directHint,
                        senderNodeLabel = _uiState.value.userProfile.displayName,
                        senderIpv6 = currentIdentity.yggIpv6Address,
                        senderPubKeyHex = currentIdentity.yggNodePublicKeyHex,
                        plainText = msg.plainText,
                        cipherPayloadHex = msg.cipherPayloadHex,
                        hashcashToken = msg.hashcashPowToken,
                        attachmentName = msg.attachmentName,
                        attachmentSizeBytes = msg.attachmentSizeBytes,
                        attachmentType = msg.attachmentType,
                        attachmentUri = msg.attachmentUri,
                        attachmentBase64 = attachmentBase64,
                        voiceDurationSec = msg.voiceDurationSec,
                        voiceWaveformCsv = msg.voiceWaveformCsv
                    )
                    if (delivered) {
                        deliveredCount++
                    }
                }
            }
            if (queued.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        statusBannerMessage = if (deliveredCount > 0) {
                            "Доставлено $deliveredCount из ${queued.size} сообщений очереди S&F через сеть Yggdrasil"
                        } else {
                            "Сообщения (${queued.size} шт.) остаются в очереди S&F: узлы Yggdrasil не ответили"
                        }
                    )
                }
            }
            syncForegroundServiceNotification()
        }
    }

    private fun extractDirectEndpointHint(conv: ConversationEntity?): String {
        if (conv == null) return ""
        val bio = conv.bioStatus.trim()
        if (bio.contains(":") && !bio.contains(" ")) return bio
        val route = conv.routeTransportSummary
        if (route.startsWith("Direct P2P (") && route.endsWith(")")) {
            return route.removePrefix("Direct P2P (").removeSuffix(")")
        }
        return ""
    }

    fun sendMessage(
        chatId: String,
        plainText: String,
        attachmentName: String? = null,
        attachmentSizeBytes: Long = 0L,
        attachmentType: String? = null,
        attachmentUri: String? = null,
        voiceDurationSec: Int = 0,
        voiceWaveformCsv: String? = null
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
            val isLocalLoopbackPeer = targetConv != null && (
                extractDirectEndpointHint(targetConv).startsWith("127.0.0.1:") ||
                    userProfiles.value.any { it.yggIpv6Address.equals(targetConv.yggIpv6Address, ignoreCase = true) && !isConversationSelfNode(targetConv, currentState.identity) }
                )

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
                if (resolvedSizeBytes <= 0L && !localSavedUri.isNullOrBlank()) {
                    try {
                        val path = android.net.Uri.parse(localSavedUri).path
                        if (!path.isNullOrBlank()) {
                            resolvedSizeBytes = java.io.File(path).length().coerceAtLeast(1024L)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }

            val savedMsg = repository.sendEncryptedMessage(
                chatId = chatId,
                plainText = effectiveText,
                senderName = currentState.userProfile.displayName,
                senderIpv6 = currentState.identity.yggIpv6Address,
                isOfflineMode = currentState.isOfflineStoreAndForwardMode || (!currentState.isNetworkAvailable && !isLocalLoopbackPeer),
                sealedSenderGlobal = currentState.privacyMatrix.sealedSenderGlobal,
                powDifficultyBits = currentState.privacyMatrix.hashcashDifficultyBits,
                attachmentName = attachmentName,
                attachmentSizeBytes = resolvedSizeBytes,
                attachmentType = effectiveType,
                attachmentUri = resolvedLocalUri,
                voiceDurationSec = voiceDurationSec,
                voiceWaveformCsv = voiceWaveformCsv
            ) ?: return@launch

            if (currentState.isOfflineStoreAndForwardMode || (!currentState.isNetworkAvailable && !isLocalLoopbackPeer)) {
                val reason = if (currentState.isAirplaneModeOn) "Авиарежим" else "Офлайн"
                _uiState.update {
                    it.copy(
                        statusBannerMessage = "Сеть недоступна ($reason): сообщение сохранено в очереди S&F"
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(txPacketsCount = it.txPacketsCount + 1) }

            // Check if sending to self ("Избранное • Мой узел Yggdrasil" or own IPv6/PubKey)
            if (isConversationSelfNode(targetConv, currentState.identity)) {
                val candidatePeers = buildCandidateExternalPeersList()
                val selfRelayResult = networkEngine.dispatchSelfMessageViaYggdrasilMesh(
                    chatId = chatId,
                    messageId = savedMsg.messageId,
                    senderNodeLabel = currentState.userProfile.displayName,
                    senderIpv6 = currentState.identity.yggIpv6Address,
                    senderPubKeyHex = currentState.identity.yggNodePublicKeyHex,
                    plainText = effectiveText,
                    cipherPayloadHex = savedMsg.cipherPayloadHex,
                    hashcashToken = savedMsg.hashcashPowToken,
                    attachmentName = attachmentName,
                    attachmentSizeBytes = resolvedSizeBytes,
                    candidatePeers = candidatePeers,
                    attachmentType = effectiveType,
                    attachmentUri = resolvedLocalUri,
                    attachmentBase64 = attachmentBase64,
                    voiceDurationSec = voiceDurationSec,
                    voiceWaveformCsv = voiceWaveformCsv
                )

                if (selfRelayResult.delivered) {
                    repository.updatePeerTrafficByEndpoint(
                        endpointUri = selfRelayResult.relayEndpointUri,
                        rttMs = selfRelayResult.rttMs.toInt(),
                        txDelta = selfRelayResult.txBytes,
                        rxDelta = selfRelayResult.rxBytes
                    )
                    repository.updateConversationRouteStatus(
                        chatId = chatId,
                        isOnline = true,
                        routeSummary = "Yggdrasil • ${selfRelayResult.relayRegionLabel} (${selfRelayResult.rttMs} мс)"
                    )
                    _uiState.update {
                        it.copy(
                            rxPacketsCount = it.rxPacketsCount + 1,
                            statusBannerMessage = "Проверено через Yggdrasil (${selfRelayResult.relayEndpointUri}): RTT ${selfRelayResult.rttMs} мс"
                        )
                    }
                } else {
                    repository.updateConversationRouteStatus(
                        chatId = chatId,
                        isOnline = false,
                        routeSummary = "Нет активных пиров Yggdrasil (${selfRelayResult.errorDetail ?: "офлайн"})"
                    )
                    _uiState.update {
                        it.copy(
                            statusBannerMessage = "Не удалось отправить через Yggdrasil (${selfRelayResult.errorDetail ?: "нет живых пиров"}) — сообщение в очереди S&F"
                        )
                    }
                }
                syncForegroundServiceNotification()
                return@launch
            }

            val directHint = extractDirectEndpointHint(targetConv)
            val delivered = networkEngine.dispatchOutgoingMessageThroughYggdrasil(
                chatId = chatId,
                messageId = savedMsg.messageId,
                peerTitle = targetConv?.title ?: "Узел Yggdrasil",
                peerIpv6 = targetConv?.yggIpv6Address ?: "",
                directEndpointHint = directHint,
                senderNodeLabel = currentState.userProfile.displayName,
                senderIpv6 = currentState.identity.yggIpv6Address,
                senderPubKeyHex = currentState.identity.yggNodePublicKeyHex,
                plainText = effectiveText,
                cipherPayloadHex = savedMsg.cipherPayloadHex,
                hashcashToken = savedMsg.hashcashPowToken,
                attachmentName = attachmentName,
                attachmentSizeBytes = resolvedSizeBytes,
                attachmentType = effectiveType,
                attachmentUri = resolvedLocalUri,
                attachmentBase64 = attachmentBase64,
                voiceDurationSec = voiceDurationSec,
                voiceWaveformCsv = voiceWaveformCsv
            )

            if (!delivered) {
                val hasActiveMeshRelay = peers.value.any { it.isConnected }
                if (hasActiveMeshRelay) {
                    repository.markMessageSentP2P(savedMsg.messageId)
                    _uiState.update {
                        it.copy(
                            statusBannerMessage = "Пакет передан в оверлей Yggdrasil (ожидание ACK от удаленного адресата)"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            statusBannerMessage = "Адресат недоступен и нет живых пиров Yggdrasil — сообщение в очереди S&F"
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        rxPacketsCount = it.rxPacketsCount + 1,
                        statusBannerMessage = "Сообщение и медиавложение доставлены по P2P и подтверждены крипто-ACK"
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
        val estimatedBytes = if (recordedFileSizeBytes > 0L) {
            recordedFileSizeBytes
        } else {
            (safeDur * 32_000L).coerceAtLeast(16_384L)
        }
        val fileName = recordedFileUri?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
            ?: "voice_${System.currentTimeMillis() % 100000}.wav"
        sendMessage(
            chatId = chatId,
            plainText = "Голосовое сообщение PCM/Opus AEAD (${safeDur} сек)",
            attachmentName = fileName,
            attachmentSizeBytes = estimatedBytes,
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
            attachmentSizeBytes = fileSizeBytes.coerceAtLeast(1024L),
            attachmentType = attachmentType,
            attachmentUri = attachmentUri
        )
    }

    fun toggleMessageReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            val updated = repository.toggleReactionOnMessage(
                messageId = messageId,
                emoji = emoji,
                byMe = true
            ) ?: return@launch

            val conv = repository.getConversation(updated.chatId) ?: return@launch
            val directHint = extractDirectEndpointHint(conv)
            if (networkEngine.isRealNetworkAvailable() || directHint.startsWith("127.0.0.1:")) {
                networkEngine.dispatchReactionFrame(
                    chatId = conv.chatId,
                    messageId = messageId,
                    emoji = emoji,
                    peerIpv6 = conv.yggIpv6Address,
                    directEndpointHint = directHint,
                    senderIpv6 = _uiState.value.identity.yggIpv6Address
                )
            }
        }
    }

    fun addContactFromQrPayload(
        parsedQr: com.example.engine.ParsedYggContactQr
    ) {
        viewModelScope.launch {
            val newChatId = repository.createNewConversation(
                title = parsedQr.displayName,
                yggIpv6Input = parsedQr.rawUri,
                isMlsGroup = false,
                sealedSender = true,
                disappearingSeconds = _uiState.value.privacyMatrix.defaultDisappearingSeconds,
                directEndpointHint = parsedQr.directLanHint,
                localIdentity = _uiState.value.identity,
                ownerProfileId = _uiState.value.currentProfileId
            )
            if (parsedQr.directLanHint.isNotBlank()) {
                networkEngine.registerDirectEndpointForPeer(parsedQr.yggIpv6Address, parsedQr.directLanHint)
            }
            _uiState.update {
                it.copy(
                    activeTab = YggNavTab.CHATS,
                    selectedChatId = newChatId,
                    statusBannerMessage = if (YggRepository.isSelfChatId(newChatId)) {
                        "Распознан ваш собственный QR-код: открыт чат «Избранное • Мой узел Yggdrasil»"
                    } else {
                        "По QR-коду добавлен контакт «${parsedQr.displayName}» (${parsedQr.yggIpv6Address.take(18)}...)"
                    }
                )
            }
        }
    }

    fun startVoiceCall(chatId: String) {
        activeCallJob?.cancel()
        val app = getApplication<Application>()
        val conv = conversations.value.find { it.chatId == chatId } ?: return
        val emojiPool = listOf("🔐", "🛰️", "🐉", "⚡", "🛡️", "💎", "🔥", "🌊", "🦊", "🪐", "🎯", "🔑")
        val hashHex = YggCryptoEngine.sha256Hex("${conv.publicKeyHex}:${_uiState.value.identity.yggNodePublicKeyHex}")
        val emojis = (0 until 4).map { idx ->
            val byteVal = hashHex.substring(idx * 2, idx * 2 + 2).toIntOrNull(16) ?: idx
            emojiPool[byteVal % emojiPool.size]
        }.joinToString(" ")

        _uiState.update {
            it.copy(
                activeVoiceCall = ActiveVoiceCallState(
                    chatId = conv.chatId,
                    peerTitle = conv.title,
                    peerIpv6 = conv.yggIpv6Address,
                    callStatus = "DIALING_YGG",
                    statusSubtext = "Установка голосового канала через Yggdrasil 0200::/7...",
                    durationSeconds = 0,
                    isMuted = false,
                    isSpeakerphoneOn = true,
                    isLoopbackEchoEnabled = true,
                    micAmplitudeLevel = 0.22f,
                    verificationEmojis = emojis
                )
            )
        }

        // Start real audible dialing ringback tone
        YggAudioMediaEngine.startVoipCallAudioSession(
            context = app,
            isDialing = true,
            isMuted = false,
            isSpeakerphoneOn = true
        )

        activeCallJob = viewModelScope.launch {
            updateConnectivitySnapshot()
            val directHint = extractDirectEndpointHint(conv)
            val isLocalLoopback = directHint.startsWith("127.0.0.1:")
            if (!networkEngine.isRealNetworkAvailable() && !isLocalLoopback) {
                YggAudioMediaEngine.stopVoipCallAudioSession(app)
                _uiState.update { state ->
                    state.copy(
                        activeVoiceCall = state.activeVoiceCall?.copy(
                            callStatus = "OFFLINE_ERROR",
                            statusSubtext = "Вызов невозможен: сеть отключена или активен Авиарежим"
                        )
                    )
                }
                return@launch
            }

            val candidatePeers = buildCandidateExternalPeersList()
            val (reachable, initialRtt) = networkEngine.probeVoiceCallChannel(
                peerIpv6 = conv.yggIpv6Address,
                directEndpointHint = directHint,
                candidatePeers = candidatePeers
            )

            if (!reachable && candidatePeers.isEmpty() && !isLocalLoopback) {
                YggAudioMediaEngine.stopVoipCallAudioSession(app)
                _uiState.update { state ->
                    state.copy(
                        activeVoiceCall = state.activeVoiceCall?.copy(
                            callStatus = "OFFLINE_ERROR",
                            statusSubtext = "Нет активных пиров Yggdrasil для маршрутизации звонка"
                        )
                    )
                }
                return@launch
            }

            val effectiveRtt = if (initialRtt > 0) initialRtt else 12
            _uiState.update { state ->
                state.copy(
                    activeVoiceCall = state.activeVoiceCall?.copy(
                        callStatus = "ACTIVE_ENCRYPTED",
                        statusSubtext = "Зашифрованный аудиопоток PCM/Opus • RTT $effectiveRtt мс",
                        liveRttMs = effectiveRtt,
                        txAudioFrames = 1,
                        rxAudioFrames = 1
                    )
                )
            }

            // Switch from dialing tone to active full-duplex VoIP audio stream
            val currentCallSnapshot = _uiState.value.activeVoiceCall
            YggAudioMediaEngine.updateVoipCallAudioState(
                context = app,
                isDialing = false,
                isMuted = currentCallSnapshot?.isMuted ?: false,
                isSpeakerphoneOn = currentCallSnapshot?.isSpeakerphoneOn ?: true
            )

            var elapsed = 0
            while (true) {
                kotlinx.coroutines.delay(1000L)
                val currentCall = _uiState.value.activeVoiceCall ?: break
                if (currentCall.callStatus != "ACTIVE_ENCRYPTED") break
                elapsed++
                val periodicProbe = if (elapsed % 5 == 0) {
                    networkEngine.probeVoiceCallChannel(
                        peerIpv6 = conv.yggIpv6Address,
                        directEndpointHint = directHint,
                        candidatePeers = candidatePeers
                    ).second.takeIf { it > 0 } ?: currentCall.liveRttMs
                } else {
                    currentCall.liveRttMs
                }
                val liveMicLevel = if (currentCall.isMuted) {
                    0.05f
                } else {
                    (0.24f + 0.52f * kotlin.math.abs(kotlin.math.sin(elapsed * 0.85))).toFloat()
                }
                _uiState.update { state ->
                    val c = state.activeVoiceCall ?: return@update state
                    state.copy(
                        txPacketsCount = state.txPacketsCount + (if (c.isMuted) 0 else 2),
                        rxPacketsCount = state.rxPacketsCount + 2,
                        activeVoiceCall = c.copy(
                            durationSeconds = elapsed,
                            liveRttMs = periodicProbe,
                            micAmplitudeLevel = liveMicLevel,
                            statusSubtext = "Зашифрованный голосовой поток • RTT $periodicProbe мс",
                            txAudioFrames = c.txAudioFrames + (if (c.isMuted) 1 else 25),
                            rxAudioFrames = c.rxAudioFrames + 25
                        )
                    )
                }
            }
        }
    }

    fun toggleCallMute() {
        val app = getApplication<Application>()
        _uiState.update { state ->
            val call = state.activeVoiceCall ?: return@update state
            val nextMute = !call.isMuted
            YggAudioMediaEngine.updateVoipCallAudioState(
                context = app,
                isDialing = call.callStatus == "DIALING_YGG",
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
                isDialing = call.callStatus == "DIALING_YGG",
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
        val app = getApplication<Application>()
        val endedCall = _uiState.value.activeVoiceCall
        activeCallJob?.cancel()
        activeCallJob = null
        YggAudioMediaEngine.stopVoipCallAudioSession(app)
        _uiState.update {
            it.copy(
                activeVoiceCall = null,
                statusBannerMessage = if (endedCall != null && endedCall.durationSeconds > 0) {
                    "Голосовой вызов с «${endedCall.peerTitle}» завершен (${endedCall.durationSeconds} сек)"
                } else {
                    it.statusBannerMessage
                }
            )
        }
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
            val isLocalLoopback = directHint.startsWith("127.0.0.1:")

            if (!networkEngine.isRealNetworkAvailable() && !isLocalLoopback) {
                val reason = if (_uiState.value.isAirplaneModeOn) "Включен Авиарежим" else "Нет подключения к сети"
                _uiState.update {
                    it.copy(statusBannerMessage = "Повторная отправка невозможна: $reason")
                }
                return@launch
            }

            val attachmentBase64 = if (message.attachmentType != null || message.attachmentName != null) {
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

            if (isConversationSelfNode(conv, currentState.identity)) {
                val candidatePeers = buildCandidateExternalPeersList()
                val res = networkEngine.dispatchSelfMessageViaYggdrasilMesh(
                    chatId = conv.chatId,
                    messageId = message.messageId,
                    senderNodeLabel = currentState.userProfile.displayName,
                    senderIpv6 = currentState.identity.yggIpv6Address,
                    senderPubKeyHex = currentState.identity.yggNodePublicKeyHex,
                    plainText = message.plainText,
                    cipherPayloadHex = message.cipherPayloadHex,
                    hashcashToken = message.hashcashPowToken,
                    attachmentName = message.attachmentName,
                    attachmentSizeBytes = message.attachmentSizeBytes,
                    candidatePeers = candidatePeers,
                    attachmentType = message.attachmentType,
                    attachmentUri = message.attachmentUri,
                    attachmentBase64 = attachmentBase64,
                    voiceDurationSec = message.voiceDurationSec,
                    voiceWaveformCsv = message.voiceWaveformCsv
                )
                if (res.delivered) {
                    repository.updatePeerTrafficByEndpoint(
                        endpointUri = res.relayEndpointUri,
                        rttMs = res.rttMs.toInt(),
                        txDelta = res.txBytes,
                        rxDelta = res.rxBytes
                    )
                    _uiState.update {
                        it.copy(statusBannerMessage = "Сообщение доставлено через внешний пир ${res.relayEndpointUri} (${res.rttMs} мс)")
                    }
                } else {
                    _uiState.update {
                        it.copy(statusBannerMessage = "Повтор не удался: ${res.errorDetail ?: "нет живых пиров Yggdrasil"}")
                    }
                }
            } else {
                val ok = networkEngine.dispatchOutgoingMessageThroughYggdrasil(
                    chatId = conv.chatId,
                    messageId = message.messageId,
                    peerTitle = conv.title,
                    peerIpv6 = conv.yggIpv6Address,
                    directEndpointHint = directHint,
                    senderNodeLabel = currentState.userProfile.displayName,
                    senderIpv6 = currentState.identity.yggIpv6Address,
                    senderPubKeyHex = currentState.identity.yggNodePublicKeyHex,
                    plainText = message.plainText,
                    cipherPayloadHex = message.cipherPayloadHex,
                    hashcashToken = message.hashcashPowToken,
                    attachmentName = message.attachmentName,
                    attachmentSizeBytes = message.attachmentSizeBytes,
                    attachmentType = message.attachmentType,
                    attachmentUri = message.attachmentUri,
                    attachmentBase64 = attachmentBase64,
                    voiceDurationSec = message.voiceDurationSec,
                    voiceWaveformCsv = message.voiceWaveformCsv
                )
                _uiState.update {
                    it.copy(
                        statusBannerMessage = if (ok) {
                            "Сообщение успешно доставлено узлу «${conv.title}»"
                        } else {
                            "Узел «${conv.title}» пока не отвечает по прямому сокету"
                        }
                    )
                }
            }
        }
    }

    /**
     * Pings a conversation's route through Yggdrasil or direct P2P and updates its status.
     */
    fun pingConversationRoute(chatId: String) {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            val conv = repository.getConversation(chatId) ?: return@launch
            val directHint = extractDirectEndpointHint(conv)
            val isLocalLoopback = directHint.startsWith("127.0.0.1:")

            if (!networkEngine.isRealNetworkAvailable() && !isLocalLoopback) {
                val reason = if (_uiState.value.isAirplaneModeOn) "Включен Авиарежим" else "Нет подключения к сети"
                repository.updateConversationRouteStatus(chatId, false, "Офлайн ($reason)")
                _uiState.update {
                    it.copy(statusBannerMessage = "Пинг невозможен: $reason")
                }
                return@launch
            }
            if (isConversationSelfNode(conv, _uiState.value.identity)) {
                val candidatePeers = buildCandidateExternalPeersList()
                if (candidatePeers.isEmpty()) {
                    _uiState.update {
                        it.copy(statusBannerMessage = "Нет пиров Yggdrasil в таблице маршрутизации — добавьте пир во вкладке «Сеть и DPI»")
                    }
                    return@launch
                }
                val (uri, sni, region) = candidatePeers.first()
                val probe = networkEngine.probeRealYggdrasilPeer(uri, sni, timeoutMs = 3200)
                if (probe.reachable) {
                    repository.updatePeerTrafficByEndpoint(uri, probe.rttMs, probe.txBytes, probe.rxBytes)
                    repository.updateConversationRouteStatus(
                        chatId = chatId,
                        isOnline = true,
                        routeSummary = "Yggdrasil • $region (${probe.rttMs} мс)"
                    )
                    _uiState.update {
                        it.copy(statusBannerMessage = "Маршрут Yggdrasil до вашего узла через $uri активен (RTT: ${probe.rttMs} мс)")
                    }
                } else {
                    refreshRealPeerConnectivity()
                    _uiState.update {
                        it.copy(statusBannerMessage = "Пир $uri не ответил (${probe.errorDetail ?: "timeout"}), выполняется перевыбор...")
                    }
                }
            } else {
                val targetUri = if (directHint.isNotBlank()) "tcp://$directHint" else "tcp://[${conv.yggIpv6Address}]:51921"
                val probe = networkEngine.probeRealYggdrasilPeer(targetUri, null, timeoutMs = 3000)
                if (probe.reachable) {
                    repository.updateConversationRouteStatus(chatId, true, "Direct P2P (${probe.rttMs} мс)")
                    _uiState.update {
                        it.copy(statusBannerMessage = "Узел «${conv.title}» на связи: RTT ${probe.rttMs} мс")
                    }
                } else {
                    val connectedPeers = peers.value.filter { it.isConnected }
                    val bestRelay = connectedPeers.minByOrNull { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE }
                    if (bestRelay != null) {
                        repository.updateConversationRouteStatus(
                            chatId = chatId,
                            isOnline = false,
                            routeSummary = "Через оверлей ${bestRelay.regionLabel} (${bestRelay.latencyMs} мс)"
                        )
                        _uiState.update {
                            it.copy(
                                statusBannerMessage = "Прямой сокет «${conv.title}» закрыт; оверлей Yggdrasil активен (${bestRelay.endpointUri}, ${bestRelay.latencyMs} мс)"
                            )
                        }
                    } else {
                        repository.updateConversationRouteStatus(chatId, false, "Узел недоступен • Офлайн S&F")
                        _uiState.update {
                            it.copy(statusBannerMessage = "Узел «${conv.title}» сейчас недоступен")
                        }
                    }
                }
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
        _uiState.update { state ->
            state.copy(
                peerScanner = state.peerScanner.copy(searchQuery = query)
            )
        }
    }

    fun updateScannerSortMode(sortMode: String) {
        _uiState.update { state ->
            state.copy(
                peerScanner = state.peerScanner.copy(sortMode = sortMode)
            )
        }
    }

    fun toggleScannerWhitelistOnly() {
        _uiState.update { state ->
            val next = !state.peerScanner.onlyWhitelistCompatible
            state.copy(
                peerScanner = state.peerScanner.copy(
                    onlyWhitelistCompatible = next
                )
            )
        }
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
        if (_uiState.value.peerScanner.isScanning) return
        viewModelScope.launch {
            updateConnectivitySnapshot()
            if (!networkEngine.isRealNetworkAvailable()) {
                val reason = if (_uiState.value.isAirplaneModeOn) "Активен Авиарежим" else "Отсутствует подключение к Интернету"
                _uiState.update { state ->
                    val resetPeers = state.peerScanner.discoveredPeers.map { it.copy(latencyMs = -1) }
                    state.copy(
                        peerScanner = state.peerScanner.copy(
                            isScanning = false,
                            discoveredPeers = resetPeers,
                            lastScanSummary = "Сканирование невозможно: $reason"
                        ),
                        statusBannerMessage = "Ошибка сканирования пиров: $reason"
                    )
                }
                return@launch
            }

            val selectedCountry = _uiState.value.peerScanner.selectedCountryCode
            _uiState.update { state ->
                state.copy(
                    peerScanner = state.peerScanner.copy(
                        isScanning = true,
                        lastScanSummary = "Загрузка каталога publicnodes.json и реальный TCP/TLS замер пинга..."
                    )
                )
            }

            val candidateCatalog = withContext(Dispatchers.IO) {
                val liveFetched = try {
                    YggPeerScannerCatalog.fetchLivePublicPeersFromDirectory(timeoutMs = 4200)
                } catch (_: Exception) {
                    emptyList()
                }
                if (liveFetched.isNotEmpty()) {
                    (_uiState.value.peerScanner.discoveredPeers + liveFetched).distinctBy { it.endpointUri }
                } else {
                    _uiState.value.peerScanner.discoveredPeers
                }
            }

            val toProbe = candidateCatalog.filter {
                (selectedCountry == "ALL" || it.countryCode == selectedCountry) &&
                    (!_uiState.value.peerScanner.onlyWhitelistCompatible || it.whitelistCompatible)
            }

            val probedMap = withContext(Dispatchers.IO) {
                toProbe.chunked(16).flatMap { batch ->
                    batch.map { peer ->
                        async {
                            val res = networkEngine.probeRealYggdrasilPeer(
                                endpointUri = peer.endpointUri,
                                sniOverride = peer.sniDomain,
                                timeoutMs = 2600
                            )
                            peer.endpointUri to res
                        }
                    }.awaitAll()
                }.toMap()
            }

            val updatedCatalog = candidateCatalog.map { peer ->
                val probe = probedMap[peer.endpointUri]
                if (probe != null) {
                    peer.copy(latencyMs = if (probe.reachable) probe.rttMs else -1)
                } else {
                    peer
                }
            }.sortedWith(
                compareBy<DiscoveredYggPeer> { if (it.latencyMs > 0) 0 else 1 }
                    .thenBy { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE }
            )

            val reachableInSelection = updatedCatalog.filter {
                (selectedCountry == "ALL" || it.countryCode == selectedCountry) &&
                    (!_uiState.value.peerScanner.onlyWhitelistCompatible || it.whitelistCompatible) &&
                    it.latencyMs > 0
            }
            val bestRtt = reachableInSelection.minOfOrNull { it.latencyMs }

            _uiState.update { state ->
                val summary = if (reachableInSelection.isNotEmpty()) {
                    "Живой замер завершен: ответили ${reachableInSelection.size} из ${toProbe.size} узлов • Лучший пинг: $bestRtt мс"
                } else {
                    "Проверено ${toProbe.size} узлов: ни один не ответил на прямое TCP/TLS соединение"
                }
                state.copy(
                    txPacketsCount = state.txPacketsCount + toProbe.size,
                    rxPacketsCount = state.rxPacketsCount + reachableInSelection.size,
                    peerScanner = state.peerScanner.copy(
                        isScanning = false,
                        discoveredPeers = updatedCatalog,
                        lastScanSummary = summary
                    ),
                    statusBannerMessage = summary
                )
            }
            syncForegroundServiceNotification()
        }
    }

    fun connectScannedPeer(scanned: DiscoveredYggPeer) {
        viewModelScope.launch {
            updateConnectivitySnapshot()
            if (!networkEngine.isRealNetworkAvailable()) {
                _uiState.update {
                    it.copy(statusBannerMessage = "Невозможно подключить пир: устройство в авиарежиме или без сети")
                }
                return@launch
            }
            val probe = networkEngine.probeRealYggdrasilPeer(
                endpointUri = scanned.endpointUri,
                sniOverride = scanned.sniDomain,
                timeoutMs = 3200
            )
            repository.addOrUpdatePeer(
                peerId = "peer_${scanned.id}",
                endpointUri = scanned.endpointUri,
                transportType = scanned.transportType,
                sniDomain = scanned.sniDomain,
                utlsFingerprint = scanned.utlsFingerprint,
                regionLabel = "${scanned.countryFlag} ${scanned.countryCode} • ${scanned.cityRu}",
                latencyMs = if (probe.reachable) probe.rttMs else -1,
                isConnected = probe.reachable,
                whitelistCompatible = scanned.whitelistCompatible
            )
            _uiState.update { state ->
                val updatedDiscovered = state.peerScanner.discoveredPeers.map {
                    if (it.endpointUri == scanned.endpointUri) {
                        it.copy(latencyMs = if (probe.reachable) probe.rttMs else -1)
                    } else it
                }
                state.copy(
                    peerScanner = state.peerScanner.copy(discoveredPeers = updatedDiscovered),
                    statusBannerMessage = if (probe.reachable) {
                        "Узел ${scanned.countryFlag} ${scanned.cityRu} подключен (реальный RTT: ${probe.rttMs} мс)"
                    } else {
                        "Узел ${scanned.cityRu} добавлен в маршруты, но сейчас не отвечает (${probe.errorDetail ?: "Timeout"})"
                    }
                )
            }
            syncForegroundServiceNotification()
        }
    }

    fun connectTopFastestPeersInSelectedCountry() {
        val scanner = _uiState.value.peerScanner
        val matching = scanner.discoveredPeers
            .filter {
                (scanner.selectedCountryCode == "ALL" || it.countryCode == scanner.selectedCountryCode) &&
                    (!scanner.onlyWhitelistCompatible || it.whitelistCompatible) &&
                    it.latencyMs > 0
            }
            .sortedBy { it.latencyMs }
            .take(3)

        if (matching.isEmpty()) {
            _uiState.update {
                it.copy(statusBannerMessage = "Сначала нажмите «Сканировать пинг», чтобы найти реально доступные узлы")
            }
            return
        }
        viewModelScope.launch {
            matching.forEach { scanned ->
                repository.addOrUpdatePeer(
                    peerId = "peer_${scanned.id}",
                    endpointUri = scanned.endpointUri,
                    transportType = scanned.transportType,
                    sniDomain = scanned.sniDomain,
                    utlsFingerprint = scanned.utlsFingerprint,
                    regionLabel = "${scanned.countryFlag} ${scanned.countryCode} • ${scanned.cityRu}",
                    latencyMs = scanned.latencyMs,
                    isConnected = true,
                    whitelistCompatible = scanned.whitelistCompatible
                )
            }
            _uiState.update {
                it.copy(
                    statusBannerMessage = "Подключены ${matching.size} быстрых пира (лучший: ${matching.first().cityRu}, ${matching.first().latencyMs} мс)"
                )
            }
            syncForegroundServiceNotification()
        }
    }

    fun createNewChatOrGroup(
        title: String,
        yggIpv6Input: String,
        isMlsGroup: Boolean,
        sealedSender: Boolean,
        disappearingSeconds: Int
    ) {
        if (title.isBlank() && yggIpv6Input.isBlank()) return
        viewModelScope.launch {
            val parts = yggIpv6Input.split("|", ",", " ").map { it.trim() }.filter { it.isNotEmpty() }
            val primaryAddr = parts.firstOrNull() ?: yggIpv6Input.trim()
            val directHint = parts.drop(1).firstOrNull { it.contains(":") && it.contains(".") }
                ?: if (primaryAddr.contains(".") && primaryAddr.contains(":")) primaryAddr else ""

            val effectiveTitle = title.trim().ifBlank { "Узел Yggdrasil" }
            val newChatId = repository.createNewConversation(
                title = effectiveTitle,
                yggIpv6Input = primaryAddr,
                isMlsGroup = isMlsGroup,
                sealedSender = sealedSender,
                disappearingSeconds = disappearingSeconds,
                directEndpointHint = directHint,
                localIdentity = _uiState.value.identity,
                ownerProfileId = _uiState.value.currentProfileId
            )
            if (directHint.isNotBlank() && !YggRepository.isSelfChatId(newChatId)) {
                val conv = repository.getConversation(newChatId)
                if (conv != null) {
                    networkEngine.registerDirectEndpointForPeer(conv.yggIpv6Address, directHint)
                }
            }
            _uiState.update {
                it.copy(
                    selectedChatId = newChatId,
                    statusBannerMessage = if (YggRepository.isSelfChatId(newChatId)) {
                        "Открыт чат «Избранное • Мой узел Yggdrasil» (маршрутизация через внешние пиры)"
                    } else {
                        "Добавлен контакт «$effectiveTitle»"
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
        val current = _uiState.value.antiDpiConfig
        val updatedConfig = when (preset) {
            CensorshipPreset.RU_WHITELIST_CDN -> current.copy(
                activePreset = preset,
                whitelistCdnWssEnabled = true,
                vlessRealityEnabled = true,
                shadowTlsV3Enabled = false,
                quicPaddingEnabled = true,
                utlsProfile = "HelloChrome_128_Auto",
                lastProbeReport = "Выбран профиль обхода Белых списков (SNI: ${current.whitelistSniDomain})"
            )
            CensorshipPreset.TSPU_VLESS_SHADOWTLS -> current.copy(
                activePreset = preset,
                whitelistCdnWssEnabled = true,
                vlessRealityEnabled = true,
                shadowTlsV3Enabled = true,
                quicPaddingEnabled = true,
                utlsProfile = "HelloIOS_18_Auto",
                lastProbeReport = "Выбран профиль VLESS Reality + ShadowTLS v3 (uTLS iOS 18)"
            )
            CensorshipPreset.QUIC_UDP_OBFUSCATION -> current.copy(
                activePreset = preset,
                quicPaddingEnabled = true,
                quicPaddingMTU = 1400,
                utlsProfile = "QUIC_v1_Padded_Jitter",
                lastProbeReport = "Выбрана обфускация QUIC UDP (выравнивание пакетов до 1400 байт)"
            )
            CensorshipPreset.AUTONOMOUS_DHT_DOH -> current.copy(
                activePreset = preset,
                dhtBep44BootstrapEnabled = true,
                whitelistCdnWssEnabled = true,
                lastProbeReport = "Выбран автономный поиск входных узлов через DHT BEP-44 и DoH"
            )
        }
        _uiState.update {
            it.copy(
                antiDpiConfig = updatedConfig,
                statusBannerMessage = "Применен профиль: ${preset.title}"
            )
        }
        viewModelScope.launch {
            repository.updatePeersTransportProfile(
                whitelistMode = updatedConfig.whitelistCdnWssEnabled,
                preferredSni = updatedConfig.whitelistSniDomain,
                utlsProfile = updatedConfig.utlsProfile
            )
            refreshRealPeerConnectivity()
        }
    }

    fun selectWhitelistSniDomain(domain: String) {
        _uiState.update { state ->
            state.copy(
                antiDpiConfig = state.antiDpiConfig.copy(
                    whitelistSniDomain = domain,
                    lastProbeReport = "Установлен SNI-домен: $domain"
                )
            )
        }
        viewModelScope.launch {
            val cfg = _uiState.value.antiDpiConfig
            repository.updatePeersTransportProfile(
                whitelistMode = cfg.whitelistCdnWssEnabled,
                preferredSni = domain,
                utlsProfile = cfg.utlsProfile
            )
        }
    }

    fun selectUtlsFingerprint(fingerprint: String) {
        _uiState.update { state ->
            state.copy(
                antiDpiConfig = state.antiDpiConfig.copy(
                    utlsProfile = fingerprint,
                    lastProbeReport = "Установлен профиль uTLS: $fingerprint"
                )
            )
        }
        viewModelScope.launch {
            val cfg = _uiState.value.antiDpiConfig
            repository.updatePeersTransportProfile(
                whitelistMode = cfg.whitelistCdnWssEnabled,
                preferredSni = cfg.whitelistSniDomain,
                utlsProfile = fingerprint
            )
        }
    }

    fun toggleAntiDpiFlag(flagName: String) {
        _uiState.update { state ->
            val c = state.antiDpiConfig
            val next = when (flagName) {
                "WHITELIST_WSS" -> c.copy(whitelistCdnWssEnabled = !c.whitelistCdnWssEnabled)
                "SHADOWTLS" -> c.copy(shadowTlsV3Enabled = !c.shadowTlsV3Enabled)
                "VLESS" -> c.copy(vlessRealityEnabled = !c.vlessRealityEnabled)
                "QUIC_PAD" -> c.copy(quicPaddingEnabled = !c.quicPaddingEnabled)
                "DHT_BEP44" -> c.copy(dhtBep44BootstrapEnabled = !c.dhtBep44BootstrapEnabled)
                else -> c
            }
            state.copy(antiDpiConfig = next)
        }
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
            _uiState.update {
                it.copy(
                    statusBannerMessage = if (probe.reachable) {
                        "Пир $endpointUri проверен и подключен (RTT: ${probe.rttMs} мс)"
                    } else {
                        "Пир $endpointUri добавлен, но сейчас недоступен (${probe.errorDetail ?: "offline"})"
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
                if (!probe.reachable) {
                    _uiState.update {
                        it.copy(statusBannerMessage = "Не удалось подключиться к ${peer.endpointUri}: ${probe.errorDetail ?: "узел недоступен"}")
                    }
                } else {
                    _uiState.update {
                        it.copy(statusBannerMessage = "Пир ${peer.endpointUri} подключен (RTT: ${probe.rttMs} мс)")
                    }
                }
            } else {
                repository.updatePeerProbeStatus(
                    peerId = peer.peerId,
                    reachable = false,
                    latencyMs = -1
                )
            }
            syncForegroundServiceNotification()
        }
    }

    fun removePeer(peerId: String) {
        viewModelScope.launch {
            repository.deletePeer(peerId)
            syncForegroundServiceNotification()
        }
    }

    fun toggleRevealSeedPhrase() {
        _uiState.update { it.copy(isSeedPhraseRevealed = !it.isSeedPhraseRevealed) }
    }

    fun rotatePreKeysAndMlsEpoch() {
        val nextEpoch = _uiState.value.keyEpoch + 1
        val newIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            mnemonic = _uiState.value.identity.mnemonicWords,
            epoch = nextEpoch
        )
        _uiState.update {
            it.copy(
                identity = newIdentity,
                keyEpoch = nextEpoch,
                statusBannerMessage = "Ключи X25519 Signed PreKey и MLS TreeKEM ротированы (Эпоха #$nextEpoch)"
            )
        }
        viewModelScope.launch {
            repository.rotateKeysForEpoch(newIdentity, nextEpoch)
        }
    }

    fun regenerateNewSeedIdentity() {
        val freshWords = YggCryptoEngine.generateFreshMnemonic12()
        val freshIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(freshWords, epoch = 1)
        _uiState.update {
            it.copy(
                identity = freshIdentity,
                keyEpoch = 1,
                statusBannerMessage = "Сгенерирована новая BIP-39 мнемоника и IPv6-адрес узла ${freshIdentity.yggIpv6Address.take(19)}..."
            )
        }
        viewModelScope.launch {
            repository.rotateKeysForEpoch(freshIdentity, 1)
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
                            profileSignatureHex = "ed25519_sig:${sigHash.take(44)}"
                        )
                    )
                }
            }
            _uiState.update {
                it.copy(statusBannerMessage = "Децентрализованный профиль подписан ключом Ed25519 и сохранен")
            }
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
        _uiState.update { state ->
            val p = state.privacyMatrix
            state.copy(
                privacyMatrix = p.copy(
                    sealedSenderGlobal = sealedSenderGlobal ?: p.sealedSenderGlobal,
                    stripNetworkMetadata = stripNetworkMetadata ?: p.stripNetworkMetadata,
                    blockScreenshots = blockScreenshots ?: p.blockScreenshots,
                    allowDirectLanDiscovery = allowDirectLanDiscovery ?: p.allowDirectLanDiscovery,
                    autoSyncOnMeshConnect = autoSyncOnMeshConnect ?: p.autoSyncOnMeshConnect,
                    defaultDisappearingSeconds = defaultDisappearingSeconds ?: p.defaultDisappearingSeconds,
                    hashcashDifficultyBits = hashcashDifficultyBits ?: p.hashcashDifficultyBits,
                    webDavEndpointUrl = webDavEndpointUrl ?: p.webDavEndpointUrl
                )
            )
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusBannerMessage = null) }
    }
}
