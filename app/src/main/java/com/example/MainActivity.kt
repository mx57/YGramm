// File: app/src/main/java/com/example/MainActivity.kt
package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.engine.YggQrCodeCodec
import com.example.service.YggNotificationCenter
import com.example.ui.components.NetworkStatusIndicator
import com.example.ui.components.ProfileSwitchConfirmationDialog
import com.example.ui.components.QrContactModal
import com.example.ui.components.VoiceCallOverlaySheet
import com.example.ui.components.YggInAppNotificationBannerOverlay
import com.example.ui.components.YggNetworkStatus
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.NetworkDpiScreen
import com.example.ui.screens.NetworkMapScreen
import com.example.ui.screens.ProfilePrivacyScreen
import com.example.ui.screens.VaultSyncScreen
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.YggGramTheme
import com.example.viewmodel.YggGramViewModel
import com.example.viewmodel.YggNavTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val incomingNotificationIntent = MutableStateFlow<Intent?>(null)

    // FLAG_SECURE must be set when the window is created, so it is read straight from the
    // persisted privacy matrix; it is re-applied whenever the switch changes.
    fun applyScreenshotBlock() {
        val block = getSharedPreferences("ygggram_ui_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("priv_block_screenshots", true)
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (block) window.addFlags(flag) else window.clearFlags(flag)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotBlock()
        incomingNotificationIntent.value = intent
        enableEdgeToEdge()
        setContent {
            val latestIntent by incomingNotificationIntent.asStateFlow().collectAsStateWithLifecycle()
            YggGramTheme {
                YggGramRootApp(
                    notificationIntent = latestIntent,
                    onNotificationIntentConsumed = { incomingNotificationIntent.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingNotificationIntent.value = intent
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YggGramRootApp(
    viewModel: YggGramViewModel = viewModel(),
    notificationIntent: Intent? = null,
    onNotificationIntentConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val userProfiles by viewModel.userProfiles.collectAsStateWithLifecycle()
    val foregroundServiceState by viewModel.foregroundServiceState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val allMessages by viewModel.allMessages.collectAsStateWithLifecycle()
    val callLog by viewModel.callLog.collectAsStateWithLifecycle()
    val messageSearchResults by viewModel.messageSearchResults.collectAsStateWithLifecycle()
    val activeGroupMembers by viewModel.activeGroupMembers.collectAsStateWithLifecycle()
    val groupMemberCounts by viewModel.groupMemberCounts.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val linkedDevices by viewModel.linkedDevices.collectAsStateWithLifecycle()
    val backupSnapshots by viewModel.backupSnapshots.collectAsStateWithLifecycle()
    val cryptoKeys by viewModel.cryptoKeys.collectAsStateWithLifecycle()
    val queuedOfflineCount by viewModel.queuedOfflineMessagesCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    var pendingAcceptCallId by rememberSaveable { mutableStateOf("") }
    val callMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val targetCallId = pendingAcceptCallId
        pendingAcceptCallId = ""
        if (granted) {
            viewModel.acceptIncomingCall()
        } else {
            Toast.makeText(
                context,
                "Без доступа к микрофону принять звонок невозможно",
                Toast.LENGTH_LONG
            ).show()
            if (targetCallId.isNotBlank()) {
                viewModel.declineIncomingCall()
            }
        }
    }
    val acceptCallWithPermission: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.acceptIncomingCall()
        } else {
            pendingAcceptCallId = uiState.activeVoiceCall?.callId.orEmpty()
            callMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = context.getSharedPreferences("ygggram_ui_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notif_permission_requested", false)) {
                prefs.edit().putBoolean("notif_permission_requested", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var showGlobalQrModal by rememberSaveable { mutableStateOf(false) }
    var showProfileQuickMenu by rememberSaveable { mutableStateOf(false) }

    // Show the ring screen over the lock screen, like a system phone call.
    val incomingRinging = uiState.activeVoiceCall?.isIncoming == true &&
        uiState.activeVoiceCall?.callStatus == "RINGING_INCOMING"
    LaunchedEffect(incomingRinging) {
        val activity = context.findHostActivity() ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(incomingRinging)
            activity.setTurnScreenOn(incomingRinging)
        } else {
            @Suppress("DEPRECATION")
            if (incomingRinging) {
                activity.window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            } else {
                activity.window.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
        }
    }

    LaunchedEffect(uiState.privacyMatrix.blockScreenshots) {
        (context.findHostActivity() as? MainActivity)?.applyScreenshotBlock()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setAppForeground(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setAppForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(notificationIntent) {
        val intent = notificationIntent ?: return@LaunchedEffect
        val deepLink = intent.dataString
        if (intent.action == Intent.ACTION_VIEW &&
            !deepLink.isNullOrBlank() &&
            deepLink.startsWith("ygggram://", ignoreCase = true)
        ) {
            val parsed = YggQrCodeCodec.parseContactString(deepLink)
            if (parsed != null) {
                viewModel.addContactFromQrPayload(parsed)
            } else {
                Toast.makeText(
                    context,
                    "Ссылка ygggram:// не содержит корректной визитки контакта",
                    Toast.LENGTH_LONG
                ).show()
            }
            onNotificationIntentConsumed()
            return@LaunchedEffect
        }
        val targetProfId = intent.getStringExtra(YggNotificationCenter.EXTRA_NOTIFICATION_PROFILE_ID).orEmpty()
        val targetChatId = intent.getStringExtra(YggNotificationCenter.EXTRA_NOTIFICATION_CHAT_ID).orEmpty()
        val categoryName = intent.getStringExtra(YggNotificationCenter.EXTRA_NOTIFICATION_CATEGORY).orEmpty()
        val notifAction = intent.getStringExtra(YggNotificationCenter.EXTRA_NOTIFICATION_ACTION).orEmpty()
        val notifCallId = intent.getStringExtra(YggNotificationCenter.EXTRA_NOTIFICATION_CALL_ID).orEmpty()
        if (targetProfId.isNotBlank() || targetChatId.isNotBlank()) {
            if (notifAction == YggNotificationCenter.ACTION_ANSWER_CALL &&
                uiState.activeVoiceCall?.callStatus == "RINGING_INCOMING"
            ) {
                acceptCallWithPermission()
            } else {
                viewModel.handleSystemNotificationIntent(
                    targetProfileId = targetProfId,
                    chatId = targetChatId,
                    categoryName = categoryName,
                    action = notifAction,
                    callId = notifCallId
                )
            }
            intent.removeExtra(YggNotificationCenter.EXTRA_NOTIFICATION_PROFILE_ID)
            intent.removeExtra(YggNotificationCenter.EXTRA_NOTIFICATION_CHAT_ID)
            intent.removeExtra(YggNotificationCenter.EXTRA_NOTIFICATION_CATEGORY)
            intent.removeExtra(YggNotificationCenter.EXTRA_NOTIFICATION_CALL_ID)
            intent.removeExtra(YggNotificationCenter.EXTRA_NOTIFICATION_ACTION)
            onNotificationIntentConsumed()
        }
    }

    if (uiState.activeTab != YggNavTab.CHATS) {
        BackHandler {
            viewModel.selectTab(YggNavTab.CHATS)
        }
    }

    val totalUnread = conversations.sumOf { it.unreadCount }
    // The node knows which public links are up; the stored peer flags only record that a
    // TCP/TLS probe once succeeded, which is not the same fact.
    val connectedPeers = if (uiState.coreMesh.available) {
        uiState.coreMesh.liveLinks
    } else {
        peers.count { it.isConnected }
    }
    val activeBgProfilesCount = userProfiles.count { it.isActiveInBackground || it.isCurrentPrimary }.coerceAtLeast(1)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 720.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = EmeraldPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (uiState.isOfflineStoreAndForwardMode) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (uiState.isOfflineStoreAndForwardMode) {
                                                "OFFLINE S&F"
                                            } else {
                                                uiState.antiDpiConfig.activePreset.badge
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (uiState.isOfflineStoreAndForwardMode) {
                                                AmberShieldTertiary
                                            } else {
                                                EmeraldPrimary
                                            },
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val networkStatus = when {
                                        !uiState.isNetworkAvailable -> YggNetworkStatus.ERROR
                                        connectedPeers > 0 -> YggNetworkStatus.CONNECTED
                                        else -> YggNetworkStatus.SEARCHING
                                    }
                                    NetworkStatusIndicator(
                                        status = networkStatus,
                                        connectedPeersCount = connectedPeers,
                                        onClick = { viewModel.selectTab(YggNavTab.NETWORK_DPI) }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${uiState.userProfile.handle} (${uiState.identity.yggIpv6Address.take(14)}...)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberTealSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Quick Multi-Profile Switcher Button in TopAppBar
                        Box {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showProfileQuickMenu = true }
                                    .testTag("top_bar_profile_switcher_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Groups,
                                        contentDescription = "Профили Yggdrasil",
                                        tint = CyberTealSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${userProfiles.size}/$activeBgProfilesCount",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CyberTealSecondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showProfileQuickMenu,
                                onDismissRequest = { showProfileQuickMenu = false }
                            ) {
                                userProfiles.forEach { prof ->
                                    val isCurrent = prof.profileId == uiState.currentProfileId
                                    val roleBadge = when (prof.nodeRelayRole) {
                                        "CLIENT_ONLY" -> "Только клиент"
                                        "SF_BLIND_RELAY" -> "S&F Ретранслятор"
                                        else -> "Пир-узел"
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = "${prof.displayName} ${if (isCurrent) "• (Активен)" else ""}",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrent) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "$roleBadge • Порт ${prof.allocatedTcpPort} • ${prof.yggIpv6Address.take(16)}...",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            showProfileQuickMenu = false
                                            if (!isCurrent) {
                                                viewModel.switchActiveProfile(prof.profileId)
                                            }
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "+ Создать новый профиль (одновременный P2P)",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = EmeraldPrimary
                                        )
                                    },
                                    onClick = {
                                        showProfileQuickMenu = false
                                        viewModel.createNewProfile(
                                            displayName = "Узел YGramm #${userProfiles.size + 1}",
                                            handle = "@node${userProfiles.size + 1}.ygg",
                                            bio = "Параллельный профиль Yggdrasil",
                                            runSimultaneouslyInBackground = true,
                                            switchImmediately = false
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Управление профилями и фоновым сервисом...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyberTealSecondary
                                        )
                                    },
                                    onClick = {
                                        showProfileQuickMenu = false
                                        viewModel.selectTab(YggNavTab.PROFILE_PRIVACY)
                                    }
                                )
                            }
                        }

                        IconButton(
                            onClick = { showGlobalQrModal = true },
                            modifier = Modifier.testTag("top_bar_qr_button")
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Добавить контакт по QR-коду",
                                tint = EmeraldPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        NavigationBarItem(
                            selected = uiState.activeTab == YggNavTab.CHATS,
                            onClick = { viewModel.selectTab(YggNavTab.CHATS) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (totalUnread > 0) {
                                            Badge(containerColor = EmeraldPrimary) {
                                                Text(totalUnread.toString(), color = Color(0xFF042217))
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Чаты")
                                }
                            },
                            label = { Text(stringResource(R.string.nav_chats)) },
                            modifier = Modifier.testTag("nav_tab_chats")
                        )

                        NavigationBarItem(
                            selected = uiState.activeTab == YggNavTab.NETWORK_MAP,
                            onClick = { viewModel.selectTab(YggNavTab.NETWORK_MAP) },
                            icon = {
                                Icon(Icons.Default.Map, contentDescription = "Карта сети")
                            },
                            label = { Text("Карта") },
                            modifier = Modifier.testTag("nav_tab_network_map")
                        )

                        NavigationBarItem(
                            selected = uiState.activeTab == YggNavTab.NETWORK_DPI,
                            onClick = { viewModel.selectTab(YggNavTab.NETWORK_DPI) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = CyberTealSecondary) {
                                            Text(connectedPeers.toString(), color = Color(0xFF02232E))
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Hub, contentDescription = "Сеть и ТСПУ")
                                }
                            },
                            label = { Text(stringResource(R.string.nav_network)) },
                            modifier = Modifier.testTag("nav_tab_network")
                        )

                        NavigationBarItem(
                            selected = uiState.activeTab == YggNavTab.VAULT_SYNC,
                            onClick = { viewModel.selectTab(YggNavTab.VAULT_SYNC) },
                            icon = {
                                Icon(Icons.Default.Key, contentDescription = "Ключи и Синхронизация")
                            },
                            label = { Text(stringResource(R.string.nav_vault)) },
                            modifier = Modifier.testTag("nav_tab_vault")
                        )

                        NavigationBarItem(
                            selected = uiState.activeTab == YggNavTab.PROFILE_PRIVACY,
                            onClick = { viewModel.selectTab(YggNavTab.PROFILE_PRIVACY) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (userProfiles.size > 1) {
                                            Badge(containerColor = EmeraldPrimary) {
                                                Text(userProfiles.size.toString(), color = Color(0xFF042217))
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = "Профиль и Приватность")
                                }
                            },
                            label = { Text(stringResource(R.string.nav_profile)) },
                            modifier = Modifier.testTag("nav_tab_profile")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        NavigationRailItem(
                            selected = uiState.activeTab == YggNavTab.CHATS,
                            onClick = { viewModel.selectTab(YggNavTab.CHATS) },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Чаты") },
                            label = { Text(stringResource(R.string.nav_chats)) },
                            modifier = Modifier.testTag("rail_tab_chats")
                        )
                        NavigationRailItem(
                            selected = uiState.activeTab == YggNavTab.NETWORK_MAP,
                            onClick = { viewModel.selectTab(YggNavTab.NETWORK_MAP) },
                            icon = { Icon(Icons.Default.Map, contentDescription = "Карта сети") },
                            label = { Text("Карта") },
                            modifier = Modifier.testTag("rail_tab_network_map")
                        )
                        NavigationRailItem(
                            selected = uiState.activeTab == YggNavTab.NETWORK_DPI,
                            onClick = { viewModel.selectTab(YggNavTab.NETWORK_DPI) },
                            icon = { Icon(Icons.Default.Hub, contentDescription = "Сеть и ТСПУ") },
                            label = { Text(stringResource(R.string.nav_network)) },
                            modifier = Modifier.testTag("rail_tab_network")
                        )
                        NavigationRailItem(
                            selected = uiState.activeTab == YggNavTab.VAULT_SYNC,
                            onClick = { viewModel.selectTab(YggNavTab.VAULT_SYNC) },
                            icon = { Icon(Icons.Default.Key, contentDescription = "Ключи и Синхронизация") },
                            label = { Text(stringResource(R.string.nav_vault)) },
                            modifier = Modifier.testTag("rail_tab_vault")
                        )
                        NavigationRailItem(
                            selected = uiState.activeTab == YggNavTab.PROFILE_PRIVACY,
                            onClick = { viewModel.selectTab(YggNavTab.PROFILE_PRIVACY) },
                            icon = { Icon(Icons.Default.Person, contentDescription = "Профиль") },
                            label = { Text(stringResource(R.string.nav_profile)) },
                            modifier = Modifier.testTag("rail_tab_profile")
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Animated Pop-Up Notification Banner for all enabled profiles (Calls, Missed Calls, Voice, Messages)
                    YggInAppNotificationBannerOverlay(
                        notification = uiState.activePopupNotification,
                        currentActiveProfileId = uiState.currentProfileId,
                        onPrimaryAction = viewModel::handlePopupNotificationAction,
                        onDeclineIncomingCall = viewModel::declineIncomingCallNotification,
                        onDismiss = viewModel::dismissPopupNotification
                    )

                    val statusBannerMessage = uiState.statusBannerMessage
                    LaunchedEffect(statusBannerMessage) {
                        if (statusBannerMessage != null) {
                            delay(8_000)
                            viewModel.dismissBanner()
                        }
                    }
                    AnimatedVisibility(visible = statusBannerMessage != null) {
                        statusBannerMessage?.let { msg ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.dismissBanner() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Закрыть уведомление",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (uiState.activeTab) {
                            YggNavTab.CHATS -> ChatsScreen(
                                uiState = uiState,
                                conversations = conversations,
                                allMessages = allMessages,
                                callLog = callLog,
                                messageSearchResults = messageSearchResults,
                                activeGroupMembers = activeGroupMembers,
                                groupMemberCounts = groupMemberCounts,
                                queuedOfflineCount = queuedOfflineCount,
                                onSelectChat = viewModel::selectChat,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onFilterChange = viewModel::updateChatFilter,
                                onToggleOfflineMode = viewModel::toggleOfflineStoreAndForwardMode,
                                onSendMessage = viewModel::sendMessage,
                                onSendVoiceMessage = viewModel::sendVoiceMessage,
                                onSendMediaOrFile = viewModel::sendMediaOrDocumentAttachment,
                                onSendReply = { chatId, text, quotedId ->
                                    viewModel.sendMessage(chatId, text, quotedMessageId = quotedId)
                                },
                                onForwardMessage = viewModel::forwardMessage,
                                onTyping = viewModel::notifyTyping,
                                onToggleReaction = viewModel::toggleMessageReaction,
                                onStartVoiceCall = viewModel::startVoiceCall,
                                onAddContactFromQr = viewModel::addContactFromQrPayload,
                                onCreateNewChat = viewModel::createNewChat,
                                onCreateGroup = viewModel::createGroup,
                                onAddContactsToGroup = viewModel::addContactsToGroup,
                                onDeleteChat = viewModel::deleteChat,
                                onRunSendReceiveTest = viewModel::runLiveMessageSendReceiveTest,
                                onOpenSelfYggdrasilChat = { runOverlayProbe ->
                                    if (runOverlayProbe) {
                                        viewModel.runYggdrasilOverlaySelfTest()
                                    } else {
                                        viewModel.openSelfYggdrasilTestChat()
                                    }
                                },
                                onRetryMessage = viewModel::retrySendMessage,
                                onPingChatRoute = viewModel::pingConversationRoute,
                                onSetContactVerified = viewModel::setContactKeyVerified
                            )

                            YggNavTab.NETWORK_MAP -> NetworkMapScreen(
                                uiState = uiState,
                                conversations = conversations,
                                allMessages = allMessages,
                                peers = peers,
                                onRunProbe = viewModel::runAntiDpiDiagnosticProbe,
                                onRunSendReceiveTest = { viewModel.runLiveMessageSendReceiveTest(null) },
                                onPingChatRoute = viewModel::pingConversationRoute
                            )

                            YggNavTab.NETWORK_DPI -> NetworkDpiScreen(
                                uiState = uiState,
                                peers = peers,
                                onSelectPreset = viewModel::applyCensorshipPreset,
                                onSelectWhitelistSni = viewModel::selectWhitelistSniDomain,
                                onSelectUtlsProfile = viewModel::selectUtlsFingerprint,
                                onToggleAntiDpiFlag = viewModel::toggleAntiDpiFlag,
                                onRunProbe = viewModel::runAntiDpiDiagnosticProbe,
                                onAddPeer = viewModel::addCustomPeer,
                                onTogglePeer = viewModel::togglePeer,
                                onRemovePeer = viewModel::removePeer,
                                onSelectScanCountry = viewModel::selectScanCountry,
                                onToggleScannerWhitelistOnly = viewModel::toggleScannerWhitelistOnly,
                                onRunCountryPeerScan = viewModel::scanYggdrasilPeersByCountry,
                                onConnectScannedPeer = viewModel::connectScannedPeer,
                                onConnectTopFastestInCountry = viewModel::connectTopFastestPeersInSelectedCountry,
                                onPingTwinNode = viewModel::pingTwinNodeNow,
                                onRunSendReceiveTest = { viewModel.runLiveMessageSendReceiveTest(null) },
                                onOpenSelfYggdrasilTest = { viewModel.runYggdrasilOverlaySelfTest() },
                                onUpdateScannerSearch = viewModel::updateScannerSearchQuery,
                                onUpdateScannerSort = viewModel::updateScannerSortMode,
                                onImportCustomPeers = viewModel::importCustomPeersFromText,
                                onClearPacketLogs = viewModel::clearPacketLogs
                            )

                            YggNavTab.VAULT_SYNC -> VaultSyncScreen(
                                uiState = uiState,
                                cryptoKeys = cryptoKeys,
                                linkedDevices = linkedDevices,
                                backupSnapshots = backupSnapshots,
                                onToggleSeedReveal = viewModel::toggleRevealSeedPhrase,
                                onRegenerateIdentity = viewModel::regenerateNewSeedIdentity,
                                onSyncDevicesNow = viewModel::triggerCrossPlatformSyncNow,
                                onPairDevice = viewModel::pairNewDevice,
                                onCreateBackup = viewModel::createEncryptedBackup,
                                onVerifyOrRestoreBackup = viewModel::verifyOrRestoreBackup
                            )

                            YggNavTab.PROFILE_PRIVACY -> ProfilePrivacyScreen(
                                uiState = uiState,
                                userProfiles = userProfiles,
                                foregroundServiceState = foregroundServiceState,
                                onUpdateProfile = viewModel::updateDecentralizedProfile,
                                onCreateNewProfile = viewModel::createNewProfile,
                                onSwitchProfile = viewModel::switchActiveProfile,
                                onToggleProfileBackgroundActive = viewModel::toggleProfileBackgroundExecution,
                                onSetProfileRelayRole = viewModel::setProfileNodeRelayRole,
                                onTriggerProfileTestNotification = viewModel::triggerProfileTestNotification,
                                onDeleteSecondaryProfile = viewModel::deleteSecondaryProfile,
                                onPanicWipe = viewModel::panicWipeAllData,
                                onOpenChatWithProfile = viewModel::openChatWithSecondaryProfile,
                                onToggleBackgroundService = viewModel::setBackgroundServiceEnabled,
                                onUpdatePrivacy = viewModel::updatePrivacySetting,
                                onUpdateNotifications = viewModel::updateNotificationPreferences,
                                onAddContactFromQr = { payload ->
                                    viewModel.addContactFromQrPayload(payload)
                                    viewModel.selectTab(YggNavTab.CHATS)
                                },
                                onPickProfileAvatar = viewModel::setProfileAvatar,
                                onClearProfileAvatar = viewModel::clearProfileAvatar
                            )
                        }
                    }
                }
            }
        }

        if (uiState.pendingProfileSwitchRequest != null) {
            ProfileSwitchConfirmationDialog(
                pendingRequest = uiState.pendingProfileSwitchRequest,
                currentProfileName = uiState.userProfile.displayName,
                onConfirmSwitchAndExecute = viewModel::confirmProfileSwitchFromNotification,
                onDismiss = viewModel::cancelProfileSwitchFromNotification
            )
        }

        uiState.activeVoiceCall?.let { callState ->
            VoiceCallOverlaySheet(
                callState = callState,
                onToggleMute = viewModel::toggleCallMute,
                onToggleSpeaker = viewModel::toggleCallSpeakerphone,
                onEndCall = viewModel::endVoiceCall,
                onTriggerTestAudioTone = viewModel::triggerCallTestAudioTone,
                onAcceptCall = acceptCallWithPermission,
                onDeclineCall = viewModel::declineIncomingCall
            )
        }

        if (showGlobalQrModal) {
            QrContactModal(
                uiState = uiState,
                initialTab = 1,
                onDismiss = { showGlobalQrModal = false },
                onContactScanned = { payload ->
                    showGlobalQrModal = false
                    viewModel.addContactFromQrPayload(payload)
                    viewModel.selectTab(YggNavTab.CHATS)
                }
            )
        }
    }
}

private fun Context.findHostActivity(): android.app.Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return current as? android.app.Activity
}
