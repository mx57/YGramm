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
import java.util.concurrent.CopyOnWriteArrayList

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
    fun `yggdrasil network repository and udp loopback deliver and receive messages end to end`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = YggDatabase.getInstance(app)
        val repo = YggRepository(db.yggDao())

        val mnemonic = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(mnemonic, epoch = 1)
        repo.ensureSeeded(identity, isCloneNode = false)

        val seededConversations = repo.conversations.first { it.isNotEmpty() }
        assertTrue(seededConversations.any { it.chatId == "chat_twin_node" })

        val ackReceived = CompletableDeferred<String>()
        val incomingReplyReceived = CompletableDeferred<IncomingTwinPacket>()
        val packetLogs = CopyOnWriteArrayList<YggLivePacketLogEntry>()

        val bridge = YggTwinP2PBridge(
            isCloneInstance = false,
            scope = this,
            onPacketReceived = { packet ->
                if (!incomingReplyReceived.isCompleted) {
                    incomingReplyReceived.complete(packet)
                }
            },
            onDeliveryAckReceived = { _, messageId, _ ->
                if (!ackReceived.isCompleted) {
                    ackReceived.complete(messageId)
                }
            },
            onPacketLogAdded = { entry ->
                packetLogs.add(entry)
            },
            onTwinReachableChanged = {}
        )

        try {
            bridge.startBridgeListener()
            kotlinx.coroutines.delay(150)

            // 1. Encrypt and persist outgoing message in Room
            val outgoingMsg = repo.sendEncryptedMessage(
                chatId = "chat_twin_node",
                plainText = "Тест сети Yggdrasil: проверка отправки и приема",
                senderIpv6 = identity.yggIpv6Address,
                isOfflineMode = false,
                sealedSenderGlobal = true,
                powDifficultyBits = 8,
                attachmentName = null,
                attachmentSizeBytes = 0L
            )
            requireNotNull(outgoingMsg)
            assertEquals("SENT_MESH", outgoingMsg.deliveryState)

            // 2. Dispatch over real Yggdrasil Userspace UDP Loopback switch
            val sent = bridge.dispatchOutgoingMessageThroughYggdrasil(
                chatId = "chat_twin_node",
                messageId = outgoingMsg.messageId,
                peerTitle = "Узел #2: YggGram Клон",
                peerIpv6 = "0201:7c91:44e0:11a2:88d3:50f1:99b2:0002",
                senderNodeLabel = "Узел #1: YggGram Основной",
                senderIpv6 = identity.yggIpv6Address,
                plainText = outgoingMsg.plainText,
                cipherPayloadHex = outgoingMsg.cipherPayloadHex,
                hashcashToken = outgoingMsg.hashcashPowToken,
                attachmentName = null,
                attachmentSizeBytes = 0L
            )
            assertTrue(sent)

            // 3. Verify cryptographic ACK arrives and updates DB state to DELIVERED
            val ackMsgId = withTimeout(3000L) { ackReceived.await() }
            assertEquals(outgoingMsg.messageId, ackMsgId)
            repo.markMessageDelivered(ackMsgId)

            // 4. Verify message delivery status in Room
            val chatMessages = repo.observeMessagesForChat("chat_twin_node").first { list ->
                list.any { it.messageId == outgoingMsg.messageId && it.deliveryState == "DELIVERED" }
            }
            assertTrue(chatMessages.isNotEmpty())
            assertTrue(packetLogs.any { it.direction.startsWith("TX") })
            assertTrue(packetLogs.any { it.direction.startsWith("ACK") })

            // 5. Test chat deletion
            repo.deleteConversation("chat_siberia_sf")
            val conversationsAfterDelete = repo.conversations.first()
            assertTrue(conversationsAfterDelete.none { it.chatId == "chat_siberia_sf" })

            // 5. Verify Country Peer Scanner adds discovered node into routing table
            val finlandPeer = YggPeerScannerCatalog.initialCatalog.first { it.countryCode == "FI" }
            repo.addOrUpdatePeer(
                peerId = "peer_${finlandPeer.id}",
                endpointUri = finlandPeer.endpointUri,
                transportType = finlandPeer.transportType,
                sniDomain = finlandPeer.sniDomain,
                utlsFingerprint = finlandPeer.utlsFingerprint,
                regionLabel = "${finlandPeer.countryFlag} ${finlandPeer.countryCode} • ${finlandPeer.cityRu}",
                latencyMs = finlandPeer.latencyMs,
                whitelistCompatible = finlandPeer.whitelistCompatible
            )
            val updatedPeers = repo.peers.first { list -> list.any { it.endpointUri == finlandPeer.endpointUri } }
            assertTrue(updatedPeers.any { it.regionLabel.contains("FI") })
        } finally {
            bridge.stop()
        }
    }
}
