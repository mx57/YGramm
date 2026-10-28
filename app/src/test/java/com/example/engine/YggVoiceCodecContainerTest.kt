// File: app/src/test/java/com/example/engine/YggVoiceCodecContainerTest.kt
package com.example.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container and the fallback, measured on the plain JVM where no Android codec exists.
 *
 * That absence is the point: the codec is asked for, never assumed, so these tests prove the
 * app degrades to uncompressed PCM instead of inventing audio it cannot produce. A green run
 * here says a peer with no Opus in the frame still gets every byte it was promised.
 */
class YggVoiceCodecContainerTest {

    private val packets = listOf(
        byteArrayOf(1, 2, 3, 4, 5),
        ByteArray(120) { (it % 17).toByte() },
        byteArrayOf(-1, 0, 1)
    )

    @Test
    fun `the frame geometry matches the bytes on the wire`() {
        assertEquals(640, YggVoiceCodec.FRAME_BYTES)
        assertEquals(32000, YggVoiceCodec.PCM_BYTES_PER_SECOND)
        assertEquals(20, YggVoiceCodec.FRAME_MS)
        // magic + version + codec + rate + channels + frameMs + count + reserved
        assertEquals(14, YggVoiceCodec.HEADER_BYTES)
    }

    @Test
    fun `a container written here reads back as the same packets`() {
        val payload = YggVoiceCodec.writeContainer(YggVoiceCodec.CODEC_OPUS, packets)
        val header = YggVoiceCodec.readHeader(payload)
        assertNotNull("целый контейнер обязан читаться", header)
        assertEquals(YggVoiceCodec.CODEC_OPUS, header!!.codecId)
        assertEquals(YggVoiceCodec.SAMPLE_RATE, header.sampleRate)
        assertEquals(YggVoiceCodec.CHANNELS, header.channels)
        assertEquals(YggVoiceCodec.FRAME_MS, header.frameMs)
        assertEquals(packets.size, header.packets.size)
        packets.zip(header.packets).forEach { (written, read) ->
            assertArrayEquals(written, read)
        }
    }

    @Test
    fun `a damaged container is refused instead of half-read`() {
        val payload = YggVoiceCodec.writeContainer(YggVoiceCodec.CODEC_OPUS, packets)
        assertNull("обрезанный хвост — это отказ, а не неполный звук", YggVoiceCodec.readHeader(payload.copyOfRange(0, payload.size - 3)))

        val truncatedTable = payload.copyOf()
        // The packet count claims more entries than the payload holds.
        truncatedTable[10] = 9
        truncatedTable[11] = 0
        assertNull(YggVoiceCodec.readHeader(truncatedTable))

        val wrongMagic = payload.copyOf()
        wrongMagic[0] = 'Y'.code.toByte()
        wrongMagic[1] = 'G'.code.toByte()
        wrongMagic[2] = 'O'.code.toByte()
        wrongMagic[3] = '9'.code.toByte()
        assertFalse(YggVoiceCodec.isOpusContainer(wrongMagic))

        val wrongVersion = payload.copyOf()
        wrongVersion[4] = 2
        assertNull("неизвестная версия контейнера не трактуется как первая", YggVoiceCodec.readHeader(wrongVersion))
    }

    @Test
    fun `a header only becomes a container once there are bytes behind it`() {
        val headerOnly = YggVoiceCodec.writeContainer(YggVoiceCodec.CODEC_OPUS, emptyList())
        assertTrue(YggVoiceCodec.hasOpusMagic(headerOnly))
        assertFalse("4 байта магии — это ещё не то, что можно открыть", YggVoiceCodec.isOpusContainer(headerOnly))
        assertFalse(YggVoiceCodec.isOpusContainer(ByteArray(3)))
        assertFalse(YggVoiceCodec.isOpusContainer("RIFF".toByteArray()))
    }

    @Test
    fun `an advertisement is read as a list of names, not as a substring`() {
        assertTrue(YggVoiceCodec.advertises("opus"))
        assertTrue(YggVoiceCodec.advertises("Opus, pcm"))
        assertTrue(YggVoiceCodec.advertises("  opus  "))
        assertFalse(YggVoiceCodec.advertises(""))
        assertFalse(YggVoiceCodec.advertises("pcm"))
        assertFalse("цифры не должны складываться в название кодека", YggVoiceCodec.advertises("2,3"))
    }

    @Test
    fun `without a platform codec the note goes out exactly as it was recorded`() {
        val pcm = ByteArray(YggVoiceCodec.PCM_BYTES_PER_SECOND * 2) { (it % 91).toByte() }
        val encoded = YggVoiceCodec.encode(pcm)
        assertNotNull(encoded)
        assertEquals(
            "на JVM нет MediaCodec, поэтому честный ответ — несжатый PCM",
            YggVoiceCodec.CODEC_PCM,
            encoded!!.codecId
        )
        assertArrayEquals(pcm, encoded.bytes)
        assertEquals(pcm.size, encoded.pcmBytes)
        assertEquals(2, encoded.durationSeconds)
    }

    @Test
    fun `a container nobody can open returns null rather than silence`() {
        val payload = YggVoiceCodec.writeContainer(YggVoiceCodec.CODEC_OPUS, packets)
        assertNull("нет декодера — значит ничего, а не тишина нужной длительности", YggVoiceCodec.decode(payload))
        assertNull(YggVoiceCodec.decode("RIFF".toByteArray() + ByteArray(64)))
        assertNull(YggVoiceCodec.encode(ByteArray(0)))
    }

    @Test
    fun `the self check names the missing codec instead of passing quietly`() {
        val check = YggVoiceCodec.selfCheck(seconds = 1)
        assertNull(check.encoder)
        assertFalse(check.passed)
        assertEquals(100, check.payloadPercent)
        assertTrue(
            "вердикт обязан объяснять, почему сжатия нет: ${check.verdict}",
            check.verdict.contains("Opus")
        )
        assertEquals(32000, check.pcmBytes)
        assertEquals(32000, check.payloadBytes)
    }
}
