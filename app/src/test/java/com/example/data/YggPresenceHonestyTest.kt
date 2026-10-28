// File: app/src/test/java/com/example/data/YggPresenceHonestyTest.kt
package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggCryptoEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A green dot next to a contact has always been a claim about a person, and nothing in this app
 * knows anything about a person: what the code measures is whether a node's TCP port answered a
 * route check. This suite pins the one presence fact the overlay really provides - the moment a
 * frame from a peer opened under this device's key - and proves that route checks, undecryptable
 * frames and group chatter never produce it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggPresenceHonestyTest {

    private val meWords = listOf(
        "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
        "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
    )
    private val peerWords = listOf(
        "glacier", "helium", "cascade", "nebula", "cobalt", "lattice",
        "photon", "quartz", "onyx", "harbor", "vertex", "cedar"
    )

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
            title = "Узел YGramm #1",
            yggIpv6Input = peer.yggIpv6Address,
            disappearingSeconds = 0,
            directEndpointHint = "",
            peerX25519PublicKeyHex = peer.x25519IdentityPubHex,
            peerEd25519PublicKeyHex = peer.yggNodePublicKeyHex
        )

    /** The way a real peer seals a message: its own long-term keys, this device's X25519 public key. */
    private fun sealedBy(
        senderWords: List<String>,
        senderEd25519Hex: String,
        recipient: DerivedIdentityHierarchy,
        plain: String
    ): String {
        val envelope = YggCryptoEngine.sealConversationEnvelope(
            plainText = plain,
            senderX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(senderWords),
            senderEd25519PublicKeyHex = senderEd25519Hex,
            peerX25519PublicKeyHex = recipient.x25519IdentityPubHex,
            keyEpoch = 1,
            ratchetStep = 1,
            sealedSender = true
        )
        assertTrue("Кадр должен запечататься под ключ адресата", envelope != null)
        return envelope!!
    }

    private fun frame(
        peer: DerivedIdentityHierarchy,
        envelope: String,
        groupId: String = ""
    ): IncomingTwinPacket = IncomingTwinPacket(
        targetChatId = "",
        // The tail of an envelope is its ciphertext and tag: two frames sealed for different
        // keys differ there, while their headers do not.
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
        groupTitle = if (groupId.isBlank()) "" else "Группа проверки",
        rttMs = 42L,
        timestamp = System.currentTimeMillis()
    )

    @Test
    fun `a message that opened stamps when the peer was last reachable`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device(meWords)
        try {
            val chatId = contact(me.repo, peer)
            val before = me.repo.getConversation(chatId)?.peerLastSeenAt ?: 0L
            val startedAt = System.currentTimeMillis()
            me.repo.recordIncomingTwinMessage(
                packet = frame(peer, sealedBy(peerWords, peer.yggNodePublicKeyHex, me.identity, "привет")),
                activeChatId = null
            )
            val seen = me.repo.getConversation(chatId)?.peerLastSeenAt ?: 0L
            assertTrue("Открытый кадр обязан оставить отметку", seen >= startedAt)
            assertTrue("Отметка не берётся из прошлого", seen > before)
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a frame this device cannot open says nothing about the peer`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val stranger = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf("tulip", "lantern", "cobweb", "marble", "quiver", "fathom",
                "harvest", "pinnacle", "tundra", "cinder", "beetle", "willow"),
            epoch = 1
        )
        val me = device(meWords)
        try {
            val chatId = contact(me.repo, peer)
            // Valid AEAD under someone else's key: the bytes are real, the sender is not proven.
            me.repo.recordIncomingTwinMessage(
                packet = frame(peer, sealedBy(peerWords, peer.yggNodePublicKeyHex, stranger, "не для нас")),
                activeChatId = null
            )
            assertEquals(
                "Кадр, который не открылся, не присутствие",
                0L,
                me.repo.getConversation(chatId)?.peerLastSeenAt ?: 0L
            )
        } finally {
            me.db.close()
        }
    }

    /**
     * The bug this pins: `isOnlineInMesh` is what a route check writes, and the chat list drew it
     * as an online dot. A node answers TCP whether or not anyone is there, so a route check must
     * leave the presence stamp exactly where it was.
     */
    @Test
    fun `a route check never claims the peer came online`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device(meWords)
        try {
            val chatId = contact(me.repo, peer)
            me.repo.updateConversationRouteStatus(chatId, isOnline = true, routeSummary = "маршрут есть")
            val conv = me.repo.getConversation(chatId)
            assertEquals("Проверка маршрута — не отметка о присутствии", 0L, conv?.peerLastSeenAt ?: 0L)
            assertTrue("Маршрут при этом она пишет честно", conv?.isOnlineInMesh == true)
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a group message marks no one as seen`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device(meWords)
        try {
            val groupId = "grp_presence_check"
            val chatId = me.repo.recordIncomingTwinMessage(
                packet = frame(
                    peer,
                    sealedBy(peerWords, peer.yggNodePublicKeyHex, me.identity, "в группе"),
                    groupId = groupId
                ),
                activeChatId = null
            )
            val conv = me.repo.getConversation(chatId)
            assertEquals("GROUP", conv?.chatType)
            assertEquals(
                "Кто-то в группе написал — это не ответ конкретного собеседника",
                0L,
                conv?.peerLastSeenAt ?: 0L
            )
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a stored presence stamp survives a later unreadable frame`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val stranger = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf("tulip", "lantern", "cobweb", "marble", "quiver", "fathom",
                "harvest", "pinnacle", "tundra", "cinder", "beetle", "willow"),
            epoch = 1
        )
        val me = device(meWords)
        try {
            val chatId = contact(me.repo, peer)
            me.repo.recordIncomingTwinMessage(
                packet = frame(peer, sealedBy(peerWords, peer.yggNodePublicKeyHex, me.identity, "первое")),
                activeChatId = null
            )
            val stamp = me.repo.getConversation(chatId)?.peerLastSeenAt ?: 0L
            assertTrue(stamp > 0L)
            kotlinx.coroutines.delay(5)
            me.repo.recordIncomingTwinMessage(
                packet = frame(peer, sealedBy(peerWords, peer.yggNodePublicKeyHex, stranger, "второе")),
                activeChatId = null
            )
            assertEquals(
                "Неоткрытый кадр не двигает отметку ни вперёд, ни назад",
                stamp,
                me.repo.getConversation(chatId)?.peerLastSeenAt ?: 0L
            )
            // The dialog list keeps flowing, so the stamp is not simply lost with the row.
            assertTrue(me.repo.conversations.first().isNotEmpty())
        } finally {
            me.db.close()
        }
    }
}
