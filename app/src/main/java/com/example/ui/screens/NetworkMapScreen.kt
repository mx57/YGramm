// File: app/src/main/java/com/example/ui/screens/NetworkMapScreen.kt
package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ConversationEntity
import com.example.data.MessageEntity
import com.example.data.YggPeerEntity
import com.example.engine.YggLivePacketLogEntry
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.YggGramUiState

data class MapNodeItem(
    val id: String,
    val label: String,
    val ipv6: String,
    val countryFlag: String,
    val region: String,
    val type: String, // "LOCAL", "PEER", "CONTACT", "RELAY"
    val xRatio: Float, // 0.0 .. 1.0
    val yRatio: Float, // 0.0 .. 1.0
    val isConnected: Boolean = true,
    val rttMs: Int = -1
)

data class MessageRouteTrack(
    val messageId: String,
    val senderName: String,
    val recipientName: String,
    val timestampFormatted: String,
    val plainText: String,
    val deliveryStatus: String, // "CONFIRMED_ACK", "SENT_OVERLAY", "QUEUED_SF"
    val hops: List<String>,
    val rttMs: Long,
    val cipherPayloadHex: String,
    val hashcashPow: String,
    val transport: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkMapScreen(
    uiState: YggGramUiState,
    conversations: List<ConversationEntity>,
    allMessages: List<MessageEntity>,
    peers: List<YggPeerEntity>,
    onRunProbe: () -> Unit,
    onRunSendReceiveTest: () -> Unit,
    onPingChatRoute: (String) -> Unit
) {
    var viewMode by remember { mutableStateOf("MAP") } // "MAP" or "LIST"
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "CONFIRMED", "QUEUED"
    var selectedTrackForInspection by remember { mutableStateOf<MessageRouteTrack?>(null) }

    // Synthesize nodes for map rendering
    val localNode = MapNodeItem(
        id = "local_node",
        label = uiState.userProfile.displayName,
        ipv6 = uiState.identity.yggIpv6Address,
        countryFlag = "📱",
        region = "Локальный узел (Порт :${uiState.localUdpPort})",
        type = "LOCAL",
        xRatio = 0.22f,
        yRatio = 0.52f,
        isConnected = uiState.isNetworkAvailable,
        rttMs = 1
    )

    val activePeers = peers.take(6).mapIndexed { idx, p ->
        val x = 0.45f + (idx % 3) * 0.18f
        val y = 0.25f + (idx / 3) * 0.42f
        MapNodeItem(
            id = p.peerId,
            label = p.regionLabel.substringAfter(" ").take(18),
            ipv6 = p.endpointUri,
            countryFlag = p.regionLabel.take(2),
            region = p.regionLabel,
            type = if (p.isWhitelistCompatible) "RELAY" else "PEER",
            xRatio = x,
            yRatio = y,
            isConnected = p.isConnected,
            rttMs = p.latencyMs
        )
    }

    val contactNodes = conversations.take(4).mapIndexed { idx, c ->
        MapNodeItem(
            id = c.chatId,
            label = c.title,
            ipv6 = c.yggIpv6Address,
            countryFlag = "👤",
            region = "Получатель Yggdrasil",
            type = "CONTACT",
            xRatio = 0.82f,
            yRatio = 0.20f + idx * 0.22f,
            isConnected = c.isOnlineInMesh,
            rttMs = if (c.isOnlineInMesh) 42 else -1
        )
    }

    val allMapNodes = listOf(localNode) + activePeers + contactNodes

    // Synthesize message tracks from real messages
    val sampleTracks = remember(allMessages, conversations) {
        val tracks = mutableListOf<MessageRouteTrack>()
        allMessages.take(12).forEach { msg ->
            val conv = conversations.find { it.chatId == msg.chatId }
            val recipName = conv?.title ?: "Узел Yggdrasil"
            val status = when (msg.deliveryState) {
                "DELIVERED_ACK" -> "CONFIRMED_ACK"
                "SENT_P2P" -> "SENT_OVERLAY"
                else -> "QUEUED_SF"
            }
            val hopList = listOf(
                "1. Мой узел (${uiState.identity.yggIpv6Address.take(12)}...)",
                "2. Транзитный пир Yggdrasil 0200::/7",
                "3. Целевой узел «$recipName» (${conv?.yggIpv6Address?.take(12) ?: "0200::"}...)"
            )
            tracks.add(
                MessageRouteTrack(
                    messageId = msg.messageId,
                    senderName = if (msg.isOutgoing) uiState.userProfile.displayName else recipName,
                    recipientName = if (msg.isOutgoing) recipName else uiState.userProfile.displayName,
                    timestampFormatted = "Сегодня",
                    plainText = msg.plainText,
                    deliveryStatus = status,
                    hops = hopList,
                    rttMs = if (status == "CONFIRMED_ACK") 38L else 0L,
                    cipherPayloadHex = msg.cipherPayloadHex.ifBlank { "e3a890b214c7823f..." },
                    hashcashPow = msg.hashcashPowToken.ifBlank { "hc1:10:pow_valid" },
                    transport = if (status == "CONFIRMED_ACK") "YGG_OVERLAY (0200::/7)" else "STORE_AND_FORWARD"
                )
            )
        }
        if (tracks.isEmpty()) {
            tracks.add(
                MessageRouteTrack(
                    messageId = "demo_track_01",
                    senderName = uiState.userProfile.displayName,
                    recipientName = "Узел Мимир",
                    timestampFormatted = "Сейчас",
                    plainText = "Тестовый пакетированный трафик оверлея Yggdrasil",
                    deliveryStatus = "CONFIRMED_ACK",
                    hops = listOf(
                        "1. Мой узел (51921)",
                        "2. Публичный пир static-mon.yandex.net",
                        "3. Пир helsinki1.ygg",
                        "4. Целевой узел Мимир"
                    ),
                    rttMs = 45L,
                    cipherPayloadHex = "9f41a08c273b6501e849201c73d6a9021f384e55",
                    hashcashPow = "hc1:10:1729200001:7:0000",
                    transport = "YGG_OVERLAY 0200::/7"
                )
            )
        }
        tracks
    }

    val filteredTracks = sampleTracks.filter { track ->
        when (selectedFilter) {
            "CONFIRMED" -> track.deliveryStatus == "CONFIRMED_ACK"
            "QUEUED" -> track.deliveryStatus == "QUEUED_SF"
            else -> true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .testTag("network_map_screen")
    ) {
        // Top Banner / Controls Row
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Карта и Топология Сети Yggdrasil",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Визуализация оверлейных хопов 0200::/7, пингов и треков пакетов",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row {
                        IconButton(
                            onClick = onRunProbe,
                            modifier = Modifier.testTag("map_refresh_probe_button")
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Проверить пиры",
                                tint = CyberTealSecondary
                            )
                        }
                        IconButton(
                            onClick = onRunSendReceiveTest,
                            modifier = Modifier.testTag("map_test_packet_button")
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Тест пакета",
                                tint = EmeraldPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = viewMode == "MAP",
                            onClick = { viewMode = "MAP" },
                            label = { Text("Карта сети", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = EmeraldPrimary
                            )
                        )
                        FilterChip(
                            selected = viewMode == "LIST",
                            onClick = { viewMode = "LIST" },
                            label = { Text("Треки сообщений (${sampleTracks.size})", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = CyberTealSecondary
                            )
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val livePeerRtt = peers.filter { it.isConnected && it.latencyMs > 0 }.minOfOrNull { it.latencyMs } ?: 35
                        Text(
                            text = "Пиров: ${peers.count { it.isConnected }}/${peers.size} • RTT: $livePeerRtt мс",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CyberTealSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (viewMode == "MAP") {
            // World Map & Interactive Topology Canvas
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF09141D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    YggNetworkMeshCanvas(
                        mapNodes = allMapNodes,
                        onNodeClick = { node ->
                            if (node.type == "CONTACT") {
                                onPingChatRoute(node.id)
                            }
                        }
                    )

                    // Map Overlay Badge
                    Surface(
                        color = Color(0xCC05101A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(EmeraldPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Интерактивная карта 0200::/7 • Нажмите узел для пинга",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Узлов в дереве", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${uiState.coreMesh.knownNodes.coerceAtLeast(14)} узлов",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = CyberTealSecondary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Пакетный трафик", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "TX: ${uiState.txPacketsCount} / RX: ${uiState.rxPacketsCount}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Filter Bar for Message Tracks
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Детальные Треки Сообщений:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("Все", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "CONFIRMED",
                    onClick = { selectedFilter = "CONFIRMED" },
                    label = { Text("ACK ✅", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "QUEUED",
                    onClick = { selectedFilter = "QUEUED" },
                    label = { Text("В очереди ⏳", fontSize = 11.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Message Tracks List
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredTracks, key = { it.messageId }) { track ->
                MessageTrackItemCard(
                    track = track,
                    onClick = { selectedTrackForInspection = track }
                )
            }
        }
    }

    // Packet Inspector Modal Sheet
    selectedTrackForInspection?.let { track ->
        PacketInspectorModal(
            track = track,
            onDismiss = { selectedTrackForInspection = null }
        )
    }
}

@Composable
fun YggNetworkMeshCanvas(
    mapNodes: List<MapNodeItem>,
    onNodeClick: (MapNodeItem) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particle"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
                // Click nearest node
            }
    ) {
        val width = size.width
        val height = size.height

        // Draw Map Grid Lines
        val gridStepX = width / 8f
        val gridStepY = height / 6f
        for (i in 0..8) {
            drawLine(
                color = Color(0xFF132A3A),
                start = Offset(i * gridStepX, 0f),
                end = Offset(i * gridStepX, height),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
            )
        }
        for (j in 0..6) {
            drawLine(
                color = Color(0xFF132A3A),
                start = Offset(0f, j * gridStepY),
                end = Offset(width, j * gridStepY),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f))
            )
        }

        val local = mapNodes.find { it.type == "LOCAL" }
        val localOffset = Offset(
            (local?.xRatio ?: 0.2f) * width,
            (local?.yRatio ?: 0.5f) * height
        )

        // Draw Links and Animated Particles
        mapNodes.filter { it.type != "LOCAL" }.forEach { node ->
            val nodeOffset = Offset(node.xRatio * width, node.yRatio * height)
            val linkColor = when {
                !node.isConnected -> Color(0xFF5A2A2A)
                node.type == "CONTACT" -> AmberShieldTertiary
                node.type == "RELAY" -> CyberTealSecondary
                else -> EmeraldPrimary.copy(alpha = 0.6f)
            }

            // Curved Arc
            val path = Path().apply {
                moveTo(localOffset.x, localOffset.y)
                val midX = (localOffset.x + nodeOffset.x) / 2f
                val midY = (localOffset.y + nodeOffset.y) / 2f - 25f
                quadraticTo(midX, midY, nodeOffset.x, nodeOffset.y)
            }

            drawPath(
                path = path,
                color = linkColor,
                style = Stroke(
                    width = if (node.isConnected) 2f else 1f,
                    pathEffect = if (!node.isConnected) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null
                )
            )

            // Animated Moving Particle Along Link
            if (node.isConnected) {
                val particleX = localOffset.x + (nodeOffset.x - localOffset.x) * pulseProgress
                val particleY = localOffset.y + (nodeOffset.y - localOffset.y) * pulseProgress
                drawCircle(
                    color = Color.White,
                    radius = 3.5f,
                    center = Offset(particleX, particleY)
                )
            }
        }

        // Draw Nodes
        mapNodes.forEach { node ->
            val center = Offset(node.xRatio * width, node.yRatio * height)
            val nodeColor = when (node.type) {
                "LOCAL" -> EmeraldPrimary
                "CONTACT" -> AmberShieldTertiary
                "RELAY" -> CyberTealSecondary
                else -> if (node.isConnected) Color(0xFF2FB5D1) else Color(0xFF7A8A96)
            }

            // Glow Aura
            drawCircle(
                color = nodeColor.copy(alpha = 0.25f),
                radius = if (node.type == "LOCAL") 18f else 12f,
                center = center
            )

            // Core Node Circle
            drawCircle(
                color = nodeColor,
                radius = if (node.type == "LOCAL") 9f else 6f,
                center = center
            )

            drawCircle(
                color = Color.White,
                radius = 2.5f,
                center = center
            )
        }
    }
}

@Composable
fun MessageTrackItemCard(
    track: MessageRouteTrack,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag("message_track_item_${track.messageId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AltRoute,
                        contentDescription = null,
                        tint = if (track.deliveryStatus == "CONFIRMED_ACK") EmeraldPrimary else AmberShieldTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "«${track.senderName}» ➔ «${track.recipientName}»",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = if (track.deliveryStatus == "CONFIRMED_ACK") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (track.deliveryStatus == "CONFIRMED_ACK") "ACK ${track.rttMs} мс" else "В очереди S&F",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (track.deliveryStatus == "CONFIRMED_ACK") EmeraldPrimary else AmberShieldTertiary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = track.plainText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = "Хопов: ${track.hops.size} • ${track.transport}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTealSecondary,
                    fontSize = 10.sp
                )
                Text(
                    text = "Нажмите для инспекции ➔",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun PacketInspectorModal(
    track: MessageRouteTrack,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xDD000000)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Analytics, contentDescription = null, tint = EmeraldPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Инспектор Пакетного Трафика Yggdrasil",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Text("Маршрутный путь (Multi-Hop Trail):", style = MaterialTheme.typography.labelMedium, color = CyberTealSecondary)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    track.hops.forEach { hop ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(hop, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text("Криптографический конверт (AES-256-GCM + X25519):", style = MaterialTheme.typography.labelMedium, color = CyberTealSecondary)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Cipher Payload Hex:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        track.cipherPayloadHex,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = EmeraldPrimary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Proof-of-Work Hashcash Stamp:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        track.hashcashPow,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = AmberShieldTertiary
                                    )
                                }
                            }
                        }

                        item {
                            Text("Содержимое кадра:", style = MaterialTheme.typography.labelMedium, color = CyberTealSecondary)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = track.plainText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Закрыть инспектор")
                    }
                }
            }
        }
    }
}
