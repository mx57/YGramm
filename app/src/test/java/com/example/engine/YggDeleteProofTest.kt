// File: app/src/test/java/com/example/engine/YggDeleteProofTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * A "delete for everyone" frame is only safe because the receiver can check who asks: the proof
 * travels as a conversation envelope, so the node whose key authenticated the original message
 * is the only one that can produce a proof opening to that key. These tests pin that property —
 * the repository refuses every remote delete that does not satisfy it.
 */
class YggDeleteProofTest {

    private data class Identity(val xPubHex: String, val xPriv: ByteArray, val edHex: String)

    // The same vectors YggCryptoParityTest pins, so a change in derivation fails there first.
    private fun identity(mnemonic: String): Identity {
        val words = mnemonic.split(" ").filter { it.isNotBlank() }
        val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(words)
        return Identity(
            xPubHex = hierarchy.x25519IdentityPubHex,
            xPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(words),
            edHex = hierarchy.yggNodePublicKeyHex
        )
    }

    private val aliceMnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bobMnemonic =
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
    private val malloryMnemonic =
        "witch combs handshakes peeking jugs unroofed reviving headlock rebooking headstand befogging deicing"

    @Test
    fun `proof text survives the round trip`() {
        val text = YggCryptoEngine.buildDeleteProofText("del_42", "msg_7")

        val parsed = YggCryptoEngine.parseDeleteProofText(text)

        assertNotNull(parsed)
        assertEquals("del_42", parsed?.deleteId)
        assertEquals("msg_7", parsed?.wireMessageId)
    }

    @Test
    fun `an ordinary message is never mistaken for a proof`() {
        assertNull(YggCryptoEngine.parseDeleteProofText("Привет, как дела?"))
        assertNull(YggCryptoEngine.parseDeleteProofText("ygggram-del-v1||msg_7"))
        assertNull(YggCryptoEngine.parseDeleteProofText("ygggram-del-v1|del_7|"))
        assertNull(YggCryptoEngine.parseDeleteProofText(null))
    }

    @Test
    fun `a proof opens only for its recipient and names the node that sealed it`() {
        val alice = identity(aliceMnemonic)
        val bob = identity(bobMnemonic)
        val envelope = YggCryptoEngine.sealConversationEnvelope(
            plainText = YggCryptoEngine.buildDeleteProofText("del_1", "msg_1"),
            senderX25519PrivateKey = alice.xPriv,
            senderEd25519PublicKeyHex = alice.edHex,
            peerX25519PublicKeyHex = bob.xPubHex
        )
        assertNotNull(envelope)

        val opened = YggCryptoEngine.openConversationEnvelope(envelope!!, bob.xPriv, alice.edHex)

        // The key reported here is what the receiver compares with the key that authenticated
        // the message being deleted — that comparison is the whole authorisation check.
        assertEquals(alice.xPubHex.lowercase(), opened.senderX25519PublicKeyHex.lowercase())
        assertEquals("del_1", YggCryptoEngine.parseDeleteProofText(opened.plainText)?.deleteId)
    }

    @Test
    fun `a stranger cannot forge a proof for somebody else's message`() {
        val alice = identity(aliceMnemonic)
        val bob = identity(bobMnemonic)
        val mallory = identity(malloryMnemonic)
        val genuine = YggCryptoEngine.sealConversationEnvelope(
            plainText = YggCryptoEngine.buildDeleteProofText("del_1", "msg_1"),
            senderX25519PrivateKey = alice.xPriv,
            senderEd25519PublicKeyHex = alice.edHex,
            peerX25519PublicKeyHex = bob.xPubHex
        ).orEmpty()

        // Mallory knows every public value on the wire, and still gets an envelope that opens
        // under a different sender key: the repository's comparison rejects it.
        val forged = YggCryptoEngine.sealConversationEnvelope(
            plainText = YggCryptoEngine.buildDeleteProofText("del_1", "msg_1"),
            senderX25519PrivateKey = mallory.xPriv,
            senderEd25519PublicKeyHex = mallory.edHex,
            peerX25519PublicKeyHex = bob.xPubHex
        ).orEmpty()
        assertNotEquals(genuine, forged)
        val openedForged = YggCryptoEngine.openConversationEnvelope(forged, bob.xPriv, mallory.edHex)
        assertNotEquals(alice.xPubHex.lowercase(), openedForged.senderX25519PublicKeyHex.lowercase())

        // A proof sealed for another recipient never opens here at all.
        val toMallory = YggCryptoEngine.sealConversationEnvelope(
            plainText = YggCryptoEngine.buildDeleteProofText("del_1", "msg_1"),
            senderX25519PrivateKey = alice.xPriv,
            senderEd25519PublicKeyHex = alice.edHex,
            peerX25519PublicKeyHex = mallory.xPubHex
        ).orEmpty()
        try {
            YggCryptoEngine.openConversationEnvelope(toMallory, bob.xPriv, alice.edHex)
            fail("Envelope sealed for another recipient must not decrypt")
        } catch (_: Exception) {
            // expected: AEAD tag does not verify under Bob's key
        }
    }

    @Test
    fun `a frame sender claiming to be someone else is rejected`() {
        val alice = identity(aliceMnemonic)
        val mallory = identity(malloryMnemonic)
        val bob = identity(bobMnemonic)
        val envelope = YggCryptoEngine.sealConversationEnvelope(
            plainText = YggCryptoEngine.buildDeleteProofText("del_9", "msg_9"),
            senderX25519PrivateKey = alice.xPriv,
            senderEd25519PublicKeyHex = alice.edHex,
            peerX25519PublicKeyHex = bob.xPubHex
        ).orEmpty()

        try {
            YggCryptoEngine.openConversationEnvelope(envelope, bob.xPriv, mallory.edHex)
            fail("Proof sealed by Alice must not be accepted as Mallory's")
        } catch (e: SecurityException) {
            assertEquals("Envelope sender key does not match the wire frame sender", e.message)
        }
    }
}
