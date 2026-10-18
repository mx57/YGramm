// File: app/src/main/java/com/example/ui/screens/NetworkDpiScreen.kt
package com.example.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.YggPeerEntity
import com.example.engine.DiscoveredYggPeer
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.CensorshipPreset
import com.example.viewmodel.YggGramUiState

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
    onRunSendReceiveTest: () -> Unit = {}
) {
    var showAddPeerDialog by rememberSaveable { mutableStateOf(false) }
    val cfg = uiState.antiDpiConfig
    val scanner = uiState.peerScanner
    val connectedPeersCount = peers.count { it.isConnected }
    val activeEndpointSet = remember(peers) { peers.map { it.endpointUri }.toSet() }

    val filteredScannedPeers = remember(
        scanner.discoveredPeers,
        scanner.selectedCountryCode,
        scanner.onlyWhitelistCompatible
    ) {
        scanner.discoveredPeers.filter { peer ->
            val countryMatch = scanner.selectedCountryCode == "ALL" || peer.countryCode == scanner.selectedCountryCode
            val whitelistMatch = !scanner.onlyWhitelistCompatible || peer.whitelistCompatible
            countryMatch && whitelistMatch
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Yggdrasil Node & Mesh Telemetry Card
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
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (uiState.isCloneInstance) {
                                            "УЗЕЛ #2 (КЛОН APK) • ПОРТ ${uiState.localUdpPort}"
                                        } else {
                                            "УЗЕЛ #1 (ОСНОВНОЙ APK) • ПОРТ ${uiState.localUdpPort}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmeraldPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "TX:${uiState.txPacketsCount} • RX:${uiState.rxPacketsCount} • Пиров: $connectedPeersCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberTealSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "IPv6: ${uiState.identity.yggIpv6Address}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Подсеть маршрутизации: ${uiState.identity.yggSubnetPrefix} • P2P Мост с клоном: 127.0.0.1:${uiState.remoteTwinUdpPort}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = cfg.lastProbeReport,
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldPrimary
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
                                Text("Проверить ТСПУ")
                            }
                            OutlinedButton(
                                onClick = onRunSendReceiveTest,
                                modifier = Modifier.testTag("network_test_send_receive_button")
                            ) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Тест TX/RX")
                            }
                        }

                        if (uiState.packetLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Живой Журнал Пакетов Yggdrasil (UDP Loopback & P2P):",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CyberTealSecondary
                                    )
                                    uiState.packetLogs.take(4).forEach { log ->
                                        Text(
                                            text = "[${log.timeFormatted}] ${log.direction}: ${log.summary}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (log.direction.startsWith("RX") || log.direction.startsWith("ACK")) {
                                                EmeraldPrimary
                                            } else {
                                                AmberShieldTertiary
                                            },
                                            maxLines = 1,
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

        // 2. YGGDRASIL PEER SCANNER WITH COUNTRY FILTER
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Radar, contentDescription = null, tint = CyberTealSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Сканер пиров Yggdrasil по странам",
                                    style = MaterialTheme.typography.titleMedium
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

                    // Country Filter Chips
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                        style = MaterialTheme.typography.labelMedium
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Только узлы с обходом Белых списков (WSS/VLESS)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
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
                            modifier = Modifier
                                .weight(1f)
                                .testTag("scan_peers_country_button")
                        ) {
                            Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Сканировать пинг")
                        }
                        OutlinedButton(
                            onClick = onConnectTopFastestInCountry,
                            modifier = Modifier.testTag("connect_top3_country_button")
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ Топ-3 быстрых")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        filteredScannedPeers.forEach { scannedPeer ->
                            val isAlreadyAdded = activeEndpointSet.contains(scannedPeer.endpointUri)
                            ScannedPeerCard(
                                peer = scannedPeer,
                                isAlreadyAdded = isAlreadyAdded,
                                onConnect = { onConnectScannedPeer(scannedPeer) }
                            )
                        }
                    }
                }
            }
        }

        // 3. Censorship & Whitelist Bypass Presets
        item {
            Text(
                text = "Профили обхода ТСПУ, DPI и Белых списков РФ",
                style = MaterialTheme.typography.titleMedium,
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
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Whitelist SNI & uTLS Fingerprint Configurator
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
                        text = "Выберите разрешенный SNI-домен для туннелирования пакетов Yggdrasil поверх HTTPS / WebSocket CDN:",
                        style = MaterialTheme.typography.bodyMedium,
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

        // 5. Granular Transport Obfuscation & Bootstrap Switches
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

        // 6. Active Yggdrasil Peers & Bridges List
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Подключенные пиры в таблице маршрутизации (${peers.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(
                    onClick = { showAddPeerDialog = true },
                    modifier = Modifier.testTag("open_add_peer_dialog_button")
                ) {
                    Text("+ Свой пир")
                }
            }
        }

        items(peers, key = { it.peerId }) { peer ->
            PeerNodeCard(
                peer = peer,
                onToggleConnect = { onTogglePeer(peer) },
                onRemove = { onRemovePeer(peer.peerId) }
            )
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
}

@Composable
private fun ScannedPeerCard(
    peer: DiscoveredYggPeer,
    isAlreadyAdded: Boolean,
    onConnect: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scanned_peer_${peer.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${peer.countryFlag} ${peer.countryNameRu} • ${peer.cityRu}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${peer.latencyMs} мс",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = peer.endpointUri,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTealSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "SNI: ${peer.sniDomain} • Uptime: ${peer.uptimePercent}% • ${peer.bandwidthMbps} Мбит/с • ${peer.ed25519PubKeyShort}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isAlreadyAdded) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
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
                            text = "В маршруте",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldPrimary
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("connect_scanned_${peer.id}")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Подключить", style = MaterialTheme.typography.labelMedium)
                }
            }
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
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun PeerNodeCard(
    peer: YggPeerEntity,
    onToggleConnect: () -> Unit,
    onRemove: () -> Unit
) {
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
                            .background(if (peer.isConnected) EmeraldPrimary else AmberShieldTertiary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = peer.regionLabel,
                        style = MaterialTheme.typography.titleMedium,
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
                Text(
                    text = "${peer.latencyMs} мс",
                    style = MaterialTheme.typography.titleSmall,
                    color = EmeraldPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = peer.endpointUri,
                style = MaterialTheme.typography.bodySmall,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SNI: ${peer.sniDomain} • uTLS: ${peer.utlsFingerprint}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "RX: ${peer.rxBytes / 1048576} МБ • TX: ${peer.txBytes / 1048576} МБ • ${if (peer.isWhitelistCompatible) "Белый список ОК" else "Прямой UDP"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (peer.isWhitelistCompatible) EmeraldPrimary else AmberShieldTertiary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
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
    var uri by rememberSaveable { mutableStateOf("wss://cdn-edge-kzn.bridge-node.ru:443/ygg-ws") }
    var sni by rememberSaveable { mutableStateOf(defaultSni) }
    var region by rememberSaveable { mutableStateOf("RU-KZN • CDN Мост Белого Списка") }
    var whitelistCompat by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить входной узел / мост Yggdrasil", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uri = it },
                    label = { Text("URI пира (wss://, vless+tls://, shadowtls://, quic://)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_peer_uri_input")
                )
                OutlinedTextField(
                    value = sni,
                    onValueChange = { sni = it },
                    label = { Text("SNI-домен маскировки (из белого списка)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("Регион / описание узла") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Совместим с режимом Белых списков", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = whitelistCompat, onCheckedChange = { whitelistCompat = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (uri.isNotBlank()) {
                        onAdd(uri, "WSS_CDN_WHITELIST", sni, defaultUtls, region, whitelistCompat)
                    }
                },
                modifier = Modifier.testTag("confirm_add_peer_button")
            ) {
                Text("Подключить пир")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
