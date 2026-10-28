// File: app/src/test/java/com/example/data/YggProfileFrameTest.kt
package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.IncomingProfilePacket
import com.example.engine.YggCryptoEngine
import com.example.engine.YggProfilePayload
import com.example.engine.YggTwinP2PBridge
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A contact's name, handle, status and picture reached this device once - as a label glued to a
 * message frame, applied when the dialog row was created and never again. Renaming a peer left the
 * chat titled with the old name forever. The PROFILE frame fixes that, and these tests pin the two
 * rules that make it safe: a profile is only believed when it opened under this device's key from
 * a node whose key reproduces its address, and a stored identity never moves backwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggProfileFrameTest {

    private val aliceWords = listOf(
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
    ) {
        val profileId: String by lazy { runBlocking { repo.getAllProfilesNow().first().profileId } }
    }

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

    private fun body(
        name: String,
        handle: String,
        status: String,
        version: Long,
        avatarHash: String = ""
    ): String = YggProfilePayload.encode(
        YggProfilePayload.ProfileBody(
            displayName = name,
            handle = handle,
            statusText = status,
            avatarHashHex = avatarHash,
            version = version
        )
    )

    /** Seals a profile body the way the writing node does: its own long-term keys, peer's X25519. */
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
        assertNotNull("Envelope must seal under a known peer key", envelope)
        return envelope!!
    }

    private suspend fun contact(
        repo: YggRepository,
        peer: DerivedIdentityHierarchy,
        title: String = "Узел YGramm #1",
        endpoint: String = ""
    ): String = repo.createNewConversation(
        title = title,
        yggIpv6Input = peer.yggIpv6Address,
        disappearingSeconds = 0,
        directEndpointHint = endpoint,
        peerX25519PublicKeyHex = peer.x25519IdentityPubHex,
        peerEd25519PublicKeyHex = peer.yggNodePublicKeyHex
    )

    @Test
    fun `a renamed peer refreshes the chat that already exists`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device(aliceWords)
        try {
            val chatId = contact(me.repo, peer)
            val envelope = sealedBy(
                peerWords, peer.yggNodePublicKeyHex, me.identity,
                body("Мира", "@mira.ygg", "пишу с телефона", 1_700_000_000_000L)
            )
            assertEquals(
                YggRepository.ProfileApplyOutcome.APPLIED,
                me.repo.applyIncomingProfile(
                    senderIpv6 = peer.yggIpv6Address,
                    senderPubKeyHex = peer.yggNodePublicKeyHex,
                    profileEnvelope = envelope,
                    avatarHashHex = "",
                    avatarBase64 = ""
                )
            )
            val rows = me.repo.conversations.first()
            assertEquals("Один собеседник остаётся одним диалогом", 1, rows.size)
            val conv = rows.first { it.chatId == chatId }
            assertEquals("Мира", conv.title)
            assertEquals("@mira.ygg", conv.peerHandle)
            assertEquals("пишу с телефона", conv.peerStatusText)
            assertEquals(1_700_000_000_000L, conv.peerProfileVersion)
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a profile frame older than the stored version does not overwrite a newer one`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device(aliceWords)
        try {
            val chatId = contact(me.repo, peer)
            val newer = sealedBy(
                peerWords, peer.yggNodePublicKeyHex, me.identity,
                body("Мира", "@mira.ygg", "актуальный", 200L)
            )
            val older = sealedBy(
                peerWords, peer.yggNodePublicKeyHex, me.identity,
                body("Прежняя Мира", "@stale.ygg", "из задержанного кадра", 100L)
            )
            suspend fun apply(envelope: String) = me.repo.applyIncomingProfile(
                senderIpv6 = peer.yggIpv6Address,
                senderPubKeyHex = peer.yggNodePublicKeyHex,
                profileEnvelope = envelope,
                avatarHashHex = "",
                avatarBase64 = ""
            )
            assertEquals(YggRepository.ProfileApplyOutcome.APPLIED, apply(newer))
            assertEquals(YggRepository.ProfileApplyOutcome.OUT_OF_DATE, apply(older))
            val conv = me.repo.getConversation(chatId)
            assertEquals("Мира", conv?.title)
            assertEquals("актуальный", conv?.peerStatusText)
            assertEquals(200L, conv?.peerProfileVersion)
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `an unsealed profile frame changes nothing`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val stranger = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf("tulip", "lantern", "cobweb", "marble", "quiver", "fathom",
                "harvest", "pinnacle", "tundra", "cinder", "beetle", "willow"),
            epoch = 1
        )
        val me = device(aliceWords)
        try {
            val chatId = contact(me.repo, peer, title = "Как задано вручную")
            // Valid AEAD, sealed for somebody else: nothing inside it is evidence about this device.
            val notForUs = sealedBy(
                peerWords, peer.yggNodePublicKeyHex, stranger,
                body("Кто-то", "@fake.ygg", "подмена", 500L)
            )
            assertEquals(
                YggRepository.ProfileApplyOutcome.UNSEALED,
                me.repo.applyIncomingProfile(
                    senderIpv6 = peer.yggIpv6Address,
                    senderPubKeyHex = peer.yggNodePublicKeyHex,
                    profileEnvelope = notForUs,
                    avatarHashHex = "",
                    avatarBase64 = ""
                )
            )
            val conv = me.repo.getConversation(chatId)
            assertEquals("Как задано вручную", conv?.title)
            assertEquals("", conv?.peerHandle)
            assertEquals(0L, conv?.peerProfileVersion)
            assertEquals(0L, conv?.peerLastSeenAt)
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a profile frame from a key that does not match its address is dropped`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val someoneElse = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf("meadow", "goblin", "quartz", "lagoon", "sable", "ember",
                "talon", "fjord", "basalt", "prism", "cedar", "helix"),
            epoch = 1
        )
        val me = device(aliceWords)
        try {
            val chatId = contact(me.repo, peer, title = "Как задано вручную")
            // The body opens under this device's key, so the claim to test is the frame's: a node
            // key that does not reproduce the address the frame arrived from.
            val envelope = sealedBy(
                peerWords, peer.yggNodePublicKeyHex, me.identity,
                body("Подставной", "@x.ygg", "враньё", 900L)
            )
            assertEquals(
                YggRepository.ProfileApplyOutcome.KEY_MISMATCH,
                me.repo.applyIncomingProfile(
                    senderIpv6 = peer.yggIpv6Address,
                    senderPubKeyHex = someoneElse.yggNodePublicKeyHex,
                    profileEnvelope = envelope,
                    avatarHashHex = "",
                    avatarBase64 = ""
                )
            )
            val conv = me.repo.getConversation(chatId)
            assertEquals("Как задано вручную", conv?.title)
            assertEquals(0L, conv?.peerProfileVersion)
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a profile with no dialog behind it is refused`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device(aliceWords)
        try {
            val envelope = sealedBy(
                peerWords, peer.yggNodePublicKeyHex, me.identity,
                body("Незнакомый", "@stranger.ygg", "первый кадр", 42L)
            )
            assertEquals(
                YggRepository.ProfileApplyOutcome.NO_DIALOG,
                me.repo.applyIncomingProfile(
                    senderIpv6 = peer.yggIpv6Address,
                    senderPubKeyHex = peer.yggNodePublicKeyHex,
                    profileEnvelope = envelope,
                    avatarHashHex = "",
                    avatarBase64 = ""
                )
            )
            assertTrue(
                "Профиль от незнакомца не открывает диалог",
                me.repo.conversations.first().isEmpty()
            )
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `editing the profile sends it to a peer and the peer stores it`() = runBlocking {
        val sender = device(aliceWords)
        val receiver = device(peerWords)
        val applied = CompletableDeferred<YggRepository.ProfileApplyOutcome>()
        val receiverNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, _, _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {},
            onProfileReceived = { packet ->
                val outcome = receiver.repo.applyIncomingProfile(
                    senderIpv6 = packet.senderIpv6,
                    senderPubKeyHex = packet.senderPubKeyHex,
                    profileEnvelope = packet.profileEnvelope,
                    avatarHashHex = packet.senderAvatarHash,
                    avatarBase64 = packet.senderAvatarData,
                    recipientProfileId = packet.recipientProfileId
                )
                if (!applied.isCompleted) applied.complete(outcome)
                verdictOf(outcome)
            }
        )
        val senderNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, _, _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        try {
            receiverNode.start()
            senderNode.start()
            kotlinx.coroutines.delay(150)
            val endpoint = "127.0.0.1:${receiverNode.localTcpPort}"

            // The sender edits its profile and holds a dialog with the receiver, sealed to it.
            sender.repo.updateCurrentProfileMetadata(
                profileId = sender.profileId,
                displayName = "Мира",
                handle = "@mira.ygg",
                bio = "остаётся локальным",
                socialStatus = "пишу с телефона"
            )
            val chatId = contact(sender.repo, receiver.identity, endpoint = endpoint)
            val offer = sender.repo.profileOfferFor(chatId, sender.profileId)
            assertNotNull("Диалог с ключом собеседника обязан дать запечатанное предложение", offer)

            // The receiver keeps its own dialog with the sender: a profile is only applied to a
            // chat that already exists, and this is the fixture that says it does.
            contact(receiver.repo, sender.identity)

            val dispatch = senderNode.dispatchProfileFrame(
                chatId = chatId,
                profileEnvelope = offer!!.envelope,
                profileVersion = offer.version,
                peerIpv6 = receiver.identity.yggIpv6Address,
                directEndpointHint = endpoint,
                senderIpv6 = sender.identity.yggIpv6Address,
                senderPubKeyHex = sender.identity.yggNodePublicKeyHex,
                recipientNodePubKeyHex = receiver.identity.yggNodePublicKeyHex
            )
            assertTrue(
                "Профиль считается отправленным только по ACK адресата: ${dispatch.errorDetail}",
                dispatch.delivered
            )
            assertEquals(
                "ACK без вердикта приёмника - это не «профиль обновился»: ${dispatch.verdict}",
                "applied",
                dispatch.verdict
            )
            val outcome = kotlinx.coroutines.withTimeoutOrNull(5_000) { applied.await() }
            assertEquals(YggRepository.ProfileApplyOutcome.APPLIED, outcome)

            val peerChatId = receiver.repo.conversations.first().first().chatId
            val stored = receiver.repo.getConversation(peerChatId)
            assertEquals("Мира", stored?.title)
            assertEquals("@mira.ygg", stored?.peerHandle)
            assertEquals("пишу с телефона", stored?.peerStatusText)
            assertTrue("Открытый кадр - это и отметка «выходил на связь»", (stored?.peerLastSeenAt ?: 0L) > 0L)
            assertNull(
                "Чужое «о себе» не переезжает по сети",
                stored?.bioStatus?.takeIf { it.contains("остаётся локальным") }
            )
        } finally {
            senderNode.stop()
            receiverNode.stop()
            sender.db.close()
            receiver.db.close()
        }
    }

    @Test
    fun `a frame without a version or a sealed body is refused by the bridge`() = runBlocking {
        val received = CopyOnWriteArrayList<IncomingProfilePacket>()
        val node = profileNode(received)
        try {
            node.start()
            kotlinx.coroutines.delay(150)
            val reply = pushRawFrame(node, "type=${b64("PROFILE")}|senderIpv6=${b64("0201::1")}|senderPubKeyHex=${b64("")}")
            kotlinx.coroutines.delay(200)
            assertTrue(
                "Кадр без версии и без запечатанного тела не заслуживает подтверждения: $reply",
                !reply.contains("ACK")
            )
            assertTrue("Такой кадр не доходит до хранилища", received.isEmpty())
        } finally {
            node.stop()
        }
    }

    /**
     * A node answering its own profile frame with an ACK is how a self-echo turns into a fake
     * confirmation: the sender's fan-out counts that ACK as a peer accepting the profile while
     * nobody outside the device ever read it. On a live device this arrived as
     * «подтвердили 1 из 4» next to «диалога с этим узлом нет».
     */
    @Test
    fun `a frame from this node's own address earns no acknowledgement`() = runBlocking {
        val me = device(peerWords)
        val received = CopyOnWriteArrayList<IncomingProfilePacket>()
        val node = profileNode(received)
        try {
            node.start()
            node.syncConcurrentProfileListeners(listOf(me.repo.getAllProfilesNow().first()))
            kotlinx.coroutines.delay(400)
            val own = me.identity
            val frame = "type=${b64("PROFILE")}" +
                "|profileVersion=${b64("1790000000000")}" +
                "|profileEnvelope=${b64("что-то запечатанное")}" +
                "|senderIpv6=${b64(own.yggIpv6Address)}" +
                "|senderPubKeyHex=${b64(own.yggNodePublicKeyHex)}"
            val reply = pushRawFrame(node, frame)
            kotlinx.coroutines.delay(200)
            assertTrue("Собственному кадру подтверждения не отправляют: $reply", !reply.contains("ACK"))
            assertTrue("Свой кадр не ложится в чужой диалог", received.isEmpty())
        } finally {
            node.stop()
            me.db.close()
        }
    }

    private fun profileNode(
        sink: MutableList<IncomingProfilePacket>
    ): YggTwinP2PBridge = YggTwinP2PBridge(
        context = null,
        onPacketReceived = {},
        onDeliveryAckReceived = { _, _, _, _, _ -> },
        onTwinReachableChanged = {},
        onPacketLogAdded = {},
        onProfileReceived = { packet ->
            sink.add(packet)
            "applied"
        }
    )

    /** Writing one raw frame and reading the answer; silence is a timeout, and a timeout is "". */
    private suspend fun pushRawFrame(node: YggTwinP2PBridge, frame: String): String =
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", node.localTcpPort), 2000)
            socket.getOutputStream().apply {
                write((frame + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            socket.soTimeout = 1500
            runCatching { socket.inputStream.bufferedReader().readLine() }.getOrNull().orEmpty()
        }

    private fun verdictOf(outcome: YggRepository.ProfileApplyOutcome): String = when (outcome) {
        YggRepository.ProfileApplyOutcome.APPLIED -> "applied"
        YggRepository.ProfileApplyOutcome.NO_DIALOG -> "no_dialog"
        YggRepository.ProfileApplyOutcome.KEY_MISMATCH -> "key_mismatch"
        YggRepository.ProfileApplyOutcome.UNSEALED -> "unsealed"
        YggRepository.ProfileApplyOutcome.CORRUPT -> "corrupt"
        YggRepository.ProfileApplyOutcome.OUT_OF_DATE -> "out_of_date"
    }

    private fun b64(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
}
