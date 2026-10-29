// File: app/src/main/java/com/example/ui/screens/VaultSyncScreen.kt
package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.BackupSnapshotEntity
import com.example.data.CryptoKeyRecordEntity
import com.example.data.LinkedDeviceEntity
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.YggGramUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultSyncScreen(
    uiState: YggGramUiState,
    cryptoKeys: List<CryptoKeyRecordEntity>,
    linkedDevices: List<LinkedDeviceEntity>,
    backupSnapshots: List<BackupSnapshotEntity>,
    onToggleSeedReveal: () -> Unit,
    onRegenerateIdentity: () -> Unit,
    onSyncDevicesNow: () -> Unit,
    onPairDevice: (String, String, String, String) -> Unit,
    onExportDatabase: (Uri) -> Unit,
    onVerifyExportedArchive: (BackupSnapshotEntity, Uri) -> Unit,
    onRestoreFromArchive: (BackupSnapshotEntity) -> Unit
) {
    var showPairDeviceDialog by rememberSaveable { mutableStateOf(false) }
    var showRegenerateIdentityDialog by remember(uiState.currentProfileId) { mutableStateOf(false) }
    var showTechnicalKeyDetails by rememberSaveable { mutableStateOf(false) }
    // The snapshot being checked is remembered only while the picker is open: creating an
    // archive needs a write target, checking one needs to open the existing file, so the
    // two jobs cannot share a contract.
    var archiveToVerify by remember { mutableStateOf<BackupSnapshotEntity?>(null) }
    val exportArchiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) onExportDatabase(uri)
    }
    val verifyArchiveLauncher = rememberLauncherForActivityResult(
        // Any mime: a document provider may label a .db file however it likes, and a filter
        // that hides the user's own archive would be worse than an over-broad list.
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val pending = archiveToVerify
        archiveToVerify = null
        if (uri != null && pending != null) onVerifyExportedArchive(pending, uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Built-in Key Manager (BIP-39 hierarchy)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = EmeraldPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Ключи и экспорт",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Root Fingerprint: ${uiState.identity.masterFingerprintHex} • Эпоха #${uiState.keyEpoch}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldPrimary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onToggleSeedReveal,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("toggle_seed_reveal_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.isSeedPhraseRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (uiState.isSeedPhraseRevealed) "Скрыть" else "Показать", maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Фраза восстановления ключей",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (uiState.isSeedPhraseRevealed) {
                                    uiState.identity.mnemonicWords.mapIndexed { i, w -> "${i + 1}.$w" }.joinToString("  ")
                                } else {
                                    "1.••••••  2.••••••  3.••••••  4.••••••  5.••••••  6.••••••  (Нажмите «Показать»)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (uiState.isSeedPhraseRevealed) AmberShieldTertiary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Восстановление по фразе пока не реализовано. " +
                                    "Она не заменяет ключ базы в Keystore.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showRegenerateIdentityDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("regenerate_seed_button")
                        ) {
                            Text("Создать новую фразу")
                        }
                    }
                }
            }
        }

        item {
            TextButton(
                onClick = { showTechnicalKeyDetails = !showTechnicalKeyDetails },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("toggle_technical_key_details_button")
            ) {
                Column {
                    Text("Технические сведения о ключах")
                    Text(if (showTechnicalKeyDetails) "Свернуть" else "Развернуть")
                }
            }
        }

        if (showTechnicalKeyDetails) {
            item {
                Text(
                    text = "Дерево криптографических ключей (${cryptoKeys.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            items(cryptoKeys, key = { it.keyId }) { keyRecord ->
                CryptoKeyCard(keyRecord = keyRecord)
            }
        }

        // 2. Cross-Platform Device Synchronization
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                            Icon(Icons.Default.Devices, contentDescription = null, tint = CyberTealSecondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Список и обнаружение устройств",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "LAN-маяк обнаружения. История сообщений между устройствами не передаётся.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTealSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSyncDevicesNow,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sync_devices_now_button")
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Отправить маяк")
                        }
                        OutlinedButton(
                            onClick = { showPairDeviceDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("open_pair_device_dialog_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Добавить устройство")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    linkedDevices.forEach { device ->
                        LinkedDeviceRow(device = device)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }

        // 3. Export of the encrypted database
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Backup, contentDescription = null, tint = AmberShieldTertiary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Экспорт шифрованной базы",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Копируется сам файл SQLCipher: archive = байты базы, " +
                                    "ключ к ней остаётся в Keystore этого устройства",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            exportArchiveLauncher.launch("ygggram_export_${System.currentTimeMillis() / 1000}.db")
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_database_button")
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Выбрать файл для архива", maxLines = 1)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Восстановление из архива не реализовано: кнопка снимка объяснит причину. " +
                            "Экспорт не резервирует ключ — без этого устройства архив не открыть.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Text(
                        text = "Журнал экспортов (${backupSnapshots.size})",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    backupSnapshots.forEach { snap ->
                        BackupSnapshotCard(
                            snapshot = snap,
                            onVerify = {
                                archiveToVerify = snap
                                verifyArchiveLauncher.launch(arrayOf("*/*"))
                            },
                            onRestore = { onRestoreFromArchive(snap) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (showRegenerateIdentityDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateIdentityDialog = false },
            modifier = Modifier.testTag("regenerate_identity_dialog"),
            title = { Text("Создать новую фразу?") },
            text = {
                Text(
                    "Текущая фраза и ключи профиля будут заменены, IPv6-адрес Yggdrasil изменится, " +
                        "а эпоха ключей сбросится до 1. Сообщите контактам новый адрес и ключи: " +
                        "по прежнему адресу этот профиль больше не будет доступен. " +
                        "Чаты и сообщения не удаляются, ключ базы в Keystore не меняется. " +
                        "Восстановление прежней фразы в приложении не реализовано."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRegenerateIdentityDialog = false
                        onRegenerateIdentity()
                    },
                    modifier = Modifier.testTag("confirm_regenerate_identity_button")
                ) {
                    Text("Заменить фразу и ключи")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRegenerateIdentityDialog = false },
                    modifier = Modifier.testTag("cancel_regenerate_identity_button")
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    if (showPairDeviceDialog) {
        PairCrossPlatformDeviceModal(
            onDismiss = { showPairDeviceDialog = false },
            onPair = { name, platform, ipv6, lan ->
                onPairDevice(name, platform, ipv6, lan)
                showPairDeviceDialog = false
            }
        )
    }
}

@Composable
private fun CryptoKeyCard(keyRecord: CryptoKeyRecordEntity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("crypto_key_card_${keyRecord.keyId}"),
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
                Text(
                    text = keyRecord.keyName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = keyRecord.algorithm,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Путь: ${keyRecord.derivationPath} • Отпечаток: ${keyRecord.fingerprintHex}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTealSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = keyRecord.usageScope,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LinkedDeviceRow(device: LinkedDeviceEntity) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (device.platform == "ANDROID_ARM64") Icons.Default.PhoneAndroid else Icons.Default.Computer,
                contentDescription = null,
                tint = if (device.isOnline) EmeraldPrimary else AmberShieldTertiary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.deviceName, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "Ygg IPv6: ${device.yggIpv6.take(22)}... • ${device.lanEndpoint}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTealSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // syncedChunksCount stayed at zero for every device because no chunk
                    // transfer exists, so it is not shown.
                    text = "StateVector #${device.stateVectorVersion} (локальный учёт) • Маяк в ${timeFormatter.format(Date(device.lastSyncTimestamp))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BackupSnapshotCard(
    snapshot: BackupSnapshotEntity,
    onVerify: () -> Unit,
    onRestore: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backup_card_${snapshot.snapshotId}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
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
                        Icons.Default.Info,
                        contentDescription = "Запись об экспорте файла шифрованной базы",
                        tint = AmberShieldTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = snapshot.archiveFileName,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${snapshot.sizeBytes / 1024} КБ • ${dateFormatter.format(Date(snapshot.createdAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                // Every number here came off the written archive: YggEncryptedExport profiles the
                // bytes read back from the document, not a guess built from row counts.
                text = "Файл: ${snapshot.sizeBytes / 1024} КБ, ${snapshot.chunkCountFastCdc} кусков по 12 КИБ • " +
                    "${snapshot.kdfSpec} • ${snapshot.cipherSpec}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTealSecondary
            )
            Text(
                text = "Merkle SHA-256 по байтам архива: ${snapshot.merkleRootHex.take(28)}...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (snapshot.isVerified) "Сверка с базой: root совпал" else "Сверка с базой: root НЕ совпал",
                style = MaterialTheme.typography.labelSmall,
                color = if (snapshot.isVerified) EmeraldPrimary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onVerify,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("verify_backup_${snapshot.snapshotId}")
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Проверить архив")
                }
                TextButton(
                    onClick = onRestore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("restore_backup_${snapshot.snapshotId}")
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Почему нельзя восстановить")
                }
            }
        }
    }
}

@Composable
private fun PairCrossPlatformDeviceModal(
    onDismiss: () -> Unit,
    onPair: (String, String, String, String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var ipv6 by rememberSaveable { mutableStateOf("") }
    var lan by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить устройство", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Устройство добавляется только в локальный список. " +
                        "Кнопка «Отправить маяк» отправляет LAN-маяк обнаружения. " +
                        "Передача истории сообщений между устройствами не реализована: " +
                        "сообщения этого узла остаются только в его базе.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название устройства") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pair_device_name_input")
                )
                OutlinedTextField(
                    value = ipv6,
                    onValueChange = { ipv6 = it },
                    label = { Text("IPv6-адрес узла Yggdrasil или код спаривания") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lan,
                    onValueChange = { lan = it },
                    label = { Text("LAN-адрес (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onPair(name, "WINDOWS_X64", ipv6, lan)
                    }
                },
                modifier = Modifier.testTag("confirm_pair_device_button")
            ) {
                Text("Добавить устройство")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
