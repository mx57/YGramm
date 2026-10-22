// File: app/src/main/java/com/example/ui/screens/NetworkMapScreen.kt
package com.example.ui.screens

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ConversationEntity
import com.example.data.MessageEntity
import com.example.data.YggPeerEntity
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.YggGramUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

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
    val rttMs: Long, // -1 when the app never measured a real round trip
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
    var selectedMapNode by remember { mutableStateOf<MapNodeItem?>(null) }

    val core = uiState.coreMesh

    val localNode = MapNodeItem(
        id = "local_node",
        label = uiState.userProfile.displayName,
        ipv6 = uiState.identity.yggIpv6Address,
        countryFlag = "📱",
        region = if (core.available) {
            val restartSuffix = if (core.restartCount > 0) " • перезапусков ${core.restartCount}" else ""
            "Go-ядро: линков ${core.liveLinks}/${core.totalLinks} • сессий ${core.sessionCount}$restartSuffix"
        } else {
            "Ядро недоступно"
        },
        type = "LOCAL",
        xRatio = 0.22f,
        yRatio = 0.52f,
        isConnected = core.available && core.liveLinks > 0
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
            rttMs = -1 // per-contact RTT is only known from a real ping
        )
    }

    val allMapNodes = listOf(localNode) + activePeers + contactNodes

    val tracks = remember(allMessages, conversations, core.liveLinks) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        allMessages.take(12).mapNotNull { msg ->
            if (msg.deliveryState == "LOCAL_NOTE") return@mapNotNull null
            val conv = conversations.find { it.chatId == msg.chatId }
            val recipName = conv?.title ?: "Узел Yggdrasil"
            val status = when (msg.deliveryState) {
                "DELIVERED", "READ" -> "CONFIRMED_ACK"
                "SENT_P2P" -> "SENT_OVERLAY"
                else -> "QUEUED_SF"
            }
            MessageRouteTrack(
                messageId = msg.messageId,
                senderName = if (msg.isOutgoing) uiState.userProfile.displayName else recipName,
                recipientName = if (msg.isOutgoing) recipName else uiState.userProfile.displayName,
                timestampFormatted = formatter.format(Date(msg.timestamp)),
                plainText = msg.plainText,
                deliveryStatus = status,
                hops = listOf(
                    "Мой узел (${uiState.identity.yggIpv6Address.take(12)}…)",
                    "Оверлей 0200::/7 • живых линков ядра: ${core.liveLinks}",
                    "Узел «$recipName» (${conv?.yggIpv6Address?.take(12) ?: "0200::"}…)"
                ),
                rttMs = -1,
                cipherPayloadHex = msg.cipherPayloadHex,
                hashcashPow = msg.hashcashPowToken,
                transport = when (status) {
                    "CONFIRMED_ACK" -> "YGG_OVERLAY (0200::/7), ACK получателя"
                    "SENT_OVERLAY" -> "YGG_OVERLAY (0200::/7), ACK не приходил"
                    else -> "ОЧЕРЕДЬ ДОСТАВКИ"
                }
            )
        }
    }

    val filteredTracks = tracks.filter { track ->
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
                                text = "Топология читается из Go-ядра и TCP/TLS-сканера без выдуманных значений",
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

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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
                            label = { Text("Треки сообщений (${tracks.size})", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.AltRoute, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = CyberTealSecondary
                            )
                        )
                    }

                    val scannedLive = peers.count { it.isConnected }
                    val bestRtt = peers.filter { it.isConnected && it.latencyMs > 0 }.minOfOrNull { it.latencyMs }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (core.available) {
                                "Ядро: ${core.liveLinks}/${core.totalLinks} линков • сканер: $scannedLive/${peers.size}" +
                                    (bestRtt?.let { " • $it мс" } ?: "") +
                                    (if (core.restartCount > 0) " • перезапусков ${core.restartCount}" else "")
                            } else {
                                "Ядро недоступно • сканер: $scannedLive/${peers.size}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (core.available && core.liveLinks > 0) CyberTealSecondary else AmberShieldTertiary,
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("map_core_status_badge")
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 4.dp)
        ) {
            if (viewMode == "MAP") {
            item(key = "mesh_map") {
            // World Map & Interactive Topology Canvas
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF09141D)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    YggNetworkMeshCanvas(
                        mapNodes = allMapNodes,
                        selectedNodeId = selectedMapNode?.id,
                        onNodeClick = { node -> selectedMapNode = node }
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
                                    .background(if (core.available && core.liveLinks > 0) EmeraldPrimary else AmberShieldTertiary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Оверлей 0200::/7 • нажмите узел для подробностей",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
            }

            // Selected node detail
            selectedMapNode?.let { node ->
                item(key = "node_detail") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${node.countryFlag} ${node.label}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = node.region,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = node.ipv6 + if (node.rttMs > 0) " • ${node.rttMs} мс" else "",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = CyberTealSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (node.type == "CONTACT") {
                            TextButton(
                                onClick = { onPingChatRoute(node.id) },
                                modifier = Modifier.testTag("map_ping_contact_${node.id}")
                            ) {
                                Text("Пинг маршрута", fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = { selectedMapNode = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Скрыть", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                }
            }

            item(key = "mesh_stats") {
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
                                text = if (core.available) "${core.knownNodes} узлов" else "нет данных",
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

            }
        }

            // Filter Bar for Message Tracks
            item(key = "filter_bar") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Треки сообщений:",
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
        }

        // Message Tracks List
        if (filteredTracks.isEmpty()) {
            item(key = "tracks_empty") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (tracks.isEmpty()) {
                        "Пока нет отправленных сообщений — трек маршрута появится после первой отправки."
                    } else {
                        "Нет треков с выбранным статусом."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
            }
        } else {
            items(filteredTracks, key = { it.messageId }) { track ->
                MessageTrackItemCard(
                    track = track,
                    onClick = { selectedTrackForInspection = track }
                )
            }
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
    selectedNodeId: String?,
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

    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 26f
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val rttPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(mapNodes) {
                detectTapGestures { tap ->
                    val nearest = mapNodes.minByOrNull { node ->
                        val dx = node.xRatio * size.width - tap.x
                        val dy = node.yRatio * size.height - tap.y
                        sqrt(dx * dx + dy * dy)
                    }
                    if (nearest != null) {
                        val dx = nearest.xRatio * size.width - tap.x
                        val dy = nearest.yRatio * size.height - tap.y
                        if (sqrt(dx * dx + dy * dy) < 90f) onNodeClick(nearest)
                    }
                }
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
                node.rttMs in 1..119 -> EmeraldPrimary.copy(alpha = 0.75f)
                node.rttMs in 120..399 -> CyberTealSecondary.copy(alpha = 0.75f)
                node.rttMs >= 400 -> AmberShieldTertiary.copy(alpha = 0.75f)
                node.type == "CONTACT" -> AmberShieldTertiary.copy(alpha = 0.6f)
                else -> CyberTealSecondary.copy(alpha = 0.5f)
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
                    width = if (node.isConnected) 2.5f else 1f,
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

        // Draw Nodes with labels
        mapNodes.forEach { node ->
            val center = Offset(node.xRatio * width, node.yRatio * height)
            val nodeColor = when (node.type) {
                "LOCAL" -> EmeraldPrimary
                "CONTACT" -> if (node.isConnected) AmberShieldTertiary else Color(0xFF7A8A96)
                "RELAY" -> CyberTealSecondary
                else -> if (node.isConnected) Color(0xFF2FB5D1) else Color(0xFF7A8A96)
            }

            if (node.id == selectedNodeId) {
                drawCircle(
                    color = Color.White,
                    radius = if (node.type == "LOCAL") 24f else 18f,
                    center = center,
                    style = Stroke(width = 3f)
                )
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

            labelPaint.color = android.graphics.Color.rgb(220, 232, 240)
            val label = node.label.take(14)
            val labelY = (center.y + if (node.type == "LOCAL") 34f else 28f).coerceAtMost(height - 8f)
            drawContext.canvas.nativeCanvas.drawText(label, center.x.coerceIn(60f, width - 60f), labelY, labelPaint)

            if (node.rttMs > 0) {
                rttPaint.color = when {
                    node.rttMs < 120 -> android.graphics.Color.rgb(46, 204, 113)
                    node.rttMs < 400 -> android.graphics.Color.rgb(41, 182, 246)
                    else -> android.graphics.Color.rgb(255, 179, 0)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "${node.rttMs} мс",
                    center.x.coerceIn(60f, width - 60f),
                    (labelY + 22f).coerceAtMost(height - 4f),
                    rttPaint
                )
            }
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
                        Icons.AutoMirrored.Filled.AltRoute,
                        contentDescription = null,
                        tint = when (track.deliveryStatus) {
                            "CONFIRMED_ACK" -> EmeraldPrimary
                            "SENT_OVERLAY" -> CyberTealSecondary
                            else -> AmberShieldTertiary
                        },
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
                    color = when (track.deliveryStatus) {
                        "CONFIRMED_ACK" -> MaterialTheme.colorScheme.primaryContainer
                        "SENT_OVERLAY" -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.tertiaryContainer
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = when (track.deliveryStatus) {
                            "CONFIRMED_ACK" -> "ACK получен"
                            "SENT_OVERLAY" -> "Отправлено"
                            else -> "В очереди S&F"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when (track.deliveryStatus) {
                            "CONFIRMED_ACK" -> EmeraldPrimary
                            "SENT_OVERLAY" -> CyberTealSecondary
                            else -> AmberShieldTertiary
                        },
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
                    text = track.transport,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTealSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = " ${track.timestampFormatted}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                text = "Инспектор пакета",
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
                            Text("Путь пакета (по данным ядра):", style = MaterialTheme.typography.labelMedium, color = CyberTealSecondary)
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
                            Text("Криптографический конверт:", style = MaterialTheme.typography.labelMedium, color = CyberTealSecondary)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Cipher Payload Hex:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        track.cipherPayloadHex.ifBlank { "не сохранилось в базе" },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = EmeraldPrimary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Proof-of-Work штамп:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        track.hashcashPow.ifBlank { "не приложен" },
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

                        item {
                            Text("Доставка:", style = MaterialTheme.typography.labelMedium, color = CyberTealSecondary)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(track.transport, style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        text = "Время: ${track.timestampFormatted}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
