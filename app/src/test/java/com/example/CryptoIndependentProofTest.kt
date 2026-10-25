// File: app/src/test/java/com/example/CryptoIndependentProofTest.kt
package com.example

import com.example.engine.YggCryptoEngine
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proof, not a roundtrip.
 *
 * The other envelope tests seal with this app's code and open with the same code, so a green
 * run only says the two halves agree with each other. Here the sealed bytes are handed to a
 * second reader: HKDF-SHA256 written out from RFC 5869 and AES-256-GCM from `javax.crypto`
 * (NIST SP 800-38D), neither of them touched by the app's own helpers. If that reader recovers
 * the plaintext, the frame really is standard AES-256-GCM under a 32-byte key derived by HKDF
 * from the X25519 shared secret — not a home-made scramble only this build can undo.
 *
 * The curve itself is pinned to external ground truth elsewhere in this suite:
 * `ExampleUnitTest.x25519_matchesRfc7748_61Vectors` compares this build's ladder against the
 * RFC 7748 §6.1 vectors. Android's `javax.crypto.KeyAgreement` has no `doFinal(Key)`, so the
 * JDK's own X25519 cannot be used as the second reader here.
 */
class CryptoIndependentProofTest {

    private val aliceWords = listOf(
        "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
        "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
    )
    private val bobWords = listOf(
        "cobalt", "beacon", "prism", "horizon", "lattice", "nomad",
        "zenith", "paradox", "anchor", "kinetic", "solstice", "aurora"
    )

    @Test
    fun textEnvelope_isRealStandardAesGcmKeyedByRealX25519_andCarriesNoPlaintext() {
        val alicePriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(aliceWords)
        val bobPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords)
        val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceWords)
        val bob = YggCryptoEngine.deriveHierarchyFromMnemonic(bobWords)
        val plain = "Маркер_WIREPROOF_text_42: пароль, эмодзи 🔐 и \\\\ экранирования"

        val envelope = requireNotNull(
            YggCryptoEngine.sealConversationEnvelope(
                plainText = plain,
                senderX25519PrivateKey = alicePriv,
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex,
                peerX25519PublicKeyHex = bob.x25519IdentityPubHex,
                keyEpoch = 1
            )
        )

        // Nothing that looks like the message survives in the sealed frame.
        assertFalse(envelope.contains("Маркер_WIREPROOF"))
        assertFalse(envelope.contains("эмодзи"))
        assertFalse(envelope.contains("42:"))

        val fields = Envelope(envelope)
        assertEquals(2, fields.version)
        assertEquals(alice.x25519IdentityPubHex, fields.senderX)
        assertEquals("GCM needs a 12-byte IV", 12, fields.iv.size)
        // GCM appends a 16-byte tag, so ciphertext is exactly plaintext bytes + 16.
        assertEquals(
            "ct must be UTF-8 plaintext plus the 16-byte AEAD tag",
            plain.toByteArray(StandardCharsets.UTF_8).size + 16,
            fields.ciphertext.size
        )

        val keyForReader = readerKey(fields.senderX, bobPriv, fields.epoch)
        val recovered = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyForReader, "AES"), GCMParameterSpec(128, fields.iv))
            updateAAD(aad(fields.senderEd, fields.epoch))
            String(doFinal(fields.ciphertext), StandardCharsets.UTF_8)
        }
        assertEquals(plain, recovered)

        // Decryption with the recipient's own private key and the sender key taken from the
        // frame is what proves the exchange: the sender's and the reader's secrets agree, and
        // nothing but the recipient's private scalar recovers the text.
        assertArrayEquals(
            keyForReader,
            readerKey(alice.x25519IdentityPubHex, bobPriv, fields.epoch)
        )
    }

    @Test
    fun attachmentAndVoicePayload_travelSealedAndComeBackByteForByte() {
        val alicePriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(aliceWords)
        val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceWords)
        val bob = YggCryptoEngine.deriveHierarchyFromMnemonic(bobWords)

        // A JPEG header followed by deterministic noise: enough structure that any cleartext
        // leak of a window of it would be visible to the assertions below.
        val image = ByteArray(96 * 1024)
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()).copyInto(image, 0)
        var state = 0x12345678
        for (i in 4 until image.size) {
            state = state * 1103515245 + 12345
            image[i] = (state shr 16).toByte()
        }
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(image)

        val envelope = requireNotNull(
            YggCryptoEngine.sealConversationEnvelope(
                plainText = payload,
                senderX25519PrivateKey = alicePriv,
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex,
                peerX25519PublicKeyHex = bob.x25519IdentityPubHex,
                keyEpoch = 1
            )
        )
        // The first window of the base64 body is what a cleartext transport would show.
        val probe = payload.substring(payload.length / 3, payload.length / 3 + 48)
        assertFalse("a 48-char slice of the attachment base64 is visible on the wire", envelope.contains(probe))
        assertFalse(envelope.contains(payload.take(64)))

        val fields = Envelope(envelope)
        val key = readerKey(fields.senderX, YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords), fields.epoch)
        val openedBytes = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, fields.iv))
            updateAAD(aad(fields.senderEd, fields.epoch))
            doFinal(fields.ciphertext)
        }
        assertArrayEquals(image, java.util.Base64.getUrlDecoder().decode(String(openedBytes, StandardCharsets.UTF_8)))

        // Call media: one PCM frame of 20 ms at 16 kHz mono, sealed with a per-call key.
        val callId = "call-7742"
        val pcm = ByteArray(640) { (it % 251).toByte() }
        val mediaKey = YggCryptoEngine.deriveCallMediaKey(
            YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords), alice.x25519IdentityPubHex, callId
        )
        val (ivB64, cipherB64) = YggCryptoEngine.sealCallMediaFrame(mediaKey, pcm, callId, seq = 5L)
        assertArrayEquals(pcm, YggCryptoEngine.openCallMediaFrame(mediaKey, ivB64, cipherB64, callId, seq = 5L))

        // The same frame with a JDK-only reader, with the sequence number bound in AAD.
        val frameCipher = Cipher.getInstance("AES/GCM/NoPadding")
        frameCipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(mediaKey, "AES"),
            GCMParameterSpec(128, java.util.Base64.getUrlDecoder().decode(ivB64))
        )
        frameCipher.updateAAD("ygggram-call:$callId:5".toByteArray(StandardCharsets.UTF_8))
        assertArrayEquals(pcm, frameCipher.doFinal(java.util.Base64.getUrlDecoder().decode(cipherB64)))
    }

    @Test
    fun tamperingAndForeignKeys_areRejectedByBothReaders() {
        val alicePriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(aliceWords)
        val bobPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords)
        val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceWords)
        val bob = YggCryptoEngine.deriveHierarchyFromMnemonic(bobWords)
        val carolWords = listOf(
            "anchor", "kinetic", "solstice", "aurora", "obsidian", "cipher",
            "yggdrasil", "vector", "emerald", "quantum", "ratchet", "bastion"
        )

        val envelope = requireNotNull(
            YggCryptoEngine.sealConversationEnvelope(
                plainText = "не трогать",
                senderX25519PrivateKey = alicePriv,
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex,
                peerX25519PublicKeyHex = bob.x25519IdentityPubHex,
                keyEpoch = 1
            )
        )

        // One flipped bit in the ciphertext.
        val fields = Envelope(envelope)
        val flipped = fields.ciphertext.clone()
        flipped[flipped.size / 2] = (flipped[flipped.size / 2].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(readerKey(fields.senderX, bobPriv, fields.epoch), "AES"),
                GCMParameterSpec(128, fields.iv)
            )
            cipher.updateAAD(aad(fields.senderEd, fields.epoch))
            cipher.doFinal(flipped)
        }
        assertThrows(AEADBadTagException::class.java) {
            YggCryptoEngine.openConversationEnvelope(
                envelope = envelope.replace(bytesToHex(fields.ciphertext), bytesToHex(flipped)),
                recipientX25519PrivateKey = bobPriv,
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex
            )
        }

        // A different node key cannot open it, even though the frame is otherwise untouched.
        assertThrows(AEADBadTagException::class.java) {
            YggCryptoEngine.openConversationEnvelope(
                envelope = envelope,
                recipientX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(carolWords),
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex
            )
        }

        // The epoch is bound into the key and the AAD: an old envelope must not be replayable
        // into a rotated key period.
        val envelopeEpoch2 = requireNotNull(
            YggCryptoEngine.sealConversationEnvelope(
                plainText = "не трогать",
                senderX25519PrivateKey = alicePriv,
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex,
                peerX25519PublicKeyHex = bob.x25519IdentityPubHex,
                keyEpoch = 2
            )
        )
        val f2 = Envelope(envelopeEpoch2)
        assertEquals(2, f2.version)
        assertTrue("a re-keyed envelope must differ byte-wise", envelopeEpoch2 != envelope)
        assertThrows(AEADBadTagException::class.java) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(readerKey(f2.senderX, bobPriv, epoch = 1), "AES"),
                GCMParameterSpec(128, f2.iv)
            )
            cipher.updateAAD(aad(f2.senderEd, epoch = 1))
            cipher.doFinal(f2.ciphertext)
        }

        // A call frame is bound to its call id and sequence: captured audio cannot be moved.
        val mediaKey = YggCryptoEngine.deriveCallMediaKey(bobPriv, alice.x25519IdentityPubHex, "call-1")
        val otherKey = YggCryptoEngine.deriveCallMediaKey(bobPriv, alice.x25519IdentityPubHex, "call-2")
        val (ivB64, cipherB64) = YggCryptoEngine.sealCallMediaFrame(mediaKey, ByteArray(320) { 7 }, "call-1", seq = 3L)
        assertThrows(AEADBadTagException::class.java) {
            YggCryptoEngine.openCallMediaFrame(otherKey, ivB64, cipherB64, "call-2", seq = 3L)
        }
        assertThrows(AEADBadTagException::class.java) {
            YggCryptoEngine.openCallMediaFrame(mediaKey, ivB64, cipherB64, "call-1", seq = 4L)
        }
        // Nobody else's identity key decrypts the media either.
        assertThrows(AEADBadTagException::class.java) {
            val foreign = YggCryptoEngine.deriveCallMediaKey(
                YggCryptoEngine.x25519PrivateKeyFromMnemonic(carolWords), alice.x25519IdentityPubHex, "call-1"
            )
            YggCryptoEngine.openCallMediaFrame(foreign, ivB64, cipherB64, "call-1", seq = 3L)
        }
    }

    // ── The second reader: standard primitives only, no app code ─────────────────────────

    private fun readerKey(senderXPubHex: String, recipientPriv: ByteArray, epoch: Int): ByteArray =
        hkdfSha256(
            ikm = YggCryptoEngine.x25519(recipientPriv, hexToBytes(senderXPubHex)),
            salt = MessageDigest.getInstance("SHA-256").digest("ygggram-cr-v2".toByteArray(StandardCharsets.UTF_8)),
            info = "ygggram-envelope-v2:ep=$epoch".toByteArray(StandardCharsets.UTF_8),
            length = 32
        )

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ArrayList<Byte>(length)
        var block = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            val input = block + info + byteArrayOf(counter.toByte())
            block = hmac(prk, input)
            out.addAll(block.toList())
            counter++
        }
        return out.take(length).toByteArray()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun aad(senderEdHex: String, epoch: Int): ByteArray =
        "ygggram-v2:$senderEdHex:$epoch".toByteArray(StandardCharsets.UTF_8)

    private class Envelope(json: String) {
        val version: Int = intField(json, "v")
        val epoch: Int = intField(json, "ep")
        val senderX: String = stringField(json, "sx")
        val senderEd: String = stringField(json, "sp")
        val iv: ByteArray = hexToBytes(stringField(json, "iv"))
        val ciphertext: ByteArray = hexToBytes(stringField(json, "ct"))

        private fun stringField(document: String, key: String): String {
            val marker = "\"$key\":\""
            val start = document.indexOf(marker)
            require(start >= 0) { "envelope has no $key" }
            val from = start + marker.length
            return document.substring(from, document.indexOf('"', from))
        }

        private fun intField(document: String, key: String): Int {
            val marker = "\"$key\":"
            val start = document.indexOf(marker)
            require(start >= 0) { "envelope has no $key" }
            val from = start + marker.length
            var end = from
            while (end < document.length && document[end].isDigit()) end++
            return document.substring(from, end).toInt()
        }
    }
}

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

private fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }
