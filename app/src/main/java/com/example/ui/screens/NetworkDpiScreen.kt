// File: app/src/main/java/com/example/ui/screens/NetworkDpiScreen.kt
package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.YggPeerEntity
import com.example.engine.DiscoveredYggPeer
import com.example.engine.YggNativeCoreBridge
import com.example.ui.components.YggSwitch
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.CensorshipPreset
import com.example.viewmodel.PeerScannerState
import com.example.viewmodel.YggGramUiState

enum class NetworkViewMode(val title: String, val icon: String) {
    SIMPLE("Умный щит", "🛡️"),
    EXPERT("Эксперт", "⚙️")
}

private enum class NetworkSubTab(val title: String) {
    ROUTES_AND_PEERS("Маршруты"),
    PEER_SCANNER("Сканер (46+)"),
    ANTI_DPI_TSPU("Обход ТСПУ")
}

@Composable
fun NetworkDpiScreen(
    uiState: YggGramUiState,
    peers: List<YggPeerEntity>,
    onSelectPreset: (CensorshipPreset) -> Unit,
    onSelectWhitelistSni: (String) -> Unit,
    onSelectUtlsProfile: (String) -> Unit,
    onToggleAntiDpiFlag: (String) -> Unit,
    onRunProbe: () -> Unit,
    onAddPeer: (String, String, String, String, String, Boolean) -> Unit,
    onTogglePeer: (YggPeerEntity) -> Unit,
    onRemovePeer: (String) -> Unit,
    onSelectScanCountry: (String) -> Unit,
    onToggleScannerWhitelistOnly: () -> Unit,
    onRunCountryPeerScan: () -> Unit,
    onConnectScannedPeer: (DiscoveredYggPeer) -> Unit,
    onConnectTopFastestInCountry: () -> Unit,
    onPingTwinNode: () -> Unit,
    onRunSendReceiveTest: () -> Unit = {},
    onOpenSelfYggdrasilTest: () -> Unit = {},
    onUpdateScannerSearch: (String) -> Unit = {},
    onUpdateScannerSort: (String) -> Unit = {},
    onImportCustomPeers: (String) -> Unit = {},
    onClearPacketLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    var viewMode by rememberSaveable { mutableStateOf(NetworkViewMode.SIMPLE) }
    var activeSubTab by rememberSaveable { mutableStateOf(NetworkSubTab.ROUTES_AND_PEERS) }
    var showAddPeerDialog by rememberSaveable { mutableStateOf(false) }
    var showBulkImportDialog by rememberSaveable { mutableStateOf(false) }
    var expandLogs by rememberSaveable { mutableStateOf(true) }
    var showAllScannedPeers by rememberSaveable { mutableStateOf(false) }

    val cfg = uiState.antiDpiConfig
    val scanner = uiState.peerScanner
    val connectedPeersCount = peers.count { it.isConnected }
    // The Go core is the authority on connectivity: the Kotlin scanner only sees
    // TCP/TLS reachability of the peer list, the core knows the live public links.
    val coreMesh = uiState.coreMesh
    val liveRoutes = if (coreMesh.available) coreMesh.liveLinks else connectedPeersCount
    val totalRoutes = if (coreMesh.available) coreMesh.totalLinks else peers.size
    val coreStatusLine = if (coreMesh.available) {
        val restartSuffix = if (coreMesh.restartCount > 0) " • перезапусков ядра: ${coreMesh.restartCount}" else ""
        "Ядро в сети: живых линков ${coreMesh.liveLinks} из ${coreMesh.totalLinks} • узлов в routing tree: ${coreMesh.knownNodes} • сессий: ${coreMesh.sessionCount}$restartSuffix"
    } else if (!com.example.engine.YggNativeCoreBridge.isNativeLibraryLoaded) {
        "Ядро не загружено: APK собран без libygggram.so — пересоберите Go-ядро из core/"
    } else {
        val restartSuffix = if (coreMesh.restartCount > 0) " • перезапусков ядра: ${coreMesh.restartCount}" else ""
        "Библиотека ядра загружена, но нода профиля ещё не запущена$restartSuffix"
    }
    val activeEndpointSet = remember(peers) { peers.map { it.endpointUri.lowercase() }.toSet() }

    val filteredScannedPeers = remember(
        scanner.discoveredPeers,
        scanner.selectedCountryCode,
        scanner.onlyWhitelistCompatible,
        scanner.searchQuery,
        scanner.sortMode
    ) {
        val base = scanner.discoveredPeers.filter { peer ->
            val countryMatch = scanner.selectedCountryCode == "ALL" || peer.countryCode == scanner.selectedCountryCode
            val whitelistMatch = !scanner.onlyWhitelistCompatible || peer.whitelistCompatible
            val q = scanner.searchQuery.trim()
            val queryMatch = q.isBlank() ||
                peer.endpointUri.contains(q, ignoreCase = true) ||
                peer.cityRu.contains(q, ignoreCase = true) ||
                peer.countryNameRu.contains(q, ignoreCase = true) ||
                peer.transportType.contains(q, ignoreCase = true)
            countryMatch && whitelistMatch && queryMatch
        }
        when (scanner.sortMode) {
            "COUNTRY" -> base.sortedWith(compareBy<DiscoveredYggPeer> { it.countryNameRu }.thenBy { it.cityRu })
            "UPTIME" -> base.sortedByDescending { it.uptimePercent }
            else -> base.sortedWith(
                compareBy<DiscoveredYggPeer> { if (it.latencyMs > 0) 0 else 1 }
                    .thenBy { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Mode Switcher Bar (Simple vs Expert)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Сеть и Обход ТСПУ",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        NetworkViewMode.entries.forEach { mode ->
                            val isSelected = viewMode == mode
                            Surface(
                                color = if (isSelected) EmeraldPrimary else Color.Transparent,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { viewMode = mode }
                                    .testTag("network_mode_${mode.name}")
                            ) {
                                Text(
                                    text = "${mode.icon} ${mode.title}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (viewMode == NetworkViewMode.SIMPLE) {
            SimpleNetworkShieldContent(
                uiState = uiState,
                peers = peers,
                scanner = scanner,
                liveRoutes = liveRoutes,
                totalRoutes = totalRoutes,
                onRunProbe = onRunProbe,
                onConnectTopFastestInCountry = onConnectTopFastestInCountry,
                onSelectScanCountry = onSelectScanCountry,
                onRunCountryPeerScan = onRunCountryPeerScan,
                onConnectScannedPeer = onConnectScannedPeer,
                onToggleAntiDpiFlag = onToggleAntiDpiFlag,
                onSelectPreset = onSelectPreset,
                onSwitchToExpertMode = { viewMode = NetworkViewMode.EXPERT }
            )
        } else {
            // Top Segmented Sub-Tab Selector
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NetworkSubTab.entries.forEach { tab ->
                    val selected = activeSubTab == tab
                    val labelText = when (tab) {
                        NetworkSubTab.ROUTES_AND_PEERS -> "Маршруты ($liveRoutes/$totalRoutes)"
                        NetworkSubTab.PEER_SCANNER -> "Сканер (${scanner.discoveredPeers.size})"
                        NetworkSubTab.ANTI_DPI_TSPU -> "Обход ТСПУ"
                    }
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { activeSubTab = tab }
                            .testTag("network_subtab_${tab.name}")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp)
                        ) {
                            Text(
                                text = labelText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Hero Node Telemetry Card is shown on Routes tab
            if (activeSubTab == NetworkSubTab.ROUTES_AND_PEERS) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(115.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_ygg_mesh_banner),
                                    contentDescription = "Топология оверлейной сети Yggdrasil",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = if (!uiState.isNetworkAvailable || uiState.isAirplaneModeOn) {
                                                MaterialTheme.colorScheme.errorContainer
                                            } else {
                                                MaterialTheme.colorScheme.primaryContainer
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = when {
                                                    uiState.isAirplaneModeOn -> "АВИАРЕЖИМ • ОФЛАЙН"
                                                    !uiState.isNetworkAvailable -> "НЕТ СЕТИ • ОФЛАЙН"
                                                    else -> "P2P ${uiState.localUdpPort} • LAN 51920"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (!uiState.isNetworkAvailable || uiState.isAirplaneModeOn) {
                                                    Color(0xFFEF4444)
                                                } else {
                                                    EmeraldPrimary
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "TX:${uiState.txPacketsCount} • RX:${uiState.rxPacketsCount} • Пиров: $liveRoutes" +
                                                    if (uiState.overlayDrops > 0) " • Потери кадров: ${uiState.overlayDrops}" else "",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = CyberTealSecondary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            text = "IPv6: ${uiState.identity.yggIpv6Address}",
                                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Подсеть: ${uiState.identity.yggSubnetPrefix} • LAN: ${uiState.localLanIp ?: "не назначен"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = coreStatusLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (!uiState.isNetworkAvailable || uiState.isAirplaneModeOn || !coreMesh.available) {
                                        Color(0xFFEF4444)
                                    } else {
                                        EmeraldPrimary
                                    }
                                )
                                Text(
                                    text = cfg.lastProbeReport,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (cfg.isProbingRoutes) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = EmeraldPrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onRunProbe,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("run_dpi_probe_button")
                                    ) {
                                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Проверить пиры", maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = onOpenSelfYggdrasilTest,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("network_test_send_receive_button")
                                    ) {
                                        Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Тест оверлея (Ygg)", maxLines = 1)
                                    }
                                }

                                if (uiState.packetLogs.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clickable { expandLogs = !expandLogs }
                                                ) {
                                                    Text(
                                                        text = "Журнал сокетов (${uiState.packetLogs.size})",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = CyberTealSecondary
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(
                                                        imageVector = if (expandLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                        contentDescription = null,
                                                        tint = CyberTealSecondary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                TextButton(
                                                    onClick = onClearPacketLogs,
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(26.dp)
                                                ) {
                                                    Text("Очистить", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            AnimatedVisibility(visible = expandLogs) {
                                                Column(
                                                    modifier = Modifier.padding(top = 6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    uiState.packetLogs.take(6).forEach { log ->
                                                        Text(
                                                            text = "[${log.timeFormatted}] ${log.direction}: ${log.summary}",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                            color = when {
                                                                log.direction.startsWith("ERR") -> Color(0xFFEF4444)
                                                                log.direction.startsWith("RX") || log.direction.startsWith("ACK") -> EmeraldPrimary
                                                                else -> AmberShieldTertiary
                                                            },
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Live state of the Go core: the only source that knows which public
                // links are up, which nodes the routing tree reached and how many
                // encrypted sessions are alive.
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, EmeraldPrimary.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Hub, contentDescription = null, tint = EmeraldPrimary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Состояние ядра Yggdrasil (0200::/7)",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = if (coreMesh.available) {
                                                "Данные читаются напрямую из Go-ядра профиля"
                                            } else if (!com.example.engine.YggNativeCoreBridge.isNativeLibraryLoaded) {
                                                "libygggram.so отсутствует в APK — показаны данные TCP/TLS-сканера"
                                            } else {
                                                "Ядро профиля не запущено — показаны данные TCP/TLS-сканера"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (coreMesh.available) EmeraldPrimary else AmberShieldTertiary
                                        )
                                    }
                                }
                                Surface(
                                    color = if (coreMesh.available && coreMesh.liveLinks > 0) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (coreMesh.available) {
                                            if (coreMesh.liveLinks > 0) "В СЕТИ" else "НЕТ ЛИНКОВ"
                                        } else if (!com.example.engine.YggNativeCoreBridge.isNativeLibraryLoaded) {
                                            "НЕТ ЯДРА"
                                        } else {
                                            "ЯДРО НЕ ЗАПУЩЕНО"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (coreMesh.available && coreMesh.liveLinks > 0) {
                                            EmeraldPrimary
                                        } else {
                                            Color(0xFFEF4444)
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CoreMeshStat(
                                    label = "Живых линков",
                                    value = if (coreMesh.available) "${coreMesh.liveLinks}/${coreMesh.totalLinks}" else "-",
                                    modifier = Modifier.weight(1f)
                                )
                                CoreMeshStat(
                                    label = "Узлов в дереве",
                                    value = if (coreMesh.available) coreMesh.knownNodes.toString() else "-",
                                    modifier = Modifier.weight(1f)
                                )
                                CoreMeshStat(
                                    label = "Сессий",
                                    value = if (coreMesh.available) coreMesh.sessionCount.toString() else "-",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Active Yggdrasil Peers & Bridges Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Таблица маршрутизации (${peers.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Активных внешних узлов: $liveRoutes",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (liveRoutes > 0) EmeraldPrimary else AmberShieldTertiary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { showBulkImportDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("open_bulk_import_peers_button")
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Импорт", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { showAddPeerDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("open_add_peer_dialog_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Свой пир", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                items(peers, key = { it.peerId }) { peer ->
                    PeerNodeCard(
                        peer = peer,
                        onCopyUri = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Yggdrasil Peer URI", peer.endpointUri))
                            Toast.makeText(context, "Скопирован пир: ${peer.endpointUri}", Toast.LENGTH_SHORT).show()
                        },
                        onToggleConnect = { onTogglePeer(peer) },
                        onRemove = { onRemovePeer(peer.peerId) }
                    )
                }
            }

            // TAB 2: PEER SCANNER (46+ Built-In Peers in 16 Countries + Live Directory + Search & Sort)
            if (activeSubTab == NetworkSubTab.PEER_SCANNER) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CyberTealSecondary.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Radar, contentDescription = null, tint = CyberTealSecondary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Сканер публичных пиров Yggdrasil",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = scanner.lastScanSummary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyberTealSecondary
                                        )
                                    }
                                }
                            }

                            if (scanner.isScanning) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = CyberTealSecondary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Search Input for Host / City / Country / Protocol
                            OutlinedTextField(
                                value = scanner.searchQuery,
                                onValueChange = onUpdateScannerSearch,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("scanner_search_input"),
                                placeholder = {
                                    Text(
                                        "Поиск пира по хосту, IP, городу или протоколу...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = "Поиск пира")
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Country Filter Chips (16 Countries + ALL)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                scanner.countries.forEach { country ->
                                    val countInCountry = remember(scanner.discoveredPeers, country.code) {
                                        if (country.code == "ALL") {
                                            scanner.discoveredPeers.size
                                        } else {
                                            scanner.discoveredPeers.count { it.countryCode == country.code }
                                        }
                                    }
                                    FilterChip(
                                        selected = scanner.selectedCountryCode == country.code,
                                        onClick = { onSelectScanCountry(country.code) },
                                        label = {
                                            Text(
                                                "${country.flagEmoji} ${country.nameRu} ($countInCountry)",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ),
                                        modifier = Modifier.testTag("country_filter_${country.code}")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Sort Mode Chips
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Сортировка:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val sortOptions = listOf(
                                    "PING_ASC" to "Быстрые",
                                    "COUNTRY" to "Страна",
                                    "UPTIME" to "Аптайм"
                                )
                                sortOptions.forEach { (key, title) ->
                                    FilterChip(
                                        selected = scanner.sortMode == key,
                                        onClick = { onUpdateScannerSort(key) },
                                        label = { Text(title, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.testTag("scanner_sort_$key")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Только порт 443 / WSS (Обход белых списков)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                YggSwitch(
                                    checked = scanner.onlyWhitelistCompatible,
                                    onCheckedChange = { onToggleScannerWhitelistOnly() },
                                    modifier = Modifier.testTag("scanner_whitelist_only_switch")
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onRunCountryPeerScan,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("scan_peers_country_button")
                                ) {
                                    Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text("Сканировать пинг", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onConnectTopFastestInCountry,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier.testTag("connect_top3_country_button")
                                ) {
                                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Топ-3", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { showBulkImportDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Импорт из буфера", modifier = Modifier.size(17.dp))
                                }
                            }
                        }
                    }
                }

                val displayedPeers = if (showAllScannedPeers) {
                    filteredScannedPeers
                } else {
                    filteredScannedPeers.take(15)
                }

                items(displayedPeers, key = { it.id }) { scannedPeer ->
                    val isAlreadyAdded = activeEndpointSet.contains(scannedPeer.endpointUri.lowercase())
                    ScannedPeerCard(
                        peer = scannedPeer,
                        isAlreadyAdded = isAlreadyAdded,
                        onCopyUri = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Yggdrasil Peer", scannedPeer.endpointUri))
                            Toast.makeText(context, "Скопировано: ${scannedPeer.endpointUri}", Toast.LENGTH_SHORT).show()
                        },
                        onConnect = { onConnectScannedPeer(scannedPeer) }
                    )
                }

                if (filteredScannedPeers.size > 15) {
                    item {
                        OutlinedButton(
                            onClick = { showAllScannedPeers = !showAllScannedPeers },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (showAllScannedPeers) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (showAllScannedPeers) {
                                    "Свернуть список до 15 узлов"
                                } else {
                                    "Показать все ${filteredScannedPeers.size} узлов"
                                }
                            )
                        }
                    }
                }
            }

            // TAB 3: ANTI-DPI / TSPU BYPASS SETTINGS
            if (activeSubTab == NetworkSubTab.ANTI_DPI_TSPU) {
                item {
                    Text(
                        text = "Профили обхода ТСПУ, DPI и Белых списков РФ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CensorshipPreset.entries.forEach { preset ->
                            val isSelected = cfg.activePreset == preset
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { onSelectPreset(preset) }
                                    .testTag("preset_card_${preset.name}"),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainer
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = preset.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = preset.badge,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = preset.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Public, contentDescription = null, tint = AmberShieldTertiary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Маскировка под Белые списки (CDN WSS + SNI)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Выберите разрешенный SNI-домен для TLS/WSS рукопожатия:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                cfg.availableWhitelistSnis.forEach { domain ->
                                    FilterChip(
                                        selected = cfg.whitelistSniDomain == domain,
                                        onClick = { onSelectWhitelistSni(domain) },
                                        label = { Text(domain, style = MaterialTheme.typography.labelMedium) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        ),
                                        modifier = Modifier.testTag("sni_chip_$domain")
                                    )
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = CyberTealSecondary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Профиль отпечатка TLS ClientHello (uTLS)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val utlsProfiles = listOf(
                                    "HelloChrome_128_Auto",
                                    "HelloIOS_18_Auto",
                                    "HelloFirefox_132_Auto",
                                    "QUIC_v1_Padded_Jitter"
                                )
                                utlsProfiles.forEach { profile ->
                                    FilterChip(
                                        selected = cfg.utlsProfile == profile,
                                        onClick = { onSelectUtlsProfile(profile) },
                                        label = { Text(profile, style = MaterialTheme.typography.labelMedium) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ),
                                        modifier = Modifier.testTag("utls_chip_$profile")
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Модули защиты от анализа трафика (DPI / ТСПУ)",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(
                                text = "Только SNI реально уходит в транспорт: для tls:// пира с доменным именем в базе ядро ставит его в ClientHello. uTLS-профиль, Reality и padding в трафик не передаются.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            DpiSwitchRow(
                                title = "Туннель WebSocket поверх CDN (wss:// Белый список)",
                                subtitle = "Инкапсуляция фреймов Yggdrasil в HTTP/2 и WebSocket через разрешенные IP-диапазоны CDN",
                                checked = cfg.whitelistCdnWssEnabled,
                                tag = "switch_whitelist_wss",
                                onToggle = { onToggleAntiDpiFlag("WHITELIST_WSS") }
                            )

                            DpiSwitchRow(
                                title = "ShadowTLS v3 + VLESS Reality (Защита от активного пробинга)",
                                subtitle = "Перенаправление неавторизованных проверок ТСПУ на реальный TLS-сертификат домена SNI",
                                checked = cfg.shadowTlsV3Enabled && cfg.vlessRealityEnabled,
                                tag = "switch_shadowtls_vless",
                                onToggle = { onToggleAntiDpiFlag("SHADOWTLS") }
                            )

                            DpiSwitchRow(
                                title = "Обфускация QUIC UDP (Выравнивание пакетов до ${cfg.quicPaddingMTU} байт)",
                                subtitle = "Скрывает сигнатуры рукопожатия протокола Yggdrasil и статистику длин пакетов",
                                checked = cfg.quicPaddingEnabled,
                                tag = "switch_quic_padding",
                                onToggle = { onToggleAntiDpiFlag("QUIC_PAD") }
                            )

                            DpiSwitchRow(
                                title = "Автономный Bootstrap: BitTorrent Mainline DHT (BEP-44) + DoH",
                                subtitle = "Поиск подписанных Ed25519 списков пиров без обращения к заблокированным каталогам",
                                checked = cfg.dhtBep44BootstrapEnabled,
                                tag = "switch_dht_bep44",
                                onToggle = { onToggleAntiDpiFlag("DHT_BEP44") }
                            )
                        }
                    }
                }
            }
        }
    }
}

    if (showAddPeerDialog) {
        AddYggPeerModal(
            defaultSni = cfg.whitelistSniDomain,
            defaultUtls = cfg.utlsProfile,
            onDismiss = { showAddPeerDialog = false },
            onAdd = { uri, transport, sni, utls, region, whitelist ->
                onAddPeer(uri, transport, sni, utls, region, whitelist)
                showAddPeerDialog = false
            }
        )
    }

    if (showBulkImportDialog) {
        BulkImportPeersDialog(
            onDismiss = { showBulkImportDialog = false },
            onImport = { rawText ->
                onImportCustomPeers(rawText)
                showBulkImportDialog = false
            }
        )
    }
}

@Composable
private fun ScannedPeerCard(
    peer: DiscoveredYggPeer,
    isAlreadyAdded: Boolean,
    onCopyUri: () -> Unit,
    onConnect: () -> Unit
) {
    val isReachable = peer.latencyMs > 0
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scanned_peer_${peer.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${peer.countryFlag} ${peer.countryNameRu} • ${peer.cityRu}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (isReachable) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (isReachable) "${peer.latencyMs} мс" else "Не проверен",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isReachable) EmeraldPrimary else AmberShieldTertiary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = peer.endpointUri,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = CyberTealSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${peer.transportType} • ${if (peer.whitelistCompatible) "Порт 443/WSS" else "Прямой порт"} • ${peer.uptimePercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onCopyUri,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Скопировать URI пира",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    if (isAlreadyAdded) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "В маршрутах",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldPrimary
                                )
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = onConnect,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("connect_scanned_${peer.id}")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Подключить", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreMeshStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DpiSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        YggSwitch(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun PeerNodeCard(
    peer: YggPeerEntity,
    onCopyUri: () -> Unit,
    onToggleConnect: () -> Unit,
    onRemove: () -> Unit
) {
    // The core's link state decides aliveness; a live link simply has no measured
    // session round trip yet, and that must not grey the card out.
    val isAlive = peer.isConnected
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("peer_card_${peer.peerId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isAlive) EmeraldPrimary else Color(0xFFEF4444))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = peer.regionLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (peer.ed25519Verified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = "Подпись Ed25519 проверена",
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = if (isAlive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = when {
                            !isAlive -> "ОФЛАЙН"
                            peer.latencyMs > 0 -> "${peer.latencyMs} мс"
                            else -> "ЛИНК, ПИНГ НЕ ИЗМЕРЕН"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isAlive) EmeraldPrimary else Color(0xFFEF4444),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = peer.endpointUri,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = CyberTealSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onCopyUri,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Скопировать адрес пира",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (YggNativeCoreBridge.corePeerUri(peer.endpointUri, peer.sniDomain) != peer.endpointUri)
                            "SNI в ClientHello: ${peer.sniDomain} • ${peer.utlsFingerprint}"
                        else
                            "SNI НЕ отправляется: ${peer.sniDomain.ifBlank { "не задан" }} • ${peer.utlsFingerprint}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "RX: ${peer.rxBytes} B • TX: ${peer.txBytes} B • ${if (peer.isWhitelistCompatible) "Порт 443 / WSS" else "Прямой TLS/TCP"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (peer.isWhitelistCompatible) EmeraldPrimary else AmberShieldTertiary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    YggSwitch(
                        checked = peer.isConnected,
                        onCheckedChange = { onToggleConnect() },
                        modifier = Modifier.testTag("toggle_peer_${peer.peerId}")
                    )
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Удалить пир",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddYggPeerModal(
    defaultSni: String,
    defaultUtls: String,
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, String, Boolean) -> Unit
) {
    var uri by rememberSaveable { mutableStateOf("tls://ygg.zbin.eu:7743") }
    var sni by rememberSaveable { mutableStateOf(defaultSni) }
    var region by rememberSaveable { mutableStateOf("Пользовательский пир Yggdrasil") }
    var whitelistCompat by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить реальный пир Yggdrasil", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it },
                    label = { Text("URI пира (tls://, tcp://, wss://, quic://)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_peer_uri_input")
                )
                OutlinedTextField(
                    value = sni,
                    onValueChange = { sni = it },
                    label = { Text("SNI-домен (для TLS/WSS)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_peer_sni_input")
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("Описание / локация узла") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_peer_region_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Совместим с Белыми списками (порт 443 / WSS)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    YggSwitch(checked = whitelistCompat, onCheckedChange = { whitelistCompat = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (uri.isNotBlank()) {
                        onAdd(uri, "TLS_YGGDRASIL", sni, defaultUtls, region, whitelistCompat)
                    }
                },
                modifier = Modifier.testTag("confirm_add_peer_button")
            ) {
                Text("Проверить и добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun BulkImportPeersDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    val context = LocalContext.current
    var rawText by rememberSaveable {
        mutableStateOf(
            "tls://ygg.zbin.eu:7743\ntls://publicpeers.neilalexander.dev:443\ntls://65.21.57.125:61995\ntls://ygg.ace.ctrl-c.liu.se:9999"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Массовый импорт пиров Yggdrasil", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Вставьте список URI пиров (по одному на строку или через пробел). Все найденные tls://, tcp://, wss:// и quic:// узлы будут проверены по сети и добавлены.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                        if (clipText.isNotBlank()) {
                            rawText = clipText
                        } else {
                            Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Вставить из буфера обмена")
                }
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("Список URI пиров") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bulk_import_peers_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (rawText.isNotBlank()) {
                        onImport(rawText)
                    }
                },
                modifier = Modifier.testTag("confirm_bulk_import_button")
            ) {
                Text("Импортировать и проверить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun SimpleNetworkShieldContent(
    uiState: YggGramUiState,
    peers: List<YggPeerEntity>,
    scanner: PeerScannerState,
    liveRoutes: Int,
    totalRoutes: Int,
    onRunProbe: () -> Unit,
    onConnectTopFastestInCountry: () -> Unit,
    onSelectScanCountry: (String) -> Unit,
    onRunCountryPeerScan: () -> Unit,
    onConnectScannedPeer: (DiscoveredYggPeer) -> Unit,
    onToggleAntiDpiFlag: (String) -> Unit,
    onSelectPreset: (CensorshipPreset) -> Unit,
    onSwitchToExpertMode: () -> Unit
) {
    val cfg = uiState.antiDpiConfig
    val isOnline = uiState.isNetworkAvailable && !uiState.isAirplaneModeOn
    val hasOverlayLink = isOnline && liveRoutes > 0
    val activePeer = peers.firstOrNull { it.isConnected }
    val bestLatency = peers.filter { it.isConnected && it.latencyMs > 0 }.minOfOrNull { it.latencyMs }
        ?: activePeer?.latencyMs?.takeIf { it > 0 } ?: -1

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. HERO SHIELD CARD ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("simple_smart_shield_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.5.dp,
                    color = if (hasOverlayLink) EmeraldPrimary.copy(alpha = 0.5f)
                    else if (!isOnline) AmberShieldTertiary.copy(alpha = 0.4f)
                    else CyberTealSecondary.copy(alpha = 0.4f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            // Without weight the title column takes the whole row and the status
                            // badge is measured out of the layout as soon as the text grows.
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (hasOverlayLink) EmeraldPrimary.copy(alpha = 0.15f)
                                        else if (!isOnline) AmberShieldTertiary.copy(alpha = 0.15f)
                                        else CyberTealSecondary.copy(alpha = 0.15f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (hasOverlayLink) Icons.Default.Shield else Icons.Default.Radar,
                                    contentDescription = null,
                                    tint = if (hasOverlayLink) EmeraldPrimary else if (!isOnline) AmberShieldTertiary else CyberTealSecondary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Умный щит Yggdrasil",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    // liveRoutes is a peer link count, not proof against DPI: the core
                                    // dials a plain TLS link, so the shield must not claim censorship
                                    // bypass it has no way to observe.
                                    text = if (hasOverlayLink) "Линк к пиру есть • маскировки трафика нет"
                                    else if (!isOnline) "Сеть недоступна или Авиарежим"
                                    else "Поиск стабильного узла 0200::/7",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hasOverlayLink) EmeraldPrimary else if (!isOnline) AmberShieldTertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Status Badge
                        Surface(
                            color = if (hasOverlayLink) EmeraldPrimary.copy(alpha = 0.2f)
                            else if (!isOnline) AmberShieldTertiary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (hasOverlayLink) "● В ОВЕРЛЕЕ"
                                else if (!isOnline) "ОФЛАЙН"
                                else "ПОИСК",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (hasOverlayLink) EmeraldPrimary
                                else if (!isOnline) AmberShieldTertiary
                                else CyberTealSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Key telemetry metrics in 3 columns
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "УЗЕЛ ВЫХОДА",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = activePeer?.regionLabel ?: "Авто-выбор",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "ЗАДЕРЖКА",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (bestLatency > 0) "$bestLatency мс" else "—",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (bestLatency in 1..120) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "ТРАНСПОРТ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                // The preset badge names a masking mode the core never applies; the
                                // only transport fact worth showing is how the live link is dialled.
                                text = activePeer?.endpointUri?.substringBefore("://")?.uppercase()
                                    ?.takeIf { it.isNotBlank() } ?: "нет линка",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = CyberTealSecondary
                            )
                        }
                    }

                    // Diagnostic report or probe summary
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = null,
                                tint = CyberTealSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = cfg.lastProbeReport,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Quick Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRunProbe,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("simple_run_probe_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Проверить", maxLines = 1)
                        }
                        Button(
                            onClick = onConnectTopFastestInCountry,
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("simple_connect_fastest_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Лучший узел", maxLines = 1)
                        }
                    }
                }
            }
        }

        // --- 2. QUICK COUNTRY LOCATION SELECTOR ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = CyberTealSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Быстрый выбор региона (16 стран)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "1 клик для связи",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Horizontal list of top countries
                    val topCountries = listOf(
                        "RU" to "🇷🇺 Россия (Белый список)",
                        "NL" to "🇳🇱 Нидерланды",
                        "DE" to "🇩🇪 Германия",
                        "FI" to "🇫🇮 Финляндия",
                        "SE" to "🇸🇪 Швеция",
                        "US" to "🇺🇸 США",
                        "CH" to "🇨🇭 Швейцария",
                        "PL" to "🇵🇱 Польша",
                        "FR" to "🇫🇷 Франция",
                        "GB" to "🇬🇧 Великобритания",
                        "EE" to "🇪🇪 Эстония",
                        "CZ" to "🇨🇿 Чехия",
                        "KZ" to "🇰🇿 Казахстан"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        topCountries.forEach { (code, label) ->
                            val isSelected = scanner.selectedCountryCode == code
                            Surface(
                                color = if (isSelected) EmeraldPrimary.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(10.dp),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary) else null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSelectScanCountry(code)
                                        onRunCountryPeerScan()
                                        onConnectTopFastestInCountry()
                                    }
                                    .testTag("simple_country_chip_$code")
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- 3. SMART PROTECTION SWITCHES ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Параметры скрытности и защиты",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "Только SNI реально уходит в транспорт: для tls:// пира с доменным именем в базе ядро ставит его в ClientHello. uTLS-профиль, Reality и padding в трафик не передаются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    DpiSwitchRow(
                        title = "Маскировка под HTTPS CDN (RU SNI)",
                        subtitle = "Трафик выглядит для ТСПУ как обращение к разрешенным сайтам (Яндекс, Госуслуги)",
                        checked = cfg.whitelistCdnWssEnabled,
                        tag = "simple_switch_whitelist_mode",
                        onToggle = { onToggleAntiDpiFlag("WHITELIST_WSS") }
                    )

                    DpiSwitchRow(
                        title = "Защита от активного пробинга (VLESS Reality)",
                        subtitle = "Отклоняет зондирующие пакеты цензурных сканеров с отпечатком uTLS Chrome 128",
                        checked = cfg.vlessRealityEnabled,
                        tag = "simple_switch_vless_mode",
                        onToggle = { onToggleAntiDpiFlag("VLESS") }
                    )

                    DpiSwitchRow(
                        title = "Анти-сигнатурный Padding (MTU ${cfg.quicPaddingMTU})",
                        subtitle = "Скрывает длины сообщений и голосовых пакетов от DPI-сигнатур",
                        checked = cfg.quicPaddingEnabled,
                        tag = "simple_switch_quic_padding",
                        onToggle = { onToggleAntiDpiFlag("QUIC_PAD") }
                    )
                }
            }
        }

        // --- 4. EXPERT MODE INVITATION CARD ---
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Нужна детальная настройка?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Кастомные SNI, uTLS профили, ShadowTLS v3, живой терминал пакетов и ручные маршруты",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSwitchToExpertMode,
                        modifier = Modifier.testTag("switch_to_expert_mode_button")
                    ) {
                        Text("Эксперт")
                    }
                }
            }
        }
    }
}

