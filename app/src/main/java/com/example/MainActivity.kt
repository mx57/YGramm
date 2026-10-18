// File: app/src/main/java/com/example/MainActivity.kt
package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.NetworkDpiScreen
import com.example.ui.screens.ProfilePrivacyScreen
import com.example.ui.screens.VaultSyncScreen
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.YggGramTheme
import com.example.viewmodel.YggGramViewModel
import com.example.viewmodel.YggNavTab

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YggGramTheme {
                YggGramRootApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YggGramRootApp(
    viewModel: YggGramViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val allMessages by viewModel.allMessages.collectAsStateWithLifecycle()
    val fileTransfers by viewModel.fileTransfers.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val linkedDevices by viewModel.linkedDevices.collectAsStateWithLifecycle()
    val backupSnapshots by viewModel.backupSnapshots.collectAsStateWithLifecycle()
    val cryptoKeys by viewModel.cryptoKeys.collectAsStateWithLifecycle()
    val queuedOfflineCount by viewModel.queuedOfflineMessagesCount.collectAsStateWithLifecycle()

    if (uiState.activeTab != YggNavTab.CHATS) {
        BackHandler {
            viewModel.selectTab(YggNavTab.CHATS)
        }
    }

    val totalUnread = conversations.sumOf { it.unreadCount }
    val connectedPeers = peers.count { it.isConnected }

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
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
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
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${uiState.identity.yggIpv6Address.take(21)}... • Пиров: $connectedPeers",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTealSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                                Icon(Icons.Default.Person, contentDescription = "Профиль и Приватность")
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
                    AnimatedVisibility(visible = uiState.statusBannerMessage != null) {
                        uiState.statusBannerMessage?.let { msg ->
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
                                fileTransfers = fileTransfers,
                                queuedOfflineCount = queuedOfflineCount,
                                onSelectChat = viewModel::selectChat,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onFilterChange = viewModel::updateChatFilter,
                                onToggleOfflineMode = viewModel::toggleOfflineStoreAndForwardMode,
                                onSendMessage = viewModel::sendMessage,
                                onCreateNewChat = viewModel::createNewChatOrGroup,
                                onToggleTransferState = viewModel::toggleFileTransferState,
                                onRunSendReceiveTest = viewModel::runLiveMessageSendReceiveTest
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
                                onRunSendReceiveTest = { viewModel.runLiveMessageSendReceiveTest(null) }
                            )

                            YggNavTab.VAULT_SYNC -> VaultSyncScreen(
                                uiState = uiState,
                                cryptoKeys = cryptoKeys,
                                linkedDevices = linkedDevices,
                                backupSnapshots = backupSnapshots,
                                onToggleSeedReveal = viewModel::toggleRevealSeedPhrase,
                                onRotatePreKeys = viewModel::rotatePreKeysAndMlsEpoch,
                                onRegenerateIdentity = viewModel::regenerateNewSeedIdentity,
                                onSyncDevicesNow = viewModel::triggerCrossPlatformSyncNow,
                                onPairDevice = viewModel::pairNewDevice,
                                onCreateBackup = viewModel::createEncryptedBackup,
                                onVerifyOrRestoreBackup = viewModel::verifyOrRestoreBackup
                            )

                            YggNavTab.PROFILE_PRIVACY -> ProfilePrivacyScreen(
                                uiState = uiState,
                                onUpdateProfile = viewModel::updateDecentralizedProfile,
                                onUpdatePrivacy = viewModel::updatePrivacySetting
                            )
                        }
                    }
                }
            }
        }
    }
}
