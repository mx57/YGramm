// File: app/src/main/java/com/example/ui/components/QrContactModal.kt
package com.example.ui.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.engine.ParsedYggContactQr
import com.example.engine.YggQrCodeCodec
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary
import com.example.viewmodel.YggGramUiState
import java.util.concurrent.Executors

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
            localPort = uiState.localUdpPort
        )
    }

    val qrBitmap = remember(myContactUri) {
        YggQrCodeCodec.renderQrBitmap(myContactUri, sizePx = 560)
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val galleryQrPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = YggQrCodeCodec.decodeQrBitmapFromUri(context, uri)
            if (decoded != null) {
                Toast.makeText(context, "QR-код распознан: ${decoded.displayName}", Toast.LENGTH_SHORT).show()
                onContactScanned(decoded)
            } else {
                Toast.makeText(context, "Не удалось прочитать QR-код на выбранном изображении", Toast.LENGTH_SHORT).show()
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
                                Text("Скопировать URI")
                            }

                            OutlinedButton(
                                onClick = {
                                    val parsedSelf = YggQrCodeCodec.parseContactString(myContactUri)
                                    if (parsedSelf != null) {
                                        onContactScanned(parsedSelf)
                                    }
                                },
                                modifier = Modifier.testTag("test_my_qr_button")
                            ) {
                                Text("Тест QR")
                            }
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
                                    onQrDetected = { parsed ->
                                        onContactScanned(parsed)
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
                                        text = "Наведите камеру на QR-код YggGram",
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
                                        text = "Разрешите доступ к камере для наведения на QR-код или выберите готовый скриншот QR-визитки из галереи.",
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
                                }
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
                                    onContactScanned(parsed)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Введите корректный URI ygggram://, IPv6 200:... или 64-символьный ключ Ed25519",
                                        Toast.LENGTH_SHORT
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

@Composable
private fun CameraQrPreviewBox(
    onQrDetected: (ParsedYggContactQr) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var alreadyTriggered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                analysisExecutor.shutdown()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            if (!alreadyTriggered) {
                                val bmp: Bitmap? = try {
                                    imageProxy.toBitmap()
                                } catch (_: Throwable) {
                                    null
                                }
                                if (bmp != null) {
                                    val candidate = YggQrCodeCodec.decodeQrBitmap(bmp)
                                    if (candidate != null && candidate.rawUri.contains("name=") && !candidate.rawUri.contains("QR-Node-")) {
                                        alreadyTriggered = true
                                        previewView.post {
                                            onQrDetected(candidate)
                                        }
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
                } catch (_: Throwable) {
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
