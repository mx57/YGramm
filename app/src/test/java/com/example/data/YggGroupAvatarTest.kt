// File: app/src/test/java/com/example/data/YggGroupAvatarTest.kt
package com.example.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggAvatarCodec
import com.example.engine.YggCryptoEngine
import com.example.engine.YggTwinP2PBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A group is a roster of mesh addresses with no server behind it, so its cover picture travels
 * the same way the roster does: on the messages. That puts two rows in play at once — the group
 * and the member who spoke — and this suite pins that each picture lands on the row it belongs
 * to, because a member's selfie written over a group cover is a corruption nobody asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggGroupAvatarTest {

    private val meWords = listOf(
        "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
        "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
    )
    private val peerWords = listOf(
        "glacier", "helium", "cascade", "nebula", "cobalt", "lattice",
        "photon", "quartz", "onyx", "harbor", "vertex", "cedar"
    )

    private class Device(val identity: DerivedIdentityHierarchy, val repo: YggRepository, val db: YggDatabase)

    private suspend fun device(): Device {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(meWords, epoch = 1)
        // A picture is bytes on this device's storage, so the repository under test needs the
        // context it is given on a real phone: without it every incoming avatar is declined.
        val repo = YggRepository(db.yggDao(), app)
        repo.ensureInitialized(identity)
        return Device(identity, repo, db)
    }

    private suspend fun Device.memberDialog(peer: DerivedIdentityHierarchy): String =
        repo.createNewConversation(
            title = "Узел собеседника",
            yggIpv6Input = peer.yggIpv6Address,
            disappearingSeconds = 0,
            directEndpointHint = "",
            peerX25519PublicKeyHex = peer.x25519IdentityPubHex,
            peerEd25519PublicKeyHex = peer.yggNodePublicKeyHex
        )

    /** A picture as the codec ships it: a real JPEG, and the bytes are exactly what their hash names. */
    private fun picture(tag: String): Pair<String, String> {
        val seed = YggCryptoEngine.sha256Hex(tag.toByteArray()).take(6).toInt(16)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.rgb(seed and 0xFF, (seed shr 8) and 0xFF, (seed shr 16) and 0xFF))
        bitmap.setPixel(31, 31, Color.rgb((seed shr 16) and 0xFF, seed and 0xFF, (seed shr 8) and 0xFF))
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
        val bytes = out.toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) to YggCryptoEngine.sha256Hex(bytes)
    }

    /** The way a real peer seals a group message: its long-term key, this device's X25519 key. */
    private fun Device.groupFrame(
        peer: DerivedIdentityHierarchy,
        groupId: String,
        cover: Pair<String, String>?,
        face: Pair<String, String>?
    ): IncomingTwinPacket {
        val envelope = YggCryptoEngine.sealConversationEnvelope(
            plainText = "в группе",
            senderX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(peerWords),
            senderEd25519PublicKeyHex = peer.yggNodePublicKeyHex,
            peerX25519PublicKeyHex = identity.x25519IdentityPubHex,
            keyEpoch = 1,
            ratchetStep = 1,
            sealedSender = true
        )!!
        return IncomingTwinPacket(
            targetChatId = "",
            // The tail is ciphertext and tag: two frames differ there while their headers do not.
            messageId = "wire_${groupId}_${envelope.takeLast(24)}",
            senderNodeLabel = "Узел участника",
            senderIpv6 = peer.yggIpv6Address,
            senderPubKeyHex = peer.yggNodePublicKeyHex,
            plainText = "открытым текстом на проводе быть не должно",
            cipherPayloadHex = envelope,
            hashcashToken = "1:20:yggdrasil",
            attachmentName = null,
            attachmentSizeBytes = 0,
            senderAvatarHash = face?.second.orEmpty(),
            senderAvatarData = face?.first.orEmpty(),
            groupAvatarHash = cover?.second.orEmpty(),
            groupAvatarData = cover?.first.orEmpty(),
            groupId = groupId,
            groupTitle = "Группа проверки",
            rttMs = 42L,
            timestamp = System.currentTimeMillis()
        )
    }

    /** The row an incoming group frame leaves behind, before any picture is filed. */
    private suspend fun Device.groupChat(peer: DerivedIdentityHierarchy, groupId: String): String =
        repo.recordIncomingTwinMessage(
            packet = groupFrame(peer, groupId, cover = null, face = null),
            activeChatId = null
        )

    @Test
    fun `a group cover lands on the group and the member's face on their dialog`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device()
        try {
            val contactChatId = me.memberDialog(peer)
            val groupChatId = me.groupChat(peer, "grp_cover_routing")
            assertEquals("GROUP", me.repo.getConversation(groupChatId)?.chatType)

            val cover = picture("групповая обложка")
            val face = picture("лицо участника")
            me.repo.storeIncomingGroupAvatars(
                groupChatId = groupChatId,
                senderIpv6 = peer.yggIpv6Address,
                ownerProfileId = YggRepository.DEFAULT_PROFILE_ID,
                groupAvatarHash = cover.second,
                groupAvatarData = cover.first,
                senderAvatarHash = face.second,
                senderAvatarData = face.first
            )

            val group = me.repo.getConversation(groupChatId)
            val contact = me.repo.getConversation(contactChatId)
            assertEquals("Обложка — на строке группы", cover.second, group?.avatarHashHex)
            assertEquals("Лицо — в личном диалоге участника", face.second, contact?.avatarHashHex)
            assertNotEquals("Селфи участника не становится обложкой", face.second, group?.avatarHashHex)
            assertTrue(
                "Файл обложки лежит на этом устройстве",
                File(Uri.parse(group?.avatarUri.orEmpty()).path!!).isFile
            )
        } finally {
            me.db.close()
        }
    }

    /**
     * The bug this pins: one call site wrote every arriving picture onto the group row, so each
     * member's own avatar replaced the group cover while their dialog stayed faceless.
     */
    @Test
    fun `a member's picture never becomes the group cover`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device()
        try {
            val groupChatId = me.groupChat(peer, "grp_cover_isolation")
            val face = picture("только лицо")
            me.repo.storeIncomingGroupAvatars(
                groupChatId = groupChatId,
                senderIpv6 = peer.yggIpv6Address,
                ownerProfileId = YggRepository.DEFAULT_PROFILE_ID,
                groupAvatarHash = "",
                groupAvatarData = "",
                senderAvatarHash = face.second,
                senderAvatarData = face.first
            )
            assertEquals(
                "Кадр без обложки не оставляет группе чужое лицо",
                "",
                me.repo.getConversation(groupChatId)?.avatarHashHex
            )
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a cover older than the stored hash is not rewritten`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device()
        try {
            val groupChatId = me.groupChat(peer, "grp_cover_same_hash")
            val cover = picture("одна и та же обложка")
            me.repo.storeIncomingAvatar(groupChatId, cover.second, cover.first)
            val storedUri = me.repo.getConversation(groupChatId)?.avatarUri.orEmpty()

            // A second frame from another member with the same hash must not re-file anything:
            // the row already holds these exact bytes under this exact name.
            me.repo.storeIncomingAvatar(groupChatId, cover.second, cover.first)
            assertEquals(
                "Совпавший хэш не порождает вторую запись",
                storedUri,
                me.repo.getConversation(groupChatId)?.avatarUri
            )

            val newer = picture("обложка позже")
            me.repo.storeIncomingAvatar(groupChatId, newer.second, newer.first)
            assertEquals(
                "Другой хэш — другая картинка: у группы нет владельца, кто бы её запрещал",
                newer.second,
                me.repo.getConversation(groupChatId)?.avatarHashHex
            )
        } finally {
            me.db.close()
        }
    }

    /**
     * The cover travels on the message frame, not on a connection of its own, so this is the one
     * place a group picture can be seen arriving: two real nodes, one socket, one frame.
     */
    @Test
    fun `a group cover crosses the socket to another node`() = runBlocking {
        val me = device()
        val peerIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val received = CompletableDeferred<IncomingTwinPacket>()
        val receiverNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = { packet ->
                if (!received.isCompleted) received.complete(packet)
            },
            onDeliveryAckReceived = { _, _, _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
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
            delay(150)
            val cover = picture("обложка через сокет")
            val face = picture("лицо через сокет")

            val delivered = senderNode.dispatchOutgoingMessageThroughYggdrasil(
                chatId = "chat_owner_grp_wire",
                messageId = "msg_grp_cover_1",
                peerTitle = "Узел участника",
                peerIpv6 = peerIdentity.yggIpv6Address,
                directEndpointHint = "127.0.0.1:${receiverNode.localTcpPort}",
                senderNodeLabel = "Узел отправителя",
                senderIpv6 = me.identity.yggIpv6Address,
                senderPubKeyHex = me.identity.yggNodePublicKeyHex,
                plainText = "открытого текста на проводе нет",
                cipherPayloadHex = "00112233",
                hashcashToken = "1:20:yggdrasil",
                attachmentName = null,
                attachmentSizeBytes = 0,
                senderAvatarHash = face.second,
                senderAvatarBase64 = face.first,
                groupAvatarHash = cover.second,
                groupAvatarBase64 = cover.first,
                groupId = "grp_cover_wire",
                groupTitle = "Группа проверки",
                groupMembersCsv = ""
            )
            assertTrue("Кадр группы должен дойти до узла: $delivered", delivered)
            val rx = withTimeout(3_000L) { received.await() }
            assertEquals("grp_cover_wire", rx.groupId)
            assertEquals("Обложка приходит со своим хэшем", cover.second, rx.groupAvatarHash)
            assertEquals("и со своими байтами", cover.first, rx.groupAvatarData)
            assertEquals("Лицо говорит отдельного участника", face.second, rx.senderAvatarHash)
        } finally {
            senderNode.stop()
            receiverNode.stop()
            me.db.close()
        }
    }

    @Test
    fun `a cover whose bytes do not match its hash is refused`() = runBlocking {
        val peer = YggCryptoEngine.deriveHierarchyFromMnemonic(peerWords, epoch = 1)
        val me = device()
        try {
            val groupChatId = me.groupChat(peer, "grp_cover_hash_mismatch")
            val bytes = picture("заявленная обложка")
            val otherHash = YggCryptoEngine.sha256Hex("другие байты".toByteArray())
            me.repo.storeIncomingAvatar(groupChatId, otherHash, bytes.first)
            assertEquals(
                "Несовпадение хэша и байтов — ничего не сохраняется",
                "",
                me.repo.getConversation(groupChatId)?.avatarHashHex
            )
            val dir = YggAvatarCodec.avatarDir(ApplicationProvider.getApplicationContext())
            assertTrue(
                "Отклонённые байты не оседают в папке аватаров",
                dir.listFiles()?.none { it.name == "$otherHash.jpg" } != false
            )
        } finally {
            me.db.close()
        }
    }
}
