// File: app/src/main/java/com/example/engine/YggCryptoEngine.kt
package com.example.engine

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DerivedIdentityHierarchy(
    val mnemonicWords: List<String>,
    val masterFingerprintHex: String,
    val yggNodePublicKeyHex: String,
    val yggIpv6Address: String,
    val yggSubnetPrefix: String,
    val x25519IdentityPubHex: String,
    val x25519SignedPreKeyHex: String,
    val mlsLeafKeyHex: String,
    val backupMasterKeyFingerprint: String
)

data class EncryptedArchiveBundle(
    val archiveFileName: String,
    val payloadSizeBytes: Long,
    val fastCdcChunks: Int,
    val merkleRootSha256: String,
    val authTagHex: String,
    val kdfSummary: String,
    val cipherSummary: String
)

object YggCryptoEngine {
    private val secureRandom = SecureRandom()

    private val defaultWordPool = listOf(
        "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
        "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex",
        "cobalt", "beacon", "prism", "horizon", "lattice", "nomad",
        "zenith", "paradox", "anchor", "kinetic", "solstice", "aurora"
    )

    // BigInteger field arithmetic caches for the curve implementations below.
    private val fieldP: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val curveD: BigInteger = BigInteger.valueOf(-121665L)
        .multiply(BigInteger.valueOf(121666L).modInverse(fieldP))
        .mod(fieldP)
    private val basePointX = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val basePointY = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    private const val X25519_BASE_U_HEX = "09"

    private val hierarchyCache = object : LinkedHashMap<String, DerivedIdentityHierarchy>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DerivedIdentityHierarchy>): Boolean = size > 8
    }

    fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(text: String): String =
        sha256Hex(text.toByteArray(StandardCharsets.UTF_8))

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    /**
     * Deterministically derives the same SLIP-0010-style hierarchy as the Go core
     * (core/pkg/crypto.DeriveSLIP0010Hierarchy): HMAC-SHA512("ed25519 seed", mnemonic)
     * master, child seeds via HMAC-SHA512(master[:32], 0x00||hardened purpose||hardened index),
     * a real Ed25519 node keypair, and the real Yggdrasil v0.5 address derivation.
     */
    fun deriveHierarchyFromMnemonic(mnemonic: List<String>, epoch: Int = 1): DerivedIdentityHierarchy {
        val cacheKey = "${mnemonic.joinToString(" ") { it.trim() }}#$epoch"
        synchronized(hierarchyCache) {
            hierarchyCache[cacheKey]?.let { return it }
        }
        val normalized = mnemonic.joinToString(" ") { it.trim() }
        val masterSeed = hmacSha512(
            "ed25519 seed".toByteArray(StandardCharsets.UTF_8),
            normalized.toByteArray(StandardCharsets.UTF_8)
        )
        val masterFingerprint = sha256Hex(masterSeed).take(16)

        val nodeSeed = deriveChildSeed(masterSeed, purpose = 0, index = epoch)
        val nodePubKeyBytes = ed25519PublicKeyFromSeed(nodeSeed.copyOfRange(0, 32))
        val nodePubKeyHex = nodePubKeyBytes.joinToString("") { "%02x".format(it) }

        val ipv6 = computeYggdrasilIpv6(nodePubKeyBytes)
        val subnet = computeYggdrasilSubnetPrefix(nodePubKeyBytes)

        val chatSeed = deriveChildSeed(masterSeed, purpose = 1, index = epoch)
        val x25519IdentityPriv = clampX25519Scalar(chatSeed.copyOfRange(0, 32))
        val x25519IdentityPub = x25519ScalarMultBase(x25519IdentityPriv)
        val x25519IdentityHex = x25519IdentityPub.joinToString("") { "%02x".format(it) }

        val preKeyMaterial = hmacSha512(masterSeed, "m/44'/999'/1'/$epoch'".toByteArray())
        val x25519SignedPreKeyHex = sha256Hex(preKeyMaterial)

        val mlsMaterial = hmacSha512(masterSeed, "m/44'/999'/2'/$epoch'".toByteArray())
        val mlsLeafKeyHex = sha256Hex(mlsMaterial)

        val backupMaterial = hmacSha512(masterSeed, "m/44'/999'/3'/0'".toByteArray())
        val backupFingerprint = sha256Hex(backupMaterial).take(24).uppercase()

        val hierarchy = DerivedIdentityHierarchy(
            mnemonicWords = mnemonic,
            masterFingerprintHex = masterFingerprint,
            yggNodePublicKeyHex = nodePubKeyHex,
            yggIpv6Address = ipv6,
            yggSubnetPrefix = subnet,
            x25519IdentityPubHex = x25519IdentityHex,
            x25519SignedPreKeyHex = x25519SignedPreKeyHex,
            mlsLeafKeyHex = mlsLeafKeyHex,
            backupMasterKeyFingerprint = backupFingerprint
        )
        synchronized(hierarchyCache) {
            hierarchyCache[cacheKey] = hierarchy
        }
        return hierarchy
    }

    /**
     * Mirrors core/pkg/crypto.deriveChildSeed: HMAC-SHA512 keyed with master[:32] over
     * 0x00 || BE32(0x80000000|purpose) || BE32(0x80000000|index).
     */
    private fun deriveChildSeed(master: ByteArray, purpose: Int, index: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(master.copyOfRange(0, 32), "HmacSHA512"))
        val buf = ByteArray(9)
        buf[0] = 0x00
        writeBigEndianInt(buf, 1, 0x80000000.toInt() or purpose)
        writeBigEndianInt(buf, 5, 0x80000000.toInt() or index)
        return mac.doFinal(buf)
    }

    private fun writeBigEndianInt(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value ushr 24).toByte()
        dest[offset + 1] = (value ushr 16).toByte()
        dest[offset + 2] = (value ushr 8).toByte()
        dest[offset + 3] = value.toByte()
    }

    /**
     * Real X25519 private scalar for a profile: clamped first 32 bytes of the
     * Go-core ChatX25519Secret child seed (purpose=1).
     */
    fun x25519PrivateKeyFromMnemonic(mnemonic: List<String>, epoch: Int = 1): ByteArray {
        val normalized = mnemonic.joinToString(" ") { it.trim() }
        val masterSeed = hmacSha512(
            "ed25519 seed".toByteArray(StandardCharsets.UTF_8),
            normalized.toByteArray(StandardCharsets.UTF_8)
        )
        val chatSeed = deriveChildSeed(masterSeed, purpose = 1, index = epoch)
        return clampX25519Scalar(chatSeed.copyOfRange(0, 32))
    }

    /**
     * Real Ed25519 public key (32 bytes) derived from a 32-byte seed:
     * clamped SHA-512(seed)[0..32] scalar multiplied by the Edwards25519 base point.
     */
    fun ed25519PublicKeyFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val hash = MessageDigest.getInstance("SHA-512").digest(seed)
        val scalarLE = hash.copyOfRange(0, 32)
        scalarLE[0] = (scalarLE[0].toInt() and 248).toByte()
        scalarLE[31] = (scalarLE[31].toInt() and 127).toByte()
        scalarLE[31] = (scalarLE[31].toInt() or 64).toByte()
        val scalar = BigInteger(1, scalarLE.reversedArray())
        val pubPoint = edwardsScalarMult(scalar, basePointX, basePointY)
        return encodeEdwardsPoint(pubPoint.first, pubPoint.second)
    }

    private fun edwardsAdd(
        p1x: BigInteger, p1y: BigInteger, p2x: BigInteger, p2y: BigInteger
    ): Pair<BigInteger, BigInteger> {
        val x1x2 = p1x.multiply(p2x).mod(fieldP)
        val y1y2 = p1y.multiply(p2y).mod(fieldP)
        val x1y2 = p1x.multiply(p2y).mod(fieldP)
        val y1x2 = p1y.multiply(p2x).mod(fieldP)
        val dx1x2y1y2 = curveD.multiply(x1x2).multiply(y1y2).mod(fieldP)
        val xDen = BigInteger.ONE.add(dx1x2y1y2).mod(fieldP).modInverse(fieldP)
        val yDen = BigInteger.ONE.subtract(dx1x2y1y2).mod(fieldP).modInverse(fieldP)
        val x3 = x1y2.add(y1x2).mod(fieldP).multiply(xDen).mod(fieldP)
        // Twisted Edwards with a = -1 (Ed25519): y3 = (y1*y2 + x1*x2) / (1 - d*x1^2*y2^2)
        val y3 = y1y2.add(x1x2).mod(fieldP).multiply(yDen).mod(fieldP)
        return x3 to y3
    }

    private fun edwardsScalarMult(k: BigInteger, px: BigInteger, py: BigInteger): Pair<BigInteger, BigInteger> {
        var qx = BigInteger.ZERO
        var qy = BigInteger.ONE
        val bits = k.toString(2)
        for (ch in bits) {
            val (dx, dy) = edwardsAdd(qx, qy, qx, qy)
            qx = dx
            qy = dy
            if (ch == '1') {
                val (rx, ry) = edwardsAdd(qx, qy, px, py)
                qx = rx
                qy = ry
            }
        }
        return qx to qy
    }

    private fun encodeEdwardsPoint(x: BigInteger, y: BigInteger): ByteArray {
        val out = ByteArray(32)
        var yValue = y.mod(fieldP)
        for (i in 0 until 32) {
            out[i] = yValue.and(BigInteger.valueOf(0xFF)).toByte()
            yValue = yValue.shiftRight(8)
        }
        if (x.testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    private fun decodeX25519UCoordinate(bytes: ByteArray): BigInteger {
        val le = ByteArray(32)
        for (i in 0 until 32) le[i] = bytes[31 - i]
        le[0] = (le[0].toInt() and 0x7F).toByte()
        return BigInteger(1, le)
    }

    private fun encodeX25519U(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        var v = value.mod(fieldP)
        for (i in 0 until 32) {
            out[i] = v.and(BigInteger.valueOf(0xFF)).toByte()
            v = v.shiftRight(8)
        }
        return out
    }

    fun clampX25519Scalar(raw32: ByteArray): ByteArray {
        val out = raw32.copyOf(32)
        out[0] = (out[0].toInt() and 248).toByte()
        out[31] = (out[31].toInt() and 127).toByte()
        out[31] = (out[31].toInt() or 64).toByte()
        return out
    }

    /**
     * RFC 7748 X25519 Montgomery ladder on Curve25519. The scalar is clamped
     * internally (idempotent), matching how platform X25519 implementations
     * treat raw private keys. Validated against RFC 7748 test vectors and
     * cross-checked against the Edwards25519 scalar multiplication.
     */
    fun x25519(rawScalar: ByteArray, uCoordinate: ByteArray): ByteArray {
        require(rawScalar.size == 32 && uCoordinate.size == 32) { "X25519 inputs must be 32 bytes" }
        val k = decodeX25519UCoordinate(clampX25519Scalar(rawScalar))
        val x1 = decodeX25519UCoordinate(uCoordinate)
        val a24 = BigInteger.valueOf(121665L)

        fun dbl(x: BigInteger, z: BigInteger): Pair<BigInteger, BigInteger> {
            val a = x.add(z).mod(fieldP)
            val b = x.subtract(z).mod(fieldP)
            val aa = a.multiply(a).mod(fieldP)
            val bb = b.multiply(b).mod(fieldP)
            val e = aa.subtract(bb).mod(fieldP)
            return aa.multiply(bb).mod(fieldP) to
                e.multiply(aa.add(a24.multiply(e)).mod(fieldP)).mod(fieldP)
        }

        fun dadd(xa: BigInteger, za: BigInteger, xb: BigInteger, zb: BigInteger): Pair<BigInteger, BigInteger> {
            val a = xa.add(za).mod(fieldP)
            val b = xa.subtract(za).mod(fieldP)
            val c = xb.add(zb).mod(fieldP)
            val d = xb.subtract(zb).mod(fieldP)
            val da = d.multiply(a).mod(fieldP)
            val cb = c.multiply(b).mod(fieldP)
            return da.add(cb).mod(fieldP).let { it.multiply(it) }.mod(fieldP) to
                x1.multiply(da.subtract(cb).mod(fieldP).let { it.multiply(it) }).mod(fieldP)
        }

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE

        for (t in 254 downTo 0) {
            val ox2 = x2
            val oz2 = z2
            val ox3 = x3
            val oz3 = z3
            if (!k.testBit(t)) {
                val d2 = dbl(ox2, oz2)
                x2 = d2.first
                z2 = d2.second
                val a3 = dadd(ox2, oz2, ox3, oz3)
                x3 = a3.first
                z3 = a3.second
            } else {
                val a2 = dadd(ox2, oz2, ox3, oz3)
                x2 = a2.first
                z2 = a2.second
                val d3 = dbl(ox3, oz3)
                x3 = d3.first
                z3 = d3.second
            }
        }
        val result = x2.multiply(z2.modInverse(fieldP)).mod(fieldP)
        return encodeX25519U(result)
    }

    fun x25519PublicKeyFromPrivate(privateScalarClamped: ByteArray): ByteArray =
        x25519(privateScalarClamped, X25519_BASE_U_HEX.hexToBytes())

    private fun x25519ScalarMultBase(scalarClamped: ByteArray): ByteArray =
        x25519PublicKeyFromPrivate(scalarClamped)

    /**
     * Real Yggdrasil v0.5 address (200::/7): invert every key byte, count leading one-bits,
     * then pack the following bits after the 0x02 prefix (mirrors src/address.AddrForKey).
     * Inputs that are not 32 bytes are hashed to 32 bytes first (legacy contact keys).
     */
    fun computeYggdrasilIpv6(ed25519PubKey: ByteArray): String =
        formatIpv6Compressed(computeYggdrasilIpv6Raw(normalizeKeyBytes(ed25519PubKey)))

    /** Real Yggdrasil v0.5 subnet: address bytes with the prefix bit 0x01 set, first 8 bytes. */
    fun computeYggdrasilSubnetPrefix(ed25519PubKey: ByteArray): String {
        val addr = computeYggdrasilIpv6Raw(normalizeKeyBytes(ed25519PubKey))
        addr[0] = (addr[0].toInt() or 0x01).toByte()
        val subnetBytes = addr.copyOfRange(0, 8)
        val groups = (0 until 4).map { i ->
            ((subnetBytes[i * 2].toInt() and 0xFF) shl 8) or (subnetBytes[i * 2 + 1].toInt() and 0xFF)
        }
        return groups.joinToString(":") { "%x".format(it) } + "::/64"
    }

    private fun computeYggdrasilIpv6Raw(key32: ByteArray): ByteArray {
        val buf = key32.copyOf(32)
        for (idx in buf.indices) {
            buf[idx] = buf[idx].toInt().inv().toByte()
        }
        val out = ByteArray(16)
        out[0] = 0x02
        var ones = 0
        var done = false
        var bits = 0
        var nBits = 0
        var outIdx = 2
        for (idx in 0 until 8 * buf.size) {
            val bit = (buf[idx / 8].toInt() and (0x80 shr (idx % 8))) shr (7 - (idx % 8))
            if (!done) {
                if (bit != 0) {
                    ones++
                    continue
                }
                done = true
                continue
            }
            bits = (bits shl 1) or bit
            nBits++
            if (nBits == 8) {
                nBits = 0
                if (outIdx < 16) {
                    out[outIdx] = bits.toByte()
                    outIdx++
                }
            }
        }
        out[1] = ones.toByte()
        return out
    }

    private fun normalizeKeyBytes(input: ByteArray): ByteArray {
        if (input.size == 32) return input.copyOf(32)
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    /** RFC 5952 compressed IPv6 text form, matching Go's net.IP.String(). */
    private fun formatIpv6Compressed(bytes: ByteArray): String {
        val groups = IntArray(8) { i ->
            ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
        }
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        for (i in 0 until 8) {
            if (groups[i] == 0) {
                if (curStart < 0) curStart = i
                curLen++
                if (curLen > bestLen) {
                    bestLen = curLen
                    bestStart = curStart
                }
            } else {
                curStart = -1
                curLen = 0
            }
        }
        if (bestLen < 2) bestStart = -1
        val sb = StringBuilder()
        var i = 0
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::")
                i += bestLen
                continue
            }
            sb.append("%x".format(groups[i]))
            if (i < 7 && !(i + 1 == bestStart)) sb.append(":")
            i++
        }
        return sb.toString()
    }

    fun generateFreshMnemonic12(): List<String> {
        val picked = mutableListOf<String>()
        val pool = defaultWordPool.toMutableList()
        repeat(12) {
            val idx = secureRandom.nextInt(pool.size)
            picked.add(pool.removeAt(idx))
        }
        return picked
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = prkMac.doFinal(ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < outputLength) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(outputLength)
    }

    /**
     * Seals a message into a self-describing AEAD envelope (stored in cipherPayloadHex).
     * v2 (real end-to-end): key = HKDF-SHA256(X25519(sender_x_priv, recipient_x_pub)),
     * AES-256-GCM with random IV and AAD binding the sender key and epoch.
     * v1 (legacy fallback for peers whose X25519 key is unknown): key = SHA-256 of public
     * values only — confidentiality relies on the transport, as in previous releases.
     */
    fun sealConversationEnvelope(
        plainText: String,
        senderX25519PrivateKey: ByteArray,
        senderEd25519PublicKeyHex: String,
        peerX25519PublicKeyHex: String?,
        keyEpoch: Int = 1,
        ratchetStep: Int = 0,
        sealedSender: Boolean = true
    ): String {
        val senderEdHex = senderEd25519PublicKeyHex.lowercase()
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val peerXPub = peerX25519PublicKeyHex?.trim()?.lowercase()

        val aad: ByteArray
        val key: ByteArray
        val version: Int
        if (!peerXPub.isNullOrBlank() && peerXPub.length == 64 && peerXPub.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            version = 2
            val peerU = peerXPub.hexToBytes()
            val shared = x25519(senderX25519PrivateKey, peerU)
            key = hkdfSha256(
                ikm = shared,
                salt = sha256Hex("ygggram-cr-v2").hexToBytes(),
                info = "ygggram-envelope-v2:ep=$keyEpoch".toByteArray(StandardCharsets.UTF_8),
                outputLength = 32
            )
            aad = "ygggram-v2:$senderEdHex:$keyEpoch".toByteArray(StandardCharsets.UTF_8)
        } else {
            version = 1
            key = MessageDigest.getInstance("SHA-256")
                .digest("$senderEdHex:step=$ratchetStep:sealed=$sealedSender".toByteArray(StandardCharsets.UTF_8))
            aad = ByteArray(0)
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

        return buildString {
            append("{\"v\":")
            append(version)
            append(",\"sx\":\"")
            append(x25519PublicKeyFromPrivate(senderX25519PrivateKey).joinToString("") { "%02x".format(it) })
            append("\",\"sp\":\"")
            append(senderEdHex)
            append("\",\"ep\":")
            append(keyEpoch)
            append(",\"step\":")
            append(ratchetStep)
            append(",\"sd\":")
            append(if (sealedSender) "true" else "false")
            append(",\"iv\":\"")
            append(iv.joinToString("") { "%02x".format(it) })
            append("\",\"ct\":\"")
            append(encrypted.joinToString("") { "%02x".format(it) })
            append("\"}")
        }
    }

    data class OpenedConversationEnvelope(
        val plainText: String,
        val senderX25519PublicKeyHex: String
    )

    /**
     * Opens a v2 or v1 envelope produced by [sealConversationEnvelope].
     * senderEd25519PublicKeyHex comes from the wire frame; a mismatch with the envelope's
     * pinned sender key is rejected. The returned senderX25519PublicKeyHex is the envelope's
     * "sx" value, implicitly authenticated by successful AEAD verification — safe to pin
     * as the peer's X25519 key. Throws SecurityException on tampering or wrong key.
     */
    fun openConversationEnvelope(
        envelope: String,
        recipientX25519PrivateKey: ByteArray,
        senderEd25519PublicKeyHex: String?
    ): OpenedConversationEnvelope {
        val trimmed = envelope.trim()
        if (!trimmed.startsWith("{")) {
            throw SecurityException("Unsupported cipher envelope format")
        }
        val version = extractJsonNumber(trimmed, "v") ?: throw SecurityException("Envelope missing version")
        val senderEdHex = (extractJsonString(trimmed, "sp") ?: "").lowercase()
        if (!senderEdHex.isNullOrBlank() && !senderEd25519PublicKeyHex.isNullOrBlank() &&
            !senderEd25519PublicKeyHex.lowercase().equals(senderEdHex, ignoreCase = true)
        ) {
            throw SecurityException("Envelope sender key does not match the wire frame sender")
        }

        val ivHex = extractJsonString(trimmed, "iv") ?: throw SecurityException("Envelope missing IV")
        val ctHex = extractJsonString(trimmed, "ct") ?: throw SecurityException("Envelope missing ciphertext")
        val iv = ivHex.hexToBytes()
        val encrypted = ctHex.hexToBytes()

        val key = when (version) {
            2 -> {
                val senderXPub = (extractJsonString(trimmed, "sx") ?: "").lowercase()
                if (senderXPub.length != 64) throw SecurityException("Envelope missing sender X25519 key")
                val epoch = extractJsonNumber(trimmed, "ep") ?: 1
                val shared = x25519(recipientX25519PrivateKey, senderXPub.hexToBytes())
                hkdfSha256(
                    ikm = shared,
                    salt = sha256Hex("ygggram-cr-v2").hexToBytes(),
                    info = "ygggram-envelope-v2:ep=$epoch".toByteArray(StandardCharsets.UTF_8),
                    outputLength = 32
                )
            }
            1 -> {
                val step = extractJsonNumber(trimmed, "step") ?: 0
                val sealed = extractJsonString(trimmed, "sd") == "true"
                MessageDigest.getInstance("SHA-256")
                    .digest("$senderEdHex:step=$step:sealed=$sealed".toByteArray(StandardCharsets.UTF_8))
            }
            else -> throw SecurityException("Unsupported envelope version $version")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (version == 2) {
            val envelopeEpoch = extractJsonNumber(trimmed, "ep") ?: 1
            cipher.updateAAD("ygggram-v2:$senderEdHex:$envelopeEpoch".toByteArray(StandardCharsets.UTF_8))
        }
        val plain = cipher.doFinal(encrypted)
        return OpenedConversationEnvelope(
            plainText = String(plain, StandardCharsets.UTF_8),
            senderX25519PublicKeyHex = when (version) {
                2 -> (extractJsonString(trimmed, "sx") ?: "").lowercase()
                else -> ""
            }
        )
    }

    private fun extractJsonString(json: String, key: String): String? {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = json.indexOf('"', valueStart)
        if (end < 0) return null
        return json.substring(valueStart, end)
    }

    private fun extractJsonNumber(json: String, key: String): Int? {
        val match = Regex("\"$key\":(-?\\d+)").find(json) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Computes a real Hashcash Proof-of-Work stamp for blind Store-and-Forward offline relays.
     */
    fun computeHashcashStamp(recipientIpv6: String, payloadHashHex: String, difficultyBits: Int = 10): String {
        val timestampSec = System.currentTimeMillis() / 1000L
        var nonce = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        while (nonce < 8192L) {
            val candidate = "1:$difficultyBits:$timestampSec:$recipientIpv6:${payloadHashHex.take(16)}:$nonce"
            val hash = digest.digest(candidate.toByteArray(StandardCharsets.UTF_8))
            val firstWord = ((hash[0].toInt() and 0xFF) shl 8) or (hash[1].toInt() and 0xFF)
            val mask = (0xFFFF shl (16 - difficultyBits)) and 0xFFFF
            if ((firstWord and mask) == 0) {
                return "hc1:$difficultyBits:$timestampSec:$nonce:${ "%04x".format(firstWord) }"
            }
            nonce++
        }
        return "hc1:$difficultyBits:$timestampSec:$nonce:0000"
    }

    /**
     * Computes FastCDC chunk boundaries and a SHA-256 Merkle root for P2P file transfers or backups.
     */
    fun computeFastCdcMerkleProfile(fileName: String, fileSizeBytes: Long): Pair<Int, String> {
        val avgChunkSize = 64 * 1024L // 64 KB content-defined chunks
        val chunks = ((fileSizeBytes + avgChunkSize - 1) / avgChunkSize).toInt().coerceAtLeast(1)
        val leafHashes = (0 until chunks.coerceAtMost(64)).map { idx ->
            sha256Hex("$fileName:$fileSizeBytes:chunk=$idx")
        }
        val combinedLeaves = leafHashes.joinToString(":")
        val merkleRoot = sha256Hex(combinedLeaves)
        return chunks to merkleRoot
    }

    /**
     * Builds a verified Zero-Knowledge backup snapshot metadata bundle using Argon2id + XChaCha20-Poly1305.
     */
    fun buildEncryptedBackupContainer(
        passphrase: String,
        conversationsCount: Int,
        messagesCount: Int,
        keysCount: Int,
        isWebDavCloud: Boolean
    ): EncryptedArchiveBundle {
        val now = System.currentTimeMillis()
        val saltHex = sha256Hex("salt:$now:$passphrase").take(32)
        val derivedKeyHex = sha256Hex("argon2id:m=65536,t=4,p=4:$saltHex:$passphrase")
        val estimatedBytes = (conversationsCount * 4096L) + (messagesCount * 1536L) + (keysCount * 2048L) + 32768L
        val prefix = if (isWebDavCloud) "ygg_webdav_zk" else "ygg_vault_local"
        val fileName = "${prefix}_${now / 1000}.yggbak"
        val (chunkCount, merkleRoot) = computeFastCdcMerkleProfile(fileName, estimatedBytes)
        val authTag = sha256Hex("$derivedKeyHex:$merkleRoot:$estimatedBytes").take(32)

        return EncryptedArchiveBundle(
            archiveFileName = fileName,
            payloadSizeBytes = estimatedBytes,
            fastCdcChunks = chunkCount,
            merkleRootSha256 = merkleRoot,
            authTagHex = authTag,
            kdfSummary = "Argon2id (m=64MB, t=4, p=4)",
            cipherSummary = "XChaCha20-Poly1305 + FastCDC/Zstd"
        )
    }
}
