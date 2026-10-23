// File: app/src/main/java/com/example/engine/YggQrCodeCodec.kt
package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max

data class ParsedYggContactQr(
    val displayName: String,
    val yggIpv6Address: String,
    val ed25519PubKeyHex: String,
    val directLanHint: String,
    val rawUri: String,
    val x25519PubKeyHex: String = "",
    val nodeKeyProblem: String = ""
)

/**
 * YggGram contact-card codec built on the standard QR Code Model 2 format (ZXing).
 * The rendered code is readable by any third-party QR scanner, and the in-app
 * camera/gallery scanner decodes standard QR frames with rotation and perspective
 * correction instead of sampling a hand-drawn matrix.
 */
object YggQrCodeCodec {

    private val DARK_COLOR = 0xFF062018.toInt()
    private const val MAX_DECODE_SIDE = 1600

    fun buildContactUri(
        displayName: String,
        yggIpv6Address: String,
        ed25519PubKeyHex: String,
        localLanIp: String?,
        localPort: Int,
        x25519PubKeyHex: String = ""
    ): String {
        val encodedName = try {
            URLEncoder.encode(displayName.trim().ifBlank { "Узел YggGram" }, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            "YggNode"
        }
        val lanPart = if (!localLanIp.isNullOrBlank() && localLanIp != "0.0.0.0" && localLanIp != "127.0.0.1") {
            "&lan=$localLanIp:$localPort"
        } else {
            ""
        }
        val xpubPart = if (x25519PubKeyHex.trim().length == 64) {
            "&xpub=${x25519PubKeyHex.trim().lowercase()}"
        } else {
            ""
        }
        return "ygggram://${yggIpv6Address.trim()}?pub=${ed25519PubKeyHex.trim()}&name=$encodedName$xpubPart$lanPart"
    }

    fun parseContactString(rawInput: String): ParsedYggContactQr? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null

        if (trimmed.startsWith("ygggram://", ignoreCase = true)) {
            val withoutScheme = trimmed.substringAfter("://")
            val addressPart = withoutScheme.substringBefore("?").trim()
            val queryPart = withoutScheme.substringAfter("?", "")
            val params = mutableMapOf<String, String>()
            queryPart.split("&").forEach { kv ->
                val idx = kv.indexOf('=')
                if (idx > 0) {
                    val k = kv.substring(0, idx).lowercase()
                    val v = kv.substring(idx + 1)
                    params[k] = try {
                        URLDecoder.decode(v, StandardCharsets.UTF_8.name())
                    } catch (_: Exception) {
                        v
                    }
                }
            }
            // Never invent an Ed25519 key from the address: the overlay would silently
            // address a node that does not exist and the mesh would drop every frame.
            val rawPubKey = params["pub"]?.trim()?.lowercase().orEmpty()
            val pubKey = rawPubKey.takeIf { YggCryptoEngine.isEd25519Hex(it) }
            // A truncated or corrupted ?pub= is the worst possible silent failure: the
            // contact looks complete but has no addressable node key, so every message
            // it receives stays queued forever. Report exactly what was in the card.
            val nodeKeyProblem = when {
                pubKey != null || rawPubKey.isEmpty() -> ""
                rawPubKey.length != 64 ->
                    "Ключ узла в визитке неполный: ${rawPubKey.length} символов вместо 64"
                else -> "Ключ узла в визитке содержит символы вне [0-9a-f]"
            }
            val name = params["name"]?.takeIf { it.isNotBlank() } ?: "Пир ${addressPart.take(14)}"
            val lan = params["lan"] ?: ""
            val ipv6 = when {
                pubKey != null && addressPart.contains(":") &&
                    YggCryptoEngine.addressMatchesEd25519Key(addressPart, pubKey) -> addressPart
                pubKey != null -> {
                    val pubBytes = pubKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    YggCryptoEngine.computeYggdrasilIpv6(pubBytes)
                }
                addressPart.contains(":") -> addressPart
                else -> return null
            }
            return ParsedYggContactQr(
                displayName = name,
                yggIpv6Address = ipv6,
                ed25519PubKeyHex = pubKey.orEmpty(),
                directLanHint = lan,
                rawUri = trimmed,
                x25519PubKeyHex = params["xpub"]?.trim()?.lowercase()
                    ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
                    .orEmpty(),
                nodeKeyProblem = nodeKeyProblem
            )
        }

        // A bare IPv6 has no node key, so it can only be matched against a local
        // profile or used as a LAN hint; remote overlay delivery requires the full URI.
        if (trimmed.startsWith("200:") || trimmed.startsWith("201:") || trimmed.startsWith("202:") || trimmed.startsWith("020")) {
            return ParsedYggContactQr(
                displayName = "Узел ${trimmed.take(14)}",
                yggIpv6Address = trimmed,
                ed25519PubKeyHex = "",
                directLanHint = "",
                rawUri = "ygggram://$trimmed"
            )
        }

        if (trimmed.length == 64 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val pubBytes = trimmed.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
            val ipv6 = YggCryptoEngine.computeYggdrasilIpv6(pubBytes)
            return ParsedYggContactQr(
                displayName = "Пир Ed25519 ${trimmed.take(8)}",
                yggIpv6Address = ipv6,
                ed25519PubKeyHex = trimmed.lowercase(),
                directLanHint = "",
                rawUri = "ygggram://$ipv6?pub=${trimmed.lowercase()}"
            )
        }

        return null
    }

    /**
     * Parses the text payload of any scanned QR code. Some scanners prepend text
     * or open the code in a browser wrapper, so the canonical `ygggram://` URI is
     * also searched inside a larger payload.
     */
    fun parseDecodedQrText(rawText: String): ParsedYggContactQr? {
        val text = rawText.trim()
        if (text.isBlank()) return null
        parseContactString(text)?.let { return it }
        val uriIndex = text.indexOf("ygggram://", ignoreCase = true)
        if (uriIndex >= 0) {
            val candidate = text.substring(uriIndex)
                .substringBefore('\n')
                .substringBefore('\r')
                .trim()
            parseContactString(candidate)?.let { return it }
        }
        return null
    }

    /** Renders a standards-compliant QR code that any Android/iOS camera can read. */
    fun renderQrBitmap(contactUri: String, sizePx: Int = 560): Bitmap {
        val side = sizePx.coerceIn(240, 2048)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2
        )
        val matrix = runCatching {
            QRCodeWriter().encode(contactUri, BarcodeFormat.QR_CODE, side, side, hints)
        }.getOrNull()

        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        if (matrix == null) {
            // Encoding fails only for absurdly large payloads; a blank card is safer
            // than crashing the identity screen.
            bitmap.eraseColor(Color.WHITE)
            return bitmap
        }
        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in 0 until side) {
            val rowOffset = y * side
            for (x in 0 until side) {
                if (matrix.get(x, y)) {
                    pixels[rowOffset + x] = DARK_COLOR
                }
            }
        }
        bitmap.setPixels(pixels, 0, side, 0, 0, side, side)
        return bitmap
    }

    /** Decodes the raw text of a QR code from a bitmap; null when no QR is present. */
    fun decodeQrText(bitmap: Bitmap): String? {
        if (bitmap.width < 16 || bitmap.height < 16) return null
        val working = downscaleForDecode(bitmap)
        return try {
            val width = working.width
            val height = working.height
            val pixels = IntArray(width * height)
            working.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                        DecodeHintType.CHARACTER_SET to "UTF-8"
                    )
                )
            }
            try {
                reader.decodeWithState(binary)?.text
            } catch (_: Throwable) {
                null
            } finally {
                reader.reset()
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (working !== bitmap) {
                working.recycle()
            }
        }
    }

    fun decodeQrTextFromUri(context: Context, imageUri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        }
        val sampleSize = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            max(1, max(bounds.outWidth, bounds.outHeight) / MAX_DECODE_SIDE)
        } else {
            1
        }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }
        }.getOrNull() ?: return null
        return try {
            decodeQrText(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun decodeQrBitmapFromUri(context: Context, imageUri: Uri): ParsedYggContactQr? {
        val text = decodeQrTextFromUri(context, imageUri) ?: return null
        return parseDecodedQrText(text)
    }

    fun decodeQrBitmap(bitmap: Bitmap, fallbackSeed: String = ""): ParsedYggContactQr? {
        // Identities are never synthesized from image bytes: a QR must carry a real
        // contact URI. fallbackSeed stays only for caller compatibility.
        val text = decodeQrText(bitmap) ?: return null
        return parseDecodedQrText(text)
    }

    private fun downscaleForDecode(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= MAX_DECODE_SIDE) return bitmap
        val scale = MAX_DECODE_SIDE.toFloat() / maxSide.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
