// File: app/src/test/java/com/example/data/YggEnvelopeV3Test.kt
package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggCryptoEngine
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val aliceWords = listOf(
    "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
    "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
)
private val bobWords = listOf(
    "glacier", "helium", "cascade", "nebula", "cobalt", "lattice",
    "photon", "quartz", "onyx", "harbor", "vertex", "cedar"
)
private val carolWords = listOf(
    "tulip", "lantern", "cobweb", "marble", "quiver", "fathom",
    "harvest", "pinnacle", "tundra", "cinder", "beetle", "willow"
)

private fun identity(words: List<String>) = YggCryptoEngine.deriveHierarchyFromMnemonic(words, epoch = 1)

private fun seal(
    sender: List<String>,
    senderEdHex: String,
    recipientXPubHex: String,
    plain: String,
    version: Int,
    epoch: Int = 1
): String {
    val envelope = YggCryptoEngine.sealConversationEnvelope(
        plainText = plain,
        senderX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(sender),
        senderEd25519PublicKeyHex = senderEdHex,
        peerX25519PublicKeyHex = recipientXPubHex,
        keyEpoch = epoch,
        envelopeVersion = version
    )
    return requireNotNull(envelope) { "v$version seal must succeed for a usable peer key" }
}

/** Pulls one string field out of an envelope the way the wire reader does. */
private fun field(envelope: String, key: String): String {
    val marker = "\"$key\":\""
    val start = envelope.indexOf(marker)
    require(start >= 0) { "envelope has no $key" }
    val from = start + marker.length
    return envelope.substring(from, envelope.indexOf('"', from))
}

/**
 * What v3 is supposed to buy, stated as bytes rather than as an intention.
 *
 * These tests run on the plain JVM: no Android, no Room. The second reader inside them derives
 * the key from scratch (RFC 5869 HKDF over the two X25519 shared secrets, AES-256-GCM from
 * `javax.crypto`), so a green run says a stranger's implementation can open this app's v3
 * frames — and that swapping any of the three public values folded into the key closes them.
 */
class YggEnvelopeV3FormatTest {

    private val alice = identity(aliceWords)
    private val bob = identity(bobWords)

    @Test
    fun `v3 frame opens under the recipients key and names a per-message point`() {
        val plain = "конверт v3: 🔐 текст, эмодзи и \\\\ экранирования"
        val first = seal(aliceWords, alice.yggNodePublicKeyHex, bob.x25519IdentityPubHex, plain, version = 3)
        val second = seal(aliceWords, alice.yggNodePublicKeyHex, bob.x25519IdentityPubHex, plain, version = 3)

        val opened = YggCryptoEngine.openConversationEnvelope(
            envelope = first,
            recipientX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords),
            senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex
        )
        assertEquals(plain, opened.plainText)
        assertEquals(3, opened.version)
        // The pinned sender key stays the long-term identity: the one-off point must never be
        // written into the contact record, or every message would look like a new peer.
        assertEquals(alice.x25519IdentityPubHex, opened.senderX25519PublicKeyHex)
        assertNotEquals(alice.x25519IdentityPubHex, field(first, "es"))

        // Two messages, two fresh ephemeral points — this is the property the version exists for.
        assertNotEquals("каждое сообщение получает свой эфемерный ключ", field(first, "es"), field(second, "es"))
        assertEquals(field(first, "sx"), field(second, "sx"))
        assertEquals(12, field(first, "iv").length / 2)
        assertEquals(
            "ct = UTF-8 текст + 16-байтный тег GCM",
            plain.toByteArray(StandardCharsets.UTF_8).size + 16,
            field(first, "ct").length / 2
        )
        assertTrue(YggCryptoEngine.envelopeVersionOf(first) == 3)
    }

    @Test
    fun `an independent reader with only the standard primitives recovers the text`() {
        val plain = "независимый читатель v3"
        val envelope = seal(aliceWords, alice.yggNodePublicKeyHex, bob.x25519IdentityPubHex, plain, version = 3)
        val ephemeral = field(envelope, "es")
        val senderStatic = field(envelope, "sx")
        val senderEd = field(envelope, "sp")
        val epoch = 1

        val bobPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords)
        // The same order the sealer uses: ephemeral half, then the identity half.
        val ikm = YggCryptoEngine.x25519(bobPriv, hexToBytes(ephemeral)) +
            YggCryptoEngine.x25519(bobPriv, hexToBytes(senderStatic))
        val key = hkdfSha256(
            ikm = ikm,
            salt = sha256("ygggram-cr-v3"),
            info = "ygggram-envelope-v3:ep=$epoch".toByteArray(StandardCharsets.UTF_8),
            length = 32
        )
        val recovered = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, hexToBytes(field(envelope, "iv"))))
            updateAAD("ygggram-v3:$senderEd:$ephemeral:$epoch".toByteArray(StandardCharsets.UTF_8))
            String(doFinal(hexToBytes(field(envelope, "ct"))), StandardCharsets.UTF_8)
        }
        assertEquals(plain, recovered)
    }

    @Test
    fun `every public value in the frame is bound into the key`() {
        val recipientPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords)
        val a = seal(aliceWords, alice.yggNodePublicKeyHex, bob.x25519IdentityPubHex, "первый", version = 3)
        val b = seal(aliceWords, alice.yggNodePublicKeyHex, bob.x25519IdentityPubHex, "второй", version = 3)

        // The ephemeral point is the key's first half: borrowing another message's "es" gives a
        // different secret, so the tag fails instead of quietly reading someone else's frame.
        assertThrows(AEADBadTagException::class.java) {
            YggCryptoEngine.openConversationEnvelope(
                a.replace(field(a, "es"), field(b, "es")), recipientPriv, alice.yggNodePublicKeyHex
            )
        }
        assertThrows(AEADBadTagException::class.java) {
            // Last tag byte flipped: deterministic, so the tamper can never be a no-op.
            val ct = field(a, "ct")
            val tamperedCt = ct.dropLast(1) + (if (ct.last() == 'f') 'e' else 'f')
            YggCryptoEngine.openConversationEnvelope(
                a.replace(ct, tamperedCt), recipientPriv, alice.yggNodePublicKeyHex
            )
        }
        // Someone else's long-term key does not unlock a frame addressed to this device.
        assertThrows(AEADBadTagException::class.java) {
            YggCryptoEngine.openConversationEnvelope(
                a, YggCryptoEngine.x25519PrivateKeyFromMnemonic(carolWords), alice.yggNodePublicKeyHex
            )
        }
        // The wire frame still has to name the same author as the envelope.
        assertThrows(SecurityException::class.java) {
            YggCryptoEngine.openConversationEnvelope(
                a, recipientPriv, identity(carolWords).yggNodePublicKeyHex
            )
        }
    }

    @Test
    fun `a v3 frame without a usable ephemeral point is refused before any crypto`() {
        val envelope = seal(aliceWords, alice.yggNodePublicKeyHex, bob.x25519IdentityPubHex, "текст", version = 3)
        val broken = envelope.replace(field(envelope, "es"), "not-a-key")
        val failure = assertThrows(SecurityException::class.java) {
            YggCryptoEngine.openConversationEnvelope(
                broken, YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords), alice.yggNodePublicKeyHex
            )
        }
        assertTrue(failure.message!!.contains("ephemeral"))
    }

    @Test
    fun `v2 stays exactly as it was and both versions are readable`() {
        val legacy = seal(aliceWords, alice.yggNodePublicKeyHex, bob.x25519IdentityPubHex, "старый кадр", version = 2)
        // The v2 shape is what peers already hold in their databases: pinned here so an upgrade
        // cannot silently reformat it and make old messages unopenable.
        assertTrue(legacy.contains("\"v\":2"))
        assertTrue(legacy.contains("\"step\":"))
        assertTrue(legacy.contains("\"sd\":"))
        assertEquals(2, YggCryptoEngine.envelopeVersionOf(legacy))
        assertEquals(
            "старый кадр",
            YggCryptoEngine.openConversationEnvelope(
                legacy, YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords), alice.yggNodePublicKeyHex
            ).plainText
        )

        val modern = seal(aliceWords, alice.yggNodePublicKeyHex, bob.x25519IdentityPubHex, "новый кадр", version = 3)
        assertEquals(
            "новый кадр",
            YggCryptoEngine.openConversationEnvelope(
                modern, YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords), alice.yggNodePublicKeyHex
            ).plainText
        )

        // Versions this build will not key at all: v1 was readable by anyone recording the wire.
        for (refused in listOf(
            "{\"v\":1,\"sp\":\"${alice.yggNodePublicKeyHex}\",\"iv\":\"${"0".repeat(24)}\",\"ct\":\"00\"}",
            "{\"v\":4,\"sp\":\"${alice.yggNodePublicKeyHex}\",\"iv\":\"${"0".repeat(24)}\",\"ct\":\"00\"}"
        )) {
            assertThrows(SecurityException::class.java) {
                YggCryptoEngine.openConversationEnvelope(
                    refused, YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords), alice.yggNodePublicKeyHex
                )
            }
        }
        assertEquals(1, YggCryptoEngine.envelopeVersionOf("обычный текст без конверта"))
        assertNull(
            "без ключа адресата не запечатывается и v3",
            YggCryptoEngine.sealConversationEnvelope(
                plainText = "текст",
                senderX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(aliceWords),
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex,
                peerX25519PublicKeyHex = null,
                envelopeVersion = 3
            )
        )
    }

    @Test
    fun `the advertisement lists what this build really opens`() {
        assertTrue(YggCryptoEngine.peerAcceptsEnvelopeVersion(YggCryptoEngine.ACCEPTED_ENVELOPE_VERSIONS, 3))
        assertTrue(YggCryptoEngine.peerAcceptsEnvelopeVersion(YggCryptoEngine.ACCEPTED_ENVELOPE_VERSIONS, 2))
        assertTrue(YggCryptoEngine.peerAcceptsEnvelopeVersion(" 2 , 3 ", 3))
        assertTrue(!YggCryptoEngine.peerAcceptsEnvelopeVersion("2", 3))
        assertTrue(!YggCryptoEngine.peerAcceptsEnvelopeVersion("", 3))
        assertTrue(!YggCryptoEngine.peerAcceptsEnvelopeVersion(null, 3))
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ArrayList<Byte>(length)
        var block = ByteArray(0)
        var counter = 1
        while (out.size < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.addAll(block.toList())
            counter++
        }
        return out.take(length).toByteArray()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(text: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * The negotiation, tested through the real repository: which version a message is sealed under
 * is a fact about the peer, and the only evidence allowed to change it is a frame that opened
 * under this device's key or an ACK that actually arrived.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggEnvelopeV3NegotiationTest {

    private val peerWords = bobWords

    private class Device(
        val identity: DerivedIdentityHierarchy,
        val repo: YggRepository,
        val db: YggDatabase
    )

    private suspend fun device(words: List<String>): Device {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(words, epoch = 1)
        val repo = YggRepository(db.yggDao())
        repo.ensureInitialized(identity)
        return Device(identity, repo, db)
    }

    private suspend fun contact(repo: YggRepository, peer: DerivedIdentityHierarchy): String =
        repo.createNewConversation(
            title = "Узел собеседника",
            yggIpv6Input = peer.yggIpv6Address,
            disappearingSeconds = 0,
            directEndpointHint = "",
            peerX25519PublicKeyHex = peer.x25519IdentityPubHex,
            peerEd25519PublicKeyHex = peer.yggNodePublicKeyHex
        )

    private fun frame(
        peer: DerivedIdentityHierarchy,
        envelope: String,
        groupId: String = "",
        membersCsv: String = ""
    ): IncomingTwinPacket = IncomingTwinPacket(
        targetChatId = "",
        messageId = "wire_${groupId}_${envelope.takeLast(24)}",
        senderNodeLabel = "Узел собеседника",
        senderIpv6 = peer.yggIpv6Address,
        senderPubKeyHex = peer.yggNodePublicKeyHex,
        plainText = "открытым текстом на проводе быть не должно",
        cipherPayloadHex = envelope,
        hashcashToken = "1:20:yggdrasil",
        attachmentName = null,
        attachmentSizeBytes = 0,
        groupId = groupId,
        groupMembersCsv = membersCsv,
        groupTitle = if (groupId.isBlank()) "" else "Группа проверки",
        rttMs = 42L,
        timestamp = System.currentTimeMillis()
    )

    private suspend fun sealedVersionOf(repo: YggRepository, chatId: String, plain: String = "исходящее"): Int {
        val conv = requireNotNull(repo.getConversation(chatId))
        val envelope = requireNotNull(repo.sealForConversation(conv, plain)) {
            "у диалога с ключом собеседника кадр обязан запечататься"
        }
        return YggCryptoEngine.envelopeVersionOf(envelope)
    }

    @Test
    fun `a peer that proved nothing gets v2 and an ack naming 3 flips it`() = runBlocking {
        val peer = identity(peerWords)
        val me = device(aliceWords)
        try {
            val chatId = contact(me.repo, peer)
            assertEquals("без подтверждения пир получает v2", 2, sealedVersionOf(me.repo, chatId))

            // A v2-only advertisement says nothing new: the peer's build cannot read v3.
            assertTrue(!me.repo.rememberPeerEnvelopeVersions(chatId, peer.yggNodePublicKeyHex, "2"))
            assertEquals(2, sealedVersionOf(me.repo, chatId))
            assertTrue(!me.repo.rememberPeerEnvelopeVersions(chatId, peer.yggNodePublicKeyHex, ""))
            assertTrue(!me.repo.rememberPeerEnvelopeVersions(chatId, peer.yggNodePublicKeyHex, "23"))
            assertTrue(!me.repo.rememberPeerEnvelopeVersions("chat_нет_такого", peer.yggNodePublicKeyHex, "2,3"))

            assertTrue(
                "ACK, назвавший v3, — это доказательство",
                me.repo.rememberPeerEnvelopeVersions(chatId, peer.yggNodePublicKeyHex, "2,3")
            )
            assertEquals("следующий кадр уже v3", 3, sealedVersionOf(me.repo, chatId))
            assertEquals(
                "и держится",
                3,
                sealedVersionOf(me.repo, chatId)
            )
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a v3 frame that opened here unlocks writing v3 back`() = runBlocking {
        val peer = identity(peerWords)
        val me = device(aliceWords)
        try {
            val chatId = contact(me.repo, peer)
            assertEquals(2, sealedVersionOf(me.repo, chatId))

            me.repo.recordIncomingTwinMessage(
                packet = frame(
                    peer,
                    seal(peerWords, peer.yggNodePublicKeyHex, me.identity.x25519IdentityPubHex, "привет v3", version = 3)
                ),
                activeChatId = null
            )
            val conv = requireNotNull(me.repo.getConversation(chatId))
            assertTrue(
                "открытый v3 кадр — доказательство не меньше, чем ACK",
                conv.peerEnvelopeV3Proven
            )
            assertEquals("ответ собеседнику уже идёт v3", 3, sealedVersionOf(me.repo, chatId))
            // The dialog still quotes the peer's long-term key, not the one-off point of the frame.
            assertEquals(peer.x25519IdentityPubHex, conv.peerX25519PublicKeyHex)
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a v2 frame from the same peer proves nothing about v3`() = runBlocking {
        val peer = identity(peerWords)
        val me = device(aliceWords)
        try {
            val chatId = contact(me.repo, peer)
            me.repo.recordIncomingTwinMessage(
                packet = frame(
                    peer,
                    seal(peerWords, peer.yggNodePublicKeyHex, me.identity.x25519IdentityPubHex, "старая сборка", version = 2)
                ),
                activeChatId = null
            )
            val conv = requireNotNull(me.repo.getConversation(chatId))
            assertTrue("v2 читается и так — права писать v3 он не даёт", !conv.peerEnvelopeV3Proven)
            assertEquals(2, sealedVersionOf(me.repo, chatId))
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a group ack belongs to the member who wrote it, not to the group row`() = runBlocking {
        val peer = identity(peerWords)
        val me = device(aliceWords)
        try {
            val dialogId = contact(me.repo, peer)
            val groupId = "grp_v3_check"
            val groupChatId = requireNotNull(
                me.repo.recordIncomingTwinMessage(
                    packet = frame(
                        peer,
                        seal(peerWords, peer.yggNodePublicKeyHex, me.identity.x25519IdentityPubHex, "в группе", version = 2),
                        groupId = groupId,
                        membersCsv = "${peer.yggIpv6Address}|${peer.yggNodePublicKeyHex}"
                    ),
                    activeChatId = null
                )
            )
            assertEquals("GROUP", me.repo.getConversation(groupChatId)?.chatType)

            assertTrue(
                me.repo.rememberPeerEnvelopeVersions(
                    groupChatId,
                    peer.yggNodePublicKeyHex,
                    YggCryptoEngine.ACCEPTED_ENVELOPE_VERSIONS
                )
            )
            assertTrue(
                "доказательство легло в личный диалог участника, которым пишет fan-out",
                requireNotNull(me.repo.getConversation(dialogId)).peerEnvelopeV3Proven
            )
            assertTrue(
                "у строки группы своего ключа собеседника нет — и доказательства тоже",
                !requireNotNull(me.repo.getConversation(groupChatId)).peerEnvelopeV3Proven
            )
            // An ACK from a node that is not in the roster is not evidence about anyone.
            val stranger = identity(carolWords)
            assertTrue(
                !me.repo.rememberPeerEnvelopeVersions(groupChatId, stranger.yggNodePublicKeyHex, "2,3")
            )
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `an unreadable v3 frame leaves the conversation exactly as it was`() = runBlocking {
        val peer = identity(peerWords)
        val stranger = identity(carolWords)
        val me = device(aliceWords)
        try {
            val chatId = contact(me.repo, peer)
            // Real v3 bytes, sealed to somebody else's key: nothing here is invented, and
            // nothing here is trusted.
            me.repo.recordIncomingTwinMessage(
                packet = frame(
                    peer,
                    seal(peerWords, peer.yggNodePublicKeyHex, stranger.x25519IdentityPubHex, "не для нас", version = 3)
                ),
                activeChatId = null
            )
            val conv = requireNotNull(me.repo.getConversation(chatId))
            assertTrue("не открылся — не доказательство", !conv.peerEnvelopeV3Proven)
            assertEquals(2, sealedVersionOf(me.repo, chatId))
            assertEquals(
                "кадр, который не открылся, остаётся подписан как не расшифрованный",
                "[Сообщение не расшифровано: нет ключа собеседника]",
                conv.lastMessagePreview
            )
        } finally {
            me.db.close()
        }
    }
}
