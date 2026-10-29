// File: app/src/main/java/com/example/viewmodel/controllers/NetworkDpiController.kt
package com.example.viewmodel.controllers

import android.app.Application
import com.example.data.YggDatabase
import com.example.data.YggPeerEntity
import com.example.data.YggRepository
import com.example.engine.DiscoveredYggPeer
import com.example.engine.YggCoverTransport
import com.example.engine.OverlayProbeOutcome
import com.example.engine.YggLivePacketLogEntry
import com.example.engine.YggNativeCoreBridge
import com.example.engine.YggNetworkFilterProbe
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
import kotlinx.coroutines.sync.Mutex
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
    private var lastWatchdogFailoverAt = 0L

    // What the current network permits, measured from this app's own sockets. Cached because
    // the answer belongs to the link, not to a dial: re-running it every 30 seconds would buy
    // nothing and would put eight more probes on a metered uplink each time.
    private var lastFilterProbeAt = 0L
    private var lastFilterReport: YggNetworkFilterProbe.Report? = null
    private var filterProbeJob: Job? = null
    private val filterProbeLock = Mutex()

    init {
        syncCoverTransport()
        startSmartWatchdog()
    }

    /**
     * Hands the anti-DPI choices to the transport layer. The mask reads them per dial, so a
     * domain saved only into the UI state would change nothing on the wire until restart.
     */
    private fun syncCoverTransport() {
        val cfg = _antiDpiConfig.value
        YggCoverTransport.enabled = cfg.coverTransportEnabled
        YggCoverTransport.whitelistMode = cfg.whitelistCdnWssEnabled
        YggCoverTransport.whitelistSni = cfg.whitelistSniDomain
    }

    /** Re-dials only the cover links, so switching the mask takes effect on the running node. */
    private suspend fun reDialCoverLinks(): String {
        val uris = repository.getAllPeersNow()
            .filter { it.isConnected }
            .map { YggNativeCoreBridge.corePeerUri(it.endpointUri, it.sniDomain) }
            .filter { it.isNotBlank() }
        val added = networkEngine.refreshCoverLinks(uris)
        return when {
            !YggCoverTransport.enabled ->
                "Маска TLS снята • cover-линков осталось: ${YggCoverTransport.activeEndpoints()}"
            added > 0 -> "Маска TLS: $added линков через стек телефона " +
                "(SNI ${YggCoverTransport.whitelistSni}, ALPN h2/http1.1)"
            else -> "Маска TLS включена, но ни один пир под неё не подошёл: нужны tls:// или " +
                "tcp://-пиры, у которых тот же узел слушает TLS на 443" +
                (YggCoverTransport.lastError?.let { " • $it" } ?: "")
        }
    }

    /**
     * Probes the mask against every saved peer and shows what the network really allows.
     *
     * The mask is only worth anything if a covered handshake completes on the current link, so
     * this handshakes to each peer with the phone's TLS stack and prints the result per peer.
     * Nothing here is estimated: a peer that does not answer is reported as refused, and a list
     * with no accepted peer means the overlay still travels over the core's own dials.
     */
    fun probeCoverMask() {
        if (_antiDpiConfig.value.isProbingRoutes) return
        setProbingRoutes(
            true,
            "Проверка маски: TLS-рукопожатие со стека телефона к каждому пиру " +
                "(SNI ${YggCoverTransport.whitelistSni}, ALPN h2/http/1.1)..."
        )
        scope.launch(Dispatchers.IO) {
            val uris = repository.getAllPeersNow()
                .map { YggNativeCoreBridge.corePeerUri(it.endpointUri, it.sniDomain) }
                .filter { it.isNotBlank() }
            val probes = YggCoverTransport.probeMask(uris)
            val accepted = probes.count { it.accepted }
            // The two facts are different and the user must not read one as the other: the
            // network letting a masked handshake through says nothing about the far node
            // finishing the overlay handshake, and vice versa.
            val liveLinks = networkEngine.coreLiveLinkCount()
            val linkSuffix = if (liveLinks >= 0) " • линков с рукопожатием: $liveLinks" else ""
            val summary = when {
                probes.isEmpty() -> "Проверять маску не на чём: список пиров пуст."
                accepted == 0 -> "Ни один пир из ${probes.size} не принял рукопожатие с SNI " +
                    "${YggCoverTransport.whitelistSni}: на 443 им отвечать некому, и оверлей " +
                    "идёт прямыми dial-ами ядра. Маска работает только там, где узел слушает TLS." +
                    linkSuffix
                else -> "Замаскированное рукопожатие приняли $accepted из ${probes.size} пиров" +
                    linkSuffix + ": " +
                    probes.filter { it.accepted }.joinToString(", ") { it.detail.substringBefore(" •") }
            }
            val lines = probes.map { "${if (it.accepted) "принят" else "отвергнут"} • ${it.detail}" }
            _antiDpiConfig.update {
                it.copy(isProbingRoutes = false, lastProbeReport = summary, coverProbeResults = lines)
            }
            withContext(Dispatchers.Main) { onBannerMessage(summary) }
        }
    }

    /**
     * Re-measures what this network permits and puts the answer on the screen.
     *
     * The button exists because the alternative is the user guessing. Without it an allow-listed
     * uplink and a night when every public node happens to be down look identical from inside the
     * app: zero links either way. With it the difference is a sentence and eight measured lines.
     */
    fun probeNetworkFilter() {
        // One measurement at a time: the watchdog can reach the same probe on its own schedule.
        if (filterProbeJob?.isActive == true) return
        filterProbeJob = scope.launch(Dispatchers.IO) {
            val report = networkFilterReport(force = true) ?: return@launch
            withContext(Dispatchers.Main) { onBannerMessage(report.summary) }
        }
    }

    /** Cached answer for the autopilot: the filter belongs to the link, not to a single dial. */
    private suspend fun networkFilterReport(force: Boolean): YggNetworkFilterProbe.Report? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = lastFilterReport
        if (!force && cached != null && now - lastFilterProbeAt < FILTER_PROBE_TTL_MS) return cached
        // Whoever is already measuring keeps measuring; a second set of reference connects
        // bought nothing and would sit on the same metered uplink twice.
        if (!filterProbeLock.tryLock()) return cached
        val report = try {
            withContext(Dispatchers.IO) { YggNetworkFilterProbe.probe() }
        } finally {
            filterProbeLock.unlock()
        }
        lastFilterProbeAt = android.os.SystemClock.elapsedRealtime()
        lastFilterReport = report
        _antiDpiConfig.update {
            it.copy(
                networkFilterSummary = report.summary,
                networkFilterEvidence = YggNetworkFilterProbe.evidenceLines(report),
                networkFilterMeasuredAtMs = report.measuredAtMs
            )
        }
        return report
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
                    // The node itself is the authority: at one confirmed link every route
                    // depends on a single flapping neighbour, whatever the stored peer flags
                    // say. The count is now peers that finished the overlay handshake, so a
                    // node holding a dozen dead sockets and shaking hands on none is finally
                    // seen as isolated - and needs a pause between spare-node hunts, otherwise
                    // this branch would hit the peer directory every 30 seconds.
                    //
                    // The pause grows when the network itself is the reason. Measured on an
                    // allow-listed uplink, none of the 28 directory nodes answering port 443
                    // could be reached at all, because the filter decides by destination
                    // address: hunting a spare there only spends the user's metered data.
                    val filter = networkFilterReport(force = false)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (filter != null && filter.verdict == YggNetworkFilterProbe.Verdict.NO_LINK) {
                        // A link that answers nothing about any address is not a link to hunt a
                        // spare node over: the directory fetch, every candidate probe and the dial
                        // would all be spent on the same route that carries nothing. The autopilot
                        // says it is standing by instead of pretending it tried, once per pause.
                        if (now - lastWatchdogFailoverAt < FILTERED_FAILOVER_COOLDOWN_MS) continue
                        lastWatchdogFailoverAt = now
                        withContext(Dispatchers.Main) {
                            onBannerMessage("Автопилот узел не ищет: ${filter.summary}")
                        }
                        continue
                    }
                    // Silence gets the same long pause a filter gets. The measurement cannot say
                    // which of the two it is, and both make a directory hunt expensive the same
                    // way: on a path that answers nothing, a candidate probe answers nothing either.
                    val cooldown = if (filter == null || filter.verdict == YggNetworkFilterProbe.Verdict.OPEN) {
                        WATCHDOG_FAILOVER_COOLDOWN_MS
                    } else {
                        FILTERED_FAILOVER_COOLDOWN_MS
                    }
                    if (now - lastWatchdogFailoverAt < cooldown) continue
                    lastWatchdogFailoverAt = now
                    autoCascadeFailover(
                        "Ядро держит лишь $liveLinks линков с рукопожатием: автопилот добавляет резервные узлы" +
                            (if (filter == null) "" else " • ${filter.verdict.labelRu}")
                    )
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
        // The shipped catalog ages: a public node taken down months ago is not a spare,
        // and under a whitelist uplink the nodes that still answer are exactly the ones a
        // stale list cannot name. Ask the directory once, here, because this path only runs
        // when the current links are already failing.
        val livePeers = withContext(Dispatchers.IO) {
            YggPeerScannerCatalog.fetchLivePublicPeersFromDirectory(timeoutMs = 4200).peers
        }
        if (livePeers.isNotEmpty()) {
            _peerScanner.update { state ->
                state.copy(
                    discoveredPeers = (livePeers + state.discoveredPeers).distinctBy { it.endpointUri }
                )
            }
        }
        val validCandidates = (candidatePeers + livePeers)
            .filter { it.endpointUri.isNotBlank() }
            .distinctBy { it.endpointUri }
            // Freshest first: this path runs precisely because the current links died, and the
            // spare most likely to still be up is a node the directory saw answering recently,
            // not one picked at random out of a list that ages.
            .sortedWith(
                compareByDescending<DiscoveredYggPeer> { it.lastSeenAtMs }
                    .thenByDescending { if (it.latencyMs > 0) 1 else 0 }
            )
            .take(5)

        for (candidate in validCandidates) {
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
                val core = networkEngine.applyPeerChangeToCores(
                    candidate.endpointUri,
                    enabled = true,
                    sniDomain = candidate.sniDomain
                )

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
        // The hunt failed, so it says so. Silence here is what made an allow-listed uplink look
        // like an app that had not tried: the banner promised spare nodes and nothing followed.
        val filter = networkFilterReport(force = false)
        val why = when {
            validCandidates.isEmpty() ->
                "каталог публичных узлов не дал ни одного кандидата (его адрес эта сеть не пропускает)"
            filter == null || filter.verdict == YggNetworkFilterProbe.Verdict.OPEN ->
                "ни один из проверенных узлов не ответил на рукопожатие Yggdrasil"
            else -> filter.summary
        }
        withContext(Dispatchers.Main) {
            onBannerMessage(
                "Автопилот: резервный узел не добавлен • проверено ${validCandidates.size} • $why"
            )
        }
    }

    private companion object {
        const val WATCHDOG_PEER_ID = "peer_watchdog_active"

        // How often the watchdog is allowed to hunt for a spare node while the core reports
        // fewer than two handshaked links.
        const val WATCHDOG_FAILOVER_COOLDOWN_MS = 300_000L

        // How long the autopilot waits between spare-node hunts once the network has been
        // measured as the thing refusing addresses.
        const val FILTERED_FAILOVER_COOLDOWN_MS = 1_800_000L

        // A cached filter measurement stays believable while the same link is up.
        const val FILTER_PROBE_TTL_MS = 900_000L

        // A scan has to finish while the user is watching the screen. The freshest nodes are
        // probed first, so the budget cuts the stale tail of the catalog rather than the live
        // head, and the summary states how many candidates were left unprobed.
        const val MAX_PEER_PROBES_PER_SCAN = 96
    }

    fun applyCensorshipPreset(preset: CensorshipPreset) {
        if (!preset.implemented) {
            onBannerMessage("Профиль «${preset.title}» не реализован в этой сборке")
            return
        }
        val current = _antiDpiConfig.value
        val updatedConfig = when (preset) {
            CensorshipPreset.RU_WHITELIST_CDN -> current.copy(
                activePreset = preset,
                whitelistCdnWssEnabled = true,
                lastProbeReport = "Применен профиль обхода Белых списков (SNI: ${current.whitelistSniDomain})"
            )
            else -> current
        }
        _antiDpiConfig.value = updatedConfig
        onBannerMessage("Применен профиль: ${preset.title}")

        scope.launch {
            applyWhitelistTransport()
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
            applyWhitelistTransport()
        }
    }

    /**
     * Persists the whitelist transport choice and hands it to the running node. The SNI a
     * peer is dialled with is baked into the link the node already opened, so saving the
     * domain into the Room rows alone left the live core sending the old ClientHello until
     * the app restarted — on a whitelist-only uplink the setting looked applied and changed
     * nothing. Dropping the link is what the node accepts as a re-dial instruction.
     *
     * Returns the gate note so the caller can keep it visible: the cover-mask line written right
     * after this pass used to replace it, and the user saw a mask report for a switch that had
     * just rewritten the dial list.
     */
    private suspend fun applyWhitelistTransport(): String {
        val cfg = _antiDpiConfig.value
        syncCoverTransport()
        val domain = cfg.whitelistSniDomain
        repository.updatePeersTransportProfile(
            whitelistMode = cfg.whitelistCdnWssEnabled,
            preferredSni = domain
        )
        val allPeers = repository.getAllPeersNow()
        val peers = allPeers.filter { it.isWhitelistCompatible }
        val dialUriOf = { peer: YggPeerEntity ->
            YggNativeCoreBridge.corePeerUri(peer.endpointUri, peer.sniDomain)
        }
        val diallable = allPeers.count { YggCoverTransport.dialsPeer(dialUriOf(it)) }
        var applied = 0
        // The node's own reason is quoted, not paraphrased: «no running node» and «this peer
        // is not in the dial list» are different facts the user has to tell apart.
        var coreReason = ""
        for (peer in peers) {
            val uri = peer.endpointUri.trim()
            if (uri.isBlank()) continue
            // yggdrasil identifies a link by scheme/host/port and ignores the SNI query, so
            // this drops exactly the peer being rewritten. A peer the user never switched on
            // answers «not configured» and stays off — this call must not enable it.
            val removed = networkEngine.applyPeerChangeToCores(uri, enabled = false)
            if (removed.applied == 0) {
                if (coreReason.isBlank()) coreReason = removed.errorDetail.orEmpty()
                continue
            }
            val added = networkEngine.applyPeerChangeToCores(uri, enabled = true, sniDomain = domain)
            if (added.applied > 0) {
                applied++
            } else if (coreReason.isBlank()) {
                coreReason = added.errorDetail.orEmpty()
            }
        }
        // Switching the profile has to move the live dial list too, not only the next boot.
        // The node's own dial list is the source of truth here: the probe flag in the database
        // says which peers answered a TCP scan, and on an allow-listed uplink that is nobody -
        // a pass keyed on it parked and restored zero peers while the node kept dialling the
        // cleartext endpoints the profile was switched on to stop.
        var parked = 0
        var restored = 0
        var masked = 0
        val dialled = networkEngine.configuredPeerDials()
        for (peer in allPeers) {
            val uri = peer.endpointUri.trim()
            if (uri.isBlank()) continue
            val dialUri = YggNativeCoreBridge.corePeerUri(uri, peer.sniDomain)
            val rawKey = dialUri.substringBefore("?").trim().lowercase()
            val coverKey = YggCoverTransport.coveredUri(dialUri)
                ?.substringBefore("?")?.trim()?.lowercase()
            val heldByCore = dialled.contains(rawKey) || (coverKey != null && dialled.contains(coverKey))
            val allowed = YggCoverTransport.dialsPeer(dialUri)
            // A peer the mask now carries onto 443 is not left on its old dial: the node would
            // keep repeating the cleartext handshake the filter drops next to the covered one.
            val rawDialLeft = allowed && YggCoverTransport.maskReplacesRawDial(dialUri) &&
                dialled.contains(rawKey)
            when {
                !allowed && heldByCore -> {
                    if (networkEngine.applyPeerChangeToCores(uri, enabled = false).applied > 0) parked++
                }
                allowed && !heldByCore -> {
                    if (networkEngine.applyPeerChangeToCores(
                            uri, enabled = true, sniDomain = peer.sniDomain
                        ).applied > 0
                    ) restored++
                }
                rawDialLeft -> {
                    // Remove-then-add is what replaces the dial; the core answers the new link alone.
                    networkEngine.applyPeerChangeToCores(uri, enabled = false)
                    if (networkEngine.applyPeerChangeToCores(
                            uri, enabled = true, sniDomain = peer.sniDomain
                        ).applied > 0
                    ) masked++
                }
                // A peer that stays diallable and is already dialled is untouched: re-adding it
                // here would only flap a working link.
                else -> continue
            }
        }
        val gateNote = whitelistGateNote(cfg.whitelistCdnWssEnabled, parked, restored, masked, diallable)
        _antiDpiConfig.update { state ->
            state.copy(
                lastProbeReport = when {
                    applied > 0 -> "SNI: $domain • ядро переподключает $applied пиров"
                    peers.isEmpty() -> "SNI: $domain • в базе нет пиров с поддержкой белых списков"
                    else -> "SNI: $domain • живой линк с новым SNI не поднят" +
                        if (coreReason.isBlank()) "" else ": $coreReason"
                } + gateNote
            )
        }
        // The profile pass has to be visible even when no SNI re-dial happened: peers taken off
        // the dial list change what the node can reach, and silence there reads as «ничего не
        // поменялось».
        if (applied > 0 || gateNote.isNotBlank()) {
            withContext(Dispatchers.Main) {
                onBannerMessage(
                    when {
                        applied > 0 -> "Ядро переподключает $applied пиров с SNI $domain" + gateNote
                        else -> "Режим белых списков: список обзвона ядра обновлён" + gateNote
                    }
                )
            }
        }
        return gateNote
    }

    /**
     * How the whitelist profile changed the dial list, in the node's own numbers. When nothing
     * survives the profile the user has to see that before the overlay silently stops routing.
     */
    private fun whitelistGateNote(
        whitelistMode: Boolean,
        parked: Int,
        restored: Int,
        masked: Int,
        diallablePeers: Int
    ): String {
        // Every number the pass changed is quoted: a profile switch that parked peers and moved
        // others onto the mask is two facts, and showing only the first hid half of the dial list.
        val parts = buildList {
            if (whitelistMode && parked > 0) {
                add("$parked пиров сняты с обзвона: до них нет пути через 443" +
                    if (diallablePeers == 0) "; не осталось ни одного — оверлей поднимется без линков" else "")
            }
            if (whitelistMode && diallablePeers == 0 && parked == 0) {
                add("ни один сохранённый пир не проходит под белые списки: обзванивать нечего")
            }
            if (whitelistMode && masked > 0) {
                add("$masked пиров переведены на 443: их открытый диал ядро больше не набирает")
            }
            if (!whitelistMode && restored > 0) add("$restored пиров возвращены в список обзвона")
        }
        return if (parts.isEmpty()) "" else " • " + parts.joinToString(" • ")
    }


    fun toggleAntiDpiFlag(flagName: String) {
        _antiDpiConfig.update { state ->
            when (flagName) {
                "WHITELIST_WSS" -> state.copy(whitelistCdnWssEnabled = !state.whitelistCdnWssEnabled)
                "COVER_TLS" -> state.copy(coverTransportEnabled = !state.coverTransportEnabled)
                else -> state
            }
        }
        if (flagName == "COVER_TLS" || flagName == "WHITELIST_WSS") {
            syncCoverTransport()
            scope.launch {
                // The whitelist profile also decides which peers may be dialled at all, so
                // switching it has to reach the live dial list, not only the cover links.
                val gateNote = if (flagName == "WHITELIST_WSS") {
                    applyWhitelistTransport()
                } else ""
                val report = reDialCoverLinks() + gateNote
                _antiDpiConfig.update { it.copy(lastProbeReport = report) }
                withContext(Dispatchers.Main) { onBannerMessage(report) }
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

            val directory = withContext(Dispatchers.IO) {
                YggPeerScannerCatalog.fetchLivePublicPeersFromDirectory(timeoutMs = 4200)
            }
            val candidateCatalog = withContext(Dispatchers.IO) {
                if (directory.peers.isNotEmpty()) {
                    // Live entries first and deduplicated on the live copy: it is the one that
                    // carries a real sighting timestamp, and the same node can appear in the
                    // shipped catalog under a hostname instead of the IP it resolved to.
                    (directory.peers + _peerScanner.value.discoveredPeers)
                        .distinctBy { it.endpointUri.lowercase() }
                } else {
                    _peerScanner.value.discoveredPeers
                }
            }

            val matching = candidateCatalog.filter {
                (selectedCountry == "ALL" || it.countryCode == selectedCountry) &&
                    (!_peerScanner.value.onlyWhitelistCompatible || it.whitelistCompatible)
            }
            // The catalog is already ordered freshest-first, so the probe budget is spent on
            // the nodes another network just saw answering. Probing all three hundred would
            // take half a minute and mostly confirm what the directory already reported dead.
            val toProbe = matching.take(MAX_PEER_PROBES_PER_SCAN)

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

            val dropped = mutableListOf<String>().apply {
                if (directory.skippedDown > 0) add("не отвечает каталогу: ${directory.skippedDown}")
                if (directory.skippedStale > 0) add("не виден ${YggPeerScannerCatalog.freshWindowMs / 60000} мин: ${directory.skippedStale}")
                if (directory.skippedOldProtocol > 0) add("старый протокол: ${directory.skippedOldProtocol}")
            }
            val directoryPart = when {
                directory.errorDetail != null ->
                    "Каталог недоступен (${directory.errorDetail}), проверен упакованный список"
                directory.peers.isEmpty() ->
                    "Каталог вернул 0 свежих узлов из ${directory.reportedTotal}"
                else ->
                    "Каталог: ${directory.reportedTotal} узлов, свежих ${directory.usableCount}" +
                        if (dropped.isEmpty()) "" else " • отброшено ${dropped.joinToString(" • ")}"
            }
            val probePart = if (reachableInSelection.isNotEmpty()) {
                "ответили ${reachableInSelection.size} из ${toProbe.size} проверенных" +
                    if (matching.size > toProbe.size) " (в очереди ещё ${matching.size - toProbe.size})" else ""
            } else {
                "из ${toProbe.size} проверенных не ответил ни один"
            }
            val summary = "$directoryPart • $probePart" +
                (if (bestRtt != null) " • лучший пинг $bestRtt мс" else "")

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
            val core = networkEngine.applyPeerChangeToCores(
                scanned.endpointUri,
                enabled = true,
                sniDomain = scanned.sniDomain
            )
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
                    regionLabel = "${scanned.countryFlag} ${scanned.countryCode} • ${scanned.cityRu}",
                    latencyMs = scanned.latencyMs,
                    isConnected = true,
                    whitelistCompatible = scanned.whitelistCompatible
                )
                val core = networkEngine.applyPeerChangeToCores(
                    scanned.endpointUri,
                    enabled = true,
                    sniDomain = scanned.sniDomain
                )
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
