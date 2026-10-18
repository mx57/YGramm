// File: app/src/test/java/com/example/ExampleRobolectricTest.kt
package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.YggDatabase
import com.example.data.YggRepository
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggCryptoEngine
import com.example.engine.YggLivePacketLogEntry
import com.example.engine.YggPeerScannerCatalog
import com.example.engine.YggTwinP2PBridge
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches YggGram`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("YggGram", appName)
    }

    @Test
    fun `clean start has zero fake chats and real tcp p2p delivers between two nodes`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = YggDatabase.getInstance(app)
        val repo = YggRepository(db.yggDao())

        val mnemonic = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(mnemonic, epoch = 1)
        repo.ensureInitialized(identity)

        // 1. Verify Clean Start (Zero Fake Conversations)
        val initialConversations = repo.conversations.first()
        assertTrue("Clean start must have zero fake conversations", initialConversations.isEmpty())

        // 2. Start two real TCP P2P nodes (Sender and Receiver) on distinct TCP sockets
        val ackReceived = CompletableDeferred<String>()
        val incomingMessageReceived = CompletableDeferred<IncomingTwinPacket>()
        val packetLogs = CopyOnWriteArrayList<YggLivePacketLogEntry>()

        val receiverNode = YggTwinP2PBridge(
            context = null,
            scope = this,
            onPacketReceived = { packet ->
                if (!incomingMessageReceived.isCompleted) {
                    incomingMessageReceived.complete(packet)
                }
            },
            onDeliveryAckReceived = { _, _, _ -> },
            onPacketLogAdded = {},
            onTwinReachableChanged = {}
        )

        val senderNode = YggTwinP2PBridge(
            context = null,
            scope = this,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, messageId, _ ->
                if (!ackReceived.isCompleted) {
                    ackReceived.complete(messageId)
                }
            },
            onPacketLogAdded = { entry -> packetLogs.add(entry) },
            onTwinReachableChanged = {}
        )

        try {
            receiverNode.startBridgeListener()
            senderNode.startBridgeListener()
            kotlinx.coroutines.delay(150)

            val receiverEndpoint = "127.0.0.1:${receiverNode.localTcpPort}"
            val peerIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8"

            // 3. Create a real contact with direct endpoint
            val chatId = repo.createNewConversation(
                title = "Mimir Peer Node",
                yggIpv6Input = peerIpv6,
                isMlsGroup = false,
                sealedSender = true,
                disappearingSeconds = 0,
                directEndpointHint = receiverEndpoint
            )

            // 4. Encrypt and queue outgoing message
            val outgoingMsg = repo.sendEncryptedMessage(
                chatId = chatId,
                plainText = "Проверка реального TCP P2P сокета и крипто-ACK",
                senderIpv6 = identity.yggIpv6Address,
                isOfflineMode = false,
                sealedSenderGlobal = true,
                powDifficultyBits = 8
            )
            requireNotNull(outgoingMsg)
            assertEquals("QUEUED_OFFLINE_SF", outgoingMsg.deliveryState)

            // 5. Dispatch over real TCP socket to receiverNode
            val delivered = senderNode.dispatchOutgoingMessageThroughYggdrasil(
                chatId = chatId,
                messageId = outgoingMsg.messageId,
                peerTitle = "Mimir Peer Node",
                peerIpv6 = peerIpv6,
                directEndpointHint = receiverEndpoint,
                senderNodeLabel = "Sender Node",
                senderIpv6 = identity.yggIpv6Address,
                senderPubKeyHex = identity.yggNodePublicKeyHex,
                plainText = outgoingMsg.plainText,
                cipherPayloadHex = outgoingMsg.cipherPayloadHex,
                hashcashToken = outgoingMsg.hashcashPowToken,
                attachmentName = null,
                attachmentSizeBytes = 0L
            )
            assertTrue("Message must be delivered over real TCP socket", delivered)

            val ackId = withTimeout(3000L) { ackReceived.await() }
            assertEquals(outgoingMsg.messageId, ackId)

            val rxPacket = withTimeout(3000L) { incomingMessageReceived.await() }
            assertEquals("Проверка реального TCP P2P сокета и крипто-ACK", rxPacket.plainText)

            repo.markMessageDelivered(ackId)
            val chatMessages = repo.observeMessagesForChat(chatId).first { list ->
                list.any { it.messageId == outgoingMsg.messageId && it.deliveryState == "DELIVERED" }
            }
            assertTrue(chatMessages.isNotEmpty())

            // 6. Verify Peer Scanner catalog parsing (45+ peers across 16 countries)
            assertTrue("Catalog must contain 45+ real peers", YggPeerScannerCatalog.initialCatalog.size >= 45)
            val distinctCountries = YggPeerScannerCatalog.initialCatalog.map { it.countryCode }.distinct()
            assertTrue("Catalog must span 16+ countries", distinctCountries.size >= 16)

            val fiPeer = YggPeerScannerCatalog.initialCatalog.first { it.countryCode == "FI" }
            assertEquals("65.21.57.125", YggPeerScannerCatalog.extractHostFromUri(fiPeer.endpointUri))
            assertEquals(61995, YggPeerScannerCatalog.extractPortFromUri(fiPeer.endpointUri))

            // 7. Verify Bulk URI parser
            val parsedBulk = YggPeerScannerCatalog.parseBulkPeersFromText(
                "Here are peers: tls://65.21.57.125:61995 and quic://ygg.moscow.Seele.Ru:65534?key=0011"
            )
            assertEquals(2, parsedBulk.size)

            // 8. Verify Self-Yggdrasil conversation routes via external YGGDRASIL_MESH_SELF
            val selfChatId = repo.ensureSelfYggdrasilConversation(identity)
            assertEquals(YggRepository.SELF_YGG_CHAT_ID, selfChatId)
            val selfConvo = repo.getConversationById(selfChatId)
            requireNotNull(selfConvo)
            assertEquals(identity.yggIpv6Address, selfConvo.peerYggIpv6)
            assertEquals("YGGDRASIL_MESH_SELF", selfConvo.directEndpointHint)

            // 9. Verify buildYggdrasilMetaHandshakeBytes (6 magic + 2 len + 48 TLV = 56 bytes)
            val metaFrame = senderNode.buildYggdrasilMetaHandshakeBytes(identity.yggNodePublicKeyHex)
            assertEquals(56, metaFrame.size)
            assertEquals("meta", String(metaFrame, 0, 4, Charsets.US_ASCII))
        } finally {
            receiverNode.stop()
            senderNode.stop()
        }
    }
}
