// File: app/src/main/java/com/example/viewmodel/controllers/NetworkDpiController.kt
package com.example.viewmodel.controllers

import android.app.Application
import com.example.data.YggDatabase
import com.example.data.YggPeerEntity
import com.example.data.YggRepository
import com.example.engine.DiscoveredYggPeer
import com.example.engine.DirectoryFetch
import com.example.engine.YggCoverTransport
import com.example.engine.OverlayProbeOutcome
import com.example.engine.YggLivePacketLogEntry
import com.example.engine.YggNativeCoreBridge
import com.example.engine.YggNetworkFilterProbe
import com.example.engine.YggPeerDirectoryCache
import com.example.engine.YggPeerScannerCatalog
import com.example.engine.YggTwinP2PBridge
import com.example.viewmodel.AntiDpiConfigState
import com.example.viewmodel.AutopilotState
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

    private val _autopilot = MutableStateFlow(AutopilotState())
    val autopilot: StateFlow<AutopilotState> = _autopilot.asStateFlow()

    private val _isAdaptiveJitterEnabled = MutableStateFlow(true)
    val isAdaptiveJitterEnabled: StateFlow<Boolean> = _isAdaptiveJitterEnabled.asStateFlow()

    private var watchdogJob: Job? = null
    private var peerScanJob: Job? = null
    private var lastWatchdogFailoverAt = 0L
    private var lastDirectoryPoolAt = 0L

    // What the autopilot dials from when a link dies. Kept apart from the scanner screen's list:
    // that one is what the user measured with their own finger, and quietly rewriting it on a
    // timer would make the ping column mean nothing. The shipped catalog seeds it so the first
    // hunt after install still has material.
    private var directoryPool: List<DiscoveredYggPeer> = YggPeerScannerCatalog.initialCatalog
    private var directoryPoolNote = "список из сборки, каталог ещё не читался"

    // When each spare was first handed to the node, in uptime milliseconds. The peer row has no
    // such column and the grace clock only has to survive as long as the process dials it.
    private val watchdogPeerSinceMs = HashMap<String, Long>()

    // Endpoints this process watched a probe or a dial fail, keyed the way the core names them.
    // Kept only in memory: whether a node answers is a fact about the current link, and a
    // refusal from yesterday's hotspot is no reason to skip it today.
    private val autopilotRejections = HashMap<String, Long>()

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
                delay(WATCHDOG_TICK_MS)
                if (!_isSmartWatchdogEnabled.value) continue
                if (!networkEngine.isRealNetworkAvailable() || networkEngine.isAirplaneModeActive()) continue

                if (android.os.SystemClock.elapsedRealtime() - lastDirectoryPoolAt >= CANDIDATE_REFRESH_MS) {
                    refreshDirectoryCandidatePool()
                }

                val liveLinks = networkEngine.coreLiveLinkCount()
                if (liveLinks < 0) continue
                maintainWatchdogPeers(liveLinks)

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
                        maintainWatchdogPeers(
                            liveLinks,
                            blockedReason = "Обнаружена блокировка ТСПУ/сбой узлов: автопилот меняет резервные узлы"
                        )
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

    /**
     * The directory as this device can actually get it: a fresh read when the uplink lets the
     * document through, otherwise the last snapshot it did read while that is still believable.
     *
     * The cached copy is labelled (`fromCache`) rather than passed off as a live read, because
     * its sighting stamps are exactly as old as they say they are.
     */
    private suspend fun resolveDirectorySnapshot(): DirectoryFetch {
        val fetched = withContext(Dispatchers.IO) {
            YggPeerScannerCatalog.fetchLivePublicPeersFromDirectory()
        }
        return if (fetched.errorDetail == null && fetched.peers.isNotEmpty()) {
            withContext(Dispatchers.IO) { YggPeerDirectoryCache.save(application, fetched) }
            fetched
        } else {
            val cached = withContext(Dispatchers.IO) { YggPeerDirectoryCache.load(application) }
            if (cached != null && cached.snapshotAgeMs() in 0L until CACHE_TRUSTED_MS) cached else fetched
        }
    }

    /**
     * Re-reads the pool the autopilot dials from, at most once per [CANDIDATE_REFRESH_MS].
     *
     * Left to the shipped catalog, the spare-node hunt fails for a reason nobody can see: the
     * directory answers, the copy in memory aged out of it. The attempt clock moves whether or
     * not the read succeeded, so a directory this network refuses costs one timeout per pause
     * instead of one per tick.
     */
    private suspend fun refreshDirectoryCandidatePool() {
        lastDirectoryPoolAt = android.os.SystemClock.elapsedRealtime()
        val snapshot = resolveDirectorySnapshot()
        if (snapshot.peers.isNotEmpty()) {
            directoryPool = snapshot.peers
            directoryPoolNote = when {
                snapshot.fromCache -> "снимок с диска"
                else -> "${snapshot.peers.size} свежих узлов"
            }
        } else {
            directoryPoolNote = when {
                snapshot.errorDetail != null -> "каталог: ${snapshot.errorDetail}"
                else -> "каталог: 0 пригодных из ${snapshot.reportedTotal}"
            }
        }
    }

    /**
     * Keeps the node holding up to [LIVE_DIAL_TARGET] spare dials the core actually handshook with.
     *
     * The running node is the only authority here: [YggTwinP2PBridge.coreConfirmedEndpoints]
     * names the peers that finished an overlay handshake, while a saved flag only says a port was
     * open once and an accepted socket says less than nothing. That is what makes this safe to
     * automate - a spare the node never accepted is removed by name, and a peer the node answers
     * is never touched, whatever else this pass decides.
     *
     * The two halves cost different things, so they run on different clocks. Reading the mesh
     * state and dropping a dead spare opens no sockets and runs every tick. Hunting replacements
     * is throttled: measured on an allow-listed uplink, none of the directory nodes answering
     * port 443 could be reached at all, because the filter decides by destination address, and
     * every probe on such a route is metered data spent on a path that carries nothing.
     */
    private suspend fun maintainWatchdogPeers(
        liveLinks: Int,
        blockedReason: String? = null
    ) {
        val confirmed = networkEngine.coreConfirmedEndpoints()
        val now = android.os.SystemClock.elapsedRealtime()
        val managed = repository.getAllPeersNow().filter { it.peerId in WATCHDOG_MANAGED_IDS }

        val pruned = mutableListOf<String>()
        val decision = classifyWatchdogSpares(
            managed = managed,
            answered = { coreAnswers(it, confirmed) },
            sinceMs = watchdogPeerSinceMs,
            nowMs = now,
            graceMs = SPARE_CONFIRM_GRACE_MS
        )
        watchdogPeerSinceMs.clear()
        watchdogPeerSinceMs.putAll(decision.stampedSinceMs)
        for (peerId in decision.prunePeerIds) {
            val peer = managed.first { it.peerId == peerId }
            repository.deletePeer(peerId)
            networkEngine.applyPeerChangeToCores(peer.endpointUri, enabled = false)
            pruned.add(peerId)
        }

        val heldSlots = managed.map { it.peerId }.toSet() - pruned.toSet()
        val freeSlots = WATCHDOG_PEER_IDS.filter { it !in heldSlots }
        if (freeSlots.isEmpty()) {
            if (pruned.isNotEmpty()) {
                reportAutopilot("Автопилот убрал резервные узлы без рукопожатия: ${pruned.size}")
            } else {
                // Full reserve with nothing to drop used to leave the card blank, and a blank card
                // reads as a dead watchdog. Say what is actually held, without a banner.
                val answered = managed.count { coreAnswers(it, confirmed) }
                reportAutopilot(
                    "Автопилот: резерв полон • узлов в резерве ${managed.size} • с рукопожатием ядра $answered",
                    banner = false
                )
            }
            return
        }

        // A node already holding the target number of handshaked links is not a reason to spend
        // probes: the reserve is rebuilt when one of its own dials died or when the links dropped
        // below the target, so an idle healthy node stays idle instead of circling the directory
        // every few minutes on the user's metered uplink.
        if (liveLinks >= LIVE_DIAL_TARGET && pruned.isEmpty() && blockedReason == null) {
            // Silent, but not invisible: the routes card needs to say the autopilot looked and
            // found nothing to do, otherwise "no banner" reads as "the watchdog is dead".
            reportAutopilot(
                "Автопилот: резерв трогать нечего • линков с рукопожатием $liveLinks (цель $LIVE_DIAL_TARGET) • " +
                    "слотов свободно ${freeSlots.size}",
                banner = false
            )
            return
        }

        val filter = networkFilterReport(force = false)
        if (filter != null && filter.verdict == YggNetworkFilterProbe.Verdict.NO_LINK) {
            // A link that answers nothing about any address is not a link to hunt a spare node
            // over: the directory fetch, every candidate probe and the dial would all be spent
            // on the same route that carries nothing. The autopilot says it is standing by
            // instead of pretending it tried, once per pause.
            if (now - lastWatchdogFailoverAt < FILTERED_FAILOVER_COOLDOWN_MS) return
            lastWatchdogFailoverAt = now
            reportAutopilot("Автопилот узел не ищет: ${filter.summary}")
            return
        }
        // Silence gets the same long pause a filter gets. The measurement cannot say which of the
        // two it is, and both make a directory hunt expensive the same way.
        val cooldown = if (filter == null || filter.verdict == YggNetworkFilterProbe.Verdict.OPEN) {
            WATCHDOG_FAILOVER_COOLDOWN_MS
        } else {
            FILTERED_FAILOVER_COOLDOWN_MS
        }
        if (now - lastWatchdogFailoverAt < cooldown) return
        lastWatchdogFailoverAt = now

        if (blockedReason != null) {
            withContext(Dispatchers.Main) { onBannerMessage(blockedReason) }
        }

        val dialled = repository.getAllPeersNow().mapTo(HashSet()) { normalizedEndpoint(it.endpointUri) }
        val whitelistProfile = _antiDpiConfig.value.whitelistCdnWssEnabled
        // The 443-only pool is a bet about the uplink, not a law. When this device's own
        // measurement says nothing is filtered, parking every other port costs links and buys
        // protection that is not needed on this route; the peers that still fit the whitelist are
        // tried first either way, so the moment the filter comes back the reserve is built from
        // the nodes that survive it.
        val linkMeasuredOpen = filter?.verdict == YggNetworkFilterProbe.Verdict.OPEN
        val poolAll = (_peerScanner.value.discoveredPeers + directoryPool)
            .filter { it.endpointUri.isNotBlank() && normalizedEndpoint(it.endpointUri) !in dialled }
            .filter { !whitelistProfile || linkMeasuredOpen || it.whitelistCompatible }
            .distinctBy { normalizedEndpoint(it.endpointUri) }
        // Freshness is not reachability. Sorting by it alone sent every pass to the same fifteen
        // newest nodes: each pass re-probed the same dead ports and the reserve never grew, while
        // the scan's own numbers say roughly one node in five answers from here at all.
        val rejected = liveAutopilotRejections(now)
        val needed = freeSlots.size * AUTOPILOT_PROBES_PER_SLOT
        // Restarts the circuit once the process has watched everything left in the pool fail.
        // Holding the memory past that point would leave the node with no reserve at all, which
        // is a worse answer than trying a dead node twice.
        val freshRound = poolAll.count { normalizedEndpoint(it.endpointUri) !in rejected } < needed
        if (freshRound) autopilotRejections.clear()
        val pool = if (freshRound) poolAll else poolAll.filter {
            normalizedEndpoint(it.endpointUri) !in rejected
        }
        // A quic:// candidate cannot be judged from here: the transport runs on UDP and the probe
        // handshakes TCP, so it answers nothing about whether the node is alive. Spending a spare
        // slot on a guess the node cannot confirm is how a dial list grows links nobody can verify,
        // and the bundled core does not listen on 443 for UDP anyway.
        val quicLeft = pool.count { it.transportType == "QUIC_YGGDRASIL" }
        // A peer this device already handshook with is the best spare there is: the directory's
        // freshness says another network saw the node recently, while a measured latency says the
        // handshake completed from this exact link. Freshest first only breaks ties between the
        // nodes nobody here has probed yet.
        val candidates = pool
            .filter { it.transportType != "QUIC_YGGDRASIL" }
            .sortedWith(
                compareByDescending<DiscoveredYggPeer> { if (it.latencyMs > 0) 1 else 0 }
                    .thenByDescending { if (whitelistProfile) it.whitelistCompatible else false }
                    .thenByDescending { it.lastSeenAtMs }
            )
            .take(needed)

        var added = 0
        var coreLive = liveLinks
        var coreTotal = 0
        val refusals = LinkedHashMap<String, Int>()
        // Nine failed probes are nine data points, not one reason. Reporting only the last one
        // made a hunt that met three different refusals look like the same refusal nine times.
        val note: (String, String) -> Unit = { endpoint, reason ->
            val key = shortRefusal(reason)
            refusals[key] = (refusals[key] ?: 0) + 1
            autopilotRejections[normalizedEndpoint(endpoint)] = now
        }
        var nextCandidate = 0
        for (slot in freeSlots) {
            var filled = false
            while (nextCandidate < candidates.size && !filled) {
                val candidate = candidates[nextCandidate++]
                val probe = networkEngine.probeRealYggdrasilPeer(
                    endpointUri = candidate.endpointUri,
                    sniOverride = candidate.sniDomain,
                    timeoutMs = 3000
                )
                if (!probe.reachable) {
                    note(candidate.endpointUri, probe.errorDetail ?: "порт не ответил на замер")
                    continue
                }
                // A reachable port is not a Yggdrasil link: hand the peer to the live node and
                // only count the slot as filled once the node accepted the dial.
                val core = networkEngine.applyPeerChangeToCores(
                    candidate.endpointUri,
                    enabled = true,
                    sniDomain = candidate.sniDomain
                )
                coreLive = core.liveLinks
                coreTotal = core.totalLinks
                if (core.applied == 0) {
                    note(candidate.endpointUri, core.errorDetail ?: "живая нода не приняла диал")
                    continue
                }
                repository.addOrUpdatePeer(
                    peerId = slot,
                    endpointUri = candidate.endpointUri,
                    transportType = candidate.transportType,
                    sniDomain = candidate.sniDomain,
                    regionLabel = "${candidate.countryFlag} ${candidate.countryCode} • ${candidate.cityRu} [Автопилот]",
                    latencyMs = probe.rttMs,
                    isConnected = true,
                    whitelistCompatible = candidate.whitelistCompatible
                )
                watchdogPeerSinceMs[slot] = now
                added++
                filled = true
            }
            if (!filled) break
        }

        // Worst first: the reason that cost the most slots is the one worth reading before the
        // rest, and a hunt that ran out of candidates says so through the count, not a message.
        val refusalReport = refusals.entries
            .sortedByDescending { it.value }
            .joinToString(" • ") { "${it.key} ×${it.value}" }
            .ifBlank { filter?.summary ?: "ни один узел не ответил на рукопожатие Yggdrasil" }
        val quicPart = if (quicLeft > 0) " • quic:// отложено: $quicLeft (UDP, TCP-замер его не проверяет)" else ""
        val openPart = if (whitelistProfile && linkMeasuredOpen) {
            " • замер сети: фильтра нет, брали узлы не только на 443"
        } else ""

        val outcome = when {
            added > 0 ->
                "Автопилот: резервных узлов добавлено $added • свободных слотов было ${freeSlots.size} • " +
                    "линков ядра: $coreLive из $coreTotal$openPart"
            candidates.isEmpty() ->
                "Автопилот: кандидатов нет • свободных слотов ${freeSlots.size} • " +
                    (if (whitelistProfile && !linkMeasuredOpen) "на 443 в каталоге пусто, " else "") +
                    directoryPoolNote + quicPart
            else ->
                "Автопилот: резервный узел не добавлен • проверено ${candidates.size} • $refusalReport$quicPart$openPart"
        }
        reportAutopilot(outcome)
        if (added > 0) withContext(Dispatchers.Main) { onSyncForegroundNotification() }
    }

    /**
     * Writes what one autopilot pass decided into the state the routes card reads, and shows it
     * as a banner unless the pass was the routine "nothing to do" one.
     *
     * The banner alone was not enough: it fades in seconds, while the question it answers - why
     * the node still holds one link - gets asked minutes later.
     */
    private suspend fun reportAutopilot(summary: String, banner: Boolean = true) {
        _autopilot.update {
            it.copy(
                lastPassSummary = summary,
                lastPassAtMs = System.currentTimeMillis(),
                poolSize = directoryPool.size,
                poolNote = directoryPoolNote
            )
        }
        if (banner) withContext(Dispatchers.Main) { onBannerMessage(summary) }
    }

    /** The URI as the core names it in its peer list, so a dial and a confirmation match. */
    private fun normalizedEndpoint(endpointUri: String): String =
        endpointUri.substringBefore('?').trim().lowercase()

    /**
     * This process's failed endpoints that are still too recent to hand a spare slot to again,
     * dropping the ones that have aged out on the way.
     */
    private fun liveAutopilotRejections(now: Long): Set<String> {
        if (autopilotRejections.isNotEmpty()) {
            autopilotRejections.entries.removeAll { now - it.value >= AUTOPILOT_REJECTION_TTL_MS }
        }
        return autopilotRejections.keys.toSet()
    }

    /**
     * A bucket name for one refusal, so ninety probes against the same wall read as "×90".
     *
     * A Java exception names itself before the colon and that name is the useful part; anything
     * else (a scheme the probe refuses to test, a socket message) is kept as its first line,
     * shortened to what still fits a banner.
     */
    private fun shortRefusal(reason: String): String {
        val firstLine = reason.lineSequence().first().trim()
        val head = firstLine.substringBefore(':').trim().substringAfterLast('.')
        if (head.length !in 4..60 || !head.first().isUpperCase()) return firstLine.take(60)
        // A TLS refusal says which alert the far side sent, and that is the difference between a
        // node that is not there and a node that objects to how we greet it. The bare class name
        // throws that away and the hunt looks random, while the socket address BoringSSL writes
        // into the message would split one refusal into a bucket per connection.
        val detail = if (head.contains("SSL", ignoreCase = true)) sslDetail(reason) else ""
        return if (detail.isBlank()) head else "$head: $detail"
    }

    /** The named BoringSSL failure, searched across the whole message: the alert sits on its own line. */
    private fun sslDetail(reason: String): String {
        val alert = Regex("OPENSSL_internal:([A-Z0-9_]+)").find(reason)?.groupValues?.get(1)
        if (alert != null) return alert
        val firstLine = reason.lineSequence().first().trim()
        val cleaned = firstLine.replace(Regex("ssl=0x[0-9a-fA-F]+"), "ssl").trim()
        return cleaned.substringAfter(':', "").trim().take(52)
    }

    /**
     * Whether the running node reports a finished overlay handshake for this peer.
     *
     * Both of its routes count. Under the TLS mask the core dials a local endpoint whose handshake
     * comes from the phone's stack, so a peer the node answers through that loopback is a working
     * link and not a spare to remove - judging it by its raw URI alone would drop the exact peers
     * the mask was switched on to reach.
     */
    private fun coreAnswers(peer: YggPeerEntity, confirmed: Map<String, Int>): Boolean {
        val uri = peer.endpointUri.trim()
        if (uri.isBlank()) return false
        if (normalizedEndpoint(uri) in confirmed) return true
        val coverKey = YggCoverTransport.coveredUri(YggNativeCoreBridge.corePeerUri(uri, peer.sniDomain))
            ?.substringBefore('?')?.trim()?.lowercase()
        return coverKey != null && coverKey in confirmed
    }

    private companion object {
        // The autopilot's own peer slots. Named by slot, not by node, so a spare that died is
        // replaced in place instead of leaving a pile of one-shot peers in the database - the
        // pile is what left a node reporting 0 links out of 24 dials.
        val WATCHDOG_PEER_IDS = listOf("peer_watchdog_1", "peer_watchdog_2", "peer_watchdog_3")

        // A spare under the id this build used before the slots existed. It is still managed -
        // dropped when the node does not answer it - but never refilled.
        const val LEGACY_WATCHDOG_PEER_ID = "peer_watchdog_active"
        val WATCHDOG_MANAGED_IDS: Set<String> = WATCHDOG_PEER_IDS.toSet() + LEGACY_WATCHDOG_PEER_ID

        // How often the watchdog looks at the node.
        const val WATCHDOG_TICK_MS = 30_000L

        // Spare dials the autopilot keeps standing. Below two handshaked links every route runs
        // through a single flapping neighbour; three gives the overlay a second path to learn
        // while the first one is being re-dialled.
        const val LIVE_DIAL_TARGET = 3

        // How long a spare may stay unanswered before the autopilot stops paying for its dial
        // loop. Ten ticks is generous: the core dials on its own backoff and a node that wants
        // the link takes the first two tries.
        const val SPARE_CONFIRM_GRACE_MS = 300_000L

        // Candidates probed per free slot in one hunt. Sized so one pass cuts a fifth of the
        // directory out of the circuit: the budget is time-bounded by the timeout, and a hunt
        // that covers 220 nodes only every hour is a node that spends the hour with one link.
        const val AUTOPILOT_PROBES_PER_SLOT = 10

        // How long a node that failed here stays skipped by the hunt. Long enough that a pass
        // moves on to new addresses instead of re-probing the same dead ports every five minutes,
        // short enough that a node which came back is reachable again inside a working day.
        const val AUTOPILOT_REJECTION_TTL_MS = 21_600_000L

        // How often the autopilot re-reads the public directory. The compressed document is a
        // few kilobytes and the publisher regenerates it hourly, so this is a floor on cost,
        // not a freshness claim.
        const val CANDIDATE_REFRESH_MS = 900_000L

        // A snapshot read by this device stays usable material this long after the directory
        // itself stopped answering - longer than the publisher's own regeneration period, so
        // one stalled read is not a reason to fall back on the shipped catalog.
        const val CACHE_TRUSTED_MS = 3 * 3_600_000L

        // How often the watchdog is allowed to hunt for a spare node while the core reports
        // fewer handshaked links than the target.
        const val WATCHDOG_FAILOVER_COOLDOWN_MS = 300_000L

        // How long the autopilot waits between spare-node hunts once the network has been
        // measured as the thing refusing addresses.
        const val FILTERED_FAILOVER_COOLDOWN_MS = 1_800_000L

        // A cached filter measurement stays believable while the same link is up.
        const val FILTER_PROBE_TTL_MS = 900_000L

        // A scan has to finish while the user is watching the screen. The freshest nodes are
        // probed first, so the budget cuts the stale tail of the catalog rather than the live
        // head, and the summary states how many candidates were left unprobed. At the batch width
        // below the whole budget is six bursts, which is the worst case on a link that answers
        // nothing at all; nodes that answer usually reply in under a fifth of the timeout.
        const val MAX_PEER_PROBES_PER_SCAN = 160
        const val PROBE_BATCH_SIZE = 24
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

            val directory = resolveDirectorySnapshot()
            // A snapshot this device managed to read before is better raw material than the
            // shipped catalog, and it is the only material left when the directory's own address
            // is not on the uplink's allow-list. It is labelled as cached below rather than
            // presented as a live read, because its sighting stamps are exactly as old as they say.
            if (directory.peers.isNotEmpty()) {
                directoryPool = directory.peers
                directoryPoolNote = if (directory.fromCache) {
                    "узлы взяты с диска этим устройством раньше"
                } else {
                    "каталог прочитан этим устройством только что"
                }
                lastDirectoryPoolAt = android.os.SystemClock.elapsedRealtime()
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
                toProbe.chunked(PROBE_BATCH_SIZE).flatMap { batch ->
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
                // A ping this pass did not take is not shown as a ping. The pool now survives
                // between scans, so a node that went quiet since last week would otherwise keep
                // its green number at the top of the list forever.
                if (probe != null && probe.reachable) {
                    peer.copy(latencyMs = probe.rttMs)
                } else {
                    peer.copy(latencyMs = -1)
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
                if (directory.skippedStale > 0) add("не виден со времени снимка: ${directory.skippedStale}")
                if (directory.skippedOldProtocol > 0) add("старый протокол: ${directory.skippedOldProtocol}")
            }
            // The snapshot's own age and transport belong in the summary. Without compression the
            // document is 181 KiB and stalls on a throttled uplink, and freshness is measured
            // against the publisher's stamp — so "0 fresh nodes" can mean either a dead directory,
            // a directory that stopped being regenerated, or a read that never completed.
            val snapshotAgeMs = directory.snapshotAgeMs()
            val transportPart = when {
                directory.elapsedMs < 0L -> ""
                directory.wireBytes > 0 ->
                    " • прочитан ${directory.wireBytes} Б по сети за ${directory.elapsedMs} мс" +
                        if (directory.contentEncoding.isBlank()) "" else " (${directory.contentEncoding})"
                else -> " • прочитан за ${directory.elapsedMs} мс"
            }
            val snapshotPart = when {
                directory.fromCache -> {
                    val ageMs = directory.snapshotAgeMs()
                    " • узлы взяты с диска" +
                        if (ageMs < 0L) "" else " (снимку ${ageMs / 60000} мин)"
                }
                snapshotAgeMs < 0L -> " • снимок без штампа"
                snapshotAgeMs > YggPeerScannerCatalog.snapshotTrustedMs ->
                    " • снимку ${snapshotAgeMs / 60000} мин: каталог давно не перегенерировался"
                else -> " • снимку ${snapshotAgeMs / 60000} мин"
            }
            val whitelistPool = directory.usableCount -
                directory.peers.count { !it.whitelistCompatible }
            val directoryPart = when {
                directory.errorDetail != null ->
                    "Каталог недоступен (${directory.errorDetail}), проверен упакованный список"
                directory.peers.isEmpty() ->
                    "Каталог вернул 0 пригодных узлов из ${directory.reportedTotal}" +
                        if (dropped.isEmpty()) "" else " • отброшено ${dropped.joinToString(" • ")}"
                else ->
                    "Каталог: ${directory.reportedTotal} узлов, пригодных ${directory.usableCount} " +
                        "(на 443: $whitelistPool)" +
                        if (dropped.isEmpty()) "" else " • отброшено ${dropped.joinToString(" • ")}"
            }
            val probePart = if (reachableInSelection.isNotEmpty()) {
                "ответили ${reachableInSelection.size} из ${toProbe.size} проверенных" +
                    if (matching.size > toProbe.size) " (в очереди ещё ${matching.size - toProbe.size})" else ""
            } else {
                "из ${toProbe.size} проверенных не ответил ни один"
            }
            val summary = "$directoryPart$snapshotPart$transportPart • $probePart" +
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

/** What one watchdog tick decided about the saved spares: whose grace clock to start, and what to delete. */
internal data class WatchdogSpareDecision(
    val prunePeerIds: List<String>,
    val stampedSinceMs: Map<String, Long>
)

/**
 * Split of the managed peer rows between "keep dialing" and "drop", pure so the restart case is
 * testable - this is the only code in the app that deletes user-visible peers by itself.
 *
 * The grace clock lives in memory and the rows live in the database, so every row saved by an
 * earlier process arrives without a stamp. Deleting on that signal would empty the reserve on the
 * first tick of every start and then wait out the candidate refresh to refill it, so an unstamped
 * row starts the window like a brand new one instead of ending it.
 */
internal fun classifyWatchdogSpares(
    managed: List<YggPeerEntity>,
    answered: (YggPeerEntity) -> Boolean,
    sinceMs: Map<String, Long>,
    nowMs: Long,
    graceMs: Long
): WatchdogSpareDecision {
    val stamps = HashMap<String, Long>()
    val doomed = mutableListOf<String>()
    for (peer in managed) {
        if (answered(peer)) {
            stamps[peer.peerId] = nowMs
            continue
        }
        val since = sinceMs[peer.peerId]
        if (since == null || nowMs - since < graceMs) {
            stamps[peer.peerId] = since ?: nowMs
            continue
        }
        doomed.add(peer.peerId)
    }
    return WatchdogSpareDecision(doomed, stamps)
}
