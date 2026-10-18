// File: app/src/main/java/com/example/ui/screens/ChatsScreen.kt
package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.ConversationEntity
import com.example.data.MessageEntity
import com.example.data.P2PFileTransferEntity
import com.example.data.YggRepository
import com.example.engine.ParsedYggContactQr
import com.example.ui.components.FileAttachmentBubbleCard
import com.example.ui.components.ImageMessagePreviewCard
import com.example.ui.components.MessageReactionsRow
import com.example.ui.components.QrContactModal
import com.example.ui.components.ReactionQuickPickerBar
import com.example.ui.components.RichAttachmentSheetDialog
import com.example.ui.components.VoiceCallOverlaySheet
import com.example.ui.components.VoiceMessagePlayerCard
import com.example.ui.components.VoiceRecordingComposerBar
import com.example.ui.components.resolveUriFileMetadata
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.YggGramUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsScreen(
    uiState: YggGramUiState,
    conversations: List<ConversationEntity>,
    allMessages: List<MessageEntity>,
    fileTransfers: List<P2PFileTransferEntity>,
    queuedOfflineCount: Int,
    onSelectChat: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (String) -> Unit,
    onToggleOfflineMode: () -> Unit,
    onSendMessage: (String, String, String?, Long) -> Unit,
    onSendVoiceMessage: (String, Int, String, String?, Long) -> Unit = { _, _, _, _, _ -> },
    onSendMediaOrFile: (String, String, Long, String, String?, String) -> Unit = { chatId, name, size, _, _, caption ->
        onSendMessage(chatId, caption, name, size)
    },
    onToggleReaction: (String, String) -> Unit = { _, _ -> },
    onStartVoiceCall: (String) -> Unit = {},
    onToggleCallMute: () -> Unit = {},
    onToggleCallSpeaker: () -> Unit = {},
    onTriggerCallTestAudioTone: () -> Unit = {},
    onEndVoiceCall: () -> Unit = {},
    onAddContactFromQr: (ParsedYggContactQr) -> Unit = {},
    onCreateNewChat: (String, String, Boolean, Boolean, Int) -> Unit,
    onDeleteChat: (String) -> Unit = {},
    onToggleTransferState: (P2PFileTransferEntity) -> Unit,
    onRunSendReceiveTest: (String?) -> Unit = {},
    onOpenSelfYggdrasilChat: (Boolean) -> Unit = {},
    onRetryMessage: (MessageEntity) -> Unit = {},
    onPingChatRoute: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var showNewChatDialog by rememberSaveable { mutableStateOf(false) }
    var showP2PAttachDialog by rememberSaveable { mutableStateOf(false) }
    var showQrModal by rememberSaveable { mutableStateOf(false) }
    var qrModalInitialTab by rememberSaveable { mutableIntStateOf(1) }

    val filteredConversations = remember(conversations, uiState.searchQuery, uiState.selectedChatFilter) {
        conversations.filter { conv ->
            val matchesQuery = uiState.searchQuery.isBlank() ||
                conv.title.contains(uiState.searchQuery, ignoreCase = true) ||
                conv.yggIpv6Address.contains(uiState.searchQuery, ignoreCase = true) ||
                conv.publicKeyHex.contains(uiState.searchQuery, ignoreCase = true)

            val matchesFilter = when (uiState.selectedChatFilter) {
                "DIRECT" -> conv.protocolType == "X3DH_DOUBLE_RATCHET"
                "MLS" -> conv.protocolType == "MLS_RFC9420"
                "OFFLINE_SF" -> !conv.isOnlineInMesh || uiState.isOfflineStoreAndForwardMode
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    val activeConversation = remember(conversations, uiState.selectedChatId) {
        conversations.find { it.chatId == uiState.selectedChatId }
    }

    val activeChatMessages = remember(allMessages, uiState.selectedChatId) {
        allMessages.filter { it.chatId == uiState.selectedChatId }.sortedBy { it.timestamp }
    }

    val activeChatTransfers = remember(fileTransfers, uiState.selectedChatId) {
        fileTransfers.filter { it.chatId == uiState.selectedChatId }
    }

    // System Photo Picker for sending Images directly in chat
    val chatPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val chat = activeConversation ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            val (name, size) = resolveUriFileMetadata(context, uri, "photo")
            val finalName = if (name.contains(".")) name else "$name.jpg"
            onSendMediaOrFile(
                chat.chatId,
                finalName,
                size,
                "IMAGE",
                uri.toString(),
                "Фотография ($finalName)"
            )
            showP2PAttachDialog = false
        }
    }

    // System Document / File Picker for sending any file
    val chatFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        val chat = activeConversation ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            val (name, size) = resolveUriFileMetadata(context, uri, "file")
            val mime = context.contentResolver.getType(uri).orEmpty()
            val isImg = mime.startsWith("image/") ||
                name.endsWith(".jpg", true) ||
                name.endsWith(".jpeg", true) ||
                name.endsWith(".png", true) ||
                name.endsWith(".webp", true)
            onSendMediaOrFile(
                chat.chatId,
                name,
                size,
                if (isImg) "IMAGE" else "FILE",
                uri.toString(),
                if (isImg) "Фотография ($name)" else "Файл P2P ($name)"
            )
            showP2PAttachDialog = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedSplitView = maxWidth >= 700.dp

        if (isExpandedSplitView) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(370.dp)
                        .fillMaxHeight()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    ChatListPane(
                        uiState = uiState,
                        conversations = filteredConversations,
                        fileTransfers = fileTransfers,
                        queuedOfflineCount = queuedOfflineCount,
                        onSelectChat = onSelectChat,
                        onSearchQueryChange = onSearchQueryChange,
                        onFilterChange = onFilterChange,
                        onToggleOfflineMode = onToggleOfflineMode,
                        onDeleteChat = onDeleteChat,
                        onToggleTransferState = onToggleTransferState,
                        onOpenNewChatDialog = { showNewChatDialog = true },
                        onOpenQrModal = { tab ->
                            qrModalInitialTab = tab
                            showQrModal = true
                        },
                        onRunSendReceiveTest = { onRunSendReceiveTest(uiState.selectedChatId) },
                        onOpenSelfYggdrasilChat = onOpenSelfYggdrasilChat
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (activeConversation != null) {
                        ChatDetailPane(
                            conversation = activeConversation,
                            messages = activeChatMessages,
                            transfers = activeChatTransfers,
                            isOfflineMode = uiState.isOfflineStoreAndForwardMode || !uiState.isNetworkAvailable,
                            showBackButton = false,
                            onBack = {},
                            onSendMessage = { text ->
                                onSendMessage(activeConversation.chatId, text, null, 0L)
                            },
                            onSendVoiceMessage = { dur, wave, uri, size ->
                                onSendVoiceMessage(activeConversation.chatId, dur, wave, uri, size)
                            },
                            onQuickPickGalleryPhoto = {
                                chatPhotoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onOpenP2PAttach = { showP2PAttachDialog = true },
                            onToggleReaction = onToggleReaction,
                            onStartVoiceCall = { onStartVoiceCall(activeConversation.chatId) },
                            onDeleteChat = { onDeleteChat(activeConversation.chatId) },
                            onToggleTransferState = onToggleTransferState,
                            onPingRoute = { onPingChatRoute(activeConversation.chatId) },
                            onSendSelfEchoTest = {
                                onSendMessage(
                                    activeConversation.chatId,
                                    "Проверка маршрута Yggdrasil через внешний публичный пир (${activeConversation.yggIpv6Address})",
                                    null,
                                    0L
                                )
                            },
                            onRetryMessage = onRetryMessage
                        )
                    } else {
                        EmptyChatSelectionPlaceholder(
                            onOpenSelfChat = { onOpenSelfYggdrasilChat(false) },
                            onOpenQrScanner = {
                                qrModalInitialTab = 1
                                showQrModal = true
                            }
                        )
                    }
                }
            }
        } else {
            if (activeConversation != null) {
                BackHandler { onSelectChat(null) }
                ChatDetailPane(
                    conversation = activeConversation,
                    messages = activeChatMessages,
                    transfers = activeChatTransfers,
                    isOfflineMode = uiState.isOfflineStoreAndForwardMode || !uiState.isNetworkAvailable,
                    showBackButton = true,
                    onBack = { onSelectChat(null) },
                    onSendMessage = { text ->
                        onSendMessage(activeConversation.chatId, text, null, 0L)
                    },
                    onSendVoiceMessage = { dur, wave, uri, size ->
                        onSendVoiceMessage(activeConversation.chatId, dur, wave, uri, size)
                    },
                    onQuickPickGalleryPhoto = {
                        chatPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onOpenP2PAttach = { showP2PAttachDialog = true },
                    onToggleReaction = onToggleReaction,
                    onStartVoiceCall = { onStartVoiceCall(activeConversation.chatId) },
                    onDeleteChat = { onDeleteChat(activeConversation.chatId) },
                    onToggleTransferState = onToggleTransferState,
                    onPingRoute = { onPingChatRoute(activeConversation.chatId) },
                    onSendSelfEchoTest = {
                        onSendMessage(
                            activeConversation.chatId,
                            "Проверка маршрута Yggdrasil через внешний публичный пир (${activeConversation.yggIpv6Address})",
                            null,
                            0L
                        )
                    },
                    onRetryMessage = onRetryMessage
                )
            } else {
                ChatListPane(
                    uiState = uiState,
                    conversations = filteredConversations,
                    fileTransfers = fileTransfers,
                    queuedOfflineCount = queuedOfflineCount,
                    onSelectChat = onSelectChat,
                    onSearchQueryChange = onSearchQueryChange,
                    onFilterChange = onFilterChange,
                    onToggleOfflineMode = onToggleOfflineMode,
                    onDeleteChat = onDeleteChat,
                    onToggleTransferState = onToggleTransferState,
                    onOpenNewChatDialog = { showNewChatDialog = true },
                    onOpenQrModal = { tab ->
                        qrModalInitialTab = tab
                        showQrModal = true
                    },
                    onRunSendReceiveTest = { onRunSendReceiveTest(null) },
                    onOpenSelfYggdrasilChat = onOpenSelfYggdrasilChat
                )
            }
        }
    }

    if (showNewChatDialog) {
        NewChatOrMlsGroupDialog(
            myIpv6Address = uiState.identity.yggIpv6Address,
            onDismiss = { showNewChatDialog = false },
            onOpenQrScanner = {
                showNewChatDialog = false
                qrModalInitialTab = 1
                showQrModal = true
            },
            onConfirm = { title, ipv6, isMls, sealed, ttl ->
                onCreateNewChat(title, ipv6, isMls, sealed, ttl)
                showNewChatDialog = false
            }
        )
    }

    if (showP2PAttachDialog && activeConversation != null) {
        RichAttachmentSheetDialog(
            peerTitle = activeConversation.title,
            onDismiss = { showP2PAttachDialog = false },
            onPickPhotoFromGallery = {
                chatPhotoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickDocumentFile = {
                chatFilePickerLauncher.launch("*/*")
            },
            onSendCustomFile = { fileName, fileSizeBytes, attachType, caption ->
                onSendMediaOrFile(
                    activeConversation.chatId,
                    fileName,
                    fileSizeBytes,
                    attachType,
                    null,
                    caption
                )
                showP2PAttachDialog = false
            }
        )
    }

    if (showQrModal) {
        QrContactModal(
            uiState = uiState,
            initialTab = qrModalInitialTab,
            onDismiss = { showQrModal = false },
            onContactScanned = { parsed ->
                showQrModal = false
                onAddContactFromQr(parsed)
            }
        )
    }

    uiState.activeVoiceCall?.let { callState ->
        VoiceCallOverlaySheet(
            callState = callState,
            onToggleMute = onToggleCallMute,
            onToggleSpeaker = onToggleCallSpeaker,
            onEndCall = onEndVoiceCall,
            onTriggerTestAudioTone = onTriggerCallTestAudioTone
        )
    }
}

@Composable
private fun ChatListPane(
    uiState: YggGramUiState,
    conversations: List<ConversationEntity>,
    fileTransfers: List<P2PFileTransferEntity>,
    queuedOfflineCount: Int,
    onSelectChat: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (String) -> Unit,
    onToggleOfflineMode: () -> Unit,
    onDeleteChat: (String) -> Unit,
    onToggleTransferState: (P2PFileTransferEntity) -> Unit,
    onOpenNewChatDialog: () -> Unit,
    onOpenQrModal: (Int) -> Unit,
    onRunSendReceiveTest: () -> Unit,
    onOpenSelfYggdrasilChat: (Boolean) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 104.dp)
        ) {
            item {
                MeshStatusTopCard(
                    uiState = uiState,
                    queuedOfflineCount = queuedOfflineCount,
                    onToggleOfflineMode = onToggleOfflineMode,
                    onRunSendReceiveTest = onRunSendReceiveTest,
                    onOpenSelfYggdrasilChat = { onOpenSelfYggdrasilChat(false) },
                    onOpenQrModal = { onOpenQrModal(0) }
                )
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("search_chats_input"),
                            placeholder = {
                                Text(
                                    "Поиск по имени, 200:... или ключу",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = "Поиск")
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )

                        IconButton(
                            onClick = { onOpenQrModal(1) },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .testTag("open_qr_scanner_top_button")
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Сканировать QR-код контакта",
                                tint = EmeraldPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val filters = listOf(
                            "ALL" to "Все (${conversations.size})",
                            "DIRECT" to "X3DH Личные",
                            "MLS" to "Группы MLS",
                            "OFFLINE_SF" to "Очередь S&F ($queuedOfflineCount)"
                        )
                        filters.forEach { (key, label) ->
                            FilterChip(
                                selected = uiState.selectedChatFilter == key,
                                onClick = { onFilterChange(key) },
                                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("chat_filter_$key")
                            )
                        }
                    }
                }
            }

            if (fileTransfers.isNotEmpty()) {
                item {
                    P2PFileTransfersStrip(
                        transfers = fileTransfers.take(3),
                        onToggleTransferState = onToggleTransferState
                    )
                }
            }

            if (conversations.isEmpty()) {
                item {
                    CleanStartZeroStateCard(
                        uiState = uiState,
                        onOpenNewChatDialog = onOpenNewChatDialog,
                        onOpenQrModal = onOpenQrModal,
                        onScanLanNodes = onRunSendReceiveTest,
                        onOpenSelfYggdrasilChat = onOpenSelfYggdrasilChat
                    )
                }
            } else {
                items(conversations, key = { it.chatId }) { conv ->
                    ConversationRowCard(
                        conversation = conv,
                        isSelected = conv.chatId == uiState.selectedChatId,
                        onClick = { onSelectChat(conv.chatId) },
                        onDelete = { onDeleteChat(conv.chatId) }
                    )
                }
            }
        }

        // Dual Action FABs: QR Scanner + New Contact
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { onOpenQrModal(1) },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.testTag("qr_scan_fab")
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Добавить по QR-коду")
            }

            FloatingActionButton(
                onClick = onOpenNewChatDialog,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("new_chat_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Новый P2P чат или группа MLS")
            }
        }
    }
}

@Composable
private fun CleanStartZeroStateCard(
    uiState: YggGramUiState,
    onOpenNewChatDialog: () -> Unit,
    onOpenQrModal: (Int) -> Unit,
    onScanLanNodes: () -> Unit,
    onOpenSelfYggdrasilChat: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val contactUri = remember(uiState.identity, uiState.localLanIp, uiState.localUdpPort) {
        val lanPart = uiState.localLanIp?.let { "&lan=$it:${uiState.localUdpPort}" } ?: ""
        "ygggram://${uiState.identity.yggIpv6Address}?pub=${uiState.identity.yggNodePublicKeyHex}$lanPart"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Реальная сеть Yggdrasil • Чистый старт",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Добавьте собеседника по QR-коду, проверьте связь отправкой сообщения себе через Yggdrasil или найдите узлы в локальной сети.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ваш узел Yggdrasil IPv6:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onOpenQrModal(0) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("empty_state_my_qr_button")
                            ) {
                                Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Мой QR", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("YggGram URI", contactUri))
                                    Toast.makeText(context, "Ваша визитка ygggram:// скопирована", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("copy_my_uri_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Копировать", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text(
                        text = uiState.identity.yggIpv6Address,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = EmeraldPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Ed25519: ${uiState.identity.yggNodePublicKeyHex}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = CyberTealSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Button(
                onClick = { onOpenSelfYggdrasilChat(true) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_self_yggdrasil_test_button")
            ) {
                Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Проверить сеть: отправить сообщение себе через Yggdrasil")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onOpenQrModal(1) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("empty_state_scan_qr_button")
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Сканировать QR")
                }

                OutlinedButton(
                    onClick = onOpenNewChatDialog,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("empty_state_add_contact_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ввести адрес")
                }
            }

            OutlinedButton(
                onClick = onScanLanNodes,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scan_lan_peers_button")
            ) {
                Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Поиск узлов YggGram в локальной Wi-Fi сети (LAN)")
            }
        }
    }
}

@Composable
private fun MeshStatusTopCard(
    uiState: YggGramUiState,
    queuedOfflineCount: Int,
    onToggleOfflineMode: () -> Unit,
    onRunSendReceiveTest: () -> Unit,
    onOpenSelfYggdrasilChat: () -> Unit,
    onOpenQrModal: () -> Unit
) {
    val isOfflineOrAirplane = uiState.isOfflineStoreAndForwardMode || !uiState.isNetworkAvailable || uiState.isAirplaneModeOn
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF0C1F1A),
                            Color(0xFF121A24)
                        )
                    )
                )
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !uiState.isNetworkAvailable || uiState.isAirplaneModeOn -> Color(0xFFEF4444)
                                    uiState.isOfflineStoreAndForwardMode -> AmberShieldTertiary
                                    else -> EmeraldPrimary
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when {
                                uiState.isAirplaneModeOn -> "Авиарежим активен • Сеть отключена"
                                !uiState.isNetworkAvailable -> "Нет подключения к сети (Офлайн)"
                                uiState.isOfflineStoreAndForwardMode -> "Офлайн S&F • Hashcash PoW"
                                else -> "Yggdrasil Userspace • ${uiState.antiDpiConfig.activePreset.badge}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "IPv6: ${uiState.identity.yggIpv6Address} • TX:${uiState.txPacketsCount} / RX:${uiState.rxPacketsCount}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isOfflineOrAirplane) {
                            Icons.Default.CloudOff
                        } else {
                            Icons.Default.CloudQueue
                        },
                        contentDescription = "Статус офлайн-режима",
                        tint = if (isOfflineOrAirplane) AmberShieldTertiary else EmeraldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = uiState.isOfflineStoreAndForwardMode,
                        onCheckedChange = { onToggleOfflineMode() },
                        modifier = Modifier.testTag("offline_mode_switch")
                    )
                }
            }

            if (queuedOfflineCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "В очереди S&F ожидают отправки: $queuedOfflineCount сообщ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberShieldTertiary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenSelfYggdrasilChat,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("top_card_self_chat_button")
                ) {
                    Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(15.dp), tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Избранное",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onOpenQrModal,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("top_card_my_qr_button")
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(15.dp), tint = EmeraldPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Мой QR",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldPrimary
                    )
                }

                OutlinedButton(
                    onClick = onRunSendReceiveTest,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("test_send_receive_loopback_button")
                ) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(15.dp), tint = CyberTealSecondary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Пинг",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTealSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun P2PFileTransfersStrip(
    transfers: List<P2PFileTransferEntity>,
    onToggleTransferState: (P2PFileTransferEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Потоки файлов P2P (FastCDC + Merkle SHA-256)",
            style = MaterialTheme.typography.labelMedium,
            color = CyberTealSecondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        transfers.forEach { tx ->
            val progress = if (tx.chunkCount > 0) {
                tx.completedChunks.toFloat() / tx.chunkCount.toFloat()
            } else 0f
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.FolderZip,
                                contentDescription = null,
                                tint = CyberTealSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = tx.fileName,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${tx.protocol} • ${tx.completedChunks}/${tx.chunkCount} чанков • Merkle: ${tx.merkleRootSha256.take(10)}...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = { onToggleTransferState(tx) },
                            modifier = Modifier.testTag("toggle_transfer_${tx.transferId}")
                        ) {
                            Icon(
                                imageVector = when (tx.status) {
                                    "ACTIVE_STREAMING" -> Icons.Default.Pause
                                    "VERIFIED_COMPLETE" -> Icons.Default.CheckCircle
                                    else -> Icons.Default.PlayArrow
                                },
                                contentDescription = "Управление P2P передачей",
                                tint = if (tx.status == "VERIFIED_COMPLETE") EmeraldPrimary else CyberTealSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (tx.status == "VERIFIED_COMPLETE") EmeraldPrimary else CyberTealSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRowCard(
    conversation: ConversationEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isSelfChat = conversation.chatId == YggRepository.SELF_NODE_CHAT_ID

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .border(
                width = if (isSelected || isSelfChat) 1.dp else 0.dp,
                color = when {
                    isSelected -> EmeraldPrimary
                    isSelfChat -> CyberTealSecondary.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .testTag("conversation_card_${conversation.chatId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceContainerHighest
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
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isSelfChat -> Icon(
                        Icons.Default.Bookmark,
                        contentDescription = "Избранное Yggdrasil",
                        tint = EmeraldPrimary
                    )
                    conversation.protocolType == "MLS_RFC9420" -> Icon(
                        Icons.Default.Groups,
                        contentDescription = "Группа MLS",
                        tint = CyberTealSecondary
                    )
                    else -> Text(
                        text = conversation.title.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = EmeraldPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = conversation.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (conversation.isVerified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = "Ключ Ed25519 верифицирован",
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = timeFormatter.format(Date(conversation.lastTimestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "${conversation.yggIpv6Address} • ${conversation.routeTransportSummary}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CyberTealSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = EmeraldPrimary,
                            shape = CircleShape
                        ) {
                            Text(
                                text = conversation.unreadCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF042217),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("delete_chat_icon_${conversation.chatId}")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить чат",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить чат?") },
            text = { Text("Вы уверены, что хотите удалить чат «${conversation.title}» и всю историю сообщений?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun ChatDetailPane(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    transfers: List<P2PFileTransferEntity>,
    isOfflineMode: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendVoiceMessage: (Int, String, String?, Long) -> Unit,
    onQuickPickGalleryPhoto: () -> Unit,
    onOpenP2PAttach: () -> Unit,
    onToggleReaction: (String, String) -> Unit,
    onStartVoiceCall: () -> Unit,
    onDeleteChat: () -> Unit = {},
    onToggleTransferState: (P2PFileTransferEntity) -> Unit,
    onPingRoute: () -> Unit = {},
    onSendSelfEchoTest: () -> Unit = {},
    onRetryMessage: (MessageEntity) -> Unit = {}
) {
    val context = LocalContext.current
    var messageInput by rememberSaveable(conversation.chatId) { mutableStateOf("") }
    var showCipherInspector by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var isRecordingVoice by rememberSaveable(conversation.chatId) { mutableStateOf(false) }
    val isSelfChat = YggRepository.isSelfChatId(conversation.chatId)

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed with voice recording whether granted or in cloud emulator fallback mode
        isRecordingVoice = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Header with full IPv6, Route Status, Voice Call, and Ping / Echo actions
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (showBackButton) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("chat_back_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад к списку чатов"
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Сквозное шифрование активно",
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Text(
                            text = conversation.routeTransportSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (conversation.isOnlineInMesh) EmeraldPrimary else AmberShieldTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Voice Call Button
                    IconButton(
                        onClick = onStartVoiceCall,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .testTag("start_voice_call_button")
                    ) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = "Голосовой звонок через Yggdrasil",
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Yggdrasil IPv6", conversation.yggIpv6Address))
                            Toast.makeText(context, "IPv6 скопирован: ${conversation.yggIpv6Address}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Скопировать IPv6",
                            tint = CyberTealSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { showDeleteConfirmDialog = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("delete_chat_in_detail_button")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить чат",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Action Bar inside Chat Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onPingRoute,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .testTag("ping_chat_route_button")
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Пинг Yggdrasil",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isSelfChat) {
                        Button(
                            onClick = onSendSelfEchoTest,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .testTag("send_self_echo_test_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Эхо-тест сети",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    TextButton(
                        onClick = { showCipherInspector = !showCipherInspector },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("toggle_cipher_inspector_button")
                    ) {
                        Text(
                            text = if (showCipherInspector) "Скрыть крипто" else "Крипто-лог",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                AnimatedVisibility(visible = showCipherInspector) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "IPv6: ${conversation.yggIpv6Address}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = EmeraldPrimary
                            )
                            Text(
                                text = "Ed25519 PubKey: ${conversation.publicKeyHex.take(40)}...",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = CyberTealSecondary
                            )
                            Text(
                                text = "Маршрут: ${conversation.routeTransportSummary} • Sealed Sender: ${if (conversation.sealedSenderEnabled) "ВКЛ" else "ВЫКЛ"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (transfers.isNotEmpty()) {
                item {
                    P2PFileTransfersStrip(
                        transfers = transfers,
                        onToggleTransferState = onToggleTransferState
                    )
                }
            }

            if (messages.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isSelfChat) {
                                    "Чат «Избранное • Мой узел Yggdrasil»"
                                } else {
                                    "История сообщений пуста"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = EmeraldPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isSelfChat) {
                                    "Отправляйте текст, голосовые сообщения, фото или файлы через внешний пир Yggdrasil. Нажмите на любое сообщение для добавления эмодзи-реакций."
                                } else {
                                    "Отправьте сообщение, голосовую запись, картинку или файл ниже. Удерживайте или нажмите на сообщение для быстрой реакции."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.messageId }) { msg ->
                EncryptedMessageBubble(
                    message = msg,
                    showCipherHex = showCipherInspector,
                    onToggleReaction = { emoji -> onToggleReaction(msg.messageId, emoji) },
                    onRetry = { onRetryMessage(msg) }
                )
            }
        }

        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Удалить чат «${conversation.title}»?") },
                text = { Text("Это действие удалит весь диалог и связанную с ним историю сообщений из памяти устройства.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirmDialog = false
                            onDeleteChat()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Удалить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }

        // Message Composer Bar or Live Voice Recording Bar
        if (isRecordingVoice) {
            VoiceRecordingComposerBar(
                onCancelRecording = { isRecordingVoice = false },
                onFinishAndSendVoice = { durationSec, waveformCsv, localUri, _, sizeBytes ->
                    isRecordingVoice = false
                    onSendVoiceMessage(durationSec, waveformCsv, localUri, sizeBytes)
                }
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onOpenP2PAttach,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("attach_p2p_file_button")
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = "Прикрепить файл или фото",
                            tint = CyberTealSecondary
                        )
                    }

                    IconButton(
                        onClick = onQuickPickGalleryPhoto,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("quick_attach_image_button")
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "Отправить картинку из галереи",
                            tint = EmeraldPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input"),
                        placeholder = {
                            Text(
                                when {
                                    isOfflineMode -> "Офлайн S&F: в очередь..."
                                    isSelfChat -> "Сообщение себе через Yggdrasil..."
                                    else -> "Сообщение..."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(18.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    if (messageInput.isBlank()) {
                        IconButton(
                            onClick = {
                                val hasMicPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMicPerm) {
                                    isRecordingVoice = true
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .testTag("record_voice_message_button")
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Записать голосовое сообщение",
                                tint = EmeraldPrimary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (messageInput.isNotBlank()) {
                                    onSendMessage(messageInput)
                                    messageInput = ""
                                }
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("send_message_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Отправить зашифрованное сообщение",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EncryptedMessageBubble(
    message: MessageEntity,
    showCipherHex: Boolean,
    onToggleReaction: (String) -> Unit,
    onRetry: () -> Unit
) {
    var showReactionPicker by rememberSaveable(message.messageId) { mutableStateOf(false) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val isQueued = message.deliveryState == "QUEUED_OFFLINE_SF"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        AnimatedVisibility(visible = showReactionPicker) {
            ReactionQuickPickerBar(
                messageId = message.messageId,
                onSelectEmoji = onToggleReaction,
                onDismiss = { showReactionPicker = false }
            )
        }

        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isOutgoing) 16.dp else 4.dp,
                bottomEnd = if (message.isOutgoing) 4.dp else 16.dp
            ),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .combinedClickable(
                    onClick = {
                        if (isQueued) {
                            onRetry()
                        } else {
                            showReactionPicker = !showReactionPicker
                        }
                    },
                    onLongClick = {
                        showReactionPicker = !showReactionPicker
                    }
                )
                .testTag("message_bubble_${message.messageId}")
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (message.isOutgoing) EmeraldPrimary else CyberTealSecondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = message.senderIpv6.take(18),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { showReactionPicker = !showReactionPicker },
                        modifier = Modifier
                            .size(22.dp)
                            .testTag("open_reactions_button_${message.messageId}")
                    ) {
                        Icon(
                            Icons.Default.AddReaction,
                            contentDescription = "Поставить реакцию",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                // Render Voice Message, Image Preview, or File Attachment Card
                when (message.attachmentType) {
                    "VOICE" -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        VoiceMessagePlayerCard(
                            messageId = message.messageId,
                            durationSec = message.voiceDurationSec.coerceAtLeast(1),
                            waveformCsv = message.voiceWaveformCsv,
                            attachmentSizeBytes = message.attachmentSizeBytes,
                            attachmentUri = message.attachmentUri
                        )
                    }
                    "IMAGE" -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        ImageMessagePreviewCard(
                            messageId = message.messageId,
                            imageName = message.attachmentName ?: "image.jpg",
                            imageUri = message.attachmentUri,
                            sizeBytes = message.attachmentSizeBytes,
                            merkleRootHex = message.merkleRootHex
                        )
                    }
                    else -> {
                        if (message.attachmentName != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FileAttachmentBubbleCard(
                                messageId = message.messageId,
                                fileName = message.attachmentName,
                                fileUri = message.attachmentUri,
                                sizeBytes = message.attachmentSizeBytes,
                                merkleRootHex = message.merkleRootHex
                            )
                        }
                    }
                }

                if (message.plainText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.plainText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Telegram/WhatsApp-style Reaction Pills Row
                MessageReactionsRow(
                    messageId = message.messageId,
                    reactionsJson = message.reactionsJson,
                    onToggleReaction = onToggleReaction
                )

                if (showCipherHex) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "AEAD Cipher: ${message.cipherPayloadHex.take(36)}...\nPoW: ${message.hashcashPowToken}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = AmberShieldTertiary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.sealedSenderFlag) {
                            Icon(
                                Icons.Default.VisibilityOff,
                                contentDescription = "Sealed Sender",
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = "#${message.ratchetStep} • ${timeFormatter.format(Date(message.timestamp))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val (statusIcon, statusText, statusTint) = when (message.deliveryState) {
                        "QUEUED_OFFLINE_SF" -> Triple(Icons.Default.Schedule, "В очереди S&F • Повторить", AmberShieldTertiary)
                        "SENT_P2P" -> Triple(Icons.Default.DoneAll, "В оверлее Yggdrasil", CyberTealSecondary)
                        else -> Triple(Icons.Default.DoneAll, "Доставлено • ACK", EmeraldPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = statusText,
                            tint = statusTint,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = statusTint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatSelectionPlaceholder(
    onOpenSelfChat: () -> Unit,
    onOpenQrScanner: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Выберите или создайте диалог в сети Yggdrasil",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Вы можете проверить реальную работу сети, отправив сообщение самому себе через внешние публичные пиры Yggdrasil, или отсканировать QR-код контакта.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenSelfChat) {
                    Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Избранное (Мой узел)")
                }
                OutlinedButton(onClick = onOpenQrScanner) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Сканировать QR")
                }
            }
        }
    }
}

@Composable
private fun NewChatOrMlsGroupDialog(
    myIpv6Address: String,
    onDismiss: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onConfirm: (String, String, Boolean, Boolean, Int) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var ipv6Input by rememberSaveable { mutableStateOf("") }
    var directHostPort by rememberSaveable { mutableStateOf("") }
    var isMlsGroup by rememberSaveable { mutableStateOf(false) }
    var sealedSender by rememberSaveable { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Добавить контакт Yggdrasil / Mimir",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenQrScanner,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_scan_qr_button")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("По QR-коду", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            title = "Избранное • Мой узел Yggdrasil"
                            ipv6Input = myIpv6Address
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Тест себе", style = MaterialTheme.typography.labelMedium)
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Имя собеседника или название группы") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_chat_title_input")
                )
                OutlinedTextField(
                    value = ipv6Input,
                    onValueChange = { ipv6Input = it },
                    label = { Text("IPv6 Yggdrasil (200:...), Ed25519 ключ или ygggram://") },
                    placeholder = { Text("Например: 200:... или публичный ключ hex") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_chat_ipv6_input")
                )
                OutlinedTextField(
                    value = directHostPort,
                    onValueChange = { directHostPort = it },
                    label = { Text("Прямой IP:Порт в LAN (опционально)") },
                    placeholder = { Text("Например: 192.168.1.15:51921") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_chat_direct_endpoint_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Группа MLS (RFC 9420 TreeKEM)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isMlsGroup, onCheckedChange = { isMlsGroup = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sealed Sender (скрыть отправителя)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = sealedSender, onCheckedChange = { sealedSender = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() || ipv6Input.isNotBlank()) {
                        val combined = if (directHostPort.isNotBlank()) {
                            "${ipv6Input.trim()} | ${directHostPort.trim()}"
                        } else {
                            ipv6Input.trim()
                        }
                        onConfirm(title.ifBlank { "Контакт Yggdrasil" }, combined, isMlsGroup, sealedSender, 0)
                    }
                },
                modifier = Modifier.testTag("confirm_new_chat_button")
            ) {
                Text("Открыть чат")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
