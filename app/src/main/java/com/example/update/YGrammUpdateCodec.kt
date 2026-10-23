// Pure, device-free parsing of a GitHub Release into an update decision.
package com.example.update

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * @param apkAssetName exact asset file name inside the release, matched case-insensitively.
 * @param sha256Hex lowercase hex digest of that asset, published by the build that produced it.
 */
data class YGrammUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkAssetName: String,
    val sha256Hex: String
)

data class YGrammAvailableUpdate(
    val manifest: YGrammUpdateManifest,
    val downloadUrl: String,
    val assetSizeBytes: Long,
    val releasePageUrl: String,
    val releaseNotes: String
)

sealed class YGrammCheckOutcome {
    data class UpToDate(val runningVersionCode: Int, val latestVersionCode: Int, val latestVersionName: String) :
        YGrammCheckOutcome()

    data class Available(val update: YGrammAvailableUpdate) : YGrammCheckOutcome()

    // A refusal always carries the reason shown to the user: a silent "no updates" would be a lie
    // whenever the release simply lacks a manifest.
    data class Failed(val reason: String) : YGrammCheckOutcome()
}

object YGrammUpdateCodec {
    const val MANIFEST_FENCE = "```ygramm-update"
    private const val HEX64 = "^[0-9a-f]{64}$"

    fun isSha256Hex(value: String): Boolean = value.matches(Regex(HEX64))

    /**
     * GitHub Release bodies are free text, so the only field that can carry an authoritative
     * versionCode is a fenced JSON block written by the release tooling. Absent or malformed
     * returns null and the caller must refuse rather than guess from the tag name.
     */
    fun extractManifest(body: String?): YGrammUpdateManifest? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty() || !text.contains(MANIFEST_FENCE)) return null
        val start = text.indexOf(MANIFEST_FENCE)
        val jsonStart = text.indexOf('\n', start).let { if (it < 0) return null else it + 1 }
        val end = text.indexOf("```", jsonStart)
        if (end < 0) return null
        val payload = text.substring(jsonStart, end).trim()
        return try {
            val obj = JSONObject(payload)
            val versionCode = obj.optInt("versionCode", -1)
            val versionName = obj.optString("versionName").trim()
            val apk = obj.optString("apk").trim()
            val sha = obj.optString("sha256").trim().lowercase()
            if (versionCode <= 0 || versionName.isEmpty() || apk.isEmpty() || !isSha256Hex(sha)) null
            else YGrammUpdateManifest(versionCode, versionName, apk, sha)
        } catch (_: Exception) {
            null
        }
    }

    fun releaseNotes(body: String?, maxChars: Int = 1200): String {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val start = text.indexOf(MANIFEST_FENCE)
        if (start < 0) return text.take(maxChars)
        val blockEnd = text.indexOf("```", text.indexOf('\n', start).coerceAtLeast(0) + 1)
        if (blockEnd < 0) return text.take(maxChars)
        // The fence line itself plus the block is metadata, not prose the reader asked for.
        val notes = (text.substring(0, start) + text.substring(blockEnd + 3)).trim()
        return notes.take(maxChars)
    }

    fun sha256HexOfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Turns the /releases/latest document into a decision. Everything that is not an actual
     * newer release reports why, in the user's language, instead of pretending the mesh is quiet.
     */
    fun parseLatestRelease(rawJson: String, runningVersionCode: Int): YGrammCheckOutcome {
        val root = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return YGrammCheckOutcome.Failed("GitHub вернул не JSON: ${rawJson.take(120)}")
        }
        val tag = root.optString("tag_name").trim()
        if (tag.isEmpty()) {
            val message = root.optString("message").trim()
            return YGrammCheckOutcome.Failed(
                if (message.isEmpty()) "У репозитория нет ни одного релиза"
                else "GitHub отказал: $message"
            )
        }

        val manifest = extractManifest(root.optString("body"))
            ?: return YGrammCheckOutcome.Failed(
                "Релиз $tag опубликован без манифеста $MANIFEST_FENCE: сравнивать versionCode " +
                    "не с чем, поэтому обновление не предлагается"
            )

        if (manifest.versionCode <= runningVersionCode) {
            return YGrammCheckOutcome.UpToDate(runningVersionCode, manifest.versionCode, manifest.versionName)
        }

        val assets = root.optJSONArray("assets")
        if (assets == null || assets.length() == 0) {
            return YGrammCheckOutcome.Failed("В релизе $tag нет ни одного файла")
        }
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            if (!name.equals(manifest.apkAssetName, ignoreCase = true)) continue
            val url = asset.optString("browser_download_url").trim()
            if (url.isEmpty()) {
                return YGrammCheckOutcome.Failed("У файла $name нет прямой ссылки для скачивания")
            }
            return YGrammCheckOutcome.Available(
                YGrammAvailableUpdate(
                    manifest = manifest,
                    downloadUrl = url,
                    assetSizeBytes = asset.optLong("size", 0L),
                    releasePageUrl = root.optString("html_url").trim(),
                    releaseNotes = releaseNotes(root.optString("body"))
                )
            )
        }
        return YGrammCheckOutcome.Failed("В релизе $tag нет файла ${manifest.apkAssetName}")
    }
}
