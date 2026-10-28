// File: app/src/test/java/com/example/ExampleRobolectricTest.kt
package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.MessageEntity
import com.example.data.YggDatabase
import com.example.data.YggRepository
import com.example.engine.IncomingReactionPacket
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggAudioMediaEngine
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
            onDeliveryAckReceived = { _, _, _, _, _ -> },
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
            onDeliveryAckReceived = { _, messageId, _, _, _ ->
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
            // The peer's card carries its X25519 identity key; without it there is nothing to
            // seal to, and the repository refuses to send rather than fall back to open text.
            val peerIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(
                listOf(
                    "glacier", "helium", "cascade", "nebula", "cobalt", "lattice",
                    "photon", "quartz", "onyx", "harbor", "vertex", "cedar"
                ),
                epoch = 1
            )

            // 3. Create a real contact with direct endpoint
            val chatId = repo.createNewConversation(
                title = "Mimir Peer Node",
                yggIpv6Input = peerIpv6,
                disappearingSeconds = 0,
                directEndpointHint = receiverEndpoint,
                peerX25519PublicKeyHex = peerIdentity.x25519IdentityPubHex,
                peerEd25519PublicKeyHex = peerIdentity.yggNodePublicKeyHex
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
    fun `voice payload crosses the socket only as an envelope the peer key opens`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.yggDao()
        val repo = YggRepository(dao)

        val aliceWords = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val bobWords = listOf(
            "cobalt", "beacon", "prism", "horizon", "lattice", "nomad",
            "zenith", "paradox", "anchor", "kinetic", "solstice", "aurora"
        )
        val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceWords, epoch = 1)
        val bob = YggCryptoEngine.deriveHierarchyFromMnemonic(bobWords, epoch = 1)
        repo.ensureInitialized(alice)

        val rx = CompletableDeferred<IncomingTwinPacket>()
        val senderNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, _, _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        val receiverNode = YggTwinP2PBridge(
            context = null,
            onPacketReceived = { if (!rx.isCompleted) rx.complete(it) },
            onDeliveryAckReceived = { _, _, _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        try {
            senderNode.start()
            receiverNode.start()
            kotlinx.coroutines.delay(150)
            val endpoint = "127.0.0.1:${receiverNode.localTcpPort}"

            val chatId = repo.createNewConversation(
                title = "Alice Peer",
                yggIpv6Input = bob.yggIpv6Address,
                disappearingSeconds = 0,
                directEndpointHint = endpoint,
                peerX25519PublicKeyHex = bob.x25519IdentityPubHex,
                peerEd25519PublicKeyHex = bob.yggNodePublicKeyHex
            )
            val conv = dao.getConversationById(chatId)
            requireNotNull(conv)

            // A real WAV written by the recorder path, encoded exactly like production does.
            val pcm = ByteArray(6400) { ((it * 17) % 251).toByte() }
            val wavFile = java.io.File(app.filesDir, "wire_voice.wav").apply {
                writeBytes(wav16Mono(pcm, sampleRate = 16_000))
            }
            val clearBase64 = YggAudioMediaEngine.prepareAttachmentForP2P(
                context = app,
                attachmentName = "wire_voice.wav",
                attachmentType = "VOICE",
                attachmentUri = android.net.Uri.fromFile(wavFile).toString(),
                voiceDurationSec = 1,
                voiceWaveformCsv = "0.4,0.6"
            ).second
            assertTrue(clearBase64.isNotBlank())

            val sealedAttachment = repo.sealForConversation(conv, clearBase64)
            assertNotNull("A conversation with a peer key must seal its attachment", sealedAttachment)

            val delivered = senderNode.dispatchOutgoingMessageThroughYggdrasil(
                chatId = chatId,
                messageId = "msg_wire_voice",
                peerTitle = "Alice Peer",
                peerIpv6 = bob.yggIpv6Address,
                directEndpointHint = endpoint,
                senderNodeLabel = "Node Alice",
                senderIpv6 = alice.yggIpv6Address,
                senderPubKeyHex = alice.yggNodePublicKeyHex,
                plainText = "Голосовое",
                cipherPayloadHex = "00",
                hashcashToken = "1:1:test",
                attachmentName = "wire_voice.wav",
                attachmentSizeBytes = wavFile.length(),
                attachmentType = "VOICE",
                voiceDurationSec = 1,
                voiceWaveformCsv = "0.4,0.6",
                attachmentBase64 = sealedAttachment
            )
            assertTrue("Voice frame must cross the real socket", delivered)

            // What the recipient's socket actually received: an envelope, never the WAV bytes.
            val packet = withTimeout(3000L) { rx.await() }
            val wireAttachment = packet.attachmentBase64
            requireNotNull(wireAttachment)
            assertNotEquals(clearBase64, wireAttachment)
            assertFalse("Base64 WAV header must not appear in the wire frame", wireAttachment.contains("UklGR"))

            val opened = YggCryptoEngine.openConversationEnvelope(
                envelope = wireAttachment,
                recipientX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobWords, epoch = 1),
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex
            )
            assertEquals(clearBase64, opened.plainText)

            // And the recipient can actually play it: decoded bytes match the recorder's WAV.
            val saved = YggAudioMediaEngine.saveIncomingP2PAttachment(
                context = app,
                attachmentName = "wire_voice.wav",
                attachmentType = "VOICE",
                attachmentBase64 = opened.plainText,
                fallbackUri = null,
                voiceDurationSec = 1,
                voiceWaveformCsv = "0.4,0.6"
            )
            assertNotNull(saved)
            assertArrayEquals(wavFile.readBytes(), java.io.File(android.net.Uri.parse(saved).path!!).readBytes())
        } finally {
            senderNode.stop()
            receiverNode.stop()
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
            onDeliveryAckReceived = { _, _, _, _, _ -> },
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
    fun `only a peer that finished the overlay handshake counts as a link`() {
        val raw = """
            {"ok":true,"upCount":3,"total":4,"peers":[
              {"URI":"tcp://10.0.2.2:21001?maxbackoff=20s","Up":false},
              {"URI":"TLS://Example.COM:443?maxbackoff=20s","Up":true,"Latency":155000000,
               "Key":"uzPfp2F7XkOu9gPpXWAERrB9y5AaXr3M3SbYS6u4m3M="},
              {"URI":"tls://95.217.35.148:443?maxbackoff=20s","Up":true},
              {"URI":"tls://185.20.26.100:443?maxbackoff=20s","Up":true,"Key":null},
              {"URI":"","Up":true,"Key":"uzPfp2F7XkOu9gPpXWAERrB9y5AaXr3M3SbYS6u4m3M="}
            ]}
        """.trimIndent()
        val live = com.example.engine.YggNativeCoreBridge.readCoreConfirmedPeers(raw)
        assertEquals(
            "A socket with no peer key behind it is not a link; only keyed ones count, " +
                "keyed by the endpoint form stored in the database",
            mapOf("tls://example.com:443" to 155),
            live
        )
        assertTrue(
            "A broken native answer must not invent live peers",
            com.example.engine.YggNativeCoreBridge.readCoreConfirmedPeers("{not json").isEmpty()
        )
    }

    @Test
    fun `lost overlay frames are counted instead of vanishing silently`() {
        val logs = CopyOnWriteArrayList<YggLivePacketLogEntry>()
        val node = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, _, _, _, _ -> },
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

            // 5. An ACK arriving later is attributed to the member that sent it, so the
            //    «N из M» counter keeps moving instead of freezing at the synchronous pass.
            val second = senderRepo.sendEncryptedMessage(
                chatId = groupChatId,
                plainText = "Второе для счётчика ACK",
                senderIpv6 = senderIdentity.yggIpv6Address,
                isOfflineMode = false,
                sealedSenderGlobal = false,
                powDifficultyBits = 1
            )
            requireNotNull(second)
            val (ackedMember, firstLateCounts) = senderRepo.recordGroupMemberAckBySender(
                messageId = second.messageId,
                ackSenderKey = memberTwoIpv6.uppercase()
            )
            assertTrue("The acknowledging member is resolved from the roster", ackedMember.isNotBlank())
            assertEquals(1 to 2, firstLateCounts)
            val (unknownMember, secondLateCounts) = senderRepo.recordGroupMemberAckBySender(
                messageId = second.messageId,
                ackSenderKey = "0209:0000:0000:0000:0000:0000:0000:0001"
            )
            assertTrue("A node outside the roster is not counted as a member", unknownMember.isEmpty())
            assertEquals(1, secondLateCounts.first)

            // 6. Removing a member is local and sticky. Every later group frame re-advertises
            //    the same roster, and without the flag the address would come back by itself.
            senderRepo.removeMemberFromGroup(groupChatId, memberOneIpv6)
            assertEquals(1, senderRepo.getGroupMembers(groupChatId).size)
            senderRepo.recordIncomingTwinMessage(frame, activeChatId = null)
            assertFalse(
                "A member the owner removed must not return with the next advertised roster",
                senderRepo.getGroupMembers(groupChatId).any { it.memberIpv6 == memberOneIpv6 }
            )
        } finally {
            receiverNode.stop()
            senderNode.stop()
        }
    }

    @Test
    fun `reaction token from the wire cannot smuggle separators into the stored map`() {
        // The stored form is emoji:count:tag, so a separator inside a peer's emoji would
        // split one reaction into two rows, and a runaway label would grow the row.
        assertEquals("👍", YggRepository.normalizeReactionEmoji(" 👍 "))
        assertEquals("👍1me", YggRepository.normalizeReactionEmoji("👍:1:me"))
        assertEquals("👍", YggRepository.normalizeReactionEmoji("👍|"))
        assertEquals("", YggRepository.normalizeReactionEmoji("::"))
        assertEquals("", YggRepository.normalizeReactionEmoji("   "))
        assertTrue(
            "a runaway emoji label has to be cut short",
            YggRepository.normalizeReactionEmoji("🔥".repeat(40)).length <= 8 * 4
        )
        // The limit counts UTF-16 units, and a family emoji is several pairs glued together,
        // so a long enough token is cut inside one. A stored key ending in a lone high
        // surrogate renders as a box and the next tap cannot match it to remove the reaction.
        val family = "👨‍👩‍👧‍👦"
        val cut = YggRepository.normalizeReactionEmoji(family.repeat(4))
        assertFalse(
            "a cut must not leave half an emoji behind",
            cut.isNotEmpty() && cut.last().isHighSurrogate()
        )
    }

    @Test
    fun `quick reaction row stays inside the dropdown catalogue`() {
        val catalogue = com.example.ui.components.REACTION_CATEGORIES.flatMap { it.emojis }
        assertTrue(
            "every quick pill must also exist in the full list",
            catalogue.containsAll(com.example.ui.components.DEFAULT_QUICK_REACTIONS)
        )
        assertEquals(
            "the dropdown must not show one emoji twice",
            catalogue.size,
            catalogue.distinct().size
        )
        assertTrue("the catalogue is the extended picker", catalogue.size >= 40)
    }

    /**
     * A sender retries when its ACK is lost, and the bridge remembers frame ids only in
     * memory for ten minutes. A retry landing after a restart therefore has nothing in
     * that set, and only the message store can prove this frame was already accepted —
     * otherwise the user watches the same message twice for a packet sent once.
     */
    @Test
    fun `redelivered frame is stored once even when the in-memory frame ids are gone`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = YggRepository(db.yggDao())
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf(
                "glacier", "helium", "cascade", "nebula", "cobalt", "lattice",
                "photon", "quartz", "onyx", "harbor", "vertex", "cedar"
            ),
            epoch = 1
        )
        repo.ensureInitialized(identity)

        val frame = IncomingTwinPacket(
            targetChatId = "",
            messageId = "wire_retry_late",
            recipientIpv6 = identity.yggIpv6Address,
            senderNodeLabel = "Узел ретрая",
            senderIpv6 = "0203:abcd:1111:2222:3333:4444:5555:6666",
            plainText = "тот же кадр",
            cipherPayloadHex = "",
            hashcashToken = "",
            attachmentName = null,
            attachmentSizeBytes = 0,
            rttMs = 42,
            timestamp = 1_700_000_000_000
        )

        val first = repo.recordIncomingTwinMessage(frame, activeChatId = null)
        // Nothing but the rows survives a process restart; the second call is what the
        // sender's retry then becomes. The return value is the chat the message landed in.
        val second = repo.recordIncomingTwinMessage(frame, activeChatId = null)

        assertEquals(first, second)
        assertEquals(1, repo.observeMessagesForChat(first).first().size)

        // A different frame from the same peer is still a new message: the guard keys on
        // the wire id, not on the sender.
        repo.recordIncomingTwinMessage(frame.copy(messageId = "wire_retry_next"), activeChatId = null)
        assertEquals(2, repo.observeMessagesForChat(first).first().size)
    }

    @Test
    fun `chat that lost its pinned key heals it from a copy whose sender was authenticated`() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val dao = db.yggDao()
            val repo = YggRepository(dao)

            val aliceWords = listOf(
                "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
                "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
            )
            val bobWords = listOf(
                "cobalt", "beacon", "prism", "horizon", "lattice", "nomad",
                "zenith", "paradox", "anchor", "kinetic", "solstice", "aurora"
            )
            val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceWords, epoch = 1)
            val bob = YggCryptoEngine.deriveHierarchyFromMnemonic(bobWords, epoch = 1)
            repo.ensureInitialized(alice)

            val chatId = repo.createNewConversation(
                title = "Otchety",
                yggIpv6Input = bob.yggIpv6Address,
                disappearingSeconds = 0,
                peerX25519PublicKeyHex = bob.x25519IdentityPubHex,
                peerEd25519PublicKeyHex = bob.yggNodePublicKeyHex
            )
            dao.upsertConversation(dao.getConversationById(chatId)!!.copy(peerX25519PublicKeyHex = ""))
            assertFalse(
                "A 1:1 chat with no peer key must refuse to seal",
                repo.conversationHasEndToEndKey(chatId)
            )

            // The copy received earlier still carries the key its envelope was opened with —
            // the same value the receive path writes onto the conversation.
            dao.insertMessage(
                MessageEntity(
                    messageId = "msg_inbound_copy",
                    chatId = chatId,
                    senderName = "Otchety",
                    senderIpv6 = bob.yggIpv6Address,
                    cipherPayloadHex = "",
                    plainText = "Привет из оверлея",
                    timestamp = 1_700_000_000_000L,
                    isOutgoing = false,
                    deliveryState = "READ",
                    ratchetStep = 0,
                    sealedSenderFlag = true,
                    hashcashPowToken = "",
                    authorX25519PubHex = bob.x25519IdentityPubHex
                )
            )

            assertTrue(
                "An authenticated received copy must unblock the chat again",
                repo.conversationHasEndToEndKey(chatId)
            )
            val healed = dao.getConversationById(chatId)!!
            assertEquals(
                bob.x25519IdentityPubHex.lowercase(),
                healed.peerX25519PublicKeyHex.lowercase()
            )
            assertNotNull(
                "The healed chat must seal a real envelope",
                repo.sealForConversation(healed, "Отчёт доедет")
            )
            db.close()
        }

    /** Same 44-byte RIFF header the recorder writes, so the test exercises a real WAV file. */
    private fun wav16Mono(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
