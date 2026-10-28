// File: app/src/main/java/com/example/engine/YggCryptoEngine.kt
package com.example.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
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
    // RFC 7748 base point u=9 as 32 little-endian bytes.
    private const val X25519_BASE_U_HEX =
        "0900000000000000000000000000000000000000000000000000000000000000"

    private val hierarchyCache = object : LinkedHashMap<String, DerivedIdentityHierarchy>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DerivedIdentityHierarchy>): Boolean = size > 8
    }

    fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(text: String): String =
        sha256Hex(text.toByteArray(StandardCharsets.UTF_8))

    /**
     * Human-comparable fingerprint of an Ed25519 node key: 12 groups of 5 digits,
     * like Signal's safety number. Both devices derive the same value from the same
     * public key, so reading it aloud or scanning a QR proves the keys match.
     * Returns "" when the key is not a usable 32-byte hex.
     */
    fun safetyNumber(nodePublicKeyHex: String): String {
        val normalized = nodePublicKeyHex.trim().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        if (normalized.length != 64) return ""
        val digestHex = sha256Hex("ygggram-safety-number:v1:" + normalized)
        return (0 until 12).joinToString(" ") { group ->
            digestHex.substring(group * 5, group * 5 + 5).toInt(16)
                .rem(100_000).toString().padStart(5, '0')
        }
    }

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

    fun isEd25519Hex(value: String): Boolean {
        val clean = value.trim()
        return clean.length == 64 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /**
     * Canonical text form of an IPv6 literal, or null when [value] is not one.
     * Requires at least two colons so "host:port" is never treated as an address.
     */
    fun normalizeIpv6Literal(value: String): String? {
        val literal = value.trim().removeSurrounding("[", "]").substringBefore("%")
        if (literal.count { it == ':' } < 2 || literal.contains("/")) return null
        return try {
            val parsed = java.net.InetAddress.getByName(literal)
            if (parsed is java.net.Inet6Address) parsed.hostAddress?.substringBefore("%")?.lowercase() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A Yggdrasil address is derived from the node's Ed25519 key, so a key that does
     * not reproduce the stored address cannot send anything through the overlay.
     */
    fun addressMatchesEd25519Key(address: String, keyHex: String): Boolean {
        val key = keyHex.trim().lowercase()
        if (!isEd25519Hex(key)) return false
        val expected = normalizeIpv6Literal(address) ?: return false
        val keyBytes = key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val derived = normalizeIpv6Literal(computeYggdrasilIpv6(keyBytes)) ?: return false
        return expected == derived
    }

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

    fun isUsablePeerX25519PublicKey(hex: String?): Boolean {
        val value = hex?.trim()?.lowercase() ?: return false
        return value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }

    /**
     * A "delete for everyone" frame asks another device to erase a message, so it has to prove
     * the requester wrote that message. The proof is an ordinary conversation envelope whose
     * plaintext binds the delete event to one message id; opening it under the recipient's own
     * key authenticates the sender's X25519 key exactly the way a message envelope does.
     */
    const val DELETE_PROOF_PREFIX = "ygggram-del-v1"

    data class DeleteProof(val deleteId: String, val wireMessageId: String)

    fun buildDeleteProofText(deleteId: String, wireMessageId: String): String =
        "$DELETE_PROOF_PREFIX|$deleteId|$wireMessageId"

    /** Null unless the text is a well-formed proof; a message body is never mistaken for one. */
    fun parseDeleteProofText(plainText: String?): DeleteProof? {
        val parts = plainText?.split('|') ?: return null
        if (parts.size != 3 || parts[0] != DELETE_PROOF_PREFIX) return null
        val deleteId = parts[1].trim()
        val wireMessageId = parts[2].trim()
        if (deleteId.isEmpty() || wireMessageId.isEmpty()) return null
        return DeleteProof(deleteId, wireMessageId)
    }

    /**
     * Seals a message into a self-describing AEAD envelope (stored in cipherPayloadHex):
     * key = HKDF-SHA256(X25519(sender_x_priv, recipient_x_pub)), AES-256-GCM with a random
     * IV and AAD binding the sender key and epoch.
     * Returns null when the peer X25519 key is unknown: without it there is no shared secret,
     * and any key derived from public values alone would be readable by anyone who records
     * the traffic, so the message must not be sent at all.
     */
    fun sealConversationEnvelope(
        plainText: String,
        senderX25519PrivateKey: ByteArray,
        senderEd25519PublicKeyHex: String,
        peerX25519PublicKeyHex: String?,
        keyEpoch: Int = 1,
        ratchetStep: Int = 0,
        sealedSender: Boolean = true
    ): String? {
        val senderEdHex = senderEd25519PublicKeyHex.lowercase()
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val peerXPub = peerX25519PublicKeyHex?.trim()?.lowercase() ?: return null
        if (!isUsablePeerX25519PublicKey(peerXPub)) return null

        val aad = "ygggram-v2:$senderEdHex:$keyEpoch".toByteArray(StandardCharsets.UTF_8)
        val key = run {
            val shared = x25519(senderX25519PrivateKey, peerXPub.hexToBytes())
            hkdfSha256(
                ikm = shared,
                salt = sha256Hex("ygggram-cr-v2").hexToBytes(),
                info = "ygggram-envelope-v2:ep=$keyEpoch".toByteArray(StandardCharsets.UTF_8),
                outputLength = 32
            )
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

        return buildString {
            append("{\"v\":")
            append(2)
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
        val senderX25519PublicKeyHex: String,
        val version: Int
    )

    /**
     * Opens an envelope produced by [sealConversationEnvelope].
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
        val fields = EnvelopeFields(trimmed)
        val version = fields.int("v") ?: throw SecurityException("Envelope missing version")
        val senderEdHex = fields.string("sp").orEmpty().lowercase()
        if (senderEdHex.isNotBlank() && !senderEd25519PublicKeyHex.isNullOrBlank() &&
            !senderEd25519PublicKeyHex.lowercase().equals(senderEdHex, ignoreCase = true)
        ) {
            throw SecurityException("Envelope sender key does not match the wire frame sender")
        }

        val ivHex = fields.string("iv") ?: throw SecurityException("Envelope missing IV")
        val ctHex = fields.string("ct") ?: throw SecurityException("Envelope missing ciphertext")
        val iv = ivHex.hexToBytes()
        val encrypted = ctHex.hexToBytes()

        if (version != 2) {
            throw SecurityException("Envelope version $version is not accepted: it is keyed from public values only")
        }
        val senderXPub = fields.string("sx").orEmpty().lowercase()
        if (senderXPub.length != 64) throw SecurityException("Envelope missing sender X25519 key")
        val envelopeEpoch = fields.int("ep") ?: 1
        val shared = x25519(recipientX25519PrivateKey, senderXPub.hexToBytes())
        val key = hkdfSha256(
            ikm = shared,
            salt = sha256Hex("ygggram-cr-v2").hexToBytes(),
            info = "ygggram-envelope-v2:ep=$envelopeEpoch".toByteArray(StandardCharsets.UTF_8),
            outputLength = 32
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("ygggram-v2:$senderEdHex:$envelopeEpoch".toByteArray(StandardCharsets.UTF_8))
        val plain = cipher.doFinal(encrypted)
        return OpenedConversationEnvelope(
            plainText = String(plain, StandardCharsets.UTF_8),
            senderX25519PublicKeyHex = senderXPub,
            version = version
        )
    }

    // ── Voice-call media encryption ──────────────────────────────────────────
    // One AES-256-GCM key per call: the X25519 identity DH shared with the peer,
    // bound to the call id through HKDF so captured audio frames cannot be replayed
    // into a different call.

    fun deriveCallMediaKeyFromMnemonic(
        mnemonic: List<String>,
        peerX25519PublicKeyHex: String,
        callId: String
    ): ByteArray = deriveCallMediaKey(
        x25519PrivateKeyFromMnemonic(mnemonic),
        peerX25519PublicKeyHex,
        callId
    )

    fun deriveCallMediaKey(
        localX25519PrivateKey: ByteArray,
        peerX25519PublicKeyHex: String,
        callId: String
    ): ByteArray {
        val peer = peerX25519PublicKeyHex.trim().lowercase()
        require(peer.length == 64 && peer.all { it.isDigit() || it in 'a'..'f' }) {
            "Peer X25519 key must be 32 bytes hex"
        }
        val shared = x25519(localX25519PrivateKey, peer.hexToBytes())
        return hkdfSha256(
            ikm = shared,
            salt = sha256Hex("ygggram-call-v1").hexToBytes(),
            info = "ygggram-call-media-v1:$callId".toByteArray(StandardCharsets.UTF_8),
            outputLength = 32
        )
    }

    /**
     * Returns (ivBase64, cipherBase64) for one PCM frame of the call media stream.
     * Base64 is used instead of hex so the wire frame stays ~25% smaller.
     * The frame number is part of the authenticated data: without it a captured frame
     * could be replayed or reordered inside the same call and still decrypt.
     */
    fun sealCallMediaFrame(key: ByteArray, pcm: ByteArray, callId: String, seq: Long): Pair<String, String> {
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(callFrameAad(callId, seq))
        val encrypted = cipher.doFinal(pcm)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString(iv) to encoder.encodeToString(encrypted)
    }

    fun openCallMediaFrame(
        key: ByteArray,
        ivBase64: String,
        cipherBase64: String,
        callId: String,
        seq: Long
    ): ByteArray {
        val decoder = Base64.getUrlDecoder()
        val iv = decoder.decode(ivBase64)
        val encrypted = decoder.decode(cipherBase64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(callFrameAad(callId, seq))
        return cipher.doFinal(encrypted)
    }

    private fun callFrameAad(callId: String, seq: Long): ByteArray =
        "ygggram-call:$callId:$seq".toByteArray(StandardCharsets.UTF_8)

    // ── Large-file piece transport ───────────────────────────────────────────
    // A file above the inline ceiling travels piece by piece. One AES-256-GCM key
    // per transfer: the same X25519 identity DH as a message envelope, stretched
    // with HKDF over the transfer id. Every piece is then its own AEAD message with
    // its index in the AAD, which is what makes the stream content-addressed the
    // way a torrent piece is: a piece either authenticates or is dropped, so the
    // receiver can write it at its own offset and the sender can resume at the
    // first piece that is still missing. The Merkle root over the real piece
    // digests is checked once at the end and covers the whole file.

    /** 12 KiB of plaintext stays under one Yggdrasil datagram after base64 + envelope. */
    const val FILE_PIECE_BYTES = 12 * 1024

    /** The largest file the piece transport will carry; anything bigger is refused. */
    const val MAX_P2P_STREAM_BYTES = 512L * 1024 * 1024

    fun deriveFileTransferKey(
        localX25519PrivateKey: ByteArray,
        peerX25519PublicKeyHex: String,
        transferId: String
    ): ByteArray {
        val peer = peerX25519PublicKeyHex.trim().lowercase()
        require(isUsablePeerX25519PublicKey(peer)) { "Peer X25519 key must be 32 bytes hex" }
        val shared = x25519(localX25519PrivateKey, peer.hexToBytes())
        return hkdfSha256(
            ikm = shared,
            salt = sha256Hex("ygggram-file-v1").hexToBytes(),
            info = "ygggram-file-transfer-v1:$transferId".toByteArray(StandardCharsets.UTF_8),
            outputLength = 32
        )
    }

    private fun filePieceAad(transferId: String, index: Int): ByteArray =
        "ygggram-file:$transferId:piece=$index".toByteArray(StandardCharsets.UTF_8)

    /** Returns (ivBase64, cipherBase64) for one piece; the index is authenticated. */
    fun sealFilePiece(key: ByteArray, plain: ByteArray, transferId: String, index: Int): Pair<String, String> {
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(filePieceAad(transferId, index))
        val encrypted = cipher.doFinal(plain)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString(iv) to encoder.encodeToString(encrypted)
    }

    /** Throws on a tampered piece or one replayed under a different index. */
    fun openFilePiece(
        key: ByteArray,
        ivBase64: String,
        cipherBase64: String,
        transferId: String,
        index: Int
    ): ByteArray {
        val decoder = Base64.getUrlDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, decoder.decode(ivBase64))
        )
        cipher.updateAAD(filePieceAad(transferId, index))
        return cipher.doFinal(decoder.decode(cipherBase64))
    }

    data class PieceProfile(val pieceCount: Int, val totalBytes: Long, val merkleRootHex: String)

    /** Real SHA-256 Merkle root over the file's own piece digests. Null if the file is unreadable. */
    fun pieceProfileOf(file: File, pieceBytes: Int = FILE_PIECE_BYTES): PieceProfile? {
        if (!file.exists() || !file.isFile) return null
        return FileInputStream(file).use { pieceProfileOfStream(it, pieceBytes) }
    }

    /**
     * Same profile over a stream, for bytes that only exist behind a content Uri or a socket.
     * A short final read is the end of the stream, not a truncated piece.
     */
    fun pieceProfileOfStream(source: InputStream, pieceBytes: Int = FILE_PIECE_BYTES): PieceProfile {
        require(pieceBytes > 0) { "Piece size must be positive" }
        val digests = mutableListOf<ByteArray>()
        val buffer = ByteArray(pieceBytes)
        var bytes = 0L
        while (true) {
            var filled = 0
            while (filled < pieceBytes) {
                val read = source.read(buffer, filled, pieceBytes - filled)
                if (read < 0) break
                filled += read
            }
            if (filled == 0) break
            digests += sha256Bytes(buffer.copyOf(filled))
            bytes += filled
            if (filled < pieceBytes) break
        }
        return PieceProfile(
            pieceCount = digests.size,
            totalBytes = bytes,
            merkleRootHex = merkleRootOfPieceDigests(digests)
        )
    }

    fun sha256Bytes(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Binary tree over the piece digests: each parent is SHA-256(left||right), an odd node is
     * carried up unchanged. An empty list hashes the empty input, so a zero-piece file still has
     * a root both ends reproduce.
     */
    fun merkleRootOfPieceDigests(digests: List<ByteArray>): String {
        var level = digests
        if (level.isEmpty()) return sha256Bytes(ByteArray(0)).joinToString("") { "%02x".format(it) }
        while (level.size > 1) {
            val next = ArrayList<ByteArray>((level.size + 1) / 2)
            var i = 0
            while (i < level.size) {
                val left = level[i]
                val right = level.getOrNull(i + 1)
                next += if (right == null) left else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(left)
                    digest.update(right)
                    digest.digest()
                }
                i += 2
            }
            level = next
        }
        return level[0].joinToString("") { "%02x".format(it) }
    }

    /**
     * Strict reader for the flat JSON object used by conversation envelopes. A regex
     * lookup could match a key inside another value (e.g. plaintext echoed into a
     * field) or silently accept malformed JSON; this parser validates the whole
     * document and rejects anything outside the envelope schema.
     */
    private class EnvelopeFields(json: String) {
        private val values: Map<String, Any?> = EnvelopeJsonParser(json).parse()

        fun string(key: String): String? = values[key] as? String
        fun int(key: String): Int? = (values[key] as? Long)?.toInt()
        fun boolean(key: String): Boolean? = values[key] as? Boolean
    }

    private class EnvelopeJsonParser(private val src: String) {
        private var pos = 0

        fun parse(): Map<String, Any?> {
            skipWhitespace()
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                skipWhitespace()
                requireEnd()
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        break
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
            skipWhitespace()
            requireEnd()
            return result
        }

        private fun readValue(): Any? = when (val c = peek()) {
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            '-', in '0'..'9' -> readNumber()
            else -> fail("Unsupported value")
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= src.length) fail("Unterminated string")
                when (val c = src[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= src.length) fail("Unterminated escape")
                        when (val escape = src[pos++]) {
                            '"', '\\', '/' -> out.append(escape)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) fail("Bad unicode escape")
                                val code = src.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: fail("Bad unicode escape")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Invalid escape")
                        }
                    }
                    else -> {
                        if (c < ' ') fail("Control character in string")
                        out.append(c)
                    }
                }
            }
        }

        private fun readNumber(): Long {
            val start = pos
            if (peek() == '-') pos++
            val digitsStart = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            if (pos == digitsStart) fail("Malformed number")
            val text = src.substring(start, pos)
            if (pos < src.length && (src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E')) {
                fail("Envelope numbers must be integers")
            }
            return text.toLongOrNull() ?: fail("Malformed number")
        }

        private fun readLiteral(text: String, value: Any?): Any? {
            if (!src.startsWith(text, pos)) fail("Malformed literal")
            pos += text.length
            return value
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else fail("Unexpected end")

        private fun expect(expected: Char) {
            if (peek() != expected) fail("Expected '$expected'")
            pos++
        }

        private fun requireEnd() {
            if (pos != src.length) fail("Trailing data")
        }

        private fun fail(message: String): Nothing =
            throw SecurityException("$message at offset $pos in envelope")
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
}
