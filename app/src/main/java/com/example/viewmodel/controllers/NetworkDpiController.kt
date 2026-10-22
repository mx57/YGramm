// File: app/src/main/java/com/example/viewmodel/controllers/NetworkDpiController.kt
package com.example.viewmodel.controllers

import android.app.Application
import com.example.data.YggDatabase
import com.example.data.YggPeerEntity
import com.example.data.YggRepository
import com.example.engine.DiscoveredYggPeer
import com.example.engine.OverlayProbeOutcome
import com.example.engine.YggLivePacketLogEntry
import com.example.engine.YggNativeCoreBridge
import com.example.engine.YggPeerScannerCatalog
import com.example.engine.YggTwinP2PBridge
import com.example.viewmodel.AntiDpiConfigState
import com.example.viewmodel.CensorshipPreset
import com.example.viewmodel.PeerScannerState
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controller responsible for network resilience, anti-DPI presets, censorship circumvention,
 * peer discovery/probing, live packet inspection, and automatic failover watchdog.
 */
class NetworkDpiController(
    private val application: Application,
    private val repository: YggRepository,
    private val networkEngine: YggTwinP2PBridge,
    private val scope: CoroutineScope,
    private val onBannerMessage: (String) -> Unit,
    private val onSyncForegroundNotification: () -> Unit
) {

    private val _antiDpiConfig = MutableStateFlow(AntiDpiConfigState())
    val antiDpiConfig: StateFlow<AntiDpiConfigState> = _antiDpiConfig.asStateFlow()

    private val _peerScanner = MutableStateFlow(PeerScannerState())
    val peerScanner: StateFlow<PeerScannerState> = _peerScanner.asStateFlow()

    private val _packetLogs = MutableStateFlow<List<YggLivePacketLogEntry>>(emptyList())
    val packetLogs: StateFlow<List<YggLivePacketLogEntry>> = _packetLogs.asStateFlow()

    private val _isSmartWatchdogEnabled = MutableStateFlow(true)
    val isSmartWatchdogEnabled: StateFlow<Boolean> = _isSmartWatchdogEnabled.asStateFlow()

    private val _isAdaptiveJitterEnabled = MutableStateFlow(true)
    val isAdaptiveJitterEnabled: StateFlow<Boolean> = _isAdaptiveJitterEnabled.asStateFlow()

    private var watchdogJob: Job? = null
    private var peerScanJob: Job? = null

    init {
        startSmartWatchdog()
    }

    /**
     * Starts background watchdog that periodically checks connected peer health
     * and triggers automatic cascade failover if active peers are blocked by TSPU/DPI.
     */
    fun startSmartWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000L) // check every 30 seconds
                if (!_isSmartWatchdogEnabled.value) continue
                if (!networkEngine.isRealNetworkAvailable() || networkEngine.isAirplaneModeActive()) continue

                val liveLinks = networkEngine.coreLiveLinkCount()
                if (liveLinks < 0) continue
                if (liveLinks < 2) {
                    // The node itself is the authority: at one public link every route
                    // depends on a single flapping neighbour, whatever the stored peer
                    // flags say.
                    autoCascadeFailover("Ядро держит лишь $liveLinks живых линков: автопилот добавляет резервные узлы...")
                    continue
                }
                val connectedPeers = repository.getAllPeersNow().filter { it.isConnected }
                if (connectedPeers.isNotEmpty()) {
                    // Check if current connected peers are failing
                    val unreachableCount = connectedPeers.count { peer ->
                        val probe = networkEngine.probeRealYggdrasilPeer(
                            endpointUri = peer.endpointUri,
                            sniOverride = peer.sniDomain,
                            timeoutMs = 2500
                        )
                        !probe.reachable
                    }
                    if (unreachableCount == connectedPeers.size) {
                        // All connected peers became unreachable (likely DPI block or node down)
                        autoCascadeFailover("Обнаружена блокировка ТСПУ/сбой узлов: автопилот переключает пир...")
                    }
                }
            }
        }
    }

    fun setSmartWatchdogEnabled(enabled: Boolean) {
        _isSmartWatchdogEnabled.value = enabled
        if (enabled) {
            startSmartWatchdog()
            onBannerMessage("Автопилот устойчивости (Smart Watchdog) включен")
        } else {
            watchdogJob?.cancel()
            onBannerMessage("Автопилот устойчивости выключен")
        }
    }

    fun setAdaptiveJitterEnabled(enabled: Boolean) {
        _isAdaptiveJitterEnabled.value = enabled
        onBannerMessage(
            if (enabled) "Адаптивный джиттер включен (защита от сигнатурного тайминг-анализа ТСПУ)"
            else "Адаптивный джиттер выключен"
        )
    }

    /**
     * Computes random micro-delay to cloak traffic timings if adaptive jitter is active.
     */
    suspend fun applyAdaptiveJitterDelay() {
        if (_isAdaptiveJitterEnabled.value) {
            val delayMs = Random.nextLong(15L, 45L)
            delay(delayMs)
        }
    }

    private suspend fun autoCascadeFailover(reason: String) {
        withContext(Dispatchers.Main) {
            onBannerMessage(reason)
        }
        val candidatePeers = _peerScanner.value.discoveredPeers
        val validCandidates = candidatePeers.filter { it.endpointUri.isNotBlank() }

        for (candidate in validCandidates.shuffled().take(5)) {
            val probe = networkEngine.probeRealYggdrasilPeer(
                endpointUri = candidate.endpointUri,
                sniOverride = candidate.sniDomain,
                timeoutMs = 3000
            )
            if (probe.reachable) {
                // One rotating spare, never a growing pile: every peer the node cannot
                // shake hands with keeps its own dial loop, and a long list of dead ones
                // is what left the node reporting 0 links out of 24.
                val previousUri = repository.getAllPeersNow()
                    .firstOrNull { it.peerId == WATCHDOG_PEER_ID }
                    ?.endpointUri
                    ?.trim()
                repository.addOrUpdatePeer(
                    peerId = WATCHDOG_PEER_ID,
                    endpointUri = candidate.endpointUri,
                    transportType = candidate.transportType,
                    sniDomain = candidate.sniDomain,
                    utlsFingerprint = candidate.utlsFingerprint,
                    regionLabel = "${candidate.countryFlag} ${candidate.countryCode} • ${candidate.cityRu} [Автопилот]",
                    latencyMs = probe.rttMs,
                    isConnected = true,
                    whitelistCompatible = candidate.whitelistCompatible
                )
                if (previousUri != null && previousUri != candidate.endpointUri) {
                    networkEngine.applyPeerChangeToCores(previousUri, enabled = false)
                }
                // A reachable port is not a Yggdrasil link: hand the peer to the live node
                // and only report recovery once the node accepted it.
                val core = networkEngine.applyPeerChangeToCores(candidate.endpointUri, enabled = true)
                if (core.applied > 0) {
                    withContext(Dispatchers.Main) {
                        onBannerMessage(
                            "Автопилот добавил ${candidate.countryFlag} ${candidate.cityRu} в ноду " +
                                "(${probe.rttMs} мс • линков ядра: ${core.liveLinks} из ${core.totalLinks})"
                        )
                        onSyncForegroundNotification()
                    }
                    return
                }
            }
        }
    }

    private companion object {
        const val WATCHDOG_PEER_ID = "peer_watchdog_active"
    }

    fun applyCensorshipPreset(preset: CensorshipPreset) {
        val current = _antiDpiConfig.value
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
        _antiDpiConfig.value = updatedConfig
        onBannerMessage("Применен профиль: ${preset.title}")

        scope.launch {
            repository.updatePeersTransportProfile(
                whitelistMode = updatedConfig.whitelistCdnWssEnabled,
                preferredSni = updatedConfig.whitelistSniDomain,
                utlsProfile = updatedConfig.utlsProfile
            )
        }
    }

    fun selectWhitelistSniDomain(domain: String) {
        _antiDpiConfig.update { state ->
            state.copy(
                whitelistSniDomain = domain,
                lastProbeReport = "Установлен SNI-домен: $domain"
            )
        }
        scope.launch {
            val cfg = _antiDpiConfig.value
            repository.updatePeersTransportProfile(
                whitelistMode = cfg.whitelistCdnWssEnabled,
                preferredSni = domain,
                utlsProfile = cfg.utlsProfile
            )
        }
    }

    fun selectUtlsFingerprint(fingerprint: String) {
        _antiDpiConfig.update { state ->
            state.copy(
                utlsProfile = fingerprint,
                lastProbeReport = "Установлен профиль uTLS: $fingerprint"
            )
        }
        scope.launch {
            val cfg = _antiDpiConfig.value
            repository.updatePeersTransportProfile(
                whitelistMode = cfg.whitelistCdnWssEnabled,
                preferredSni = cfg.whitelistSniDomain,
                utlsProfile = fingerprint
            )
        }
    }

    fun toggleAntiDpiFlag(flagName: String) {
        _antiDpiConfig.update { state ->
            when (flagName) {
                "WHITELIST_WSS" -> state.copy(whitelistCdnWssEnabled = !state.whitelistCdnWssEnabled)
                "SHADOWTLS" -> state.copy(shadowTlsV3Enabled = !state.shadowTlsV3Enabled)
                "VLESS" -> state.copy(vlessRealityEnabled = !state.vlessRealityEnabled)
                "QUIC_PAD" -> state.copy(quicPaddingEnabled = !state.quicPaddingEnabled)
                "DHT_BEP44" -> state.copy(dhtBep44BootstrapEnabled = !state.dhtBep44BootstrapEnabled)
                else -> state
            }
        }
    }

    fun updateScannerSearchQuery(query: String) {
        _peerScanner.update { it.copy(searchQuery = query) }
    }

    fun updateScannerSortMode(sortMode: String) {
        _peerScanner.update { it.copy(sortMode = sortMode) }
    }

    fun selectScanCountry(countryCode: String) {
        _peerScanner.update { it.copy(selectedCountryCode = countryCode) }
    }

    fun toggleScannerWhitelistOnly() {
        _peerScanner.update { it.copy(onlyWhitelistCompatible = !it.onlyWhitelistCompatible) }
    }

    fun addPacketLog(entry: YggLivePacketLogEntry) {
        _packetLogs.update { (listOf(entry) + it).take(80) }
    }

    fun clearPacketLogs() {
        _packetLogs.value = emptyList()
    }

    fun scanYggdrasilPeersByCountry() {
        if (_peerScanner.value.isScanning) return
        peerScanJob = scope.launch {
            if (!networkEngine.isRealNetworkAvailable() || networkEngine.isAirplaneModeActive()) {
                val reason = if (networkEngine.isAirplaneModeActive()) "Активен Авиарежим" else "Отсутствует подключение к Интернету"
                _peerScanner.update { state ->
                    val resetPeers = state.discoveredPeers.map { it.copy(latencyMs = -1) }
                    state.copy(
                        isScanning = false,
                        discoveredPeers = resetPeers,
                        lastScanSummary = "Сканирование невозможно: $reason"
                    )
                }
                onBannerMessage("Ошибка сканирования пиров: $reason")
                return@launch
            }

            val selectedCountry = _peerScanner.value.selectedCountryCode
            _peerScanner.update { state ->
                state.copy(
                    isScanning = true,
                    lastScanSummary = "Загрузка каталога publicnodes.json и реальный TCP/TLS замер пинга..."
                )
            }

            val candidateCatalog = withContext(Dispatchers.IO) {
                val liveFetched = try {
                    YggPeerScannerCatalog.fetchLivePublicPeersFromDirectory(timeoutMs = 4200)
                } catch (_: Exception) {
                    emptyList()
                }
                if (liveFetched.isNotEmpty()) {
                    (_peerScanner.value.discoveredPeers + liveFetched).distinctBy { it.endpointUri }
                } else {
                    _peerScanner.value.discoveredPeers
                }
            }

            val toProbe = candidateCatalog.filter {
                (selectedCountry == "ALL" || it.countryCode == selectedCountry) &&
                    (!_peerScanner.value.onlyWhitelistCompatible || it.whitelistCompatible)
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
                    (!_peerScanner.value.onlyWhitelistCompatible || it.whitelistCompatible) &&
                    it.latencyMs > 0
            }
            val bestRtt = reachableInSelection.minOfOrNull { it.latencyMs }

            val summary = if (reachableInSelection.isNotEmpty()) {
                "Живой замер завершен: ответили ${reachableInSelection.size} из ${toProbe.size} узлов • Лучший пинг: $bestRtt мс"
            } else {
                "Проверено ${toProbe.size} узлов: ни один не ответил на прямое TCP/TLS соединение"
            }

            _peerScanner.update { state ->
                state.copy(
                    isScanning = false,
                    discoveredPeers = updatedCatalog,
                    lastScanSummary = summary
                )
            }
            onBannerMessage(summary)
            onSyncForegroundNotification()
        }
    }

    fun connectScannedPeer(scanned: DiscoveredYggPeer) {
        scope.launch {
            if (!networkEngine.isRealNetworkAvailable() || networkEngine.isAirplaneModeActive()) {
                onBannerMessage("Невозможно подключить пир: устройство в авиарежиме или без сети")
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
            _peerScanner.update { state ->
                val updatedDiscovered = state.discoveredPeers.map {
                    if (it.endpointUri == scanned.endpointUri) {
                        it.copy(latencyMs = if (probe.reachable) probe.rttMs else -1)
                    } else it
                }
                state.copy(discoveredPeers = updatedDiscovered)
            }
            val core = networkEngine.applyPeerChangeToCores(scanned.endpointUri, enabled = true)
            onBannerMessage(
                when {
                    core.applied == 0 ->
                        "Пир ${scanned.cityRu} сохранён, но живая нода его не приняла: " +
                            (core.errorDetail ?: "нет активной ноды Yggdrasil")
                    probe.reachable ->
                        "Пир ${scanned.countryFlag} ${scanned.cityRu} добавлен в ноду (RTT: ${probe.rttMs} мс • " +
                            "линков ядра: ${core.liveLinks} из ${core.totalLinks})"
                    else ->
                        "Пир ${scanned.cityRu} добавлен в маршруты, но сейчас не отвечает " +
                            "(${probe.errorDetail ?: "Timeout"}) • линков ядра: ${core.liveLinks} из ${core.totalLinks}"
                }
            )
            onSyncForegroundNotification()
        }
    }

    fun connectTopFastestPeersInSelectedCountry() {
        scope.launch {
            // The region chips fire scan and connect back to back. Reading the catalog
            // before the scan finishes would connect the previous run's peers — or none.
            peerScanJob?.join()
            val scanner = _peerScanner.value
            val matching = scanner.discoveredPeers
                .filter {
                    (scanner.selectedCountryCode == "ALL" || it.countryCode == scanner.selectedCountryCode) &&
                        (!scanner.onlyWhitelistCompatible || it.whitelistCompatible) &&
                        it.latencyMs > 0
                }
                .sortedBy { it.latencyMs }
                .take(3)

            if (matching.isEmpty()) {
                onBannerMessage("Сначала нажмите «Сканировать пинг», чтобы найти реально доступные узлы")
                return@launch
            }
            var applied = 0
            var live = 0
            var total = 0
            var failure: String? = null
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
                val core = networkEngine.applyPeerChangeToCores(scanned.endpointUri, enabled = true)
                applied += core.applied
                live = maxOf(live, core.liveLinks)
                total = maxOf(total, core.totalLinks)
                failure = core.errorDetail ?: failure
            }
            onBannerMessage(
                if (applied == 0) {
                    "Нода не приняла пиры: ${failure ?: "нет активной ноды Yggdrasil"}"
                } else {
                    "В ноду добавлено $applied из ${matching.size} быстрых пиров " +
                        "(лучший: ${matching.first().cityRu}, ${matching.first().latencyMs} мс • " +
                        "линков ядра: $live из $total)"
                }
            )
            onSyncForegroundNotification()
        }
    }

    fun setProbingRoutes(probing: Boolean, report: String) {
        _antiDpiConfig.update {
            it.copy(isProbingRoutes = probing, lastProbeReport = report)
        }
    }

    fun stop() {
        watchdogJob?.cancel()
    }
}
