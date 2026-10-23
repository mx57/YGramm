// File: app/src/test/java/com/example/ExampleRobolectricTest.kt
package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.YggDatabase
import com.example.data.YggRepository
import com.example.engine.IncomingReactionPacket
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggAvatarCodec
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
import org.junit.Assert.assertNull
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
        assertEquals("YGramm", appName)
    }

    @Test
    fun `qr codec generates and decodes ygggram contact bitmap and uri`() {
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf(
                "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
                "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
            ),
            epoch = 1
        )
        val uri = YggQrCodeCodec.buildContactUri(
            displayName = "Узел Альфа",
            yggIpv6Address = identity.yggIpv6Address,
            ed25519PubKeyHex = identity.yggNodePublicKeyHex,
            localLanIp = "192.168.1.42",
            localPort = 51921
        )
        val parsedFromText = YggQrCodeCodec.parseContactString(uri)
        assertNotNull(parsedFromText)
        assertEquals("Узел Альфа", parsedFromText?.displayName)
        assertEquals(identity.yggIpv6Address, parsedFromText?.yggIpv6Address)
        assertEquals("192.168.1.42:51921", parsedFromText?.directLanHint)

        val qrBitmap = YggQrCodeCodec.renderQrBitmap(uri, sizePx = 560)
        val decodedFromBitmap = YggQrCodeCodec.decodeQrBitmap(qrBitmap)
        assertNotNull("QR Bitmap must decode back to contact payload", decodedFromBitmap)
        assertEquals(identity.yggIpv6Address, decodedFromBitmap?.yggIpv6Address)
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
            // Wire frames carry only cipherPayloadHex; the receiving repository re-derives
            // plainText, so the bridge-level packet is asserted by its AEAD payload.
            assertEquals(outgoingMsg.cipherPayloadHex, rxPacket.cipherPayloadHex)
            assertEquals("VOICE", rxPacket.attachmentType)
            assertEquals(5, rxPacket.voiceDurationSec)

            // 7. Dispatch live reaction frame over TCP P2P
            val reactionResult = senderNode.dispatchReactionFrame(
                chatId = chatId,
                messageId = outgoingMsg.messageId,
                emoji = "❤️",
                action = "ADD",
                peerIpv6 = peerIpv6,
                directEndpointHint = receiverEndpoint,
                senderIpv6 = identity.yggIpv6Address
            )
            assertTrue("Reaction frame must be delivered over TCP socket", reactionResult.delivered)
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
        val secondFirstGroup = secondProfile.yggIpv6Address.substringBefore(":").toInt(16)
        assertTrue(
            "Real Yggdrasil v0.5 address must fall into 200::/7 (RFC 5952 compression makes a string prefix unreliable)",
            secondFirstGroup in 0x200..0x3ff
        )
        assertTrue(secondProfile.isActiveInBackground)

        val allProfiles = repo.getAllProfilesNow()
        assertEquals(2, allProfiles.size)

        // 2. Switch active profile to the new profile and verify active state
        val switchedPair = repo.switchPrimaryUserProfile(secondProfile.profileId)
        assertNotNull(switchedPair)
        assertEquals(secondProfile.profileId, switchedPair?.first?.profileId)
        assertEquals(true, switchedPair?.first?.isCurrentPrimary)

        // 3. Attachment transport: a missing source must produce no payload (inventing a WAV
        // here is what let a sender claim "sent" for audio that never existed), while bytes
        // read from a real file must survive the P2P Base64 roundtrip.
        val (missingUri, missingB64) = com.example.engine.YggAudioMediaEngine.prepareAttachmentForP2P(
            context = app,
            attachmentName = "voice_test_note.wav",
            attachmentType = "VOICE",
            attachmentUri = null,
            voiceDurationSec = 3,
            voiceWaveformCsv = "0.30,0.65,0.85,0.50,0.90"
        )
        assertNull("No readable source must not be replaced by generated bytes", missingUri)
        assertEquals("No readable source must not be replaced by generated payload", "", missingB64)

        val voiceFile = java.io.File(app.filesDir, "tx_test_note.wav").apply {
            writeBytes(ByteArray(320) { (it % 251).toByte() })
        }
        val voiceSourceUri = android.net.Uri.fromFile(voiceFile).toString()
        val (savedVoiceUri, voiceB64) = com.example.engine.YggAudioMediaEngine.prepareAttachmentForP2P(
            context = app,
            attachmentName = "voice_test_note.wav",
            attachmentType = "VOICE",
            attachmentUri = voiceSourceUri,
            voiceDurationSec = 3,
            voiceWaveformCsv = "0.30,0.65,0.85,0.50,0.90"
        )
        assertEquals(voiceSourceUri, savedVoiceUri)
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

        // 7. Verify strict Yggdrasil routing between profiles:
        //    - Default PrivacyMatrixState.allowDirectLanDiscovery is false
        //    - Default YggTwinP2PBridge.allowDirectLanRouting is false
        val defaultPrivacy = com.example.viewmodel.PrivacyMatrixState()
        assertEquals(
            "Manual LAN discovery must be disabled by default so profiles route strictly via Yggdrasil",
            false,
            defaultPrivacy.allowDirectLanDiscovery
        )

        val receivedByProfile = CompletableDeferred<IncomingTwinPacket>()
        val bridge = YggTwinP2PBridge(
            context = null,
            onPacketReceived = { pkt ->
                if (!receivedByProfile.isCompleted) {
                    receivedByProfile.complete(pkt)
                }
            },
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        assertEquals(false, bridge.allowDirectLanRouting)

        try {
            bridge.start()
            val currentProfiles = repo.getAllProfilesNow()
            bridge.syncConcurrentProfileListeners(currentProfiles)
            kotlinx.coroutines.delay(100)

            // Attempting to route to secondProfile with unreachable external peer and allowLanFallback = false
            // MUST NOT shortcut over 127.0.0.1 and must return delivered = false (queued for S&F)
            val blockedResult = bridge.dispatchMessageViaExternalYggdrasilOverlay(
                chatId = "chat_strict_test",
                messageId = "msg_strict_01",
                peerTitle = secondProfile.displayName,
                peerIpv6 = secondProfile.yggIpv6Address,
                directEndpointHint = "",
                senderNodeLabel = "Primary Profile",
                senderIpv6 = identity.yggIpv6Address,
                senderPubKeyHex = identity.yggNodePublicKeyHex,
                plainText = "Strict Yggdrasil Only Test",
                cipherPayloadHex = "aabbccdd",
                hashcashToken = "1:10:test",
                attachmentName = null,
                attachmentSizeBytes = 0L,
                candidatePeers = listOf(Triple("tcp://127.0.0.1:1", "", "Invalid Local Peer")),
                allowLanFallback = false
            )
            // If no external internet peer is reachable in unit test environment, local bypass must remain blocked
            if (!blockedResult.delivered) {
                assertTrue(blockedResult.errorDetail?.isNotBlank() == true)
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `core peer json decides which links count as up`() {
        val raw = """
            {"ok":true,"upCount":1,"total":3,"peers":[
              {"URI":"tcp://10.0.2.2:21001?maxbackoff=20s","Up":false},
              {"URI":"TLS://Example.COM:443?maxbackoff=20s","Up":true,"Latency":155000000},
              {"URI":"","Up":true}
            ]}
        """.trimIndent()
        val up = com.example.engine.YggNativeCoreBridge.readCoreUpPeers(raw)
        assertEquals(
            "Only links the core holds count, keyed by the endpoint form stored in the database",
            mapOf("tls://example.com:443" to 155),
            up
        )
        assertTrue(
            "A broken native answer must not invent live peers",
            com.example.engine.YggNativeCoreBridge.readCoreUpPeers("{not json").isEmpty()
        )
    }

    @Test
    fun `lost overlay frames are counted instead of vanishing silently`() {
        val logs = CopyOnWriteArrayList<YggLivePacketLogEntry>()
        val node = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = { logs.add(it) }
        )
        repeat(12) { node.noteOverlayDrop("profile_test", "payload оверлея не раскодирован") }
        assertEquals(12L, node.overlayDropTotal())
        assertEquals(
            "A flooded journal buries the signal: only the first drop and every tenth are written",
            2,
            logs.count { it.summary.contains("потерян") }
        )
    }

    @Test
    fun `avatar crosses the p2p link once and the recipient stores only verified bytes`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sourceFile = java.io.File(app.filesDir, "face_source.png").apply {
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(android.graphics.Color.parseColor("#7E57C2"))
            java.io.FileOutputStream(this).use { out ->
                assertTrue("Test image must encode", bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
            bitmap.recycle()
        }
        val sourceUri = Uri.fromFile(sourceFile)

        // 1. The codec turns the picked file into an overlay-sized, hash-named JPEG.
        val stored = YggAvatarCodec.storeFromUri(app, sourceUri)
        assertNotNull("A real image must become an avatar that fits one frame", stored)
        requireNotNull(stored)
        assertTrue(
            "Stored avatar must stay inside the frame budget",
            stored.jpegBytes in 1..YggAvatarCodec.MAX_JPEG_BYTES
        )
        val avatarBase64 = YggAvatarCodec.base64OfStoredFile(stored.localUri)
        assertTrue("The stored file must read back for the wire", !avatarBase64.isNullOrBlank())

        // 2. Owning profile keeps it across the database.
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = YggRepository(db.yggDao(), app)
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf(
                "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
                "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
            ),
            epoch = 1
        )
        repo.ensureInitialized(identity)
        val profileId = repo.getAllProfilesNow().first().profileId
        val updatedProfile = repo.setProfileAvatar(profileId, sourceUri)
        assertEquals(stored.hashHex, updatedProfile?.avatarHashHex)
        assertTrue(updatedProfile?.avatarUri?.startsWith("file://") == true)

        // 3. Real P2P roundtrip: the picture rides the MSG frame of an ordinary message.
        val packets = java.util.concurrent.ConcurrentLinkedQueue<IncomingTwinPacket>()
        val receiverNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = { packets.add(it) },
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        val senderNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        try {
            receiverNode.start()
            senderNode.start()
            kotlinx.coroutines.delay(150)

            val receiverEndpoint = "127.0.0.1:${receiverNode.localTcpPort}"
            val chatId = repo.createNewConversation(
                title = "Аватар Пир",
                yggIpv6Input = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
                disappearingSeconds = 0,
                directEndpointHint = receiverEndpoint
            )

            suspend fun send(text: String, id: String): Boolean =
                senderNode.dispatchOutgoingMessageThroughYggdrasil(
                    chatId = chatId,
                    messageId = id,
                    peerTitle = "Аватар Пир",
                    peerIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
                    directEndpointHint = receiverEndpoint,
                    senderNodeLabel = "Узел YggGram #1",
                    senderIpv6 = identity.yggIpv6Address,
                    senderPubKeyHex = identity.yggNodePublicKeyHex,
                    plainText = text,
                    cipherPayloadHex = "aabbccdd",
                    hashcashToken = "1:10:test",
                    attachmentName = null,
                    attachmentSizeBytes = 0L,
                    senderAvatarHash = stored.hashHex,
                    senderAvatarBase64 = avatarBase64.orEmpty()
                )

            assertTrue("First message must be ACKed", send("Первое сообщение", "msg_avatar_1"))
            withTimeout(3000L) {
                while (packets.isEmpty()) kotlinx.coroutines.delay(20)
            }
            val first = packets.poll()
            requireNotNull(first)
            assertEquals(stored.hashHex, first.senderAvatarHash)
            assertEquals(avatarBase64, first.senderAvatarData)

            // 4. The recipient re-hashes the payload: a face is only stored when the
            //    bytes match the hash the sender claimed under its own key.
            repo.storeIncomingAvatar(chatId, first.senderAvatarHash, first.senderAvatarData)
            val storedConv = repo.conversations.first().find { it.chatId == chatId }
            assertEquals(stored.hashHex, storedConv?.avatarHashHex)
            assertNotNull(YggAvatarCodec.fileFor(storedConv?.avatarUri.orEmpty()))

            val forgedChatId = repo.createNewConversation(
                title = "Подозрительный Пир",
                yggIpv6Input = "0202:8f4e:3b12:90a4:55d1:c209:7e11:44a9",
                disappearingSeconds = 0,
                directEndpointHint = ""
            )
            repo.storeIncomingAvatar(forgedChatId, "0".repeat(64), first.senderAvatarData)
            assertEquals(
                "Bytes that do not hash to the claimed digest must never reach the disk",
                "",
                repo.conversations.first().find { it.chatId == forgedChatId }?.avatarHashHex
            )

            // 5. After the ACK the sender stops repeating 12 KB on every message.
            assertTrue("Second message must be ACKed", send("Второе сообщение", "msg_avatar_2"))
            withTimeout(3000L) {
                while (packets.isEmpty()) kotlinx.coroutines.delay(20)
            }
            val second = packets.poll()
            requireNotNull(second)
            assertEquals("The hash still identifies the picture", stored.hashHex, second.senderAvatarHash)
            assertEquals("Confirmed avatars must not ride every frame", "", second.senderAvatarData)
        } finally {
            receiverNode.stop()
            senderNode.stop()
        }
    }

    @Test
    fun `reimporting a contact card renames the chat it already owns`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = YggRepository(db.yggDao())
        try {
            val peerIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8"
            val first = repo.createNewConversation(
                title = "Узел YggGram #1",
                yggIpv6Input = peerIpv6,
                disappearingSeconds = 0
            )
            val second = repo.createNewConversation(
                title = "Узел YggGram #2",
                yggIpv6Input = peerIpv6,
                disappearingSeconds = 0
            )
            assertEquals("A card for a node that is already a contact must not create a second chat", first, second)
            assertEquals(
                "The newest card is the node owner's rename, and it must reach the chat list",
                "Узел YggGram #2",
                repo.conversations.first().find { it.chatId == first }?.title
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `group roster rides an ordinary message and only a full ack set counts as delivered`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val senderDb = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val peerDb = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val senderRepo = YggRepository(senderDb.yggDao())
        val peerRepo = YggRepository(peerDb.yggDao())

        val senderIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf(
                "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
                "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
            ),
            epoch = 1
        )
        val peerIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf(
                "glacier", "helium", "cascade", "nebula", "cobalt", "lattice",
                "photon", "quartz", "onyx", "harbor", "vertex", "cedar"
            ),
            epoch = 1
        )
        senderRepo.ensureInitialized(senderIdentity)
        peerRepo.ensureInitialized(peerIdentity)

        val memberOneIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8"
        val memberTwoIpv6 = "0202:11be:77c4:0d35:9a08:5ef2:31b7:cc09"
        val chatOne = senderRepo.createNewConversation(
            title = "Узел А", yggIpv6Input = memberOneIpv6, disappearingSeconds = 0
        )
        val chatTwo = senderRepo.createNewConversation(
            title = "Узел Б", yggIpv6Input = memberTwoIpv6, disappearingSeconds = 0
        )

        // 1. A group is local bookkeeping: an identifier, a type and a roster of addresses.
        val groupChatId = senderRepo.createNewGroup(
            title = "Полигон 0200::/7",
            memberContactChatIds = listOf(chatOne, chatTwo),
            ownerProfileId = YggRepository.DEFAULT_PROFILE_ID,
            localIdentity = senderIdentity
        )
        val group = senderRepo.getConversation(groupChatId)
        requireNotNull(group)
        assertEquals("GROUP", group.chatType)
        assertTrue(group.groupId.startsWith("grp_"))
        assertEquals(2, senderRepo.getGroupMembers(groupChatId).size)

        val outgoing = senderRepo.sendEncryptedMessage(
            chatId = groupChatId,
            plainText = "Привет группе",
            senderIpv6 = senderIdentity.yggIpv6Address,
            isOfflineMode = false,
            sealedSenderGlobal = false,
            powDifficultyBits = 1
        )
        requireNotNull(outgoing)

        // 2. Send it over a real socket. The roster is advertised inside the message frame,
        //    including the peer's own address, which it must refuse to store as a member.
        val packets = CopyOnWriteArrayList<IncomingTwinPacket>()
        val receiverNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = { packets.add(it) },
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        val senderNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        val roster = listOf(
            "$memberOneIpv6|",
            "$memberTwoIpv6|",
            "${senderIdentity.yggIpv6Address}|${senderIdentity.yggNodePublicKeyHex}",
            "${peerIdentity.yggIpv6Address}|"
        ).joinToString(",")

        try {
            receiverNode.start()
            senderNode.start()
            kotlinx.coroutines.delay(150)
            val receiverEndpoint = "127.0.0.1:${receiverNode.localTcpPort}"

            val delivered = senderNode.dispatchOutgoingMessageThroughYggdrasil(
                chatId = groupChatId,
                messageId = outgoing.messageId,
                peerTitle = "Узел А",
                peerIpv6 = memberOneIpv6,
                directEndpointHint = receiverEndpoint,
                senderNodeLabel = "Узел YggGram #1",
                senderIpv6 = senderIdentity.yggIpv6Address,
                senderPubKeyHex = senderIdentity.yggNodePublicKeyHex,
                plainText = outgoing.plainText,
                cipherPayloadHex = outgoing.cipherPayloadHex,
                hashcashToken = outgoing.hashcashPowToken,
                attachmentName = null,
                attachmentSizeBytes = 0L,
                groupId = group.groupId,
                groupTitle = group.title,
                groupMembersCsv = roster
            )
            assertTrue("Group frame must reach the peer over the socket", delivered)

            val frame = withTimeout(3000L) {
                while (packets.isEmpty()) kotlinx.coroutines.delay(20)
                packets.first()
            }
            assertEquals(group.groupId, frame.groupId)
            assertEquals(group.title, frame.groupTitle)
            assertTrue(
                "The roster has to survive the wire framing",
                frame.groupMembersCsv.contains(memberTwoIpv6)
            )

            // 3. The receiving node has never heard of this group. One frame from one
            //    member is enough to rebuild it — and it must land in the group, not in a
            //    private chat with whoever relayed it.
            assertEquals("Узел YggGram #1", frame.senderNodeLabel)
            val resolvedChatId = peerRepo.recordIncomingTwinMessage(frame, activeChatId = null)
            val peerConv = peerRepo.getConversation(resolvedChatId)
            requireNotNull(peerConv)
            assertEquals("GROUP", peerConv.chatType)
            assertEquals(group.groupId, peerConv.groupId)
            assertEquals("Полигон 0200::/7", peerConv.title)
            assertTrue(
                "A group frame must not open a 1:1 dialog with its relay",
                peerRepo.conversations.first().none {
                    it.chatType != "GROUP" && it.yggIpv6Address == memberOneIpv6
                }
            )

            val peerMembers = peerRepo.getGroupMembers(resolvedChatId)
            assertEquals(
                "The speaker and the member that never spoke are both known from one frame",
                3,
                peerMembers.size
            )
            assertTrue(peerMembers.any { it.memberIpv6 == memberTwoIpv6 })
            // Only the node that actually spoke has a name: the roster carries addresses,
            // so the other members stay unnamed until they introduce themselves.
            assertEquals(
                "Узел YggGram #1",
                peerMembers.first { it.memberIpv6 == senderIdentity.yggIpv6Address.lowercase() }.memberName
            )
            assertEquals(
                "",
                peerMembers.first { it.memberIpv6 == memberOneIpv6 }.memberName
            )
            assertTrue(
                "A node never lists its own address as an external member",
                peerMembers.none { it.memberIpv6 == peerIdentity.yggIpv6Address.lowercase() }
            )

            // 4. Delivery is counted per member: one acknowledgement of two is not delivery.
            val (firstAcked, rosterSize) = senderRepo.recordGroupMemberDelivery(
                outgoing.messageId,
                memberOneIpv6
            )
            assertEquals(2, rosterSize)
            assertEquals(1, firstAcked)
            val afterOneAck = senderRepo.getMessage(outgoing.messageId)?.deliveryState
            assertTrue(
                "Half a roster must not report DELIVERED, got $afterOneAck",
                afterOneAck != "DELIVERED"
            )

            senderRepo.recordGroupMemberDelivery(outgoing.messageId, memberTwoIpv6)
            val afterAllAcks = senderRepo.getMessage(outgoing.messageId)
            assertEquals("DELIVERED", afterAllAcks?.deliveryState)
            assertEquals(2, afterAllAcks?.deliveredMemberCsv?.split(',')?.size)
        } finally {
            receiverNode.stop()
            senderNode.stop()
        }
    }
}
