// File: app/src/main/java/com/example/ui/components/QrContactModal.kt
package com.example.ui.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.engine.ParsedYggContactQr
import com.example.engine.YggQrCodeCodec
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.YggGramUiState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Composable
fun QrContactModal(
    uiState: YggGramUiState,
    initialTab: Int = 0, // 0 = Мой QR, 1 = Сканер (Камера + Галерея)
    onDismiss: () -> Unit,
    onContactScanned: (ParsedYggContactQr) -> Unit
) {
    val context = LocalContext.current
    var activeTab by rememberSaveable { mutableIntStateOf(initialTab) }
    var manualUriInput by rememberSaveable { mutableStateOf("") }

    val myContactUri = remember(uiState.identity, uiState.userProfile.displayName, uiState.localLanIp, uiState.localUdpPort) {
        YggQrCodeCodec.buildContactUri(
            displayName = uiState.userProfile.displayName,
            yggIpv6Address = uiState.identity.yggIpv6Address,
            ed25519PubKeyHex = uiState.identity.yggNodePublicKeyHex,
            localLanIp = uiState.localLanIp,
            localPort = uiState.localUdpPort,
            x25519PubKeyHex = uiState.identity.x25519IdentityPubHex
        )
    }

    val qrBitmap = remember(myContactUri) {
        YggQrCodeCodec.renderQrBitmap(myContactUri, sizePx = 560)
    }

    val ownSafetyNumber = remember(uiState.identity.yggNodePublicKeyHex) {
        com.example.engine.YggCryptoEngine.safetyNumber(uiState.identity.yggNodePublicKeyHex)
            .ifBlank { "Ключ узла ещё не вычислен" }
    }

    var scannerStatus by remember { mutableStateOf("Камера не активна") }
    var scannerStatusError by remember { mutableStateOf(false) }

    fun deliverScannedText(rawText: String) {
        val parsed = YggQrCodeCodec.parseDecodedQrText(rawText)
        if (parsed != null && parsed.nodeKeyProblem.isNotBlank() && parsed.directLanHint.isBlank()) {
            scannerStatusError = true
            scannerStatus = "${parsed.nodeKeyProblem}. Контакт не добавлен: без полного ключа ноды сообщения не доставимы"
            Toast.makeText(context, scannerStatus, Toast.LENGTH_LONG).show()
            return
        }
        if (parsed != null) {
            scannerStatusError = false
            scannerStatus = "Распознан контакт: ${parsed.displayName}"
            vibrateOnScan(context)
            Toast.makeText(context, "QR-код распознан: ${parsed.displayName}", Toast.LENGTH_SHORT).show()
            onContactScanned(parsed)
        } else {
            scannerStatusError = true
            val preview = rawText.trim().take(48).ifBlank { "пустой код" }
            scannerStatus = "QR найден, но это не визитка YggGram: $preview"
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraPermissionDenied by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        cameraPermissionDenied = !granted
        if (granted) {
            scannerStatusError = false
            scannerStatus = "Камера активна • наведите на QR-код"
        } else {
            scannerStatusError = true
            scannerStatus = "Нет доступа к камере: используйте галерею или выдайте разрешение в настройках"
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            scannerStatusError = false
            scannerStatus = "Камера активна • сканирую QR-код…"
        }
    }

    val galleryQrPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val rawText = YggQrCodeCodec.decodeQrTextFromUri(context, uri)
            if (rawText == null) {
                scannerStatusError = true
                scannerStatus = "На выбранном изображении не найден QR-код"
                Toast.makeText(context, "Не удалось найти QR-код на изображении", Toast.LENGTH_SHORT).show()
            } else {
                deliverScannedText(rawText)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "QR-визитка и Сканер Yggdrasil",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("close_qr_modal_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                // Segmented Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        leadingIcon = {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Мой QR-код") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("qr_tab_my_code")
                    )
                    FilterChip(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        leadingIcon = {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Сканировать QR") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("qr_tab_scanner")
                    )
                }

                if (activeTab == 0) {
                    // My QR Code View
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .border(2.dp, EmeraldPrimary, RoundedCornerShape(18.dp))
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR-код вашего узла Yggdrasil",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("my_qr_bitmap_image")
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiState.userProfile.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = "Стандартный QR-код: читается камерой YggGram и любым сторонним сканером",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "IPv6 Yggdrasil: ${uiState.identity.yggIpv6Address}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = EmeraldPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Ed25519: ${uiState.identity.yggNodePublicKeyHex.take(36)}...",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = CyberTealSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Ключевое число вашего узла (safety number)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = ownSafetyNumber,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = EmeraldPrimary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("my_safety_number_text")
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Собеседник сверяет это число с инспектором ключей в вашем чате на своём устройстве.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("YggGram QR URI", myContactUri))
                                    Toast.makeText(context, "Ссылка-визитка ygggram:// скопирована", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("copy_qr_uri_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Копировать", maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "YggGram • ${uiState.userProfile.displayName}")
                                        putExtra(Intent.EXTRA_TEXT, myContactUri)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Поделиться визиткой YggGram")
                                    )
                                },
                                modifier = Modifier.testTag("share_qr_uri_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Поделиться", maxLines = 1)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val parsedSelf = YggQrCodeCodec.parseContactString(myContactUri)
                                if (parsedSelf != null) {
                                    onContactScanned(parsedSelf)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("test_my_qr_button")
                        ) {
                            Text("Открыть чат со своим узлом (тест)")
                        }
                    }
                } else {
                    // Scanner View: Camera + Gallery Picker + Paste URI
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (hasCameraPermission) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.5.dp, EmeraldPrimary, RoundedCornerShape(16.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                CameraQrPreviewBox(
                                    onQrTextDetected = { rawText -> deliverScannedText(rawText) },
                                    onCameraError = { message ->
                                        scannerStatusError = true
                                        scannerStatus = message
                                    }
                                )
                                // Scanner target reticle
                                Box(
                                    modifier = Modifier
                                        .size(140.dp)
                                        .border(2.dp, EmeraldPrimary.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                                )
                                Surface(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                ) {
                                    Text(
                                        text = "Держите QR-код в рамке",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CameraAlt,
                                        contentDescription = null,
                                        tint = CyberTealSecondary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Text(
                                        text = "Сканирование камерой или из галереи",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Разрешите доступ к камере, чтобы навести её на QR-код, либо распознайте готовый скриншот визитки из галереи.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                        modifier = Modifier.testTag("request_camera_qr_button")
                                    ) {
                                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Включить камеру для QR")
                                    }
                                    if (cameraPermissionDenied) {
                                        OutlinedButton(
                                            onClick = {
                                                val intent = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.fromParts("package", context.packageName, null)
                                                )
                                                context.startActivity(intent)
                                            },
                                            modifier = Modifier.testTag("open_camera_settings_button")
                                        ) {
                                            Text("Открыть настройки приложения")
                                        }
                                    }
                                }
                            }
                        }

                        // Live scanner feedback: proves that analysis is running
                        Surface(
                            color = if (scannerStatusError) {
                                AmberShieldTertiary.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (scannerStatusError) AmberShieldTertiary else EmeraldPrimary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = scannerStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (scannerStatusError) AmberShieldTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("qr_scanner_status")
                                )
                            }
                        }

                        // Gallery Photo Picker Button for QR
                        Button(
                            onClick = {
                                galleryQrPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("scan_qr_from_gallery_button")
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Распознать QR-код с фото из галереи")
                        }

                        // Manual / Clipboard URI fallback
                        OutlinedTextField(
                            value = manualUriInput,
                            onValueChange = { manualUriInput = it },
                            label = { Text("Или вставьте ygggram:// URI / ключ Ed25519") },
                            placeholder = { Text("ygggram://200:...") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                        if (clipText.isNotBlank()) {
                                            manualUriInput = clipText
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Вставить из буфера")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("qr_manual_uri_input")
                        )

                        Button(
                            onClick = {
                                val parsed = YggQrCodeCodec.parseContactString(manualUriInput)
                                if (parsed != null) {
                                    deliverScannedText(manualUriInput)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Введите ygggram://…?pub=…, 64-символьный ключ Ed25519 или IPv6 2xx:…. Голый IPv6 не содержит ключа ноды — оверлей не сможет доставить по нему",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            enabled = manualUriInput.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("confirm_qr_uri_add_button")
                        ) {
                            Text("Добавить контакт из QR / URI")
                        }
                    }
                }
            }
        }
    }
}

private fun vibrateOnScan(context: Context) {
    try {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(60L)
        }
    } catch (_: Throwable) {
    }
}

@Composable
private fun CameraQrPreviewBox(
    onQrTextDetected: (String) -> Unit,
    onCameraError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val disposed = remember { AtomicBoolean(false) }
    val lastAnalysisAt = remember { AtomicLong(0L) }
    val lastDetectionAt = remember { AtomicLong(0L) }
    val cameraProviderRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    val currentOnQrTextDetected by rememberUpdatedState(onQrTextDetected)
    val currentOnCameraError by rememberUpdatedState(onCameraError)

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            try {
                cameraProviderRef[0]?.unbindAll()
            } catch (_: Throwable) {
            }
            try {
                analysisExecutor.shutdown()
            } catch (_: Throwable) {
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                if (disposed.get()) return@addListener
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProviderRef[0] = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            val now = SystemClock.elapsedRealtime()
                            val previous = lastAnalysisAt.get()
                            if (now - previous < 300L) {
                                return@setAnalyzer
                            }
                            lastAnalysisAt.set(now)
                            if (now - lastDetectionAt.get() < 2_500L) {
                                return@setAnalyzer
                            }
                            if (disposed.get()) return@setAnalyzer
                            val bitmap: Bitmap? = try {
                                imageProxy.toBitmap()
                            } catch (_: Throwable) {
                                null
                            }
                            if (bitmap != null) {
                                val text = try {
                                    YggQrCodeCodec.decodeQrText(bitmap)
                                } finally {
                                    bitmap.recycle()
                                }
                                if (text != null && !disposed.get()) {
                                    lastDetectionAt.set(SystemClock.elapsedRealtime())
                                    previewView.post {
                                        if (!disposed.get()) currentOnQrTextDetected(text)
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            imageProxy.close()
                        }
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (t: Throwable) {
                    if (!disposed.get()) {
                        previewView.post {
                            currentOnCameraError(
                                "Камера недоступна: ${t.message?.take(60) ?: "ошибка инициализации"}. Используйте галерею."
                            )
                        }
                    }
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier
            .fillMaxSize()
            .testTag("camera_qr_preview")
    )
}
