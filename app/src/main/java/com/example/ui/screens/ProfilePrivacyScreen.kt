// File: app/src/main/java/com/example/ui/screens/ProfilePrivacyScreen.kt
package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.YggGramUiState

@Composable
fun ProfilePrivacyScreen(
    uiState: YggGramUiState,
    onUpdateProfile: (String, String, String, String) -> Unit,
    onUpdatePrivacy: (
        sealedSenderGlobal: Boolean?,
        stripNetworkMetadata: Boolean?,
        blockScreenshots: Boolean?,
        allowDirectLanDiscovery: Boolean?,
        autoSyncOnMeshConnect: Boolean?,
        defaultDisappearingSeconds: Int?,
        hashcashDifficultyBits: Int?,
        webDavEndpointUrl: String?
    ) -> Unit
) {
    var showEditProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showQrIdentityDialog by rememberSaveable { mutableStateOf(false) }

    val profile = uiState.userProfile
    val privacy = uiState.privacyMatrix

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Decentralized Profile Card
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
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = profile.displayName.take(2).uppercase(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = EmeraldPrimary
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
                                text = "${profile.handle} • ${profile.statusText}",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberTealSecondary
                            )
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
                            Text(
                                text = "Постоянный IPv6-адрес в сети Yggdrasil (200::/7):",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = uiState.identity.yggIpv6Address,
                                style = MaterialTheme.typography.titleSmall,
                                color = EmeraldPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Криптографическая подпись профиля: ${profile.profileSignatureHex}",
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
                            Text("Изменить профиль")
                        }
                        OutlinedButton(
                            onClick = { showQrIdentityDialog = true },
                            modifier = Modifier.testTag("show_qr_identity_button")
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("URI / Ключ")
                        }
                    }
                }
            }
        }

        // 2. Flexible Privacy Settings Matrix
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
                        title = "Sealed Sender (Скрытие отправителя от релеев)",
                        subtitle = "Шифрует сертификат отправителя внутри конверта X3DH, чтобы промежуточные и S&F узлы не видели метаданные",
                        checked = privacy.sealedSenderGlobal,
                        tag = "privacy_sealed_sender_switch",
                        onCheckedChange = {
                            onUpdatePrivacy(it, null, null, null, null, null, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Удаление сетевых метаданных и EXIF при P2P-передаче",
                        subtitle = "Автоматическая очистка заголовков файлов и маскировка локальных портов при обмене чанками",
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
                        title = "Локальное обнаружение устройств (Zero-Conf LAN P2P)",
                        subtitle = "Позволяет синхронизировать телефон и ПК в одной Wi-Fi/Ethernet сети на максимальной скорости без интернета",
                        checked = privacy.allowDirectLanDiscovery,
                        tag = "privacy_lan_discovery_switch",
                        onCheckedChange = {
                            onUpdatePrivacy(null, null, null, it, null, null, null, null)
                        }
                    )

                    PrivacyToggleItem(
                        title = "Автосинхронизация дельт при выходе в оверлей Yggdrasil",
                        subtitle = "Фоновая сверка вектора состояния StateVector и очереди S&F между вашими устройствами",
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
                }
            }
        }

        // 3. Open-Source Architecture & Cryptographic Audit Card
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
                            text = "Открытый исходный код и Архитектура YggGram",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Сетевое ядро: Userspace Yggdrasil v0.5 (200::/7) + gVisor Netstack без создания системного TUN и без прав Root/Администратора.\n" +
                            "• Анти-DPI / Обход Белых списков: uTLS (Chrome/iOS), CDN WebSocket (wss://), VLESS Reality, ShadowTLS v3, QUIC UDP Padding, DHT BEP-44.\n" +
                            "• Криптография: BIP-39 -> SLIP-0010, X3DH + Double Ratchet, MLS RFC 9420, Argon2id + XChaCha20-Poly1305.\n" +
                            "• Лицензия: Open-Source GPLv3 / воспроизводимая сборка.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showEditProfileDialog) {
        EditDecentralizedProfileModal(
            initialName = profile.displayName,
            initialHandle = profile.handle,
            initialBio = profile.bio,
            initialStatus = profile.statusText,
            onDismiss = { showEditProfileDialog = false },
            onSave = { name, handle, bio, status ->
                onUpdateProfile(name, handle, bio, status)
                showEditProfileDialog = false
            }
        )
    }

    if (showQrIdentityDialog) {
        DecentralizedIdentityUriModal(
            uiState = uiState,
            onDismiss = { showQrIdentityDialog = false }
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
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
                    label = { Text("Статус узла") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("О себе / криптографические реквизиты") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
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

@Composable
private fun DecentralizedIdentityUriModal(
    uiState: YggGramUiState,
    onDismiss: () -> Unit
) {
    val uri = "ygggram://${uiState.identity.yggIpv6Address}?pub=${uiState.identity.yggNodePublicKeyHex.take(32)}&spk=${uiState.identity.x25519SignedPreKeyHex.take(24)}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Децентрализованная визитка YggGram", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Передайте этот URI собеседнику для прямого P2P-соединения в сети Yggdrasil без центральных серверов:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uri,
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldPrimary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Готово")
            }
        }
    )
}
