// File: app/src/main/java/com/example/ui/screens/VaultSyncScreen.kt
package com.example.ui.screens

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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
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
    onCreateBackup: (String, Boolean) -> Unit,
    onVerifyOrRestoreBackup: (BackupSnapshotEntity, Boolean) -> Unit
) {
    var showPairDeviceDialog by rememberSaveable { mutableStateOf(false) }
    var backupPassphrase by rememberSaveable { mutableStateOf("") }

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
                                    text = "Встроенный Менеджер Ключей (BIP-39)",
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
                                text = "Единая мнемоническая фраза BIP-39 (12 слов -> Ed25519 + X25519 + Backup):",
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
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRegenerateIdentity,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("regenerate_seed_button")
                        ) {
                            Text("Сгенерировать новый сид")
                        }
                    }
                }
            }
        }

        // Derived keys
        item {
            Text(
                text = "Дерево криптографических ключей (${cryptoKeys.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        items(cryptoKeys, key = { it.keyId }) { keyRecord ->
            CryptoKeyCard(keyRecord = keyRecord)
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
                                    text = "Кроссплатформенная синхронизация",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Windows • Android • Linux (Прямой P2P по LAN и Yggdrasil)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyberTealSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSyncDevicesNow,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sync_devices_now_button")
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Синхронизировать", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { showPairDeviceDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("open_pair_device_dialog_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Связать")
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

        // 3. Encrypted Local & Zero-Knowledge Cloud Backups
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
                                text = "Снимки метаданных хранилища (архив не создаётся)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Argon2id, XChaCha20-Poly1305, Zstd и FastCDC здесь не применяются: " +
                                    "сводка считается из SHA-256 по размерам записей",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = backupPassphrase,
                        onValueChange = { backupPassphrase = it },
                        label = { Text("Парольная фраза контейнера (или фингерпринт мастер-ключа)") },
                        placeholder = { Text("Оставьте пустым для использования 256-bit Master Key") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("backup_passphrase_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onCreateBackup(backupPassphrase, false)
                                backupPassphrase = ""
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("create_local_backup_button")
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Метка: локально", maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = {
                                onCreateBackup(backupPassphrase, true)
                                backupPassphrase = ""
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("create_webdav_backup_button")
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Метка: WebDAV", maxLines = 1)
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Text(
                        text = "Журнал контейнеров резервных копий (${backupSnapshots.size})",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    backupSnapshots.forEach { snap ->
                        BackupSnapshotCard(
                            snapshot = snap,
                            onVerify = { onVerifyOrRestoreBackup(snap, false) },
                            onRestore = { onVerifyOrRestoreBackup(snap, true) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
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
                        contentDescription = "Снимок метаданных: архив на диск не записан",
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
                // These numbers come from buildEncryptedBackupContainer, which estimates a size
                // from the row counts instead of sealing the database - so they are labelled as
                // an estimate, not as properties of a file that exists.
                text = "Оценка: ${snapshot.sizeBytes / 1024} КБ, ${snapshot.chunkCountFastCdc} чанков • " +
                    "${snapshot.kdfSpec} • ${snapshot.cipherSpec}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTealSecondary
            )
            Text(
                text = "Merkle SHA-256 (от оценки, не от файла): ${snapshot.merkleRootHex.take(28)}...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onVerify,
                    modifier = Modifier.testTag("verify_backup_${snapshot.snapshotId}")
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Проверить MAC")
                }
                Button(
                    onClick = onRestore,
                    modifier = Modifier.testTag("restore_backup_${snapshot.snapshotId}")
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Восстановить")
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
    var name by rememberSaveable { mutableStateOf("Ноутбук Linux / Windows (YGramm Desktop)") }
    var ipv6 by rememberSaveable { mutableStateOf("0201:6f22:a810:49c1:77e0:12b4:9081:33d2") }
    var lan by rememberSaveable { mutableStateOf("192.168.1.25:51920 (Прямой LAN P2P)") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Связать новое устройство для P2P-синхронизации", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Устройство добавляется в ваш список и получает LAN-маяк обнаружения. " +
                        "Передача истории сообщений между устройствами по оверлею ещё не реализована: " +
                        "сообщения, отправленные на этом узле, останутся только в его базе.",
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
                    label = { Text("LAN адрес (опционально для локального ускорения)") },
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
                Text("Связать и синхронизировать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
