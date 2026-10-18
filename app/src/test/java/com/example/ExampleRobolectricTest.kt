// File: app/src/test/java/com/example/ExampleRobolectricTest.kt
package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.YggDatabase
import com.example.data.YggRepository
import com.example.engine.IncomingReactionPacket
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggCryptoEngine
import com.example.engine.YggLivePacketLogEntry
import com.example.engine.YggPeerScannerCatalog
import com.example.engine.YggQrCodeCodec
import com.example.engine.YggTwinP2PBridge
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `qr codec generates and decodes ygggram contact bitmap and uri`() {
        val uri = YggQrCodeCodec.buildContactUri(
            displayName = "Узел Альфа",
            yggIpv6Address = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
            ed25519PubKeyHex = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            localLanIp = "192.168.1.42",
            localPort = 51921
        )
        val parsedFromText = YggQrCodeCodec.parseContactString(uri)
        assertNotNull(parsedFromText)
        assertEquals("Узел Альфа", parsedFromText?.displayName)
        assertEquals("0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8", parsedFromText?.yggIpv6Address)
        assertEquals("192.168.1.42:51921", parsedFromText?.directLanHint)

        val qrBitmap = YggQrCodeCodec.renderQrBitmap(uri, sizePx = 560)
        val decodedFromBitmap = YggQrCodeCodec.decodeQrBitmap(qrBitmap)
        assertNotNull("QR Bitmap must decode back to contact payload", decodedFromBitmap)
        assertEquals("0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8", decodedFromBitmap?.yggIpv6Address)
        assertEquals("Узел Альфа", decodedFromBitmap?.displayName)
    }

    @Test
    fun `clean start has zero fake chats and real tcp p2p delivers messages voice and reactions`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
        val incomingReactionReceived = CompletableDeferred<IncomingReactionPacket>()
        val packetLogs = CopyOnWriteArrayList<YggLivePacketLogEntry>()

        val receiverNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = { packet ->
                if (!incomingMessageReceived.isCompleted) {
                    incomingMessageReceived.complete(packet)
                }
            },
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {},
            onReactionReceived = { reaction ->
                if (!incomingReactionReceived.isCompleted) {
                    incomingReactionReceived.complete(reaction)
                }
            }
        )

        val senderNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, messageId, _ ->
                if (!ackReceived.isCompleted) {
                    ackReceived.complete(messageId)
                }
            },
            onTwinReachableChanged = {},
            onPacketLogAdded = { entry -> packetLogs.add(entry) }
        )

        try {
            receiverNode.start()
            senderNode.start()
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

            // 4. Encrypt and queue outgoing voice message
            val outgoingMsg = repo.sendEncryptedMessage(
                chatId = chatId,
                plainText = "Голосовое сообщение Opus/AEAD (5 сек)",
                senderIpv6 = identity.yggIpv6Address,
                isOfflineMode = false,
                sealedSenderGlobal = true,
                powDifficultyBits = 8,
                attachmentName = "voice_test.ogg",
                attachmentSizeBytes = 32000L,
                attachmentType = "VOICE",
                voiceDurationSec = 5,
                voiceWaveformCsv = "0.25,0.60,0.85,0.40,0.95"
            )
            requireNotNull(outgoingMsg)
            assertEquals("QUEUED_OFFLINE_SF", outgoingMsg.deliveryState)
            assertEquals("VOICE", outgoingMsg.attachmentType)
            assertEquals(5, outgoingMsg.voiceDurationSec)

            // 5. Toggle reaction locally and verify reactionsJson
            val reactedMsg = repo.toggleReactionOnMessage(outgoingMsg.messageId, "🔥", byMe = true)
            requireNotNull(reactedMsg)
            assertTrue(reactedMsg.reactionsJson.contains("🔥"))

            // 6. Dispatch over real TCP socket to receiverNode
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
                attachmentName = outgoingMsg.attachmentName,
                attachmentSizeBytes = outgoingMsg.attachmentSizeBytes,
                attachmentType = outgoingMsg.attachmentType,
                voiceDurationSec = outgoingMsg.voiceDurationSec,
                voiceWaveformCsv = outgoingMsg.voiceWaveformCsv
            )
            assertTrue("Message must be delivered over real TCP socket", delivered)

            val ackId = withTimeout(3000L) { ackReceived.await() }
            assertEquals(outgoingMsg.messageId, ackId)

            val rxPacket = withTimeout(3000L) { incomingMessageReceived.await() }
            assertEquals("Голосовое сообщение Opus/AEAD (5 сек)", rxPacket.plainText)
            assertEquals("VOICE", rxPacket.attachmentType)
            assertEquals(5, rxPacket.voiceDurationSec)

            // 7. Dispatch live reaction frame over TCP P2P
            val reactionSent = senderNode.dispatchReactionFrame(
                chatId = chatId,
                messageId = outgoingMsg.messageId,
                emoji = "❤️",
                peerIpv6 = peerIpv6,
                directEndpointHint = receiverEndpoint,
                senderIpv6 = identity.yggIpv6Address
            )
            assertTrue("Reaction frame must be delivered over TCP socket", reactionSent)
            val rxReaction = withTimeout(3000L) { incomingReactionReceived.await() }
            assertEquals("❤️", rxReaction.emoji)
            assertEquals(outgoingMsg.messageId, rxReaction.messageId)

            // 8. Verify Peer Scanner catalog (46+ peers across 16 countries)
            assertTrue("Catalog must contain 46+ real peers", YggPeerScannerCatalog.initialCatalog.size >= 46)
        } finally {
            receiverNode.stop()
            senderNode.stop()
        }
    }

    @Test
    fun `multi profile generation concurrent mode and binary voice media p2p roundtrip work`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = YggRepository(db.yggDao(), app)

        val mnemonic = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(mnemonic, epoch = 1)
        repo.ensureInitialized(identity)

        // 1. Verify initial profile exists and create a second concurrent profile
        val (secondProfile, _) = repo.createNewUserProfile(
            displayName = "Резервный Узел Бета",
            handle = "@beta_mesh.ygg",
            bio = "Второй параллельный профиль Yggdrasil",
            runSimultaneouslyInBackground = true,
            switchImmediately = false
        )
        assertNotNull(secondProfile)
        assertTrue(secondProfile.yggIpv6Address.startsWith("02"))
        assertTrue(secondProfile.isActiveInBackground)

        val allProfiles = repo.getAllProfilesNow()
        assertEquals(2, allProfiles.size)

        // 2. Switch active profile to the new profile and verify active state
        val switchedPair = repo.switchPrimaryUserProfile(secondProfile.profileId)
        assertNotNull(switchedPair)
        assertEquals(secondProfile.profileId, switchedPair?.first?.profileId)
        assertEquals(true, switchedPair?.first?.isCurrentPrimary)

        // 3. Verify real WAV voice generation and P2P Base64 binary attachment roundtrip
        val (savedVoiceUri, voiceB64) = com.example.engine.YggAudioMediaEngine.prepareAttachmentForP2P(
            context = app,
            attachmentName = "voice_test_note.wav",
            attachmentType = "VOICE",
            attachmentUri = null,
            voiceDurationSec = 3,
            voiceWaveformCsv = "0.30,0.65,0.85,0.50,0.90"
        )
        assertNotNull(savedVoiceUri)
        assertTrue("Encoded voice WAV Base64 payload must not be empty", voiceB64.isNotBlank())

        val decodedRxUri = com.example.engine.YggAudioMediaEngine.saveIncomingP2PAttachment(
            context = app,
            attachmentName = "voice_test_note.wav",
            attachmentType = "VOICE",
            attachmentBase64 = voiceB64,
            fallbackUri = null,
            voiceDurationSec = 3,
            voiceWaveformCsv = "0.30,0.65,0.85,0.50,0.90"
        )
        assertNotNull("Receiver must decode and save incoming voice WAV file", decodedRxUri)

        // 4. Verify YggNodeForegroundService state updates
        com.example.service.YggNodeForegroundService.startOrUpdateNodeService(
            context = app,
            activeProfilesCount = 2,
            connectedPeersCount = 4,
            primaryIpv6 = secondProfile.yggIpv6Address,
            activePortsSummary = ":43100, :43101",
            txPackets = 12,
            rxPackets = 15
        )
        val fgState = com.example.service.YggNodeForegroundService.serviceState.value
        assertEquals(secondProfile.yggIpv6Address, fgState.primaryIpv6)
        assertEquals(2, fgState.activeProfilesCount)
        assertEquals(4, fgState.connectedPeersCount)

        // 5. Verify per-profile relay role configuration (CLIENT_ONLY, PEER_RELAY, SF_BLIND_RELAY) and transit counter
        val updatedRoles = repo.setProfileNodeRelayRole(secondProfile.profileId, "SF_BLIND_RELAY")
        val updatedSecond = updatedRoles.find { it.profileId == secondProfile.profileId }
        assertNotNull(updatedSecond)
        assertEquals("SF_BLIND_RELAY", updatedSecond?.nodeRelayRole)

        repo.incrementProfileRelayedPackets(secondProfile.profileId, 3)
        val afterRelayCount = repo.getAllProfilesNow().find { it.profileId == secondProfile.profileId }
        assertEquals(3, afterRelayCount?.relayedPacketsCount)

        // 6. Verify YggNotificationCenter posts heads-up notifications for enabled profiles without throwing
        val testPopupItem = com.example.service.YggPopupNotificationItem(
            notificationId = "test_notif_call_01",
            category = com.example.service.YggAlertCategory.INCOMING_CALL,
            targetProfileId = secondProfile.profileId,
            targetProfileName = secondProfile.displayName,
            targetProfileIpv6 = secondProfile.yggIpv6Address,
            targetProfileRelayRole = "SF_BLIND_RELAY",
            chatId = "chat_test_01",
            senderName = "Узел Мимир",
            senderIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
            messagePreview = "Входящий голосовой вызов Yggdrasil"
        )
        com.example.service.YggNotificationCenter.postSystemHeadsUpNotification(app, testPopupItem)
    }
}
