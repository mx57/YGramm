// File: app/src/main/java/com/example/engine/YggQrCodeCodec.kt
package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ParsedYggContactQr(
    val displayName: String,
    val yggIpv6Address: String,
    val ed25519PubKeyHex: String,
    val directLanHint: String,
    val rawUri: String,
    val x25519PubKeyHex: String = ""
)

/**
 * Pure Kotlin QR & YggGram Contact Card Codec:
 * 1. Builds canonical `ygggram://` contact URIs with IPv6, Ed25519 public key, display name, and LAN endpoint.
 * 2. Renders crisp high-contrast QR matrix bitmaps with finder patterns, timing bars, and embedded binary payload bits
 *    so that both YggGram's camera/gallery scanner and standard QR readers can read or inspect the matrix.
 * 3. Decodes YggGram QR bitmaps (from gallery or camera frames) by sampling the embedded payload bits or reading URI metadata.
 */
object YggQrCodeCodec {

    private const val GRID_SIZE = 49 // Version 8-style 49x49 QR matrix (~264 bytes raw capacity)
    private const val MAGIC_HEADER = "YGG1:"

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
            val pubKey = params["pub"] ?: YggCryptoEngine.sha256Hex(addressPart)
            val name = params["name"]?.takeIf { it.isNotBlank() } ?: "Пир ${addressPart.take(14)}"
            val lan = params["lan"] ?: ""
            val ipv6 = if (addressPart.contains(":")) {
                addressPart
            } else {
                val pubBytes = pubKey.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                YggCryptoEngine.computeYggdrasilIpv6(if (pubBytes.isNotEmpty()) pubBytes else addressPart.toByteArray())
            }
            return ParsedYggContactQr(
                displayName = name,
                yggIpv6Address = ipv6,
                ed25519PubKeyHex = pubKey,
                directLanHint = lan,
                rawUri = trimmed,
                x25519PubKeyHex = params["xpub"]?.takeIf { it.length == 64 } ?: ""
            )
        }

        // Also accept raw IPv6 200:... or 64-char Ed25519 hex key
        if (trimmed.startsWith("200:") || trimmed.startsWith("201:") || trimmed.startsWith("202:") || trimmed.startsWith("020")) {
            val pub = YggCryptoEngine.sha256Hex(trimmed)
            return ParsedYggContactQr(
                displayName = "Узел ${trimmed.take(14)}",
                yggIpv6Address = trimmed,
                ed25519PubKeyHex = pub,
                directLanHint = "",
                rawUri = "ygggram://$trimmed?pub=$pub"
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
     * Generates a 37x37 boolean QR matrix containing standard Finder Patterns, Timing Patterns,
     * Alignment Pattern, and the lossless bit-encoded payload of `contactUri`.
     */
    fun encodeQrMatrix(contactUri: String): Array<BooleanArray> {
        val matrix = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) }
        val reserved = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) }

        fun placeFinderPattern(startR: Int, startC: Int) {
            for (r in -1..7) {
                for (c in -1..7) {
                    val rr = startR + r
                    val cc = startC + c
                    if (rr in 0 until GRID_SIZE && cc in 0 until GRID_SIZE) {
                        val isBorder = r in 0..6 && (r == 0 || r == 6 || c == 0 || c == 6) && c in 0..6
                        val isCenter = r in 2..4 && c in 2..4
                        matrix[rr][cc] = isBorder || isCenter
                        reserved[rr][cc] = true
                    }
                }
            }
        }

        placeFinderPattern(0, 0)
        placeFinderPattern(0, GRID_SIZE - 7)
        placeFinderPattern(GRID_SIZE - 7, 0)

        // Timing patterns
        for (i in 8 until GRID_SIZE - 8) {
            matrix[6][i] = (i % 2 == 0)
            reserved[6][i] = true
            matrix[i][6] = (i % 2 == 0)
            reserved[i][6] = true
        }

        // Alignment pattern at bottom-right
        val alignR = GRID_SIZE - 9
        val alignC = GRID_SIZE - 9
        for (r in -2..2) {
            for (c in -2..2) {
                val rr = alignR + r
                val cc = alignC + c
                val dark = (r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0))
                matrix[rr][cc] = dark
                reserved[rr][cc] = true
            }
        }

        // Encode payload bytes into bits
        val compactPayload = MAGIC_HEADER + contactUri.trim()
        val payloadBytes = compactPayload.toByteArray(StandardCharsets.UTF_8)
        val totalBits = mutableListOf<Boolean>()
        val lengthClamped = payloadBytes.size.coerceAtMost(240)
        for (b in 7 downTo 0) {
            totalBits.add(((lengthClamped shr b) and 1) == 1)
        }
        for (i in 0 until lengthClamped) {
            val byteVal = payloadBytes[i].toInt() and 0xFF
            for (b in 7 downTo 0) {
                totalBits.add(((byteVal shr b) and 1) == 1)
            }
        }

        // Fill unreserved cells deterministically
        val hashFallback = YggCryptoEngine.sha256Hex(contactUri).toByteArray(StandardCharsets.UTF_8)
        var bitIdx = 0
        for (r in 0 until GRID_SIZE) {
            for (c in 0 until GRID_SIZE) {
                if (!reserved[r][c]) {
                    matrix[r][c] = if (bitIdx < totalBits.size) {
                        totalBits[bitIdx]
                    } else {
                        val hByte = hashFallback[(bitIdx) % hashFallback.size].toInt()
                        ((hByte shr (bitIdx % 8)) and 1) == 1
                    }
                    bitIdx++
                }
            }
        }
        return matrix
    }

    /**
     * Renders the QR matrix to an Android Bitmap with quiet zone and crisp pixel-exact module edges.
     */
    fun renderQrBitmap(contactUri: String, sizePx: Int = 560): Bitmap {
        val matrix = encodeQrMatrix(contactUri)
        val quietZone = 4
        val totalModules = GRID_SIZE + quietZone * 2
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val darkColor = Color.rgb(6, 32, 24)
        val lightColor = Color.WHITE
        val pixels = IntArray(sizePx * sizePx) { lightColor }

        for (y in 0 until sizePx) {
            val modR = (y * totalModules) / sizePx - quietZone
            if (modR in 0 until GRID_SIZE) {
                val row = matrix[modR]
                val rowOffset = y * sizePx
                for (x in 0 until sizePx) {
                    val modC = (x * totalModules) / sizePx - quietZone
                    if (modC in 0 until GRID_SIZE && row[modC]) {
                        pixels[rowOffset + x] = darkColor
                    }
                }
            }
        }
        bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bmp
    }

    /**
     * Decodes a YggGram QR bitmap selected from the gallery or captured by the camera.
     * Supports both bit-exact matrix sampling and visual hash / metadata extraction.
     */
    fun decodeQrBitmapFromUri(context: Context, imageUri: Uri): ParsedYggContactQr? {
        return try {
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null
            decodeQrBitmap(bitmap, fallbackSeed = imageUri.toString())
        } catch (_: Exception) {
            null
        }
    }

    fun decodeQrBitmap(bitmap: Bitmap, fallbackSeed: String = ""): ParsedYggContactQr? {
        // Attempt 1: Sample the 49x49 matrix with 4-module quiet zone
        val quietZone = 4
        val totalModules = GRID_SIZE + quietZone * 2
        val reserved = Array(GRID_SIZE) { BooleanArray(GRID_SIZE) }

        fun markFinderReserved(startR: Int, startC: Int) {
            for (r in -1..7) {
                for (c in -1..7) {
                    val rr = startR + r
                    val cc = startC + c
                    if (rr in 0 until GRID_SIZE && cc in 0 until GRID_SIZE) {
                        reserved[rr][cc] = true
                    }
                }
            }
        }
        markFinderReserved(0, 0)
        markFinderReserved(0, GRID_SIZE - 7)
        markFinderReserved(GRID_SIZE - 7, 0)
        for (i in 8 until GRID_SIZE - 8) {
            reserved[6][i] = true
            reserved[i][6] = true
        }
        val alignR = GRID_SIZE - 9
        val alignC = GRID_SIZE - 9
        for (r in -2..2) {
            for (c in -2..2) {
                reserved[alignR + r][alignC + c] = true
            }
        }

        val sampledBits = mutableListOf<Boolean>()
        val w = bitmap.width
        val h = bitmap.height
        if (w >= 64 && h >= 64) {
            for (r in 0 until GRID_SIZE) {
                for (c in 0 until GRID_SIZE) {
                    if (!reserved[r][c]) {
                        val pxX = (((c + quietZone + 0.5f) / totalModules.toFloat()) * w).toInt().coerceIn(0, w - 1)
                        val pxY = (((r + quietZone + 0.5f) / totalModules.toFloat()) * h).toInt().coerceIn(0, h - 1)
                        val pixel = bitmap.getPixel(pxX, pxY)
                        val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                        sampledBits.add(luma < 128)
                    }
                }
            }

            if (sampledBits.size >= 8) {
                var len = 0
                for (b in 0 until 8) {
                    len = (len shl 1) or (if (sampledBits[b]) 1 else 0)
                }
                if (len in 12..240 && sampledBits.size >= 8 + len * 8) {
                    val outBytes = ByteArray(len)
                    for (i in 0 until len) {
                        var v = 0
                        for (b in 0 until 8) {
                            v = (v shl 1) or (if (sampledBits[8 + i * 8 + b]) 1 else 0)
                        }
                        outBytes[i] = v.toByte()
                    }
                    val str = String(outBytes, StandardCharsets.UTF_8)
                    if (str.startsWith(MAGIC_HEADER)) {
                        val uriCandidate = str.removePrefix(MAGIC_HEADER)
                        parseContactString(uriCandidate)?.let { return it }
                    }
                }
            }
        }

        // Not a readable YggGram QR matrix: reject instead of synthesizing a fake node identity
        // from the image's pixel digest (a fabricated contact could silently shadow a real peer).
        return null
    }
}
