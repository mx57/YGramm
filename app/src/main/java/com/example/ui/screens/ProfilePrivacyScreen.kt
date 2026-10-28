// File: app/src/main/java/com/example/ui/screens/ProfilePrivacyScreen.kt
package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.ConversationEntity
import com.example.data.UserProfileEntity
import com.example.engine.ParsedYggContactQr
import com.example.service.YggAlertCategory
import com.example.service.YggForegroundServiceState
import com.example.ui.components.QrContactModal
import com.example.ui.components.YggAvatar
import com.example.ui.components.YggSwitch
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.AppTheme
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.YggThemePref
import com.example.update.YGrammUpdateController
import com.example.update.YGrammUpdateEngine
import com.example.viewmodel.YggGramUiState

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

@Composable
fun ProfilePrivacyScreen(
    uiState: YggGramUiState,
    userProfiles: List<UserProfileEntity> = emptyList(),
    foregroundServiceState: YggForegroundServiceState = YggForegroundServiceState(),
    onUpdateProfile: (String, String, String, String) -> Unit,
    onCreateNewProfile: (
        displayName: String,
        handle: String,
        bio: String,
        runSimultaneouslyInBackground: Boolean,
        switchImmediately: Boolean,
        nodeRelayRole: String
    ) -> Unit = { _, _, _, _, _, _ -> },
    onSwitchProfile: (String) -> Unit = {},
    onToggleProfileBackgroundActive: (String, Boolean) -> Unit = { _, _ -> },
    onSetProfileRelayRole: (String, String) -> Unit = { _, _ -> },
    onTriggerProfileTestNotification: (String, YggAlertCategory) -> Unit = { _, _ -> },
    onDeleteSecondaryProfile: (String) -> Unit = {},
    onPanicWipe: () -> Unit = {},
    onOpenChatWithProfile: (UserProfileEntity) -> Unit = {},
    onToggleBackgroundService: (Boolean) -> Unit = {},
    onUpdatePrivacy: (
        sealedSenderGlobal: Boolean?,
        stripNetworkMetadata: Boolean?,
        blockScreenshots: Boolean?,
        allowDirectLanDiscovery: Boolean?,
        autoSyncOnMeshConnect: Boolean?,
        defaultDisappearingSeconds: Int?,
        hashcashDifficultyBits: Int?,
        webDavEndpointUrl: String?
    ) -> Unit,
    onUpdateNotifications: (
        inAppNotificationsEnabled: Boolean?,
        systemNotificationsEnabled: Boolean?,
        suppressNotificationsForOpenChat: Boolean?,
        messageNotificationsEnabled: Boolean?,
        voiceNotificationsEnabled: Boolean?,
        callNotificationsEnabled: Boolean?,
        hideNotificationContent: Boolean?
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onAddContactFromQr: (ParsedYggContactQr) -> Unit = {},
    onPickProfileAvatar: (Uri) -> Unit = {},
    onClearProfileAvatar: () -> Unit = {},
    onRefreshStorage: () -> Unit = {},
    onPurgeOrphanedFiles: () -> Unit = {},
    conversations: List<ConversationEntity> = emptyList(),
    onRequestApkFromPeer: (String) -> Unit = {},
    onCancelApkRelay: () -> Unit = {},
    onImportReleaseManifest: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var showEditProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showQrIdentityDialog by rememberSaveable { mutableStateOf(false) }
    var showPanicWipeDialog by rememberSaveable { mutableStateOf(false) }

    val notificationPrefs = remember(context) {
        context.getSharedPreferences("ygggram_ui_prefs", Context.MODE_PRIVATE)
    }
    var notificationPermissionGranted by remember {
        mutableStateOf(hasNotificationPermission(context))
    }
    var notificationPermissionAsked by remember {
        mutableStateOf(notificationPrefs.getBoolean("notif_permission_requested", false))
    }
    var pendingNotificationPermissionTarget by remember { mutableStateOf("") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        notificationPermissionAsked = true
        notificationPrefs.edit().putBoolean("notif_permission_requested", true).apply()
        when (pendingNotificationPermissionTarget) {
            "system" -> {
                onUpdateNotifications(null, granted, null, null, null, null, null)
                if (!granted) {
                    Toast.makeText(
                        context,
                        "Без разрешения Android системные уведомления не появятся в шторке",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            "background_service" -> onToggleBackgroundService(true)
        }
        pendingNotificationPermissionTarget = ""
    }

    val permissionLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(permissionLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationPermissionGranted = hasNotificationPermission(context)
                notificationPermissionAsked = notificationPrefs.getBoolean("notif_permission_requested", false)
            }
        }
        permissionLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { permissionLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val profile = uiState.userProfile
    val privacy = uiState.privacyMatrix

    // The gallery, not the camera: the picture is resized on-device into the 12 KB the
    // overlay frame can carry, and the user must see which photo that will be.
    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onPickProfileAvatar(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Current Decentralized Profile Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .testTag("profile_avatar_picker")
                                .clickable {
                                    avatarPickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            YggAvatar(
                                avatarUri = profile.avatarUri,
                                name = profile.displayName,
                                size = 64.dp
                            )
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Выбрать фотографию профиля",
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.displayName,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = "Децентрализованный профиль подписан Ed25519",
                                    tint = EmeraldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = listOf(profile.handle, profile.socialStatus)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" • "),
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberTealSecondary
                            )
                        }
                    }

                    if (profile.avatarUri.isNotBlank()) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = onClearProfileAvatar,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = AmberShieldTertiary
                                )
                            ) {
                                Text("Убрать фото")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = profile.bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val ipv6Display = uiState.identity.yggIpv6Address
                                        .split(':')
                                        .chunked(4)
                                        .joinToString("\n") { it.joinToString(":") }
                                    Text(
                                        text = "Постоянный IPv6-адрес в сети Yggdrasil (200::/7):",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = ipv6Display,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp
                                        ),
                                        color = EmeraldPrimary,
                                        lineHeight = 18.sp
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Yggdrasil IPv6", uiState.identity.yggIpv6Address)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "IPv6-адрес скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("copy_ygg_ipv6_button")
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Скопировать IPv6",
                                        tint = EmeraldPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "P2P-порт: ${profile.allocatedTcpPort}" +
                                    profile.profileFingerprintHex.takeIf { it.isNotBlank() }
                                        ?.let { " • Отпечаток: ${it.removePrefix("sha256_fp:")}" }.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showEditProfileDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("edit_profile_button")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Изменить", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { showQrIdentityDialog = true },
                            modifier = Modifier.testTag("show_qr_identity_button")
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("QR-визитка")
                        }
                    }
                }
            }
        }

        // 2. Multi-Profile Manager Card (Generate 2+ Profiles & Simultaneous Background Execution)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Groups, contentDescription = null, tint = EmeraldPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Мультипрофильность Yggdrasil (${userProfiles.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Одновременная работа нескольких профилей на разных портах P2P",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(
                            onClick = { showCreateProfileDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("create_new_profile_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Новый профиль", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Text(
                        text = "Каждый профиль имеет собственную BIP-39 мнемонику, уникальный IPv6-адрес 200::/7 и выделенный TCP-сокет. Вы можете включить параллельную работу в фоне для любого профиля и даже переписываться или звонить между своими профилями по P2P.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    userProfiles.forEach { itemProfile ->
                        val isPrimary = itemProfile.profileId == uiState.currentProfileId
                        Surface(
                            color = if (isPrimary) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isPrimary) 1.5.dp else 1.dp,
                                    color = if (isPrimary) EmeraldPrimary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            YggAvatar(
                                                avatarUri = itemProfile.avatarUri,
                                                name = itemProfile.displayName,
                                                size = 38.dp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = itemProfile.displayName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isPrimary) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        color = EmeraldPrimary.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        Text(
                                                            text = "АКТИВНЫЙ",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = EmeraldPrimary,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = "${itemProfile.handle} • Порт ${itemProfile.allocatedTcpPort}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = CyberTealSecondary
                                            )
                                            Text(
                                                text = itemProfile.yggIpv6Address,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (!isPrimary && userProfiles.size > 1) {
                                        IconButton(
                                            onClick = { onDeleteSecondaryProfile(itemProfile.profileId) },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("delete_profile_${itemProfile.profileId}")
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Удалить профиль",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                // Toggle simultaneous background listening for this profile
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Одновременная работа в фоне (P2P сокет :${itemProfile.allocatedTcpPort})",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Text(
                                            text = if (itemProfile.isActiveInBackground || isPrimary) {
                                                "Принимает сообщения, файлы и звонки в реальном времени"
                                            } else {
                                                "Сокет остановлен (только при переключении)"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (itemProfile.isActiveInBackground || isPrimary) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    YggSwitch(
                                        checked = itemProfile.isActiveInBackground || isPrimary,
                                        enabled = !isPrimary,
                                        onCheckedChange = { enabled ->
                                            onToggleProfileBackgroundActive(itemProfile.profileId, enabled)
                                        },
                                        modifier = Modifier.testTag("toggle_profile_bg_${itemProfile.profileId}")
                                    )
                                }

                                // Per-Profile Node Relay Role Selector: CLIENT_ONLY / PEER_RELAY / SF_BLIND_RELAY
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Роль в сети (Пир и ретранслятор):",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "Транзит: ${itemProfile.relayedPacketsCount} пак.",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = CyberTealSecondary
                                            )
                                        }

                                        val roles = listOf(
                                            "CLIENT_ONLY" to "Только клиент",
                                            "PEER_RELAY" to "Пир-узел",
                                            "SF_BLIND_RELAY" to "S&F Ретранслятор"
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            roles.forEach { (roleCode, roleTitle) ->
                                                val selected = itemProfile.nodeRelayRole == roleCode
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = {
                                                        onSetProfileRelayRole(itemProfile.profileId, roleCode)
                                                    },
                                                    label = {
                                                        Text(
                                                            text = roleTitle,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = when (roleCode) {
                                                            "CLIENT_ONLY" -> MaterialTheme.colorScheme.secondaryContainer
                                                            "SF_BLIND_RELAY" -> AmberShieldTertiary.copy(alpha = 0.25f)
                                                            else -> EmeraldPrimary.copy(alpha = 0.22f)
                                                        },
                                                        selectedLabelColor = when (roleCode) {
                                                            "CLIENT_ONLY" -> CyberTealSecondary
                                                            "SF_BLIND_RELAY" -> AmberShieldTertiary
                                                            else -> EmeraldPrimary
                                                        }
                                                    ),
                                                    modifier = Modifier.testTag("role_chip_${itemProfile.profileId}_$roleCode")
                                                )
                                            }
                                        }

                                        Text(
                                            text = when (itemProfile.nodeRelayRole) {
                                                "CLIENT_ONLY" -> "Режим «Только клиент»: узел не маршрутизирует чужой трафик и отклоняет транзитные пакеты."
                                                "SF_BLIND_RELAY" -> "Режим «S&F ретранслятор»: узел работает как пир Yggdrasil и хранит слепые офлайн-конверты (Hashcash PoW)."
                                                else -> "Режим «Пир-узел»: узел участвует в маршрутизации пакетов оверлейной сети Yggdrasil 0200::/7."
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Quick Notification Test Bar for enabled profiles (Incoming Call, Missed Call, Voice, Message)
                                if (itemProfile.isActiveInBackground || isPrimary) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Тест всплывающих уведомлений профиля «${itemProfile.displayName}»:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    onTriggerProfileTestNotification(
                                                        itemProfile.profileId,
                                                        YggAlertCategory.INCOMING_CALL
                                                    )
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("test_notif_call_${itemProfile.profileId}")
                                            ) {
                                                Text("📞 Звонок", style = MaterialTheme.typography.labelSmall)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onTriggerProfileTestNotification(
                                                        itemProfile.profileId,
                                                        YggAlertCategory.MISSED_CALL
                                                    )
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("test_notif_missed_${itemProfile.profileId}")
                                            ) {
                                                Text("📲 Пропущенный", style = MaterialTheme.typography.labelSmall)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onTriggerProfileTestNotification(
                                                        itemProfile.profileId,
                                                        YggAlertCategory.NEW_VOICE
                                                    )
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("test_notif_voice_${itemProfile.profileId}")
                                            ) {
                                                Text("🎙️ Голосовое", style = MaterialTheme.typography.labelSmall)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    onTriggerProfileTestNotification(
                                                        itemProfile.profileId,
                                                        YggAlertCategory.NEW_MESSAGE
                                                    )
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("test_notif_msg_${itemProfile.profileId}")
                                            ) {
                                                Text("💬 Сообщение", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }

                                if (!isPrimary) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { onSwitchProfile(itemProfile.profileId) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("switch_profile_${itemProfile.profileId}")
                                        ) {
                                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Переключиться", style = MaterialTheme.typography.labelMedium)
                                        }
                                        Button(
                                            onClick = { onOpenChatWithProfile(itemProfile) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("chat_with_profile_${itemProfile.profileId}")
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Написать P2P", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Persistent Yggdrasil Foreground Service Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = if (uiState.isBackgroundServiceEnabled) EmeraldPrimary else AmberShieldTertiary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Фоновый сервис узла Yggdrasil",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (uiState.isBackgroundServiceEnabled) {
                                        "Постоянное уведомление активно • Сокеты ${uiState.activePortsSummary}"
                                    } else {
                                        "Фоновое удержание соединения отключено"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.isBackgroundServiceEnabled) EmeraldPrimary else AmberShieldTertiary
                                )
                            }
                        }
                        YggSwitch(
                            checked = uiState.isBackgroundServiceEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !notificationPermissionGranted
                                ) {
                                    pendingNotificationPermissionTarget = "background_service"
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    return@YggSwitch
                                }
                                onToggleBackgroundService(enabled)
                            },
                            modifier = Modifier.testTag("toggle_foreground_service_switch")
                        )
                    }

                    Text(
                        text = "Удерживает подключение к пирам Yggdrasil (200::/7) и фоновые P2P-сокеты всех активных профилей при сворачивании приложения. В шторке уведомлений доступны статус узлов, пинг и кнопка мгновенной остановки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Активных профилей: ${foregroundServiceState.activeProfilesCount} • Пиров: ${foregroundServiceState.connectedPeersCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberTealSecondary
                            )
                            Text(
                                text = "Порты: ${uiState.activePortsSummary}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = EmeraldPrimary
                            )
                        }
                    }
                }
            }
        }

        // 4. Notification preferences
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Уведомления о сообщениях и звонках",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    PrivacyToggleItem(
                        title = "Всплывающие уведомления в приложении",
                        subtitle = "Карточка-баннер внутри YGramm при новом сообщении, голосовом или вызове",
                        checked = uiState.privacyMatrix.inAppNotificationsEnabled,
                        tag = "notif_in_app_switch",
                        onCheckedChange = {
                            onUpdateNotifications(it, null, null, null, null, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Системные уведомления Android",
                        subtitle = "Уведомления в шторке и на экране блокировки. Требуется разрешение Android 13+",
                        checked = uiState.privacyMatrix.systemNotificationsEnabled &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionGranted),
                        tag = "notif_system_switch",
                        onCheckedChange = { enabled ->
                            if (enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !notificationPermissionGranted
                            ) {
                                pendingNotificationPermissionTarget = "system"
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onUpdateNotifications(null, enabled, null, null, null, null, null)
                            }
                        }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Surface(
                            color = if (notificationPermissionGranted) {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            } else {
                                AmberShieldTertiary.copy(alpha = 0.12f)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("notification_permission_status")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (notificationPermissionGranted) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = if (notificationPermissionGranted) EmeraldPrimary else AmberShieldTertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (notificationPermissionGranted) {
                                            "Разрешение Android выдано"
                                        } else {
                                            "Системные уведомления заблокированы"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (notificationPermissionGranted) EmeraldPrimary else AmberShieldTertiary
                                    )
                                    Text(
                                        text = if (notificationPermissionGranted) {
                                            "Шторка и экран блокировки доступны, оповещения приходят при свёрнутом приложении"
                                        } else {
                                            "Без POST_NOTIFICATIONS шторка Android не покажет оповещения. Баннеры внутри приложения продолжат работать"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!notificationPermissionGranted) {
                                    TextButton(
                                        onClick = {
                                            if (notificationPermissionAsked) {
                                                val intent = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.fromParts("package", context.packageName, null)
                                                )
                                                context.startActivity(intent)
                                            } else {
                                                pendingNotificationPermissionTarget = "system"
                                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        },
                                        modifier = Modifier.testTag("request_notification_permission_button")
                                    ) {
                                        Text(if (notificationPermissionAsked) "Настройки" else "Разрешить")
                                    }
                                }
                            }
                        }
                    }

                    PrivacyToggleItem(
                        title = "Не уведомлять об открытом чате",
                        subtitle = "Если чат с отправителем уже открыт под активным профилем, сообщение не дублируется баннером и системным уведомлением",
                        checked = uiState.privacyMatrix.suppressNotificationsForOpenChat,
                        tag = "notif_suppress_open_chat_switch",
                        onCheckedChange = {
                            onUpdateNotifications(null, null, it, null, null, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Скрывать текст в уведомлениях",
                        subtitle = "На экране блокировки видно только «Новое сообщение» или «Голосовое»: ни текста, ни адреса узлов. С выключенным переключателем содержимое читается любому, кто возьмёт телефон в руки",
                        checked = uiState.privacyMatrix.hideNotificationContent,
                        tag = "notif_hide_content_switch",
                        onCheckedChange = {
                            onUpdateNotifications(null, null, null, it, null, null, null)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    PrivacyToggleItem(
                        title = "Уведомления о текстовых сообщениях",
                        subtitle = "Новые сообщения и файлы в чатах",
                        checked = uiState.privacyMatrix.messageNotificationsEnabled,
                        tag = "notif_messages_switch",
                        onCheckedChange = {
                            onUpdateNotifications(null, null, null, it, null, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Уведомления о голосовых сообщениях",
                        subtitle = "Отдельный переключатель для голосовых заметок",
                        checked = uiState.privacyMatrix.voiceNotificationsEnabled,
                        tag = "notif_voice_switch",
                        onCheckedChange = {
                            onUpdateNotifications(null, null, null, null, it, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Уведомления о звонках",
                        subtitle = "Входящие и пропущенные голосовые вызовы",
                        checked = uiState.privacyMatrix.callNotificationsEnabled,
                        tag = "notif_calls_switch",
                        onCheckedChange = {
                            onUpdateNotifications(null, null, null, null, null, it, null)
                        }
                    )
                }
            }
        }

        // 5. Flexible Privacy Settings Matrix
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Гибкая система настроек конфиденциальности",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    PrivacyToggleItem(
                        title = "Удаление сетевых метаданных и EXIF при P2P-передаче",
                        subtitle = "Не реализовано: EXIF из вложений не вырезается, переключатель сохраняет только " +
                            "намерение и на передачу не влияет",
                        checked = privacy.stripNetworkMetadata,
                        tag = "privacy_strip_metadata_switch",
                        onCheckedChange = {
                            onUpdatePrivacy(null, it, null, null, null, null, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Защита экрана (Блокировка скриншотов и записи экрана)",
                        subtitle = "Активирует флаг безопасности окна и очистку буфера обмена через 60 секунд",
                        checked = privacy.blockScreenshots,
                        tag = "privacy_block_screenshots_switch",
                        onCheckedChange = {
                            onUpdatePrivacy(null, null, it, null, null, null, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Ручной переключатель LAN (Резервный обход при отсутствии пиров Yggdrasil)",
                        subtitle = "По умолчанию ВЫКЛ: пассивный поиск узлов в локальной сети отключен. Явный lan= адрес из карточки контакта считается согласием и используется для прямой доставки с ACK даже при выключенном переключателе",
                        checked = privacy.allowDirectLanDiscovery,
                        tag = "privacy_lan_discovery_switch",
                        onCheckedChange = {
                            onUpdatePrivacy(null, null, null, it, null, null, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Автосинхронизация очереди при выходе в оверлей Yggdrasil",
                        subtitle = "Отправлять очередь S&F сразу, как только mesh растёт: у ядра " +
                            "появился новый узел в routing tree или резко прибавилось линков с " +
                            "рукопожатием. " +
                            "Если выключено, отправка идёт только по таймеру: от 45 секунд " +
                            "до 15 минут, пока очередь стоит на месте. " +
                            "История сообщений между вашими устройствами по оверлею не передаётся.",
                        checked = privacy.autoSyncOnMeshConnect,
                        tag = "privacy_autosync_switch",
                        onCheckedChange = {
                            onUpdatePrivacy(null, null, null, null, it, null, null, null)
                        }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = "Сложность Hashcash PoW для офлайн-узлов Store-and-Forward:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(8 to "8 бит (Быстро)", 10 to "10 бит (Стандарт)", 12 to "12 бит (Анти-спам)", 14 to "14 бит (Строгий)").forEach { (bits, label) ->
                            FilterChip(
                                selected = privacy.hashcashDifficultyBits == bits,
                                onClick = {
                                    onUpdatePrivacy(null, null, null, null, null, null, bits, null)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("pow_bits_chip_$bits")
                            )
                        }
                    }

                    Text(
                        text = "Таймер исчезающих сообщений по умолчанию:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0 to "Выкл", 3600 to "1 час", 86400 to "24 часа", 604800 to "7 дней").forEach { (ttl, label) ->
                            FilterChip(
                                selected = privacy.defaultDisappearingSeconds == ttl,
                                onClick = {
                                    onUpdatePrivacy(null, null, null, null, null, ttl, null, null)
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                modifier = Modifier.testTag("ttl_chip_$ttl")
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = "Тема оформления:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppTheme.values().forEach { theme ->
                            FilterChip(
                                selected = YggThemePref.state.value == theme,
                                onClick = { YggThemePref.save(context, theme) },
                                label = { Text(theme.labelRu, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("theme_chip_${theme.key}")
                            )
                        }
                    }
                    Text(
                        text = "Светлые темы (" + AppTheme.SnowLight.labelRu + ", " +
                            AppTheme.GraphiteLight.labelRu + ") перекрашивают интерфейс, но в " +
                            "некоторых экранах часть текста задана белым цветом вне палитры - " +
                            "на светлом фоне он может читаться плохо.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5. Open-Source Architecture & Cryptographic Audit Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, contentDescription = null, tint = CyberTealSecondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Открытый исходный код и Архитектура YGramm",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Сетевое ядро: Go-сборка Yggdrasil v0.5.14 (LINKS/ironwood) как libygggram.so через JNI. Системный VPN, TUN и root не используются: оверлей идёт по обычным сокетам приложения.\n" +
                            "• Обход блокировок: пиры подключаются по tls:// и wss:// на 443 (включая пути через CDN). Пир считается подтверждённым только после реального TLS-handshake с подменой SNI и ответа по протоколу Yggdrasil — открытого порта мало. uTLS-подмена фингерпринта, VLESS Reality, ShadowTLS v3 и DHT BEP-44 в этой сборке НЕ реализованы: такие схемы встречаются только как метки в каталоге пиров.\n" +
                            "• Криптография: BIP-39 -> HMAC-SHA512 для узла, Ed25519 для адреса IPv6 в 0200::/7. Поверх транспорта — X25519 + HKDF-SHA256 + AES-256-GCM на текст, вложения, голосовые и медиа звонка. Конвертов две версии: v2 на статическом ECDH (без прямой секретности) и v3, где к тому же DH сворачивается ключ, живущий одно сообщение; v3 пишется только собеседнику, подтвердившему что умеет его читать, а v2 читается всегда, поэтому смешанная сборка не теряет сообщения. Сам транспорт Yggdrasil шифрует кадры Arceliar/ironwood (X25519 + XSalsa20-Poly1305).\n" +
                            "• Без ключа X25519 собеседника из визитки сообщение не отправляется: открытого текста «с косметикой» в сборке нет, конверты старой версии 1 принимающая сторона отвергает.\n" +
                            "• Доставка: ACK на каждое сообщение, очередь S&F с повтором при росте mesh, подтверждение по каждому участнику группы.\n" +
                            "• Обновления: релиз на GitHub, проверка sha256 и подписи APK по закреплённому ключу, установка системным установщиком.\n" +
                            "• Лицензия: GPL-3.0 (файл LICENSE в репозитории); шрифты — см. LICENSE-fonts.md.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5.5 Auto-update from GitHub Releases
        item {
            val updateState by YGrammUpdateController.state.collectAsState()
            val available = updateState.available
            var peerPickerOpen by rememberSaveable { mutableStateOf(false) }
            var manifestDraft by rememberSaveable { mutableStateOf("") }
            val relayPeers = conversations.filter { it.chatType != "GROUP" }
            Card(
                modifier = Modifier.fillMaxWidth().testTag("auto_update_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = CyberTealSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Обновления приложения",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Установлена версия ${updateState.runningVersionLabel} • " +
                            "репозиторий ${updateState.repoSlug} • " +
                            "последняя проверка: ${updateState.lastCheckedLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        YggSwitch(
                            checked = updateState.autoCheckEnabled,
                            onCheckedChange = { enabled ->
                                YGrammUpdateController.setAutoCheckEnabled(context, enabled)
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Проверять релиз на GitHub автоматически",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = updateState.statusLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (updateState.statusLine.startsWith("Проверка не удалась") ||
                            updateState.statusLine.startsWith("Скачивание отменено")
                        ) Color(0xFFEF4444) else EmeraldPrimary
                    )
                    if (available != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = available.releaseNotes.take(400),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { YGrammUpdateController.checkNow() },
                            enabled = !updateState.busy,
                            modifier = Modifier.testTag("update_check_button")
                        ) {
                            Text(if (updateState.busy) "Проверяю…" else "Проверить сейчас")
                        }
                        if (available != null && updateState.downloadedApkPath == null) {
                            Button(
                                onClick = { YGrammUpdateController.downloadUpdate() },
                                enabled = !updateState.busy,
                                modifier = Modifier.testTag("update_download_button")
                            ) {
                                Text(
                                    if (updateState.downloadPercent >= 0)
                                        "Скачиваю ${updateState.downloadPercent}%"
                                    else "Скачать ${available.assetSizeBytes / 1024 / 1024} МБ"
                                )
                            }
                        }
                        if (updateState.downloadedApkPath != null) {
                            Button(
                                onClick = { YGrammUpdateController.installUpdate() },
                                modifier = Modifier.testTag("update_install_button")
                            ) {
                                Text("Установить")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Скачанный файл проверяется по sha256 из манифеста релиза и по подписи: " +
                            "APK должен быть подписан релизным ключом ${YGrammUpdateEngine.PINNED_RELEASE_CERT_SHA256_HEX.take(16)}…, " +
                            "иначе файл удаляется и установка не предлагается. " +
                            if (updateState.installPermissionMissing)
                                "\nРазрешение на установку из неизвестных источников не выдано — " +
                                "при нажатии «Установить» откроются системные настройки."
                            else
                                "\nУстановку выполняет системный установщик Android, приложение не ставит APK само.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Hub,
                            contentDescription = null,
                            tint = CyberTealSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "APK через знакомый узел",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Когда GitHub недоступен, тот же самый APK можно попросить у собеседника " +
                            "в оверлее. Пир только переносит байты и ничего не решает: файл принимается " +
                            "исключительно когда его sha256 и подпись совпадают с манифестом, который это " +
                            "устройство видело само, — с GitHub или вставленным вручную ниже.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = updateState.localManifestLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateState.localManifestLabel.startsWith("Своего манифеста"))
                            Color(0xFFEF4444) else EmeraldPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        YggSwitch(
                            checked = updateState.relayOfferEnabled,
                            onCheckedChange = { enabled ->
                                YGrammUpdateController.setRelayOfferEnabled(context, enabled)
                            },
                            modifier = Modifier.testTag("relay_offer_switch")
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Раздавать свой APK собеседникам из личных диалогов",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manifestDraft,
                        onValueChange = { manifestDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("relay_manifest_field"),
                        label = { Text("Свой блок манифеста релиза") },
                        placeholder = {
                            Text("Вставь сюда блок ygramm-update из описания релиза — " +
                                "это устройство сохранит его как якорь, не спрашивая пира")
                        },
                        minLines = 3,
                        maxLines = 8,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                onImportReleaseManifest(manifestDraft)
                                manifestDraft = ""
                            },
                            enabled = manifestDraft.isNotBlank(),
                            modifier = Modifier.testTag("relay_import_button")
                        ) {
                            Text("Принять этот манифест")
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Box {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { peerPickerOpen = true },
                                enabled = !updateState.relayBusy && relayPeers.isNotEmpty(),
                                modifier = Modifier.testTag("relay_request_button")
                            ) {
                                Text(if (updateState.relayBusy) "Пир несёт файл…" else "Спросить APK у пира")
                            }
                            if (updateState.relayBusy) {
                                OutlinedButton(
                                    onClick = { onCancelApkRelay() },
                                    modifier = Modifier.testTag("relay_cancel_button")
                                ) {
                                    Text("Отменить")
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = peerPickerOpen,
                            onDismissRequest = { peerPickerOpen = false }
                        ) {
                            relayPeers.forEach { peer ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(peer.title.ifBlank { "Без имени" })
                                            Text(
                                                text = peer.yggIpv6Address,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        peerPickerOpen = false
                                        onRequestApkFromPeer(peer.chatId)
                                    }
                                )
                            }
                        }
                    }
                    if (relayPeers.isEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Личных диалогов нет — спросить не у кого. Сначала нужен контакт, " +
                                "с которым уже идёт переписка по оверлею.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (updateState.relayBusy && updateState.relayPercent >= 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { updateState.relayPercent / 100f },
                            modifier = Modifier.fillMaxWidth().testTag("relay_progress"),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    if (updateState.relayStatusLine.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val relayLine = updateState.relayStatusLine
                        val relayIsGood = relayLine.startsWith("APK от собеседника совпал") ||
                            relayLine.startsWith("Запрос на") ||
                            relayLine.startsWith("Пир передаёт") ||
                            relayLine.startsWith("Проверяю принятый") ||
                            relayLine.startsWith("Раздача включена")
                        Text(
                            text = relayLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (relayIsGood) EmeraldPrimary else Color(0xFFEF4444)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "По оверлею идёт тот же зашифрованный потоковый транспорт файлов, что и " +
                            "для вложений: куски в AEAD-контейнере, корень Меркла проверяется по всему " +
                            "файлу. Приём чужого установщика невозможен: запрос на APK это устройство " +
                            "изобретает само, и без своего потока передачи оно ничего не принимает.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5.6 On-disk footprint of this app and the tools to shrink it
        item {
            LaunchedEffect(Unit) { onRefreshStorage() }
            val storage = uiState.storage
            fun kib(bytes: Long): String = if (bytes >= 1024 * 1024) {
                "${bytes / 1024 / 1024} МБ"
            } else {
                "${bytes / 1024} КБ"
            }
            Card(
                modifier = Modifier.fillMaxWidth().testTag("storage_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = CyberTealSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Хранилище устройства",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Файлы вложений: ${kib(storage.attachmentsBytes)}\n" +
                            "Голосовые: ${kib(storage.voiceBytes)}\n" +
                            "Аватары: ${kib(storage.avatarBytes)}\n" +
                            "Зашифрованная база: ${kib(storage.databaseBytes)}\n" +
                            "Кэш: ${kib(storage.cacheBytes)}\n" +
                            "Загруженное обновление: ${kib(storage.updateBytes)}\n" +
                            "Итого: ${kib(storage.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Цифры измерены по самим файлам, а не по счётчикам в базе. " +
                            "Удаление сообщения или очистка чата стирает его файл сразу; «убрать осиротевшие» " +
                            "добирает те, на которые не ссылается ни одно сообщение. " +
                            "Осколки незавершённых приёмов (.part) стираются при следующем запуске моста, " +
                            "а загруженный APK обновления остаётся до установки — иначе «Установить» не сработает.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onRefreshStorage,
                            modifier = Modifier.testTag("storage_rescan_button")
                        ) {
                            Text("Измерить заново")
                        }
                        Button(
                            onClick = onPurgeOrphanedFiles,
                            modifier = Modifier.testTag("storage_purge_button")
                        ) {
                            Text("Убрать осиротевшие файлы")
                        }
                    }
                }
            }
        }

        // 6. Panic-wipe: emergency removal of all local key material and history
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = Color(0xFFEF4444)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Экстренное стирание (Panic-wipe)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Останавливает все ноды Yggdrasil и безвозвратно удаляет с устройства: профили вместе с mnemonic-ключами, переписку, файлы, журналы звонков и резервные копии. Приложение вернётся в состояние свежей установки.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showPanicWipeDialog = true },
                        modifier = Modifier.testTag("panic_wipe_button")
                    ) {
                        Text("Стереть всё…", color = Color(0xFFEF4444))
                    }
                }
            }
        }
    }

    if (showEditProfileDialog) {
        EditDecentralizedProfileModal(
            initialName = profile.displayName,
            initialHandle = profile.handle,
            initialBio = profile.bio,
            initialStatus = profile.socialStatus,
            onDismiss = { showEditProfileDialog = false },
            onSave = { name, handle, bio, status ->
                onUpdateProfile(name, handle, bio, status)
                showEditProfileDialog = false
            }
        )
    }

    if (showCreateProfileDialog) {
        CreateNewDecentralizedProfileModal(
            nextIndex = userProfiles.size + 1,
            onDismiss = { showCreateProfileDialog = false },
            onCreate = { name, handle, bio, runInBg, switchNow, relayRole ->
                onCreateNewProfile(name, handle, bio, runInBg, switchNow, relayRole)
                showCreateProfileDialog = false
            }
        )
    }

    if (showQrIdentityDialog) {
        QrContactModal(
            uiState = uiState,
            initialTab = 0,
            onDismiss = { showQrIdentityDialog = false },
            onContactScanned = { payload ->
                showQrIdentityDialog = false
                onAddContactFromQr(payload)
            }
        )
    }

    if (showPanicWipeDialog) {
        AlertDialog(
            onDismissRequest = { showPanicWipeDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF4444)) },
            title = { Text("Экстренное стирание всех данных?") },
            text = {
                Text(
                    "Будут удалены без возможности восстановления: все профили с их mnemonic-ключами, " +
                        "переписка, файлы, журналы звонков и резервные копии на этом устройстве. " +
                        "Скопии переписки на узлах собеседников останутся — восстановить их нельзя " +
                        "ни с одного из устройств после стирания."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPanicWipeDialog = false
                        onPanicWipe()
                    },
                    modifier = Modifier.testTag("panic_wipe_confirm_button")
                ) {
                    Text("Стереть безвозвратно", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicWipeDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun PrivacyToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
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
        YggSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun CreateNewDecentralizedProfileModal(
    nextIndex: Int,
    onDismiss: () -> Unit,
    onCreate: (
        displayName: String,
        handle: String,
        bio: String,
        runSimultaneouslyInBackground: Boolean,
        switchImmediately: Boolean,
        nodeRelayRole: String
    ) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("Узел YGramm #$nextIndex") }
    var handle by rememberSaveable { mutableStateOf("@node$nextIndex.ygg") }
    var bio by rememberSaveable {
        mutableStateOf("Независимый крипто-профиль #$nextIndex в сети Yggdrasil • Прямой P2P")
    }
    var runInBackground by rememberSaveable { mutableStateOf(true) }
    var switchImmediately by rememberSaveable { mutableStateOf(false) }
    var selectedRelayRole by rememberSaveable { mutableStateOf("PEER_RELAY") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Сгенерировать новый профиль Yggdrasil",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Будет сгенерирована новая 12-словная мнемоника BIP-39, независимый IPv6-адрес 200::/7 и отдельный фоновый порт P2P.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя нового профиля") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_profile_name_input")
                )
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it },
                    label = { Text("Идентификатор (@handle.ygg)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_profile_handle_input")
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("Описание профиля") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Роль в сети Yggdrasil (Пир / Ретранслятор):",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val roleOptions = listOf(
                        "CLIENT_ONLY" to "Только клиент",
                        "PEER_RELAY" to "Пир-узел",
                        "SF_BLIND_RELAY" to "S&F Ретранслятор"
                    )
                    roleOptions.forEach { (code, label) ->
                        FilterChip(
                            selected = selectedRelayRole == code,
                            onClick = { selectedRelayRole = code },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("new_profile_role_$code")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Одновременная работа в фоне",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "Держать TCP-сокет этого профиля активным параллельно с основным профилем",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    YggSwitch(
                        checked = runInBackground,
                        onCheckedChange = { runInBackground = it },
                        modifier = Modifier.testTag("new_profile_bg_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Сразу переключиться на этот профиль",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "Сделать созданный профиль текущим на экране",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    YggSwitch(
                        checked = switchImmediately,
                        onCheckedChange = { switchImmediately = it },
                        modifier = Modifier.testTag("new_profile_switch_now")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, handle, bio, runInBackground, switchImmediately, selectedRelayRole) },
                modifier = Modifier.testTag("confirm_create_profile_button")
            ) {
                Text("Сгенерировать ключи и создать")
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
private fun EditDecentralizedProfileModal(
    initialName: String,
    initialHandle: String,
    initialBio: String,
    initialStatus: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var handle by rememberSaveable { mutableStateOf(initialHandle) }
    var bio by rememberSaveable { mutableStateOf(initialBio) }
    var status by rememberSaveable { mutableStateOf(initialStatus) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать децентрализованный профиль", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Отображаемое имя") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_name_input")
                )
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it },
                    label = { Text("Децентрализованный идентификатор (@handle.ygg)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = status,
                    onValueChange = { status = it },
                    label = { Text("Статус — виден собеседникам") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_status_input")
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("О себе / криптографические реквизиты") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Имя, @handle, статус и фото профиля уходят только тем, кому вы пишете, " +
                        "и каждому — запечатанными его собственным ключом. Без этого кадра узел " +
                        "на том конце не смог бы представиться вашим именем. Справочника профилей " +
                        "и поиска по адресу в сети нет: сервера, который их держал бы, здесь нет. " +
                        "Поле «О себе» остаётся на этом устройстве.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, handle, bio, status) },
                modifier = Modifier.testTag("save_profile_button")
            ) {
                Text("Подписать Ed25519 и сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
