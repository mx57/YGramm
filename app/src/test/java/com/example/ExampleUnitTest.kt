// File: app/src/test/java/com/example/ExampleUnitTest.kt
package com.example

import com.example.engine.IncomingTwinPacket
import com.example.engine.YggCryptoEngine
import com.example.engine.YggPeerScannerCatalog
import com.example.engine.YggQrCodeCodec
import com.example.engine.YggTwinP2PBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun slip0010HierarchyAndYggIpv6_areDeterministicAndValid() {
        val mnemonic = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val first = YggCryptoEngine.deriveHierarchyFromMnemonic(mnemonic, epoch = 1)
        val second = YggCryptoEngine.deriveHierarchyFromMnemonic(mnemonic, epoch = 1)
        val rotated = YggCryptoEngine.deriveHierarchyFromMnemonic(mnemonic, epoch = 2)

        assertEquals(first.yggIpv6Address, second.yggIpv6Address)
        assertTrue(first.yggIpv6Address.startsWith("0201:"))
        assertEquals(first.yggNodePublicKeyHex, rotated.yggNodePublicKeyHex)
        assertNotEquals(first.x25519SignedPreKeyHex, rotated.x25519SignedPreKeyHex)
    }

    @Test
    fun doubleRatchetAndHashcashAndMerkle_produceValidEnvelopes() {
        val cipherHex = YggCryptoEngine.encryptDoubleRatchetEnvelope(
            plainText = "Проверка обхода ТСПУ и Белых списков",
            peerPublicKeyHex = "7f8b3c1099e245a7d108c633b49a22f1",
            ratchetStep = 42,
            sealedSender = true
        )
        assertTrue(cipherHex.length >= 32)

        val pow = YggCryptoEngine.computeHashcashStamp(
            recipientIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
            payloadHashHex = cipherHex,
            difficultyBits = 8
        )
        assertTrue(pow.startsWith("hc1:8:"))

        val (chunks, merkleRoot) = YggCryptoEngine.computeFastCdcMerkleProfile(
            fileName = "ygg_whitelist_bridges_ru.json.zst",
            fileSizeBytes = 262_144L
        )
        assertEquals(4, chunks)
        assertEquals(64, merkleRoot.length)
    }

    @Test
    fun qrUriParser_extractsContactFields() {
        val uri = YggQrCodeCodec.buildContactUri(
            displayName = "Пир-Нода",
            yggIpv6Address = "0201:7c91:44e0:11a2:88d3:50f1:99b2:0002",
            ed25519PubKeyHex = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff",
            localLanIp = "10.0.0.15",
            localPort = 51921
        )
        val parsed = YggQrCodeCodec.parseContactString(uri)
        assertNotNull(parsed)
        assertEquals("Пир-Нода", parsed?.displayName)
        assertEquals("0201:7c91:44e0:11a2:88d3:50f1:99b2:0002", parsed?.yggIpv6Address)
        assertEquals("10.0.0.15:51921", parsed?.directLanHint)
    }

    @Test
    fun peerScannerCatalog_filtersByCountryAndWhitelist() {
        val ruPeers = YggPeerScannerCatalog.initialCatalog.filter { it.countryCode == "RU" }
        val fiPeers = YggPeerScannerCatalog.initialCatalog.filter { it.countryCode == "FI" }
        assertTrue(ruPeers.size >= 4)
        assertTrue(fiPeers.size >= 2)
    }

    @Test
    fun mainNodeAndCloneNode_exchangeDirectBidirectionalP2PMessages() = runBlocking {
        val cloneReceivedFromMain = CompletableDeferred<IncomingTwinPacket>()
        val mainReceivedFromClone = CompletableDeferred<IncomingTwinPacket>()

        val mainNodeBridge = YggTwinP2PBridge(
            context = null,
            onPacketReceived = { pkt ->
                if (pkt.plainText.contains("Ответ от Клона #2") && !mainReceivedFromClone.isCompleted) {
                    mainReceivedFromClone.complete(pkt)
                }
            },
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )

        val cloneNodeBridge = YggTwinP2PBridge(
            context = null,
            onPacketReceived = { pkt ->
                if (pkt.plainText.contains("Привет от Основного APK #1") && !cloneReceivedFromMain.isCompleted) {
                    cloneReceivedFromMain.complete(pkt)
                }
            },
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )

        try {
            mainNodeBridge.start()
            cloneNodeBridge.start()
            kotlinx.coroutines.delay(150)

            val cloneEndpoint = "127.0.0.1:${cloneNodeBridge.localTcpPort}"
            val mainEndpoint = "127.0.0.1:${mainNodeBridge.localTcpPort}"

            mainNodeBridge.dispatchOutgoingMessageThroughYggdrasil(
                chatId = "chat_twin_node",
                messageId = "msg_main_to_clone",
                peerTitle = "Узел #2: YggGram Клон",
                peerIpv6 = "0201:7c91:44e0:11a2:88d3:50f1:99b2:0002",
                directEndpointHint = cloneEndpoint,
                senderNodeLabel = "Узел #1: YggGram Основной",
                senderIpv6 = "0201:7c91:44e0:11a2:88d3:50f1:99b2:0001",
                senderPubKeyHex = "aabbcc",
                plainText = "Привет от Основного APK #1 через прямой P2P сокет!",
                cipherPayloadHex = "c0112233445566778899aabbccddeeff",
                hashcashToken = "hc1:10:1729200001:7:0000",
                attachmentName = null,
                attachmentSizeBytes = 0L
            )

            val receivedAtClone = withTimeout(3000L) { cloneReceivedFromMain.await() }
            assertEquals("Привет от Основного APK #1 через прямой P2P сокет!", receivedAtClone.plainText)

            cloneNodeBridge.dispatchOutgoingMessageThroughYggdrasil(
                chatId = "chat_twin_node",
                messageId = "msg_clone_to_main",
                peerTitle = "Узел #1: YggGram Основной",
                peerIpv6 = "0201:7c91:44e0:11a2:88d3:50f1:99b2:0001",
                directEndpointHint = mainEndpoint,
                senderNodeLabel = "Узел #2: YggGram Клон",
                senderIpv6 = "0201:7c91:44e0:11a2:88d3:50f1:99b2:0002",
                senderPubKeyHex = "ddeeff",
                plainText = "Ответ от Клона #2 доставлен в Основной APK!",
                cipherPayloadHex = "f099887766554433221100ffeeddccbb",
                hashcashToken = "hc1:10:1729200002:9:0000",
                attachmentName = null,
                attachmentSizeBytes = 0L
            )

            val receivedAtMain = withTimeout(3000L) { mainReceivedFromClone.await() }
            assertEquals("Ответ от Клона #2 доставлен в Основной APK!", receivedAtMain.plainText)
        } finally {
            mainNodeBridge.stop()
            cloneNodeBridge.stop()
        }
    }
}
