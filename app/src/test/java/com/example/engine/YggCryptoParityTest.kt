// File: app/src/test/java/com/example/engine/YggCryptoParityTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden vectors shared with core/pkg/crypto/hierarchy_parity_test.go. The Kotlin side
 * derives the identity advertised to contacts while the Go core runs the real node from
 * the same mnemonic; if the two derivations ever diverge, inbound messages are silently
 * lost — this test fails in CI on any change to either format or algorithm.
 */
class YggCryptoParityTest {

    private data class Vector(
        val mnemonic: String,
        val nodePubHex: String,
        val masterFingerprintHex: String,
        val ipv6: String,
    )

    private val vectors = listOf(
        Vector(
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            nodePubHex = "3cdaefc58b86fcae39fd859029b573bb30b66ea103bb0f635f8d3653fa17bb0d",
            masterFingerprintHex = "7449eb29528b6c28",
            ipv6 = "202:1928:81d3:a3c8:1a8e:3013:d37e:b254",
        ),
        Vector(
            mnemonic = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            nodePubHex = "ae4c2aa69a48086edd529fc351163fc10739b9a473f74dcb230f7fd16a1b576a",
            masterFingerprintHex = "bc9e4b426a9e191a",
            ipv6 = "200:a367:aab2:cb6f:ef22:455a:c079:5dd3",
        ),
        Vector(
            mnemonic = "witch combs handshakes peeking jugs unroofed reviving headlock rebooking headstand befogging deicing",
            nodePubHex = "46068858adc43b0283485c47163e115d4ae7d95bebd85da26403e20b35c912ec",
            masterFingerprintHex = "9d97b121460c4d7b",
            ipv6 = "201:e7e5:de9d:48ef:13f5:f2de:8ee3:a707",
        ),
    )

    @Test
    fun kotlinDerivationMatchesGoCore() {
        for (vector in vectors) {
            val words = vector.mnemonic.split(" ").filter { it.isNotBlank() }
            val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(words, epoch = 1)
            assertEquals("nodePub for ${vector.mnemonic.take(20)}", vector.nodePubHex, hierarchy.yggNodePublicKeyHex)
            assertEquals("masterFp for ${vector.mnemonic.take(20)}", vector.masterFingerprintHex, hierarchy.masterFingerprintHex)
            assertEquals("ipv6 for ${vector.mnemonic.take(20)}", vector.ipv6, hierarchy.yggIpv6Address)
        }
    }

    @Test
    fun mnemonicFormattingDoesNotChangeIdentity() {
        val words = vectors.first().mnemonic.split(" ").filter { it.isNotBlank() }
        val expected = YggCryptoEngine.deriveHierarchyFromMnemonic(words, epoch = 1)
        // The bridge normalizes the stored phrase the same way before nativeInitCore;
        // extra whitespace must not produce a second node identity.
        val withNoise = "  ${words.joinToString("  ")} \n "
        val normalized = withNoise.split(" ").filter { it.isNotBlank() }.joinToString(" ")
        val again = YggCryptoEngine.deriveHierarchyFromMnemonic(normalized.split(" "), epoch = 1)
        assertEquals(expected.yggNodePublicKeyHex, again.yggNodePublicKeyHex)
        assertEquals(expected.yggIpv6Address, again.yggIpv6Address)
    }

    @Test
    fun safetyNumberIsStableAcrossFormatting() {
        // The same 12-group digest must appear on both devices when the users compare
        // the chat inspector with the contact QR screen; anything else means the two
        // sides stopped agreeing on the encoding.
        val vector = vectors.first()
        val expected = "86911 22507 98750 46061 00968 61509 11690 82036 02128 69451 91877 39511"
        assertEquals(expected, YggCryptoEngine.safetyNumber(vector.nodePubHex))
        assertEquals(expected, YggCryptoEngine.safetyNumber("  ${vector.nodePubHex.uppercase()} "))
        assertEquals("", YggCryptoEngine.safetyNumber(""))
        assertEquals("", YggCryptoEngine.safetyNumber("not-a-key"))
    }
}
