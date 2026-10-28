package com.example.engine

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The body of a PROFILE frame: what one node says about itself.
 *
 * It travels as the plaintext inside an ordinary conversation envelope, and that is the whole
 * security argument. A clear-text profile frame would let any node on the path rename a contact,
 * plant a status line or swap a picture, because nothing about it proves who wrote it. Sealed the
 * same way a message is, the envelope opens only under this device's key against the sender's
 * pinned X25519 key, so the fields below are statements the peer's own long-term key signed.
 *
 * The encoding is the wire frame's own `key=base64url|...` form rather than JSON: the value ends
 * up as an envelope plaintext, and an escaping bug in a JSON writer would then read as a corrupt
 * envelope on every other device.
 */
object YggProfilePayload {

    // A peer names its own bounds; this names the bounds this device is willing to store.
    // A display name longer than a chat row can show is not a fact about the peer, it is an
    // attack on the layout.
    const val MAX_DISPLAY_NAME_CHARS = 64
    const val MAX_HANDLE_CHARS = 32
    const val MAX_STATUS_CHARS = 120

    data class ProfileBody(
        val displayName: String,
        val handle: String,
        val statusText: String,
        /** sha256 of the avatar bytes advertised beside this body, empty when there is none. */
        val avatarHashHex: String,
        /** The sender's own edit timestamp: what makes a newer copy win over an older one. */
        val version: Long
    )

    fun encode(body: ProfileBody): String = sequenceOf(
        "dn" to body.displayName,
        "hd" to body.handle,
        "st" to body.statusText,
        "av" to body.avatarHashHex,
        "pv" to body.version.toString()
    ).joinToString("|") { (key, value) ->
        "$key=${Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))}"
    }

    /**
     * Reads a body back, or null when it is not one at all.
     *
     * Every field is bounded and shape-checked here rather than at the call sites: this is the
     * only place that sees the bytes another node chose, and a stored value that skipped the
     * check would be read by code that never looks again.
     */
    fun decode(payload: String): ProfileBody? {
        val values = mutableMapOf<String, String>()
        payload.trim().split("|").forEach { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) return@forEach
            val decoded = runCatching {
                String(
                    Base64.getUrlDecoder().decode(token.substring(separator + 1)),
                    StandardCharsets.UTF_8
                )
            }.getOrNull() ?: return@forEach
            values[token.substring(0, separator)] = decoded
        }
        val version = values["pv"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val avatarHash = values["av"].orEmpty().trim().lowercase()
        if (avatarHash.isNotEmpty() &&
            !(avatarHash.length <= 64 && avatarHash.all { it in '0'..'9' || it in 'a'..'f' })
        ) {
            return null
        }
        return ProfileBody(
            displayName = clean(values["dn"].orEmpty(), MAX_DISPLAY_NAME_CHARS),
            handle = clean(values["hd"].orEmpty(), MAX_HANDLE_CHARS),
            statusText = clean(values["st"].orEmpty(), MAX_STATUS_CHARS),
            avatarHashHex = avatarHash,
            version = version
        )
    }

    /**
     * Control characters are scrubbed to spaces; a value longer than the bound is dropped whole.
     *
     * Truncating instead would store a name the sender never wrote - and a display name is read by
     * people as an identity, so half of somebody's name is a wrong fact, not a smaller one.
     */
    private fun clean(value: String, maxChars: Int): String {
        val trimmed = value.replace(Regex("[\\p{Cntrl}\\u200B\\uFEFF]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (trimmed.length > maxChars) "" else trimmed
    }
}
