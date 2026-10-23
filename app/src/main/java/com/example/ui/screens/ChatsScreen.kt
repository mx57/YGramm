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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.CallLogEntity
import com.example.data.ConversationEntity
import com.example.data.MessageEntity
import com.example.data.YggRepository
import com.example.engine.ParsedYggContactQr
import com.example.ui.components.FileAttachmentBubbleCard
import com.example.ui.components.ImageMessagePreviewCard
import com.example.ui.components.MessageReactionsRow
import com.example.ui.components.QrContactModal
import com.example.ui.components.ReactionQuickPickerBar
import com.example.ui.components.RichAttachmentSheetDialog
import com.example.ui.components.VoiceMessagePlayerCard
import com.example.ui.components.VoiceRecordingComposerBar
import com.example.ui.components.YggAvatar
import com.example.ui.components.YggSwitch
import com.example.ui.components.resolveUriFileMetadata
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.YggGramUiState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ChatsScreen(
    uiState: YggGramUiState,
    conversations: List<ConversationEntity>,
    allMessages: List<MessageEntity>,
    callLog: List<CallLogEntity> = emptyList(),
    messageSearchResults: List<MessageEntity> = emptyList(),
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
    onSendReply: (String, String, String) -> Unit = { _, _, _ -> },
    onForwardMessage: (String, String) -> Unit = { _, _ -> },
    onTyping: (String) -> Unit = {},
    onToggleReaction: (String, String) -> Unit = { _, _ -> },
    onStartVoiceCall: (String) -> Unit = {},
    onAddContactFromQr: (ParsedYggContactQr) -> Unit = {},
    onCreateNewChat: (String, String, Int) -> Unit,
    onDeleteChat: (String) -> Unit = {},
    onRunSendReceiveTest: (String?) -> Unit = {},
    onOpenSelfYggdrasilChat: (Boolean) -> Unit = {},
    onRetryMessage: (MessageEntity) -> Unit = {},
    onPingChatRoute: (String) -> Unit = {},
    onSetContactVerified: (String, Boolean) -> Unit = { _, _ -> }
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
                "DIRECT" -> !conv.chatId.startsWith("chat_self_ygg_node")
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

    val conversationTitles = remember(conversations) {
        conversations.associate { it.chatId to it.title }
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
                        callLog = callLog,
                        searchResults = messageSearchResults,
                        conversationTitles = conversationTitles,
                        queuedOfflineCount = queuedOfflineCount,
                        onSelectChat = onSelectChat,
                        onSearchQueryChange = onSearchQueryChange,
                        onFilterChange = onFilterChange,
                        onToggleOfflineMode = onToggleOfflineMode,
                        onDeleteChat = onDeleteChat,
                        onStartVoiceCall = onStartVoiceCall,
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
                            onPingRoute = { onPingChatRoute(activeConversation.chatId) },
                            typingLabel = uiState.typingPeers[activeConversation.chatId],
                            forwardTargets = conversations,
                            onSendReply = { text, quotedId ->
                                onSendReply(activeConversation.chatId, text, quotedId)
                            },
                            onForwardMessage = onForwardMessage,
                            onTyping = { onTyping(activeConversation.chatId) },
                            onSetContactVerified = { verified ->
                                onSetContactVerified(activeConversation.chatId, verified)
                            },
                            onSendSelfEchoTest = {
                                onSendMessage(
                                    activeConversation.chatId,
                                    "Проверка маршрута Yggdrasil через оверлей 0200::/7 (${activeConversation.yggIpv6Address})",
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
                    onPingRoute = { onPingChatRoute(activeConversation.chatId) },
                    typingLabel = uiState.typingPeers[activeConversation.chatId],
                    forwardTargets = conversations,
                    onSendReply = { text, quotedId ->
                        onSendReply(activeConversation.chatId, text, quotedId)
                    },
                    onForwardMessage = onForwardMessage,
                    onTyping = { onTyping(activeConversation.chatId) },
                    onSetContactVerified = { verified ->
                        onSetContactVerified(activeConversation.chatId, verified)
                    },
                    onSendSelfEchoTest = {
                        onSendMessage(
                            activeConversation.chatId,
                            "Проверка маршрута Yggdrasil через оверлей 0200::/7 (${activeConversation.yggIpv6Address})",
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
                    callLog = callLog,
                    searchResults = messageSearchResults,
                    conversationTitles = conversationTitles,
                    queuedOfflineCount = queuedOfflineCount,
                    onSelectChat = onSelectChat,
                    onSearchQueryChange = onSearchQueryChange,
                    onFilterChange = onFilterChange,
                    onToggleOfflineMode = onToggleOfflineMode,
                    onDeleteChat = onDeleteChat,
                    onStartVoiceCall = onStartVoiceCall,
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
        NewChatDialog(
            myIpv6Address = uiState.identity.yggIpv6Address,
            onDismiss = { showNewChatDialog = false },
            onOpenQrScanner = {
                showNewChatDialog = false
                qrModalInitialTab = 1
                showQrModal = true
            },
            onConfirm = { title, ipv6 ->
                onCreateNewChat(title, ipv6, uiState.privacyMatrix.defaultDisappearingSeconds)
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

}

@Composable
private fun ChatListPane(
    uiState: YggGramUiState,
    conversations: List<ConversationEntity>,
    callLog: List<CallLogEntity>,
    searchResults: List<MessageEntity>,
    conversationTitles: Map<String, String>,
    queuedOfflineCount: Int,
    onSelectChat: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (String) -> Unit,
    onToggleOfflineMode: () -> Unit,
    onDeleteChat: (String) -> Unit,
    onStartVoiceCall: (String) -> Unit,
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
                                    "Поиск по имени, IPv6 или ключу",
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
                            "DIRECT" to "Личные",
                            "OFFLINE_SF" to "Очередь ($queuedOfflineCount)"
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

            if (uiState.searchQuery.isNotBlank()) {
                item(key = "message_search_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = CyberTealSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "В сообщениях: ${searchResults.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(searchResults.take(20), key = { "hit_${it.messageId}" }) { msg ->
                    MessageSearchHitRow(
                        message = msg,
                        chatTitle = conversationTitles[msg.chatId] ?: "Диалог",
                        query = uiState.searchQuery,
                        onClick = { onSelectChat(msg.chatId) }
                    )
                }
            }

            if (callLog.isNotEmpty()) {
                item(key = "call_log_section") {
                    CallLogSection(
                        entries = callLog.take(8),
                        onOpenChat = { onSelectChat(it) },
                        onRedial = onStartVoiceCall
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

        FloatingActionButton(
            onClick = onOpenNewChatDialog,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("new_chat_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Новый чат Yggdrasil")
        }
    }
}

@Composable
private fun MessageSearchHitRow(
    message: MessageEntity,
    chatTitle: String,
    query: String,
    onClick: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    val snippet = remember(message.plainText, message.attachmentName, query) {
        buildSearchSnippet(message.plainText, query)
            .ifBlank { message.attachmentName?.let { "📎 $it" } ?: "Сообщение без текста" }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clickable(onClick = onClick)
            .testTag("message_search_hit_${message.messageId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chatTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = CyberTealSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = timeFormatter.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun buildSearchSnippet(text: String, query: String, radius: Int = 48): String {
    if (text.isBlank()) return ""
    val idx = text.indexOf(query, ignoreCase = true)
    if (idx < 0 || query.isBlank()) return text.take(120)
    val start = (idx - radius).coerceAtLeast(0)
    val end = (idx + query.length + radius).coerceAtMost(text.length)
    return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
}

@Composable
private fun CallLogSection(
    entries: List<CallLogEntity>,
    onOpenChat: (String) -> Unit,
    onRedial: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("call_log_section"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Журнал вызовов",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) {
                        "Свернуть журнал вызовов"
                    } else {
                        "Развернуть журнал вызовов"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                entries.forEachIndexed { index, entry ->
                    CallLogRow(entry = entry, onOpenChat = onOpenChat, onRedial = onRedial)
                    if (index != entries.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallLogRow(
    entry: CallLogEntity,
    onOpenChat: (String) -> Unit,
    onRedial: (String) -> Unit
) {
    val incoming = entry.direction == "INCOMING"
    val missed = entry.outcome == "MISSED"
    val failed = entry.outcome == "FAILED"
    val icon = when {
        entry.outcome == "COMPLETED" && incoming -> Icons.AutoMirrored.Filled.CallReceived
        entry.outcome == "COMPLETED" -> Icons.AutoMirrored.Filled.CallMade
        entry.outcome == "CANCELED" || failed -> Icons.Default.CallEnd
        else -> Icons.AutoMirrored.Filled.CallMissed
    }
    val tint = when {
        missed || failed -> MaterialTheme.colorScheme.error
        entry.outcome == "COMPLETED" -> EmeraldPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val outcomeLabel = when (entry.outcome) {
        "COMPLETED" -> "завершён"
        "MISSED" -> "пропущен"
        "DECLINED" -> "отклонён"
        "BUSY" -> "занято"
        "CANCELED" -> "отменён"
        "FAILED" -> "не доставлен"
        else -> entry.outcome.lowercase(Locale.getDefault())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.chatId.isNotBlank()) { onOpenChat(entry.chatId) }
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.peerName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatCallLogTime(entry.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val details = buildString {
                append(if (incoming) "Входящий" else "Исходящий")
                append(" • ")
                append(outcomeLabel)
                if (entry.durationSeconds > 0) {
                    append(" • ")
                    append(formatCallLogDuration(entry.durationSeconds))
                }
                if (entry.transport.isNotBlank()) {
                    append(" • ")
                    append(compactCallTransport(entry.transport))
                }
            }
            Text(
                text = details,
                style = MaterialTheme.typography.labelSmall,
                color = if (missed || failed) tint else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.chatId.isNotBlank()) {
            IconButton(
                onClick = { onRedial(entry.chatId) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Перезвонить ${entry.peerName}",
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun formatCallLogTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(
        if (sameDay) "HH:mm" else "dd.MM HH:mm",
        Locale.getDefault()
    ).format(Date(timestamp))
}

private fun formatCallLogDuration(seconds: Int): String =
    if (seconds >= 60) "%d:%02d".format(Locale.US, seconds / 60, seconds % 60) else "$seconds с"

private fun compactCallTransport(transport: String): String = when (transport.uppercase(Locale.US)) {
    "LAN_DIRECT" -> "LAN"
    "YGG_OVERLAY" -> "Yggdrasil"
    "LOCAL_PROFILE" -> "локально"
    else -> transport
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
                Text("Проверить сеть: круговой маршрут через Yggdrasil (0200::/7)")
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
                    YggSwitch(
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
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YggAvatar(
                avatarUri = conversation.avatarUri,
                name = conversation.title,
                placeholderIcon = if (isSelfChat) Icons.Default.Bookmark else null
            )

            Spacer(modifier = Modifier.width(10.dp))

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
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (conversation.isOnlineInMesh) {
                                        EmeraldPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    }
                                )
                                .testTag("conversation_online_dot_${conversation.chatId}")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
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
    onPingRoute: () -> Unit = {},
    onSendSelfEchoTest: () -> Unit = {},
    onRetryMessage: (MessageEntity) -> Unit = {},
    typingLabel: String? = null,
    forwardTargets: List<ConversationEntity> = emptyList(),
    onSendReply: (String, String) -> Unit = { _, _ -> },
    onForwardMessage: (String, String) -> Unit = { _, _ -> },
    onTyping: () -> Unit = {},
    onSetContactVerified: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var messageInput by rememberSaveable(conversation.chatId) { mutableStateOf("") }
    var showCipherInspector by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var isRecordingVoice by rememberSaveable(conversation.chatId) { mutableStateOf(false) }
    var replyTarget by remember(conversation.chatId) { mutableStateOf<MessageEntity?>(null) }
    var forwardTarget by remember(conversation.chatId) { mutableStateOf<MessageEntity?>(null) }
    val isSelfChat = YggRepository.isSelfChatId(conversation.chatId)
    val wireIdIndex = remember(messages) {
        buildMap {
            messages.forEach { msg ->
                put(msg.messageId, msg)
                if (msg.peerMessageId.isNotBlank()) put(msg.peerMessageId, msg)
            }
        }
    }

    val messageListState = rememberLazyListState()
    var anchoredChatId by remember { mutableStateOf<String?>(null) }
    val lastMessageId = messages.lastOrNull()?.messageId

    // On chat open the newest message is shown instantly; when the conversation grows
    // the list glides to the bottom instead of snapping.
    LaunchedEffect(conversation.chatId, lastMessageId, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val targetIndex = messages.lastIndex.coerceAtLeast(0)
        if (anchoredChatId != conversation.chatId) {
            anchoredChatId = conversation.chatId
            messageListState.scrollToItem(targetIndex)
        } else {
            messageListState.animateScrollToItem(targetIndex)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isRecordingVoice = true
        } else {
            Toast.makeText(
                context,
                "Нет доступа к микрофону: запись голосового невозможна",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val callAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartVoiceCall()
        } else {
            Toast.makeText(
                context,
                "Звонок невозможен: нет доступа к микрофону",
                Toast.LENGTH_LONG
            ).show()
        }
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
                    YggAvatar(
                        avatarUri = conversation.avatarUri,
                        name = conversation.title,
                        size = 34.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
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
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { showCipherInspector = !showCipherInspector },
                                modifier = Modifier
                                    .size(24.dp)
                                    .testTag("toggle_cipher_inspector_button")
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = if (showCipherInspector) {
                                        "Скрыть крипто-лог"
                                    } else {
                                        "Показать крипто-лог"
                                    },
                                    tint = if (showCipherInspector) AmberShieldTertiary else EmeraldPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Text(
                            text = conversation.routeTransportSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (conversation.isOnlineInMesh) EmeraldPrimary else AmberShieldTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (typingLabel != null) {
                            Text(
                                text = "$typingLabel печатает…",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTealSecondary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("typing_indicator")
                            )
                        }
                    }

                    IconButton(
                        onClick = onPingRoute,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("ping_chat_route_button")
                    ) {
                        Icon(
                            Icons.Default.NetworkCheck,
                            contentDescription = "Пинг Yggdrasil",
                            tint = CyberTealSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (isSelfChat) {
                        IconButton(
                            onClick = onSendSelfEchoTest,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("send_self_echo_test_button")
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Тест оверлея",
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Voice Call Button (недоступен для чата со своим узлом)
                    if (!isSelfChat) {
                        IconButton(
                            onClick = {
                                if (
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    onStartVoiceCall()
                                } else {
                                    callAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
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
                    }

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
                                text = "Маршрут: ${conversation.routeTransportSummary} • Сообщения: X25519 + AES-256-GCM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(6.dp))
                            val peerSafetyNumber = remember(conversation.publicKeyHex) {
                                com.example.engine.YggCryptoEngine.safetyNumber(conversation.publicKeyHex)
                            }
                            Text(
                                text = "Ключевое число узла (safety number)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = peerSafetyNumber.ifBlank { "Ключ узла неизвестен — дождитесь обмена сообщениями" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (conversation.isVerified) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Сверьте это число с экраном «Мой QR» на устройстве собеседника — совпадение означает, что ключи не подменены.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (conversation.isVerified) Icons.Default.VerifiedUser else Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (conversation.isVerified) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (conversation.isVerified) "Ключ верифицирован" else "Ключ не проверен",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                TextButton(
                                    onClick = { onSetContactVerified(!conversation.isVerified) },
                                    modifier = Modifier.testTag("toggle_contact_verified_${conversation.chatId}")
                                ) {
                                    Text(
                                        if (conversation.isVerified) "Снять отметку" else "Совпадает",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            state = messageListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                    quotedMessage = wireIdIndex[msg.replyToMessageId],
                    showCipherHex = showCipherInspector,
                    onToggleReaction = { emoji -> onToggleReaction(msg.messageId, emoji) },
                    onReply = { replyTarget = msg },
                    onForward = { forwardTarget = msg },
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
            Column {
                val quoted = replyTarget
                if (quoted != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = CyberTealSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ответ ${quoted.senderName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CyberTealSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = quoted.plainText.ifBlank {
                                        quoted.attachmentName ?: "Вложение"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { replyTarget = null },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("cancel_reply_button")
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Отменить ответ",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
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
                        onValueChange = { updated ->
                            messageInput = updated
                            if (updated.isNotBlank()) onTyping()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input"),
                        placeholder = {
                            Text(
                                when {
                                    isOfflineMode -> "Офлайн S&F: в очередь..."
                                    isSelfChat -> "Локальная заметка своему узлу..."
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
                                    val activeReply = replyTarget
                                    if (activeReply != null) {
                                        onSendReply(messageInput, activeReply.messageId)
                                    } else {
                                        onSendMessage(messageInput)
                                    }
                                    replyTarget = null
                                    messageInput = ""
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
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

    val pendingForward = forwardTarget
    if (pendingForward != null) {
        val targets = forwardTargets.filter { it.chatId != conversation.chatId }
        AlertDialog(
            onDismissRequest = { forwardTarget = null },
            title = { Text("Переслать сообщение") },
            text = {
                if (targets.isEmpty()) {
                    Text("Нет других диалогов: создайте чат, чтобы переслать сообщение.")
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        targets.forEach { target ->
                            TextButton(
                                onClick = {
                                    forwardTarget = null
                                    onForwardMessage(pendingForward.messageId, target.chatId)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("forward_target_${target.chatId}")
                            ) {
                                Text(
                                    text = target.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { forwardTarget = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EncryptedMessageBubble(
    message: MessageEntity,
    quotedMessage: MessageEntity?,
    showCipherHex: Boolean,
    onToggleReaction: (String) -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onRetry: () -> Unit
) {
    var showReactionPicker by rememberSaveable(message.messageId) { mutableStateOf(false) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    // Both an offline queue item and an unconfirmed overlay send can be retried by tapping.
    val canRetry = message.deliveryState == "QUEUED_OFFLINE_SF" || message.deliveryState == "SENT_P2P"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        AnimatedVisibility(visible = showReactionPicker) {
            ReactionQuickPickerBar(
                messageId = message.messageId,
                onSelectEmoji = onToggleReaction,
                onDismiss = { showReactionPicker = false },
                onReply = {
                    showReactionPicker = false
                    onReply()
                },
                onForward = {
                    showReactionPicker = false
                    onForward()
                }
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
                        if (canRetry) {
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
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!message.isOutgoing) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTealSecondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (message.forwardedFrom.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 3.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Forward,
                            contentDescription = "Переслано",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Переслано от ${message.forwardedFrom}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (message.replyToMessageId.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(CyberTealSecondary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = quotedMessage?.senderName ?: "Цитата",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CyberTealSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = quotedMessage?.let { quoted ->
                                        quoted.plainText.ifBlank { quoted.attachmentName ?: "Вложение" }
                                    } ?: "Исходное сообщение недоступно",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
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
                            sizeBytes = message.attachmentSizeBytes
                        )
                    }
                    else -> {
                        if (message.attachmentName != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            FileAttachmentBubbleCard(
                                messageId = message.messageId,
                                fileName = message.attachmentName,
                                fileUri = message.attachmentUri,
                                sizeBytes = message.attachmentSizeBytes
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
                        text = "AEAD Cipher: ${message.cipherPayloadHex.take(36)}...\nPoW: ${message.hashcashPowToken}\nEnvelope: v${message.envelopeVersion} • HKDF step #${message.ratchetStep}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = AmberShieldTertiary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.envelopeVersion >= 2) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Сквозное шифрование X25519 + AES-256-GCM",
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = timeFormatter.format(Date(message.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (message.isOutgoing) {
                            val (statusIcon, statusText, statusTint) = when (message.deliveryState) {
                                "QUEUED_OFFLINE_SF" -> Triple(Icons.Default.Schedule, "В очереди", AmberShieldTertiary)
                                "SENT_P2P" -> Triple(Icons.Default.Schedule, "Ожидает доставки", AmberShieldTertiary)
                                "LOCAL_NOTE" -> Triple(Icons.Default.Bookmark, "Заметка", CyberTealSecondary)
                                "DELIVERED" -> Triple(Icons.Default.DoneAll, "Доставлено", EmeraldPrimary)
                                "READ" -> Triple(Icons.Default.DoneAll, "Прочитано", CyberTealSecondary)
                                else -> Triple(Icons.Default.Schedule, "Не подтверждено", MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = statusText,
                                tint = statusTint,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = statusTint
                            )
                            if (canRetry) {
                                IconButton(
                                    onClick = onRetry,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .testTag("retry_message_${message.messageId}")
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Повторить отправку",
                                        tint = AmberShieldTertiary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = { showReactionPicker = !showReactionPicker },
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("open_reactions_button_${message.messageId}")
                    ) {
                        Icon(
                            Icons.Default.AddReaction,
                            contentDescription = "Поставить реакцию",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
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
private fun NewChatDialog(
    myIpv6Address: String,
    onDismiss: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var ipv6Input by rememberSaveable { mutableStateOf("") }
    var directHostPort by rememberSaveable { mutableStateOf("") }

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
                        Text("Мой узел", style = MaterialTheme.typography.labelMedium)
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Имя собеседника") },
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
                        onConfirm(title.ifBlank { "Контакт Yggdrasil" }, combined)
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
