// File: app/src/test/java/com/example/engine/YggFilePieceTransportTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * A file above the inline ceiling travels as separately encrypted pieces. The whole design rests on
 * two properties: a piece is only readable at the index it was sealed for, and the Merkle root is
 * built from the bytes actually read off the disk. If either breaks, a peer accepts a file it cannot
 * open while the sender still sees «доставлено».
 */
class YggFilePieceTransportTest {

    private data class Identity(val xPubHex: String, val xPriv: ByteArray)

    // The same vectors YggCryptoParityTest pins, so a change in derivation fails there first.
    private fun identity(mnemonic: String): Identity {
        val words = mnemonic.split(" ").filter { it.isNotBlank() }
        return Identity(
            xPubHex = YggCryptoEngine.deriveHierarchyFromMnemonic(words).x25519IdentityPubHex,
            xPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(words)
        )
    }

    private val alice = identity(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    )
    private val bob = identity(
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
    )

    private fun tempFile(bytes: ByteArray): File = File.createTempFile("ygg_piece_", ".bin").apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    @Test
    fun `both ends derive the same key for one transfer`() {
        val senderKey = YggCryptoEngine.deriveFileTransferKey(alice.xPriv, bob.xPubHex, "tx_1")
        val receiverKey = YggCryptoEngine.deriveFileTransferKey(bob.xPriv, alice.xPubHex, "tx_1")

        assertEqualsBytes(senderKey, receiverKey)
        assertEquals(32, senderKey.size)
    }

    @Test
    fun `every transfer gets its own key`() {
        val first = YggCryptoEngine.deriveFileTransferKey(alice.xPriv, bob.xPubHex, "tx_1")
        val second = YggCryptoEngine.deriveFileTransferKey(alice.xPriv, bob.xPubHex, "tx_2")

        assertFalse(first contentEquals second)
    }

    @Test
    fun `a piece only opens at the index it was sealed for`() {
        val key = YggCryptoEngine.deriveFileTransferKey(alice.xPriv, bob.xPubHex, "tx_1")
        val plain = ByteArray(4096) { (it % 251).toByte() }
        val (iv, cipher) = YggCryptoEngine.sealFilePiece(key, plain, "tx_1", 7)

        assertEqualsBytes(plain, YggCryptoEngine.openFilePiece(key, iv, cipher, "tx_1", 7))
        runCatching { YggCryptoEngine.openFilePiece(key, iv, cipher, "tx_1", 8) }.onSuccess {
            throw AssertionError("кусок 7 расшифровался на позиции 8")
        }
    }

    @Test
    fun `a piece of another transfer does not open`() {
        val key = YggCryptoEngine.deriveFileTransferKey(alice.xPriv, bob.xPubHex, "tx_1")
        val otherKey = YggCryptoEngine.deriveFileTransferKey(alice.xPriv, bob.xPubHex, "tx_2")
        val plain = "фрагмент файла".toByteArray()
        val (iv, cipher) = YggCryptoEngine.sealFilePiece(key, plain, "tx_1", 0)

        runCatching { YggCryptoEngine.openFilePiece(otherKey, iv, cipher, "tx_2", 0) }.onSuccess {
            throw AssertionError("кусок одной передачи открылся ключом другой")
        }
    }

    @Test
    fun `identical pieces at different positions are not identical on the wire`() {
        val key = YggCryptoEngine.deriveFileTransferKey(alice.xPriv, bob.xPubHex, "tx_1")
        val plain = ByteArray(1024) { 65 }

        val first = YggCryptoEngine.sealFilePiece(key, plain, "tx_1", 0)
        val second = YggCryptoEngine.sealFilePiece(key, plain, "tx_1", 1)

        assertNotEquals(first.second, second.second)
    }

    @Test
    fun `piece profile hashes the bytes on disk, not the file name`() {
        val body = ByteArray(30_000) { (it % 256).toByte() }
        val file = tempFile(body)

        val profile = YggCryptoEngine.pieceProfileOf(file, pieceBytes = 8_192)
        requireNotNull(profile)

        // 8192 + 8192 + 8192 + 5524
        assertEquals(4, profile.pieceCount)
        assertEquals(30_000L, profile.totalBytes)
        val manual = YggCryptoEngine.merkleRootOfPieceDigests(
            listOf(0, 8192, 16384, 24576).map { offset ->
                YggCryptoEngine.sha256Bytes(body.copyOfRange(offset, minOf(offset + 8192, body.size)))
            }
        )
        assertEquals(manual, profile.merkleRootHex)

        val flipped = tempFile(body.copyOf().also { it[25_000] = (it[25_000] + 1).toByte() })
        val otherProfile = YggCryptoEngine.pieceProfileOf(flipped, pieceBytes = 8_192)
        requireNotNull(otherProfile)
        assertNotEquals(profile.merkleRootHex, otherProfile.merkleRootHex)
    }

    @Test
    fun `an absent file has no profile instead of a fabricated root`() {
        assertNull(YggCryptoEngine.pieceProfileOf(File("ygg_does_not_exist.bin")))
    }

    @Test
    fun `a stream of the same bytes hashes exactly like the file`() {
        val body = ByteArray(12 * 1024 * 3) { 7 }
        val file = tempFile(body)
        val real = YggCryptoEngine.pieceProfileOf(file)
        requireNotNull(real)

        val streamed = ByteArrayInputStream(body).use { YggCryptoEngine.pieceProfileOfStream(it) }

        assertEquals(real.merkleRootHex, streamed.merkleRootHex)
        assertEquals(real.pieceCount, streamed.pieceCount)
        assertEquals(64, streamed.merkleRootHex.length)

        // A storage provider that hands back one byte less is a different archive: the old
        // backup path could not notice, because it never read bytes at all.
        val short = ByteArrayInputStream(body.copyOfRange(0, body.size - 1))
            .use { YggCryptoEngine.pieceProfileOfStream(it) }
        assertNotEquals(real.merkleRootHex, short.merkleRootHex)
        assertEquals(body.size - 1L, short.totalBytes)
    }

    private fun assertEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue("байты не совпали", expected contentEquals actual)
    }
}
