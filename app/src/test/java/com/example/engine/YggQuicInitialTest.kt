// File: app/src/test/java/com/example/engine/YggQuicInitialTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UDP probe is only worth running if the far side can read what it sends: a malformed Initial
 * is dropped in silence by a compliant server, and that silence looks exactly like a network that
 * blocks UDP. So the builder is verified against the hex RFC 9001 publishes for the same
 * construction, byte for byte, rather than against what this code happens to output.
 *
 * Every expected value below is copied from RFC 9001 Appendix A.1-A.2.
 */
class YggQuicInitialTest {

    private companion object {
        /** RFC 9001 A.2: the destination connection id the client chose; the keys come from it. */
        const val DCID = "8394c8f03e515708"

        /** RFC 9001 A.2: CRYPTO frame plus the hello, before the padding that fills the packet. */
        const val RFC_CRYPTO_FRAME =
            "060040f1010000ed0303ebf8fa56f12939b9584a3896472ec40bb863cfd3e86804fe3a47f06a2b69484c" +
                "00000413011302010000c000000010000e00000b6578616d706c652e636f6dff01000100000a0008" +
                "0006001d0017001800100007000504616c706e000500050100000000003300260024001d00209370" +
                "b2c9caa47fbabaf4559fedba753de171fa71f50f1ce15d43e994ec74d748002b0003020304000d00" +
                "10000e0403050306030203080408050806002d00020101001c00024001003900320408ffffffffff" +
                "ffffff05048000ffff07048000ffff0801100104800075300901100f088394c8f03e515708060480" +
                "00ffff"

        /** RFC 9001 A.2: the header before protection, with a packet number of 2. */
        const val RFC_UNPROTECTED_HEADER = "c30000000108" + DCID + "0000449e00000002"

        /** RFC 9001 A.2: the packet as it goes on the wire - 1200 bytes, floor included. */
        const val RFC_PROTECTED_PACKET =
            "c000000001088394c8f03e5157080000449e7b9aec34d1b1c98dd7689fb8ec11d242b123" +
                "dc9bd8bab936b47d92ec356c0bab7df5976d27cd449f63300099f3991c260ec4c60d17b3" +
                "1f8429157bb35a1282a643a8d2262cad67500cadb8e7378c8eb7539ec4d4905fed1bee1f" +
                "c8aafba17c750e2c7ace01e6005f80fcb7df621230c83711b39343fa028cea7f7fb5ff89" +
                "eac2308249a02252155e2347b63d58c5457afd84d05dfffdb20392844ae812154682e9cf" +
                "012f9021a6f0be17ddd0c2084dce25ff9b06cde535d0f920a2db1bf362c23e596d11a4f5" +
                "a6cf3948838a3aec4e15daf8500a6ef69ec4e3feb6b1d98e610ac8b7ec3faf6ad760b7ba" +
                "d1db4ba3485e8a94dc250ae3fdb41ed15fb6a8e5eba0fc3dd60bc8e30c5c4287e53805db" +
                "059ae0648db2f64264ed5e39be2e20d82df566da8dd5998ccabdae053060ae6c7b4378e8" +
                "46d29f37ed7b4ea9ec5d82e7961b7f25a9323851f681d582363aa5f89937f5a67258bf63" +
                "ad6f1a0b1d96dbd4faddfcefc5266ba6611722395c906556be52afe3f565636ad1b17d50" +
                "8b73d8743eeb524be22b3dcbc2c7468d54119c7468449a13d8e3b95811a198f3491de3e7" +
                "fe942b330407abf82a4ed7c1b311663ac69890f4157015853d91e923037c227a33cdd5ec" +
                "281ca3f79c44546b9d90ca00f064c99e3dd97911d39fe9c5d0b23a229a234cb36186c481" +
                "9e8b9c5927726632291d6a418211cc2962e20fe47feb3edf330f2c603a9d48c0fcb5699d" +
                "bfe5896425c5bac4aee82e57a85aaf4e2513e4f05796b07ba2ee47d80506f8d2c25e50fd" +
                "14de71e6c418559302f939b0e1abd576f279c4b2e0feb85c1f28ff18f58891ffef132eef" +
                "2fa09346aee33c28eb130ff28f5b766953334113211996d20011a198e3fc433f9f254101" +
                "0ae17c1bf202580f6047472fb36857fe843b19f5984009ddc324044e847a4f4a0ab34f71" +
                "9595de37252d6235365e9b84392b061085349d73203a4a13e96f5432ec0fd4a1ee65accd" +
                "d5e3904df54c1da510b0ff20dcc0c77fcb2c0e0eb605cb0504db87632cf3d8b4dae6e705" +
                "769d1de354270123cb11450efc60ac47683d7b8d0f811365565fd98c4c8eb936bcab8d06" +
                "9fc33bd801b03adea2e1fbc5aa463d08ca19896d2bf59a071b851e6c239052172f296bfb" +
                "5e72404790a2181014f3b94a4e97d117b438130368cc39dbb2d198065ae3986547926cd2" +
                "162f40a29f0c3c8745c0f50fba3852e566d44575c29d39a03f0cda721984b6f440591f35" +
                "5e12d439ff150aab7613499dbd49adabc8676eef023b15b65bfc5ca06948109f23f350db" +
                "82123535eb8a7433bdabcb909271a6ecbcb58b936a88cd4e8f2e6ff5800175f113253d8f" +
                "a9ca8885c2f552e657dc603f252e1a8e308f76f0be79e2fb8f5d5fbbe2e30ecadd220723" +
                "c8c0aea8078cdfcb3868263ff8f0940054da48781893a7e49ad5aff4af300cd804a6b627" +
                "9ab3ff3afb64491c85194aab760d58a606654f9f4400e8b38591356fbf6425aca26dc852" +
                "44259ff2b19c41b9f96f3ca9ec1dde434da7d2d392b905ddf3d1f9af93d1af5950bd493f" +
                "5aa731b4056df31bd267b6b90a079831aaf579be0a39013137aac6d404f518cfd4684064" +
                "7e78bfe706ca4cf5e9c5453e9f7cfd2b8b4c8d169a44e55c88d4a9a7f9474241e221af44" +
                "860018ab0856972e194cd934"
    }

    @Test
    fun `hkdf label bytes match rfc 9001 a1`() {
        // The label encoding is where an off-by-one would quietly produce keys no server can use.
        assertEquals("00200f746c73313320636c69656e7420696e00", hexOf("client in", 32))
        assertEquals("00200f746c7331332073657276657220696e00", hexOf("server in", 32))
        assertEquals("00100e746c7331332071756963206b657900", hexOf("quic key", 16))
        assertEquals("000c0d746c733133207175696320697600", hexOf("quic iv", 12))
        assertEquals("00100d746c733133207175696320687000", hexOf("quic hp", 16))
    }

    private fun hexOf(label: String, length: Int) =
        YggQuicInitial.unhex(YggQuicInitial.hkdfLabelBytes(label, length))

    @Test
    fun `initial keys match rfc 9001 a1`() {
        val dcid = YggQuicInitial.hex(DCID)
        val initialSecret = YggQuicInitial.expandLabel(
            YggQuicInitial.hkdfExtract(YggQuicInitial.INITIAL_SALT_V1, dcid), "client in", 32
        )
        assertEquals(
            "c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea",
            YggQuicInitial.unhex(initialSecret)
        )
        val keys = YggQuicInitial.initialKeys(dcid)
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", YggQuicInitial.unhex(keys.aeadKey))
        assertEquals("fa044b2f42a3fd3b46fb255c", YggQuicInitial.unhex(keys.iv))
        assertEquals("9f50449e04a0e810283a1e9933adedd2", YggQuicInitial.unhex(keys.headerKey))
    }

    @Test
    fun `protected initial reproduces rfc 9001 a2 byte for byte`() {
        val cryptoFrame = YggQuicInitial.hex(RFC_CRYPTO_FRAME)
        // A.2 carries the hello inside a CRYPTO frame: 06, offset varint 0, length varint 241.
        val clientHello = cryptoFrame.copyOfRange(4, cryptoFrame.size)
        assertEquals(241, clientHello.size)

        val packet = YggQuicInitial.build(
            destinationConnectionId = YggQuicInitial.hex(DCID),
            sourceConnectionId = ByteArray(0),
            packetNumber = 2,
            clientHello = clientHello
        )
        val expected = YggQuicInitial.hex(RFC_PROTECTED_PACKET)
        assertEquals(expected.size, packet.size)
        assertEquals(RFC_PROTECTED_PACKET, YggQuicInitial.unhex(packet))
    }

    @Test
    fun `unprotected header carries the length field the rfc shows`() {
        // Rebuilding the same packet with the protection step skipped is what the RFC prints as
        // c300000001088394c8f03e5157080000449e00000002, so the 0x449e length and the 4-byte
        // packet number have to come out of this builder, not out of a hand-written array.
        val cryptoFrame = YggQuicInitial.hex(RFC_CRYPTO_FRAME)
        val clientHello = cryptoFrame.copyOfRange(4, cryptoFrame.size)
        val protectedPacket = YggQuicInitial.build(
            YggQuicInitial.hex(DCID), ByteArray(0), 2, clientHello
        )
        val header = protectedPacket.copyOfRange(0, 22)
        // Only the low four bits of the first byte and the packet number are masked, so undoing
        // the mask with the published bytes has to give the published unprotected header back.
        val mask = YggQuicInitial.hex("437b9aec36")
        header[0] = (header[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
        for (i in 0 until 4) {
            header[18 + i] = (header[18 + i].toInt() xor mask[1 + i].toInt()).toByte()
        }
        assertEquals(RFC_UNPROTECTED_HEADER, YggQuicInitial.unhex(header))
    }

    @Test
    fun `runtime hello names the host and alpn it was asked to`() {
        val hello = YggQuicInitial.clientHello("www.google.com", "h3", ByteArray(32))
        val asText = String(hello, Charsets.ISO_8859_1)
        assertEquals(0x01, hello[0].toInt() and 0xFF)
        assertTrue("SNI не в КлиентХелло", asText.contains("www.google.com"))
        assertTrue("ALPN не в КлиентХелло", asText.contains("h3"))
        // 32 bytes of key_share for group 0x001d: without a parseable share the server discards it.
        assertTrue(hello.toList().windowed(4).any { it == listOf(0x00, 0x1d, 0x00, 0x20.toByte()) })
    }

    @Test
    fun `packet clears the amplification floor the spec sets`() {
        val hello = YggQuicInitial.clientHello("cloudflare.com", "h3", ByteArray(32) { (it + 1).toByte() })
        val packet = YggQuicInitial.build(ByteArray(8) { 0x42 }, ByteArray(0), 0, hello)
        assertTrue(
            "пакет меньше " + YggQuicInitial.MIN_INITIAL_BYTES + " байт: сервер обязан его игнорировать",
            packet.size >= YggQuicInitial.MIN_INITIAL_BYTES
        )
        // Long header, fixed bit set, Initial type; header protection only touches the low nibble.
        assertEquals(0xC0, packet[0].toInt() and 0xF0)
        assertEquals("00000001", YggQuicInitial.unhex(packet.copyOfRange(1, 5)))
    }
}
