// File: app/src/main/java/com/example/engine/YggQuicInitial.kt
package com.example.engine

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Builds a QUIC v1 Initial packet that a real server is able to parse.
 *
 * This exists for one measurement: does the current network carry UDP to port 443 and back. The
 * only honest instrument is a packet the far side is willing to answer, because an Initial that
 * fails decryption is discarded in silence by every compliant implementation, and that silence
 * would be indistinguishable from a network that drops UDP. So the packet is assembled the way
 * RFC 9000 and RFC 9001 define it - Initial keys derived from the destination connection id,
 * AES-128-GCM over the CRYPTO frame, AES-ECB header protection - and the builder is pinned to the
 * hex vectors published in RFC 9001 Appendix A (see YggQuicInitialTest). A packet that reproduces
 * the RFC byte for byte is a packet a server can read.
 *
 * Nothing here is a transport: the app never speaks QUIC. This produces one datagram, and the
 * answer it is looking for is "did anything come back at all", not a finished handshake.
 */
object YggQuicInitial {

    const val QUIC_V1 = 0x00000001

    /** RFC 9000 14.1: a client Initial is padded until the packet is at least this large. */
    const val MIN_INITIAL_BYTES = 1200

    /** RFC 9001 5.2, verbatim: the salt version 1 publishes for deriving Initial keys. */
    internal val INITIAL_SALT_V1 = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")

    internal const val AEAD_TAG_BYTES = 16
    internal const val PACKET_NUMBER_BYTES = 4

    data class InitialKeys(val aeadKey: ByteArray, val iv: ByteArray, val headerKey: ByteArray)

    /**
     * The keys both sides derive from nothing but the salt and the client's chosen connection id.
     * These are public by design: an Initial carries no secrets, which is exactly why sending one
     * costs the measurement nothing in confidentiality terms.
     */
    fun initialKeys(destinationConnectionId: ByteArray): InitialKeys {
        val initialSecret = hkdfExtract(INITIAL_SALT_V1, destinationConnectionId)
        val clientInitial = expandLabel(initialSecret, "client in", 32)
        return InitialKeys(
            aeadKey = expandLabel(clientInitial, "quic key", 16),
            iv = expandLabel(clientInitial, "quic iv", 12),
            headerKey = expandLabel(clientInitial, "quic hp", 16)
        )
    }

    /**
     * A ClientHello, hand-encoded, aimed at what actually listens on 443/udp: an HTTP/3 endpoint.
     * ALPN is h3 rather than hq because the reference addresses serve web traffic there; a server
     * that dislikes the ALPN still has to answer with a close, and an answer is the observation.
     *
     * [keySharePublic] is an X25519-sized public value so the extension parses. There is no
     * matching private half: the probe never intends to finish a handshake, and holding handshake
     * state for a packet sent at a third party's server would buy nothing.
     */
    fun clientHello(serverName: String, alpn: String, keySharePublic: ByteArray): ByteArray {
        require(keySharePublic.size == 32) { "key_share для x25519 всегда 32 байта" }
        val sessionId = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val extensions = Builder()
            .extension(0x0000, serverNameList(serverName))
            .extension(0x000A, groupList(0x001D))
            .extension(0x000D, signatureList(0x0403, 0x0503, 0x0603, 0x0804, 0x0805, 0x0806))
            .extension(0x0010, alpnList(alpn))
            .extension(0x002B, byteArrayOf(0x01, 0x03, 0x04))
            .extension(0x002D, byteArrayOf(0x01, 0x01))
            .extension(0x0033, keyShareList(keySharePublic))
            .toByteArray()
        val body = Builder()
            // legacy_version 0x0303, then the three fields TLS 1.3 keeps for middleboxes that read
            // them: a hello that omits them is the unusual one, and unusual is what a filter looks for.
            .u16(0x0303)
            .u8(sessionId.size)
            .raw(sessionId)
            .u16(6)
            .u16(0x1301).u16(0x1302).u16(0x1303)
            .u8(1).u8(0)
            .u16(extensions.size)
            .raw(extensions)
            .toByteArray()
        return Builder().u8(0x01).u24(body.size).raw(body).toByteArray()
    }

    /** A complete protected Initial: keys, sealed CRYPTO frame, padding to the floor, masked header. */
    fun build(
        destinationConnectionId: ByteArray,
        sourceConnectionId: ByteArray,
        packetNumber: Long,
        clientHello: ByteArray,
        minTotalBytes: Int = MIN_INITIAL_BYTES
    ): ByteArray {
        val keys = initialKeys(destinationConnectionId)
        val cryptoFrame = Builder()
            .u8(0x06)
            .varInt(0)
            .varInt(clientHello.size.toLong())
            .raw(clientHello)
            .toByteArray()
        val fixedHeaderBytes = 1 + 4 + 1 + destinationConnectionId.size + 1 +
            sourceConnectionId.size + 1 + 2
        val paddingBytes = (minTotalBytes - fixedHeaderBytes - PACKET_NUMBER_BYTES -
            cryptoFrame.size - AEAD_TAG_BYTES).coerceAtLeast(0)
        val frames = cryptoFrame + ByteArray(paddingBytes)
        val lengthValue = PACKET_NUMBER_BYTES + frames.size + AEAD_TAG_BYTES

        val header = Builder()
            // Long header: fixed bit set, type 0x00 = Initial, low four bits = pn length - 1.
            .u8(0xC0 or (PACKET_NUMBER_BYTES - 1))
            .u32(QUIC_V1.toLong())
            .u8(destinationConnectionId.size)
            .raw(destinationConnectionId)
            .u8(sourceConnectionId.size)
            .raw(sourceConnectionId)
            .u8(0)
            .varInt16(lengthValue.toLong())
            .u32(packetNumber)
            .toByteArray()

        val nonce = keys.iv.copyOf()
        for (i in 0 until PACKET_NUMBER_BYTES) {
            val at = nonce.size - 1 - i
            nonce[at] = (nonce[at].toInt() xor ((packetNumber shr (8 * i)) and 0xFFL).toInt()).toByte()
        }
        val aead = Cipher.getInstance("AES/GCM/NoPadding")
        aead.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keys.aeadKey, "AES"),
            GCMParameterSpec(AEAD_TAG_BYTES * 8, nonce)
        )
        aead.updateAAD(header)
        val packet = (header.toList() + aead.doFinal(frames).toList()).toByteArray()

        val mask = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.headerKey, "AES"))
        }.doFinal(packet.copyOfRange(header.size, header.size + 16)).copyOf(5)

        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
        val packetNumberOffset = header.size - PACKET_NUMBER_BYTES
        for (i in 0 until PACKET_NUMBER_BYTES) {
            packet[packetNumberOffset + i] =
                (packet[packetNumberOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        }
        return packet
    }

    // --- TLS encodings. Kept here because the exact bytes are the point of the exercise. ---

    private fun serverNameList(name: String): ByteArray {
        val host = name.toByteArray()
        return Builder().u16(host.size + 3).u8(0).u16(host.size).raw(host).toByteArray()
    }

    private fun alpnList(alpn: String): ByteArray {
        val proto = alpn.toByteArray()
        return Builder().u16(proto.size + 1).u8(proto.size).raw(proto).toByteArray()
    }

    private fun keyShareList(key: ByteArray): ByteArray = Builder()
        .u16(key.size + 4)
        .u16(0x001D)
        .u16(key.size)
        .raw(key)
        .toByteArray()

    private fun groupList(vararg groups: Int): ByteArray {
        val out = Builder().u16(groups.size * 2)
        groups.forEach { out.u16(it) }
        return out.toByteArray()
    }

    private fun signatureList(vararg algorithms: Int): ByteArray = groupList(*algorithms)

    // --- RFC 9001 key schedule: HKDF-SHA256 with the "tls13 " label prefix. ---

    internal fun hkdfExtract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray =
        hmac(salt, inputKeyMaterial)

    internal fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * prk.size) { "HKDF-Expand не выдаст $length байт" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    internal fun expandLabel(secret: ByteArray, label: String, length: Int): ByteArray =
        hkdfExpand(secret, hkdfLabelBytes(label, length), length)

    /** The HkdfLabel bytes published in RFC 9001 A.1, exposed so a test can compare them one by one. */
    internal fun hkdfLabelBytes(label: String, length: Int): ByteArray = Builder()
        .u16(length)
        .u8("tls13 $label".length)
        .raw("tls13 $label".toByteArray())
        .u8(0)
        .toByteArray()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Big-endian field writer; the encodings below are all network byte order. */
    internal class Builder {
        private val out = ArrayList<Byte>()

        fun raw(value: ByteArray): Builder { value.forEach { out.add(it) }; return this }
        fun u8(value: Int): Builder { out.add(value.toByte()); return this }
        fun u16(value: Int): Builder { out.add((value shr 8).toByte()); out.add(value.toByte()); return this }
        fun u24(value: Int): Builder {
            out.add((value shr 16).toByte()); out.add((value shr 8).toByte()); out.add(value.toByte())
            return this
        }
        fun u32(value: Long): Builder {
            out.add((value shr 24).toByte()); out.add((value shr 16).toByte())
            out.add((value shr 8).toByte()); out.add(value.toByte())
            return this
        }

        fun extension(type: Int, payload: ByteArray): Builder =
            u16(type).u16(payload.size).raw(payload)

        /** RFC 9000 19: two prefix bits pick the width, so the value only has to fit 62 bits. */
        fun varInt(value: Long): Builder = raw(varIntBytes(value))

        fun varInt16(value: Long): Builder {
            out.add(((value shr 8) or 0x40).toByte())
            out.add(value.toByte())
            return this
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    internal fun varIntBytes(value: Long): ByteArray = when {
        value < 64 -> byteArrayOf(value.toByte())
        value < 16384 -> byteArrayOf(((value shr 8) or 0x40).toByte(), value.toByte())
        value < 1073741824 -> byteArrayOf(
            ((value shr 24) or 0x80).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
        else -> throw IllegalArgumentException("varint $value не помещается в 4 байта")
    }

    internal fun hex(value: String): ByteArray {
        val clean = value.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "нечётное число hex-символов" }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    internal fun unhex(value: ByteArray): String =
        value.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
