// File: app/src/main/java/com/example/ui/screens/ChatsScreen.kt
package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.CallLogEntity
import com.example.data.ConversationEntity
import com.example.data.GroupMemberCount
import com.example.data.GroupMemberEntity
import com.example.data.MessageEntity
import com.example.data.YggRepository
import com.example.engine.ParsedYggContactQr
import com.example.engine.YggCryptoEngine
import com.example.ui.components.FileAttachmentBubbleCard
import com.example.ui.components.ImageMessagePreviewCard
import com.example.ui.components.MessageReactionsRow
import com.example.ui.components.QrContactModal
import com.example.ui.components.ReactionQuickPickerBar
import com.example.ui.components.RichAttachmentSheetDialog
import com.example.ui.components.VoiceMessagePlayerCard
import com.example.ui.components.VoiceRecordingComposerBar
import com.example.ui.components.YggAvatar
import com.example.ui.rememberCopyToClipboard
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
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ChatsScreen(
    uiState: YggGramUiState,
    conversations: List<ConversationEntity>,
    allMessages: List<MessageEntity>,
    callLog: List<CallLogEntity> = emptyList(),
    messageSearchResults: List<MessageEntity> = emptyList(),
    activeGroupMembers: List<GroupMemberEntity> = emptyList(),
    groupMemberCounts: List<GroupMemberCount> = emptyList(),
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
    onCreateGroup: (String, List<String>) -> Unit = { _, _ -> },
    onAddContactsToGroup: (String, List<String>) -> Unit = { _, _ -> },
    onRenameGroup: (String, String) -> Unit = { _, _ -> },
    onSetGroupAvatar: (String, Uri) -> Unit = { _, _ -> },
    onRemoveGroupMember: (String, String) -> Unit = { _, _ -> },
    onLeaveGroup: (String) -> Unit = {},
    onDeleteChat: (String) -> Unit = {},
    onToggleChatPin: (String) -> Unit = {},
    onClearChat: (String) -> Unit = {},
    onRunSendReceiveTest: (String?) -> Unit = {},
    onOpenSelfYggdrasilChat: (Boolean) -> Unit = {},
    onRetryMessage: (MessageEntity) -> Unit = {},
    onDeleteMessage: (String, Boolean) -> Unit = { _, _ -> },
    onPingChatRoute: (String) -> Unit = {},
    onSetContactVerified: (String, Boolean) -> Unit = { _, _ -> },
    onRestoreRejectedDraft: () -> Unit = {}
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
                conv.publicKeyHex.contains(uiState.searchQuery, ignoreCase = true) ||
                conv.peerHandle.contains(uiState.searchQuery, ignoreCase = true)

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
                        groupMemberCounts = groupMemberCounts,
                        queuedOfflineCount = queuedOfflineCount,
                        onSelectChat = onSelectChat,
                        onSearchQueryChange = onSearchQueryChange,
                        onFilterChange = onFilterChange,
                        onToggleOfflineMode = onToggleOfflineMode,
                        onDeleteChat = onDeleteChat,
                        onToggleChatPin = onToggleChatPin,
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
                            onClearChat = { onClearChat(activeConversation.chatId) },
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
                            onRetryMessage = onRetryMessage,
                            onDeleteMessage = onDeleteMessage,
                            groupMembers = activeGroupMembers,
                            groupContactCandidates = conversations,
                            onAddContactsToGroup = { ids ->
                                onAddContactsToGroup(activeConversation.chatId, ids)
                            },
                            onRenameGroup = { title -> onRenameGroup(activeConversation.chatId, title) },
                            onSetGroupAvatar = { uri -> onSetGroupAvatar(activeConversation.chatId, uri) },
                            onRemoveGroupMember = { ipv6 ->
                                onRemoveGroupMember(activeConversation.chatId, ipv6)
                            },
                            onLeaveGroup = { onLeaveGroup(activeConversation.chatId) },
                            rejectedDraft = uiState.rejectedDraft
                                ?.takeIf { it.first == activeConversation.chatId }?.second,
                            onRejectedDraftRestored = onRestoreRejectedDraft
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
                    onClearChat = { onClearChat(activeConversation.chatId) },
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
                    onRetryMessage = onRetryMessage,
                    onDeleteMessage = onDeleteMessage,
                    groupMembers = activeGroupMembers,
                    groupContactCandidates = conversations,
                    onAddContactsToGroup = { ids ->
                        onAddContactsToGroup(activeConversation.chatId, ids)
                    },
                    onRenameGroup = { title -> onRenameGroup(activeConversation.chatId, title) },
                    onSetGroupAvatar = { uri -> onSetGroupAvatar(activeConversation.chatId, uri) },
                    onRemoveGroupMember = { ipv6 -> onRemoveGroupMember(activeConversation.chatId, ipv6) },
                    onLeaveGroup = { onLeaveGroup(activeConversation.chatId) },
                    rejectedDraft = uiState.rejectedDraft
                        ?.takeIf { it.first == activeConversation.chatId }?.second,
                    onRejectedDraftRestored = onRestoreRejectedDraft
                )
            } else {
                ChatListPane(
                    uiState = uiState,
                    conversations = filteredConversations,
                    callLog = callLog,
                    searchResults = messageSearchResults,
                    conversationTitles = conversationTitles,
                    groupMemberCounts = groupMemberCounts,
                    queuedOfflineCount = queuedOfflineCount,
                    onSelectChat = onSelectChat,
                    onSearchQueryChange = onSearchQueryChange,
                    onFilterChange = onFilterChange,
                    onToggleOfflineMode = onToggleOfflineMode,
                    onDeleteChat = onDeleteChat,
                    onToggleChatPin = onToggleChatPin,
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
            contacts = conversations,
            onDismiss = { showNewChatDialog = false },
            onOpenQrScanner = {
                showNewChatDialog = false
                qrModalInitialTab = 1
                showQrModal = true
            },
            onConfirm = { title, ipv6 ->
                onCreateNewChat(title, ipv6, uiState.privacyMatrix.defaultDisappearingSeconds)
                showNewChatDialog = false
            },
            onCreateGroup = { title, memberIds ->
                onCreateGroup(title, memberIds)
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
    groupMemberCounts: List<GroupMemberCount> = emptyList(),
    queuedOfflineCount: Int,
    onSelectChat: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (String) -> Unit,
    onToggleOfflineMode: () -> Unit,
    onDeleteChat: (String) -> Unit,
    onToggleChatPin: (String) -> Unit = {},
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
                                    "Поиск по имени, @handle, IPv6 или ключу",
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
                        groupMemberCount = if (conv.chatType == "GROUP") {
                            groupMemberCounts.firstOrNull { it.chatId == conv.chatId }?.memberCount ?: 0
                        } else {
                            0
                        },
                        onClick = { onSelectChat(conv.chatId) },
                        onDelete = { onDeleteChat(conv.chatId) },
                        onTogglePin = { onToggleChatPin(conv.chatId) }
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
                    text = formatDayAwareTime(entry.startedAt),
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

private fun formatDayAwareTime(timestamp: Long): String {
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
                                    clipboard.setPrimaryClip(ClipData.newPlainText("YGramm URI", contactUri))
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
                Text("Поиск узлов YGramm в локальной Wi-Fi сети (LAN)")
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
    groupMemberCount: Int = 0,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit = {}
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
                                        CyberTealSecondary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    }
                                )
                                // A route check answers for the node, not for the person behind
                                // it, so this dot is deliberately not the "online" green: it says
                                // whether the overlay path was up when it was last measured.
                                .semantics {
                                    contentDescription = if (conversation.isOnlineInMesh) {
                                        "Маршрут к узлу подтверждён"
                                    } else {
                                        "Маршрут к узлу не подтверждён"
                                    }
                                }
                                .testTag("conversation_route_dot_${conversation.chatId}")
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
                        if (conversation.isPinned) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "Чат закреплён",
                                tint = CyberTealSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        if (conversation.chatType == "GROUP") {
                            Spacer(modifier = Modifier.width(5.dp))
                            Icon(
                                Icons.Default.Groups,
                                contentDescription = null,
                                tint = CyberTealSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            // The roster table only ever holds the other members: a node never
                            // lists itself, so the group is one person larger than the table.
                            Text(
                                text = "${groupMemberCount + 1} участн.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(
                                    "group_member_count_${conversation.chatId}"
                                )
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
                        onClick = onTogglePin,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("toggle_pin_icon_${conversation.chatId}")
                    ) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = if (conversation.isPinned) {
                                "Открепить чат"
                            } else {
                                "Закрепить чат"
                            },
                            tint = if (conversation.isPinned) {
                                EmeraldPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            },
                            modifier = Modifier.size(16.dp)
                        )
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
    onClearChat: () -> Unit = {},
    onPingRoute: () -> Unit = {},
    onSendSelfEchoTest: () -> Unit = {},
    onRetryMessage: (MessageEntity) -> Unit = {},
    onDeleteMessage: (String, Boolean) -> Unit = { _, _ -> },
    typingLabel: String? = null,
    forwardTargets: List<ConversationEntity> = emptyList(),
    onSendReply: (String, String) -> Unit = { _, _ -> },
    onForwardMessage: (String, String) -> Unit = { _, _ -> },
    onTyping: () -> Unit = {},
    onSetContactVerified: (Boolean) -> Unit = {},
    groupMembers: List<GroupMemberEntity> = emptyList(),
    groupContactCandidates: List<ConversationEntity> = emptyList(),
    onAddContactsToGroup: (List<String>) -> Unit = {},
    onRenameGroup: (String) -> Unit = {},
    onSetGroupAvatar: (Uri) -> Unit = {},
    onRemoveGroupMember: (String) -> Unit = {},
    onLeaveGroup: () -> Unit = {},
    rejectedDraft: String? = null,
    onRejectedDraftRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var messageInput by rememberSaveable(conversation.chatId) { mutableStateOf("") }
    // A send that was refused never became a message, so its text goes back where it came from.
    LaunchedEffect(rejectedDraft) {
        if (!rejectedDraft.isNullOrBlank()) {
            messageInput = rejectedDraft
            onRejectedDraftRestored()
        }
    }
    var showCipherInspector by rememberSaveable { mutableStateOf(false) }
    var showAddMembersDialog by rememberSaveable(conversation.chatId) { mutableStateOf(false) }
    var membersToAddCsv by rememberSaveable(conversation.chatId) { mutableStateOf("") }
    var showGroupManageDialog by rememberSaveable(conversation.chatId) { mutableStateOf(false) }
    var groupTitleDraft by rememberSaveable(conversation.chatId) { mutableStateOf("") }
    var confirmLeaveGroup by rememberSaveable(conversation.chatId) { mutableStateOf(false) }
    var showDeleteConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var isRecordingVoice by rememberSaveable(conversation.chatId) { mutableStateOf(false) }
    var replyTarget by remember(conversation.chatId) { mutableStateOf<MessageEntity?>(null) }
    var forwardTarget by remember(conversation.chatId) { mutableStateOf<MessageEntity?>(null) }
    var deleteTarget by remember(conversation.chatId) { mutableStateOf<MessageEntity?>(null) }
    val isSelfChat = YggRepository.isSelfChatId(conversation.chatId)
    // The same picker and the same codec a profile picture uses: a cover that will not fit one
    // frame is refused here instead of arriving torn at a peer's device.
    val groupAvatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onSetGroupAvatar(uri)
    }
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
                            if (conversation.chatType == "GROUP") {
                                IconButton(
                                    onClick = {
                                        groupTitleDraft = conversation.title
                                        showGroupManageDialog = true
                                    },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .testTag("group_manage_button")
                                ) {
                                    Icon(
                                        Icons.Default.Groups,
                                        contentDescription = "Имя группы, участники, выход",
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { showAddMembersDialog = true },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .testTag("group_add_members_button")
                                ) {
                                    Icon(
                                        Icons.Default.PersonAdd,
                                        contentDescription = "Добавить контакты в группу",
                                        tint = CyberTealSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = conversation.routeTransportSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (conversation.isOnlineInMesh) EmeraldPrimary else AmberShieldTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (conversation.chatType != "GROUP" && !isSelfChat) {
                            // The one presence fact this app has, stated as a past event: the
                            // time the peer's frame last opened on this device. There is no
                            // "now" to report — nothing on the overlay answers for a person. And
                            // "не зафиксирован" rather than "не выходил": the stamp only exists
                            // from 1.7.6, so an old dialog simply has no measurement yet.
                            Text(
                                text = if (conversation.peerLastSeenAt > 0L) {
                                    "Выходил на связь: ${formatDayAwareTime(conversation.peerLastSeenAt)}"
                                } else {
                                    "Выход на связь не зафиксирован"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("peer_last_seen_line_text")
                            )
                        }
                        if (conversation.chatType != "GROUP" &&
                            (conversation.peerHandle.isNotBlank() || conversation.peerStatusText.isNotBlank())
                        ) {
                            // Only what the peer sealed for this device is shown: a contact whose
                            // profile never arrived has no line here at all, rather than a guess
                            // built from the mesh address.
                            Text(
                                text = listOfNotNull(
                                    conversation.peerHandle.takeIf { it.isNotBlank() },
                                    conversation.peerStatusText.takeIf { it.isNotBlank() }
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTealSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("peer_profile_line_text")
                            )
                        }
                        if (conversation.chatType == "GROUP") {
                            Text(
                                // The roster is what the mesh actually told us, so an empty
                                // one is reported as such instead of hiding behind a fake count.
                                text = if (groupMembers.isEmpty()) {
                                    "Группа • участники ещё не подтверждены"
                                } else {
                                    "Группа • " + groupMembers.joinToString(", ") { member ->
                                        // Names repeat because every node owner can call their
                                        // node «Узел YGramm #1»; the address prefix cannot.
                                        val wanted = YggCryptoEngine.normalizeIpv6Literal(member.memberIpv6)
                                        val current = groupContactCandidates.firstOrNull { candidate ->
                                            wanted != null &&
                                                YggCryptoEngine.normalizeIpv6Literal(candidate.yggIpv6Address) == wanted
                                        }?.title?.trim().orEmpty()
                                        val name = current.ifBlank { member.memberName }
                                        val prefix = member.memberIpv6.lowercase()
                                            .split(":").filter { it.isNotBlank() }.take(2).joinToString(":")
                                        "${name.ifBlank { "узел" }} ($prefix…)"
                                    }.take(160)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("group_members_line")
                            )
                        }
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
                    groupMemberTotal = if (conversation.chatType == "GROUP") groupMembers.size else 0,
                    onToggleReaction = { emoji -> onToggleReaction(msg.messageId, emoji) },
                    onReply = { replyTarget = msg },
                    onForward = { forwardTarget = msg },
                    onRetry = { onRetryMessage(msg) },
                    onDelete = { deleteTarget = msg }
                )
            }
        }

        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Удалить сообщение?") },
                text = {
                    Text(
                        if (target.isOutgoing) {
                            "«Удалить у всех» отправит собеседнику доказательство авторства и сотрёт копию " +
                                "на его устройстве. Успех скажется только после его подтверждения (ACK)."
                        } else {
                            "Это сообщение написал собеседник — у него его не удалить. " +
                                "Копия будет стёрта только на этом устройстве."
                        }
                    )
                },
                confirmButton = {
                    if (target.isOutgoing) {
                        Button(
                            onClick = {
                                deleteTarget = null
                                onDeleteMessage(target.messageId, true)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("delete_message_for_everyone_${target.messageId}")
                        ) {
                            Text("Удалить у всех")
                        }
                    } else {
                        Button(
                            onClick = {
                                deleteTarget = null
                                onDeleteMessage(target.messageId, false)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("delete_message_for_me_${target.messageId}")
                        ) {
                            Text("Удалить у меня")
                        }
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (target.isOutgoing) {
                            TextButton(
                                onClick = {
                                    deleteTarget = null
                                    onDeleteMessage(target.messageId, false)
                                },
                                modifier = Modifier.testTag("delete_message_for_me_${target.messageId}")
                            ) {
                                Text("Удалить у меня")
                            }
                        }
                        TextButton(onClick = { deleteTarget = null }) {
                            Text("Отмена")
                        }
                    }
                }
            )
        }

        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Удалить чат «${conversation.title}»?") },
                text = { Text("«Удалить» убирает диалог целиком, «Очистить» оставляет контакт и стирает историю сообщений и файлы этого чата с устройства.") },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                showDeleteConfirmDialog = false
                                onClearChat()
                            },
                            modifier = Modifier.testTag("clear_chat_${conversation.chatId}")
                        ) {
                            Text("Очистить")
                        }
                        TextButton(onClick = { showDeleteConfirmDialog = false }) {
                            Text("Отмена")
                        }
                    }
                }
            )
        }

        if (showAddMembersDialog) {
            val memberAddresses =
                remember(groupMembers) { groupMembers.map { it.memberIpv6.lowercase() }.toSet() }
            val candidates = remember(groupContactCandidates, memberAddresses) {
                groupContactCandidates.filter {
                    !YggRepository.isSelfChatId(it.chatId) && it.chatType != "GROUP" &&
                        it.yggIpv6Address.isNotBlank() && it.yggIpv6Address.lowercase() !in memberAddresses
                }
            }
            val pickedToAdd =
                remember(membersToAddCsv) { membersToAddCsv.split(",").filter { it.isNotBlank() }.toSet() }
            AlertDialog(
                onDismissRequest = { showAddMembersDialog = false },
                title = { Text("Добавить в «${conversation.title}»") },
                text = {
                    if (candidates.isEmpty()) {
                        Text(
                            text = "Добавлять некого: каждый подходящий контакт уже в группе. " +
                                "Сначала откройте личный диалог с узлом.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                                .testTag("group_add_member_list")
                        ) {
                            candidates.forEach { contact ->
                                val checked = pickedToAdd.contains(contact.chatId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            val next = pickedToAdd.toMutableSet()
                                            if (checked) next.remove(contact.chatId) else next.add(contact.chatId)
                                            membersToAddCsv = next.joinToString(",")
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            val next = pickedToAdd.toMutableSet()
                                            if (isChecked) next.add(contact.chatId) else next.remove(contact.chatId)
                                            membersToAddCsv = next.joinToString(",")
                                        }
                                    )
                                    YggAvatar(
                                        avatarUri = contact.avatarUri,
                                        name = contact.title,
                                        size = 30.dp
                                    )
                                    Text(
                                        text = contact.title,
                                        style = MaterialTheme.typography.bodyMedium,
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
                    Button(
                        enabled = pickedToAdd.isNotEmpty(),
                        onClick = {
                            onAddContactsToGroup(pickedToAdd.toList())
                            membersToAddCsv = ""
                            showAddMembersDialog = false
                        },
                        modifier = Modifier.testTag("group_add_members_confirm")
                    ) {
                        Text("Добавить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddMembersDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }

        if (showGroupManageDialog) {
            AlertDialog(
                onDismissRequest = {
                    showGroupManageDialog = false
                    confirmLeaveGroup = false
                },
                title = { Text("Управление «${conversation.title}»") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("group_manage_dialog"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = groupTitleDraft,
                            onValueChange = { groupTitleDraft = it },
                            label = { Text("Имя группы") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("group_rename_field")
                        )
                        Button(
                            enabled = groupTitleDraft.isNotBlank() &&
                                groupTitleDraft.trim() != conversation.title,
                            onClick = {
                                onRenameGroup(groupTitleDraft)
                                showGroupManageDialog = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("group_rename_confirm")
                        ) {
                            Text("Переименовать")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            YggAvatar(
                                avatarUri = conversation.avatarUri,
                                name = conversation.title,
                                size = 40.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Обложка группы",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                // What this app does not have: a role that owns the group. Saying
                                // so is the honest version of "who can change this".
                                Text(
                                    text = "Заменить может любой участник. Новая картинка уйдёт " +
                                        "остальным с первым вашим сообщением в группу.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = {
                                    groupAvatarPickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("group_avatar_pick_button")
                            ) {
                                Text("Выбрать")
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = if (groupMembers.isEmpty()) {
                                "Участники ещё не подтверждены ни одним узлом."
                            } else {
                                "Участники (${groupMembers.size}) — убирание не отправляет узлу " +
                                    "никакого сигнала: просто ваши новые сообщения в группу до него " +
                                    "не дойдут, а остальные участники продолжат писать ему сами"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        groupMembers.forEach { member ->
                            val label = member.memberName.ifBlank { "узел" }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "$label • ${member.memberIpv6}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onRemoveGroupMember(member.memberIpv6) },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("group_remove_member_${member.memberIpv6}")
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Убрать $label из группы",
                                        tint = AmberShieldTertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        if (confirmLeaveGroup) {
                            Text(
                                text = "Выход удалит группу и всю её переписку с этого устройства. " +
                                    "Остальные участники останутся в группе и продолжат писать без вас.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = {
                                    confirmLeaveGroup = false
                                    showGroupManageDialog = false
                                    onLeaveGroup()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("group_leave_confirm")
                            ) {
                                Text("Подтвердить выход")
                            }
                        } else {
                            TextButton(
                                onClick = { confirmLeaveGroup = true },
                                modifier = Modifier.testTag("group_leave_button")
                            ) {
                                Text("Выйти из группы", color = AmberShieldTertiary)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showGroupManageDialog = false
                        confirmLeaveGroup = false
                    }) {
                        Text("Закрыть")
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

// How far the bubble has to be dragged before the gesture is treated as an answer,
// and where it stops visually. Both in dp, converted inside the pointer handler.
private const val REPLY_SWIPE_THRESHOLD_DP = 90f
private const val REPLY_SWIPE_MAX_OFFSET_DP = 140f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EncryptedMessageBubble(
    message: MessageEntity,
    quotedMessage: MessageEntity?,
    showCipherHex: Boolean,
    groupMemberTotal: Int = 0,
    onToggleReaction: (String) -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit = {}
) {
    var showReactionPicker by rememberSaveable(message.messageId) { mutableStateOf(false) }
    val (copyText, copiedMessageKey) = rememberCopyToClipboard()
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val alignment = if (message.isOutgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    // Both an offline queue item and an unconfirmed overlay send can be retried by tapping.
    val canRetry = message.deliveryState == "QUEUED_OFFLINE_SF" || message.deliveryState == "SENT_P2P"
    // A horizontal drag is the gesture answer for one message: the bubble follows the finger
    // and the quote is armed as soon as the drag passes the threshold.
    var swipeOffset by remember(message.messageId) { mutableFloatStateOf(0f) }
    val swipeDensity = LocalDensity.current.density
    val replySwipeThresholdPx = REPLY_SWIPE_THRESHOLD_DP * swipeDensity
    val replySwipeMaxOffsetPx = REPLY_SWIPE_MAX_OFFSET_DP * swipeDensity

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
                },
                onDelete = {
                    showReactionPicker = false
                    onDelete()
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
                .offset { IntOffset(x = swipeOffset.roundToInt(), y = 0) }
                .pointerInput(message.messageId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(swipeOffset) >= replySwipeThresholdPx) {
                                onReply()
                            }
                            swipeOffset = 0f
                        },
                        onDragCancel = { swipeOffset = 0f }
                    ) { _, dragAmount ->
                        swipeOffset = (swipeOffset + dragAmount).coerceIn(-replySwipeMaxOffsetPx, replySwipeMaxOffsetPx)
                    }
                }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (abs(swipeOffset) >= replySwipeThresholdPx * 0.4f) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = "Ответить жестом",
                        tint = CyberTealSecondary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { copyText(message.messageId, message.plainText) },
                            modifier = Modifier.testTag("copy_message_${message.messageId}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Копировать", style = MaterialTheme.typography.labelSmall)
                        }
                        // Подпись только у той строки, которую скопировали только что: без неё
                        // нажатие неотличимо от того, что система молча проглотила буфер.
                        if (copiedMessageKey.value == message.messageId) {
                            Text(
                                text = "в буфере обмена",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldPrimary
                            )
                        }
                    }
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
                        // ratchetStep is cosmetic: v2 carries it without mixing it into the HKDF
                        // key and v3 leaves it out of the frame entirely, so calling this an
                        // "HKDF step" would advertise a ratchet this build does not have.
                        text = "AEAD Cipher: ${message.cipherPayloadHex.take(36)}...\nPoW: ${message.hashcashPowToken}\nEnvelope: v${message.envelopeVersion}" +
                            (if (message.envelopeVersion >= 3) " • ключ на это сообщение одноразовый" else "") +
                            " • шаг #${message.ratchetStep} (в ключ HKDF не входит)",
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
                            val (statusIcon, statusLabel, statusTint) = when (message.deliveryState) {
                                "QUEUED_OFFLINE_SF" -> Triple(Icons.Default.Schedule, "В очереди", AmberShieldTertiary)
                                "SENT_P2P" -> Triple(Icons.Default.Schedule, "Ожидает доставки", AmberShieldTertiary)
                                "LOCAL_NOTE" -> Triple(Icons.Default.Bookmark, "Заметка", CyberTealSecondary)
                                "DELIVERED" -> Triple(Icons.Default.DoneAll, "Доставлено", EmeraldPrimary)
                                "READ" -> Triple(Icons.Default.DoneAll, "Прочитано", CyberTealSecondary)
                                else -> Triple(Icons.Default.Schedule, "Не подтверждено", MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // In a group every acknowledgement is per member, so a note is only
                            // fully delivered when the last roster address has acked.
                            val ackedInGroup = message.deliveredMemberCsv
                                .split(',').map { it.trim() }.count { it.isNotBlank() }
                            val statusText = if (groupMemberTotal > 0 && ackedInGroup < groupMemberTotal) {
                                "$statusLabel $ackedInGroup из $groupMemberTotal"
                            } else {
                                statusLabel
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
    contacts: List<ConversationEntity>,
    onDismiss: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onConfirm: (String, String) -> Unit,
    onCreateGroup: (String, List<String>) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var ipv6Input by rememberSaveable { mutableStateOf("") }
    var directHostPort by rememberSaveable { mutableStateOf("") }
    var groupMode by rememberSaveable { mutableStateOf(false) }
    // A comma-separated string, because that is the only shape rememberSaveable can persist.
    var pickedCsv by rememberSaveable { mutableStateOf("") }
    val picked = remember(pickedCsv) { pickedCsv.split(",").filter { it.isNotBlank() }.toSet() }
    val selectableContacts = remember(contacts) {
        contacts.filter { !YggRepository.isSelfChatId(it.chatId) && it.chatType != "GROUP" }
    }
    fun togglePicked(chatId: String, add: Boolean) {
        val next = picked.toMutableSet()
        if (add) next.add(chatId) else next.remove(chatId)
        pickedCsv = next.joinToString(",")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (groupMode) "Новая группа" else "Добавить контакт Yggdrasil / Mimir",
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
                        onClick = { groupMode = false },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Контакт", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { groupMode = true },
                        colors = if (groupMode) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialog_new_group_tab")
                    ) {
                        Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Группа", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (groupMode) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название группы") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_group_title_input")
                    )
                    if (selectableContacts.isEmpty()) {
                        Text(
                            text = "Группу можно собрать только из уже добавленных контактов. " +
                                "Сначала откройте диалог с узлом на вкладке «Контакт».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Участников: ${picked.size} (плюс вы). Без сервера в оверлее группа " +
                                "передаётся как набор адресов — увидите, кто ответил.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .testTag("new_group_member_list")
                        ) {
                            selectableContacts.forEach { contact ->
                                val isSelected = picked.contains(contact.chatId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { togglePicked(contact.chatId, !isSelected) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked -> togglePicked(contact.chatId, checked) }
                                    )
                                    YggAvatar(
                                        avatarUri = contact.avatarUri,
                                        name = contact.title,
                                        size = 30.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = contact.yggIpv6Address,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (groupMode) {
                        if (picked.isNotEmpty()) {
                            onCreateGroup(title.ifBlank { "Группа на ${picked.size + 1}" }, picked.toList())
                        }
                    } else if (title.isNotBlank() || ipv6Input.isNotBlank()) {
                        val combined = if (directHostPort.isNotBlank()) {
                            "${ipv6Input.trim()} | ${directHostPort.trim()}"
                        } else {
                            ipv6Input.trim()
                        }
                        onConfirm(title.ifBlank { "Контакт Yggdrasil" }, combined)
                    }
                },
                enabled = if (groupMode) picked.isNotEmpty() else title.isNotBlank() || ipv6Input.isNotBlank(),
                modifier = Modifier.testTag("confirm_new_chat_button")
            ) {
                Text(if (groupMode) "Создать группу" else "Открыть чат")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
