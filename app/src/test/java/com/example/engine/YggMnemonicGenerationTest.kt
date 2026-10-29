package com.example.engine

import java.math.BigInteger
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YggMnemonicGenerationTest {
    private val wordListBytes = YggCryptoEngine::class.java.getResourceAsStream("/bip39-english.txt")!!
        .use { it.readBytes() }
    private val wordList = wordListBytes.toString(Charsets.US_ASCII).trim().split("\n")

    @Test
    fun packagedDictionaryMatchesTheOfficialBip39List() {
        assertEquals(2048, wordList.size)
        assertEquals(2048, wordList.toSet().size)
        assertEquals(wordList.sorted(), wordList)
        assertEquals("abandon", wordList.first())
        assertEquals("zoo", wordList.last())
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            YggCryptoEngine.sha256Hex(wordListBytes)
        )
    }

    @Test
    fun entropyEncodingMatchesPublishedBip39Vectors() {
        val vectors = listOf(
            0x00 to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            0x7f to "legal winner thank year wave sausage worth useful legal winner thank yellow",
            0x80 to "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
            0xff to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
        )
        vectors.forEach { (byte, phrase) ->
            assertEquals(phrase.split(" "), YggCryptoEngine.mnemonic12FromEntropy(ByteArray(16) { byte.toByte() }))
        }
    }

    @Test
    fun freshPhrasesHaveTwelveDictionaryWordsAndAnIndependentChecksum() {
        val phrases = List(16) { YggCryptoEngine.generateFreshMnemonic12() }
        assertEquals(16, phrases.toSet().size)
        phrases.forEach { phrase ->
            assertEquals(12, phrase.size)
            val encoded = phrase.fold(BigInteger.ZERO) { value, word ->
                val index = wordList.indexOf(word)
                assertTrue(index >= 0)
                value.shiftLeft(11).or(BigInteger.valueOf(index.toLong()))
            }
            val entropyValue = encoded.shiftRight(4)
            val entropy = ByteArray(16) { index -> entropyValue.shiftRight((15 - index) * 8).toByte() }
            val expected = (MessageDigest.getInstance("SHA-256").digest(entropy)[0].toInt() ushr 4) and 15
            assertEquals(expected, encoded.and(BigInteger.valueOf(15)).toInt())
        }
    }
}
