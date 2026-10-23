// File: app/src/main/java/com/example/engine/YggAvatarCodec.kt
package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * Turning a picture the user picked into something small enough to travel inside a
 * message frame, and turning it back into a file again on the other side.
 *
 * The whole point of the size cap is that an avatar rides on the MSG frame, not on its
 * own connection: a node that has to open a second channel for a profile picture is a
 * node that leaks the picture to nobody. That constrains the file to what one overlay
 * datagram can carry, so the output is a square [SIDE_PX] JPEG and anything that will
 * not fit is refused rather than silently degraded on the far side.
 */
object YggAvatarCodec {

    const val SIDE_PX = 128

    /**
     * Raw JPEG ceiling. A wire frame base64url-codes every value it carries and the Go
     * core stops reading at 65535 bytes, so this leaves the message itself room for its
     * own text plus the ~1.4x inflation of encoding the picture twice.
     */
    const val MAX_JPEG_BYTES = 12_000

    data class Stored(val localUri: String, val hashHex: String, val jpegBytes: Int)

    private val QUALITY_LADDER = intArrayOf(82, 70, 58, 46)

    /**
     * @return null when the URI is not a readable image or cannot be compressed small
     *         enough — the caller has to say so instead of keeping a stale picture that
     *         the other node will still see as current.
     */
    fun storeFromUri(context: Context, source: Uri): Stored? {
        val decoded = decodeSquare(context, source) ?: return null
        try {
            val dir = avatarDir(context)
            for (quality in QUALITY_LADDER) {
                val bytes = encodeJpeg(decoded, quality)
                if (bytes.size <= MAX_JPEG_BYTES) {
                    val hash = YggCryptoEngine.sha256Hex(bytes)
                    val file = File(dir, "$hash.jpg")
                    FileOutputStream(file).use { it.write(bytes) }
                    return Stored(Uri.fromFile(file).toString(), hash, bytes.size)
                }
            }
            return null
        } finally {
            decoded?.recycle()
        }
    }

    /**
     * Writing a picture that arrived over the overlay.
     *
     * The hash is checked against the bytes before anything is stored: an avatar is
     * claimed by the sender under its own key, but a mismatch would mean either a torn
     * frame or someone rewriting a contact's face, and neither belongs on disk.
     */
    fun storeIncoming(base64Payload: String, expectedHashHex: String, dir: File): Stored? {
        if (base64Payload.isBlank() || expectedHashHex.isBlank()) return null
        val bytes = runCatching {
            Base64.getUrlDecoder().decode(base64Payload)
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_JPEG_BYTES) return null
        val hash = YggCryptoEngine.sha256Hex(bytes)
        if (!hash.equals(expectedHashHex.trim().lowercase(), ignoreCase = true)) return null
        if (!isDecodableImage(bytes)) return null
        val file = File(dir, "$hash.jpg")
        if (!file.exists()) {
            runCatching { FileOutputStream(file).use { it.write(bytes) } }.getOrNull() ?: return null
        }
        return Stored(Uri.fromFile(file).toString(), hash, bytes.size)
    }

    fun base64OfStoredFile(localUri: String): String? {
        if (!localUri.startsWith("file://")) return null
        val file = Uri.parse(localUri).path?.let(::File) ?: return null
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_JPEG_BYTES) return null
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun fileFor(localUri: String): File? =
        if (localUri.startsWith("file://")) Uri.parse(localUri).path?.let(::File)?.takeIf { it.isFile } else null

    fun avatarDir(context: Context): File =
        File(context.filesDir, "avatars").apply { if (!exists()) mkdirs() }

    /** Deleting a replaced picture, so the folder does not accumulate every face the user tried. */
    fun forget(context: Context, localUri: String) {
        if (!localUri.startsWith("file://")) return
        val path = Uri.parse(localUri).path ?: return
        if (!path.startsWith(avatarDir(context).absolutePath)) return
        runCatching { File(path).delete() }
    }

    private fun openDecodable(context: Context, source: Uri): Bitmap? =
        runCatching {
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
            // An avatar we stored ourselves is a file:// URI; if the resolver declines one,
            // the bytes are still right there on our own storage.
            ?: source.path?.let { path ->
                if (source.scheme == "file") runCatching { BitmapFactory.decodeFile(path) }.getOrNull() else null
            }

    private fun decodeSquare(context: Context, source: Uri): Bitmap? {
        val raw = openDecodable(context, source) ?: return null
        val side = minOf(raw.width, raw.height)
        if (side <= 0) {
            raw.recycle()
            return null
        }
        val x = (raw.width - side) / 2
        val y = (raw.height - side) / 2
        val square = runCatching { Bitmap.createBitmap(raw, x, y, side, side) }.getOrNull()
        if (square == null) {
            raw.recycle()
            return null
        }
        if (square !== raw) raw.recycle()
        val scaled = Bitmap.createScaledBitmap(square, SIDE_PX, SIDE_PX, true)
        if (scaled !== square) square.recycle()
        return scaled
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun isDecodableImage(bytes: ByteArray): Boolean =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null }.getOrDefault(false)
}
