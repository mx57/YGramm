// Pure half of the peer APK relay: frame fields, request validation, the serving decision and
// the local copy of the release manifest. No Android types, so every rule is unit-testable.
package com.example.update

import java.io.File
import org.json.JSONObject

/**
 * One device's order to another: carry this exact release file over the overlay.
 *
 * Nothing here is a claim the peer gets to make about the file. `sha256Hex` always comes from the
 * manifest *this* device read from GitHub (or that the user pasted in themselves), never from what
 * the other side says it holds.
 */
data class ApkRelayRequest(
    val transferId: String,
    val chatId: String,
    val apkAssetName: String,
    val sha256Hex: String,
    val versionCode: Int
)

sealed class YGrammRelayDecision {
    data object Serve : YGrammRelayDecision()

    // A refusal always names itself: the requester has to be able to tell "peer has nothing"
    // apart from "peer refuses to share" and "this device never asked correctly".
    data class Refuse(val reason: String) : YGrammRelayDecision()
}

/** The release document this device itself saw, kept so a later peer transfer has an anchor. */
data class StoredLocalManifest(val manifest: YGrammUpdateManifest, val savedAtMs: Long)

object YGrammPeerRelay {
    const val FRAME_TYPE = "APKREQ"
    const val RELAY_ATTACHMENT_TYPE = "APK_RELAY"
    const val LOCAL_MANIFEST_FILE = "last_manifest.json"
    const val PREF_OFFER_ENABLED = "peer_relay_offer_enabled"

    private const val MAX_NAME_CHARS = 80
    private val ASSET_NAME = Regex("^[A-Za-z0-9._+-]{1,$MAX_NAME_CHARS}\\.apk$")
    private val TRANSFER_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** The relay-specific fields of a request frame; identity fields are added by the bridge. */
    fun requestFields(request: ApkRelayRequest): Map<String, String> = mapOf(
        "type" to FRAME_TYPE,
        "transferId" to request.transferId,
        "chatId" to request.chatId,
        "apk" to request.apkAssetName,
        "sha256" to request.sha256Hex,
        "versionCode" to request.versionCode.toString()
    )

    /**
     * An inbound frame is untrusted text, so every field is validated before anything is read from
     * disk with it. A name that is not a plain `*.apk` is refused here rather than sanitised: the
     * requester must not be able to pick where the serving device looks.
     */
    fun parseRequest(fields: Map<String, String>): ApkRelayRequest? {
        if (fields["type"] != FRAME_TYPE) return null
        val transferId = fields["transferId"].orEmpty().trim()
        if (!transferId.matches(TRANSFER_ID)) return null
        val sha = fields["sha256"].orEmpty().trim().lowercase()
        if (!YGrammUpdateCodec.isSha256Hex(sha)) return null
        val name = fields["apk"].orEmpty().trim()
        if (!name.matches(ASSET_NAME)) return null
        val versionCode = fields["versionCode"]?.toIntOrNull() ?: return null
        if (versionCode <= 0) return null
        return ApkRelayRequest(
            transferId = transferId,
            chatId = fields["chatId"].orEmpty().trim().take(120),
            apkAssetName = name,
            sha256Hex = sha,
            versionCode = versionCode
        )
    }

    /**
     * The answer to a request. `verdict` is "serving" only when the file really is about to
     * stream; every other case names the reason in the same frame, so the requester stops
     * waiting instead of burning a piece-window timeout.
     */
    fun replyFields(transferId: String, serving: Boolean, refusalReason: String = ""): Map<String, String> =
        buildMap {
            put("type", "ACK")
            put("apkReply", if (serving) "serving" else "refused")
            put("transferId", transferId)
            if (!serving && refusalReason.isNotBlank()) put("apkRefusal", refusalReason.take(240))
        }

    fun parseReply(fields: Map<String, String>): Pair<Boolean, String>? {
        val verdict = fields["apkReply"] ?: return null
        val transferId = fields["transferId"].orEmpty().trim()
        if (transferId.isBlank()) return null
        return when (verdict) {
            "serving" -> true to ""
            else -> false to fields["apkRefusal"].orEmpty().ifBlank { "собеседник не назвал причины отказа" }
        }
    }

    /**
     * What this device may hand to a peer. Sharing is off until the user turns it on, needs a real
     * 1:1 dialog with the requester, and only ever serves the file whose digest this device already
     * verified against GitHub — a peer asking for a hash we do not hold gets a refusal, not a guess.
     */
    fun decideServe(
        request: ApkRelayRequest?,
        refusalFromParsing: String = "запрос не читается: нужны корректные transferId, sha256, имя файла и versionCode",
        offerEnabled: Boolean,
        hasDialogWithRequester: Boolean,
        heldFile: File?,
        heldFileSha256Hex: String?
    ): YGrammRelayDecision {
        if (!offerEnabled) {
            return YGrammRelayDecision.Refuse("раздача APK по оверлею на этом устройстве выключена")
        }
        val req = request ?: return YGrammRelayDecision.Refuse(refusalFromParsing)
        if (!hasDialogWithRequester) {
            return YGrammRelayDecision.Refuse("с этой нодой нет личного диалога — делиться не с кем и нечем проверить ключ")
        }
        if (heldFile == null || !heldFile.isFile || heldFile.length() <= 0L) {
            return YGrammRelayDecision.Refuse("у этого устройства нет скачанного ${req.apkAssetName}")
        }
        if (heldFileSha256Hex == null) {
            return YGrammRelayDecision.Refuse("файл не читается до конца — контрольную сумму построить нечем")
        }
        if (heldFileSha256Hex != req.sha256Hex) {
            return YGrammRelayDecision.Refuse(
                "файл на этом устройстве не тот: ${heldFileSha256Hex.take(16)}… вместо ${req.sha256Hex.take(16)}…"
            )
        }
        return YGrammRelayDecision.Serve
    }

    fun localManifestFile(updateDir: File): File = File(updateDir, LOCAL_MANIFEST_FILE)

    /**
     * Writes the release document this device saw with its own network request. Peer answers never
     * reach this file — that is the whole point of keeping it.
     */
    fun saveLocalManifest(updateDir: File, manifest: YGrammUpdateManifest, savedAtMs: Long): Boolean =
        try {
            if (!updateDir.exists()) updateDir.mkdirs()
            val json = JSONObject()
                .put("versionCode", manifest.versionCode)
                .put("versionName", manifest.versionName)
                .put("apk", manifest.apkAssetName)
                .put("sha256", manifest.sha256Hex)
                .put("savedAtMs", savedAtMs)
            localManifestFile(updateDir).writeText(json.toString())
            // Read it straight back: a manifest that cannot be parsed later cannot anchor a
            // transfer, and a write that silently failed must not be reported as stored.
            readLocalManifest(updateDir) != null
        } catch (_: Throwable) {
            false
        }

    fun readLocalManifest(updateDir: File): StoredLocalManifest? = try {
        val file = localManifestFile(updateDir)
        if (!file.isFile || file.length() <= 0L) null
        else {
            val obj = JSONObject(file.readText())
            val versionCode = obj.optInt("versionCode", -1)
            val versionName = obj.optString("versionName").trim()
            val apk = obj.optString("apk").trim()
            val sha = obj.optString("sha256").trim().lowercase()
            if (versionCode <= 0 || versionName.isEmpty() || apk.isEmpty() || !YGrammUpdateCodec.isSha256Hex(sha)) {
                null
            } else {
                StoredLocalManifest(
                    manifest = YGrammUpdateManifest(versionCode, versionName, apk, sha),
                    savedAtMs = obj.optLong("savedAtMs", 0L)
                )
            }
        }
    } catch (_: Throwable) {
        null
    }
}
