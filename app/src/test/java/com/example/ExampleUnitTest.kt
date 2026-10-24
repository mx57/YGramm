// File: app/src/test/java/com/example/ExampleUnitTest.kt
package com.example

import com.example.engine.IncomingTwinPacket
import com.example.engine.YggCryptoEngine
import com.example.engine.YggNativeCoreBridge
import com.example.engine.YggPeerScannerCatalog
import com.example.engine.YggQrCodeCodec
import com.example.engine.YggTwinP2PBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hexStr(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun x25519_matchesRfc7748_61Vectors() {
        // RFC 7748 §6.1: Alice keypair and the shared secret with Bob.
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")

        val alicePub = YggCryptoEngine.x25519PublicKeyFromPrivate(alicePriv)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePub.hexStr())

        val shared = YggCryptoEngine.x25519(alicePriv, bobPub)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", shared.hexStr())
    }

    @Test
    fun deriveHierarchyFromMnemonic_producesValidX25519Identity() {
        // Regression: the X25519 base point constant must be a full 32-byte u-coordinate,
        // otherwise every hierarchy derivation throws IllegalArgumentException.
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            YggCryptoEngine.generateFreshMnemonic12(), epoch = 1
        )
        assertEquals(64, identity.x25519IdentityPubHex.length)
        assertTrue(identity.x25519IdentityPubHex.any { it != '0' })
    }

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
        // RFC 5952 compression can drop leading zeros of the first group (0x0200 -> "200"),
        // so 200::/7 membership is asserted numerically, not by string prefix.
        val firstGroup = first.yggIpv6Address.substringBefore(":").toInt(16)
        assertTrue("Real Yggdrasil v0.5 address must fall into 200::/7", firstGroup in 0x200..0x3ff)
        // Same epoch: fully deterministic. Epoch rotation re-derives the node keypair
        // and chat X25519 prekey (the identity rotates with the key epoch).
        assertEquals(first.yggNodePublicKeyHex, second.yggNodePublicKeyHex)
        assertNotEquals(first.yggNodePublicKeyHex, rotated.yggNodePublicKeyHex)
        assertNotEquals(first.x25519SignedPreKeyHex, rotated.x25519SignedPreKeyHex)
    }

    @Test
    fun envelopeSealOpen_roundTripAuthenticatesSender() {
        val aliceMnemonic = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val bobMnemonic = listOf(
            "cobalt", "beacon", "prism", "horizon", "lattice", "nomad",
            "zenith", "paradox", "anchor", "kinetic", "solstice", "aurora"
        )
        val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceMnemonic, epoch = 1)
        val bob = YggCryptoEngine.deriveHierarchyFromMnemonic(bobMnemonic, epoch = 1)

        val envelope = YggCryptoEngine.sealConversationEnvelope(
            plainText = "Проверка E2E: AES-256-GCM + X25519",
            senderX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(aliceMnemonic, epoch = 1),
            senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex,
            peerX25519PublicKeyHex = bob.x25519IdentityPubHex,
            keyEpoch = 1
        )
        requireNotNull(envelope)
        val opened = YggCryptoEngine.openConversationEnvelope(
            envelope = envelope,
            recipientX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobMnemonic, epoch = 1),
            senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex
        )
        assertEquals("Проверка E2E: AES-256-GCM + X25519", opened.plainText)
        assertEquals(alice.x25519IdentityPubHex, opened.senderX25519PublicKeyHex)
    }

    @Test
    fun envelopeSeal_refusesWithoutPeerKey_andReaderRejectsPublicOnlyEnvelope() {
        val aliceMnemonic = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val bobMnemonic = listOf(
            "cobalt", "beacon", "prism", "horizon", "lattice", "nomad",
            "zenith", "paradox", "anchor", "kinetic", "solstice", "aurora"
        )
        val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceMnemonic, epoch = 1)
        val senderPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(aliceMnemonic, epoch = 1)

        // No peer key means no shared secret: nothing may be sealed, not even a stub.
        assertNull(YggCryptoEngine.sealConversationEnvelope("текст", senderPriv, alice.yggNodePublicKeyHex, null))
        assertNull(
            YggCryptoEngine.sealConversationEnvelope(
                "текст", senderPriv, alice.yggNodePublicKeyHex, "not-a-key"
            )
        )

        // The v1 envelope was keyed from public values alone, so anyone who recorded the
        // traffic could decrypt it. Such frames must be refused, not silently opened.
        val forgedV1 = "{\"v\":1,\"sp\":\"${alice.yggNodePublicKeyHex}\",\"step\":7,\"sd\":true," +
            "\"iv\":\"${"0".repeat(24)}\",\"ct\":\"00\"}"
        assertThrows(SecurityException::class.java) {
            YggCryptoEngine.openConversationEnvelope(
                envelope = forgedV1,
                recipientX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobMnemonic, epoch = 1),
                senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex
            )
        }
    }

    @Test
    fun attachmentEnvelope_sealsBytesAndStaysUnreadableOnTheWire() {
        val aliceMnemonic = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val bobMnemonic = listOf(
            "cobalt", "beacon", "prism", "horizon", "lattice", "nomad",
            "zenith", "paradox", "anchor", "kinetic", "solstice", "aurora"
        )
        val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceMnemonic, epoch = 1)
        val bob = YggCryptoEngine.deriveHierarchyFromMnemonic(bobMnemonic, epoch = 1)

        // A chunk of a real voice WAV, base64'd exactly the way the attachment sender does it.
        val wavHeader = "RIFF$\u0000\u0000\u0000WAVEfmt ".toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(wavHeader + ByteArray(2047) { (it % 251).toByte() })

        val sealed = YggCryptoEngine.sealConversationEnvelope(
            plainText = payload,
            senderX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(aliceMnemonic, epoch = 1),
            senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex,
            peerX25519PublicKeyHex = bob.x25519IdentityPubHex,
            keyEpoch = 1
        )
        requireNotNull(sealed)
        assertFalse("Sealed chunk must not carry its own clear payload", sealed.contains(payload))
        assertTrue(sealed.contains("\"v\":"))
        val opened = YggCryptoEngine.openConversationEnvelope(
            envelope = sealed,
            recipientX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobMnemonic, epoch = 1),
            senderEd25519PublicKeyHex = alice.yggNodePublicKeyHex
        )
        assertEquals(payload, opened.plainText)
    }

    @Test
    fun callMediaFrame_isBoundToItsSequenceNumber() {
        val bobMnemonic = listOf(
            "cobalt", "beacon", "prism", "horizon", "lattice", "nomad",
            "zenith", "paradox", "anchor", "kinetic", "solstice", "aurora"
        )
        val aliceMnemonic = listOf(
            "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
            "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
        )
        val alice = YggCryptoEngine.deriveHierarchyFromMnemonic(aliceMnemonic, epoch = 1)
        val bob = YggCryptoEngine.deriveHierarchyFromMnemonic(bobMnemonic, epoch = 1)
        val callId = "call_0f2e1d"
        val pcm = ByteArray(1280) { (it % 7).toByte() }

        val aliceKey = YggCryptoEngine.deriveCallMediaKey(
            YggCryptoEngine.x25519PrivateKeyFromMnemonic(aliceMnemonic, epoch = 1),
            bob.x25519IdentityPubHex,
            callId
        )
        val bobKey = YggCryptoEngine.deriveCallMediaKey(
            YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobMnemonic, epoch = 1),
            alice.x25519IdentityPubHex,
            callId
        )
        val (iv, ct) = YggCryptoEngine.sealCallMediaFrame(aliceKey, pcm, callId, seq = 42L)
        assertTrue(
            "Frame must decrypt to the exact PCM bytes",
            pcm.contentEquals(YggCryptoEngine.openCallMediaFrame(bobKey, iv, ct, callId, seq = 42L))
        )

        // Reordered or replayed inside the same call: the sequence is authenticated data, so
        // the tag fails instead of playing audio out of order.
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            YggCryptoEngine.openCallMediaFrame(bobKey, iv, ct, callId, seq = 43L)
        }
        // The same frame in another call must fail too (the key is derived per call id).
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            YggCryptoEngine.openCallMediaFrame(
                YggCryptoEngine.deriveCallMediaKey(
                    YggCryptoEngine.x25519PrivateKeyFromMnemonic(bobMnemonic, epoch = 1),
                    alice.x25519IdentityPubHex,
                    "call_99a0bb"
                ),
                iv, ct, "call_99a0bb", seq = 42L
            )
        }
    }

    @Test
    fun hashcashAndFastCdcMerkle_produceValidOutputs() {
        val pow = YggCryptoEngine.computeHashcashStamp(
            recipientIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
            payloadHashHex = "c0112233445566778899aabbccddeeff",
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
    fun registerDirectEndpointForPeer_doesNotTriggerNetworkOnMainThreadException() {
        val bridge = YggTwinP2PBridge(
            context = null,
            onPacketReceived = {},
            onDeliveryAckReceived = { _, _, _ -> },
            onTwinReachableChanged = {},
            onPacketLogAdded = {}
        )
        bridge.registerDirectEndpointForPeer("200:1234::1", "192.168.1.100:51921")
        bridge.registerDirectEndpointForPeer("200:5678::2", "example.com:51921")
        bridge.registerDirectEndpointForPeer("200:9999::3", "127.0.0.1:51922")
    }

    @Test
    fun qrUriParser_extractsContactFields() {
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf(
                "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
                "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
            ),
            epoch = 1
        )
        val uri = YggQrCodeCodec.buildContactUri(
            displayName = "Пир-Нода",
            yggIpv6Address = identity.yggIpv6Address,
            ed25519PubKeyHex = identity.yggNodePublicKeyHex,
            localLanIp = "10.0.0.15",
            localPort = 51921
        )
        val parsed = YggQrCodeCodec.parseContactString(uri)
        assertNotNull(parsed)
        assertEquals("Пир-Нода", parsed?.displayName)
        assertEquals(identity.yggIpv6Address, parsed?.yggIpv6Address)
        assertEquals("10.0.0.15:51921", parsed?.directLanHint)
    }

    @Test
    fun qrUriParser_flagsTruncatedNodeKeyInsteadOfSilentlyDroppingIt() {
        val identity = YggCryptoEngine.deriveHierarchyFromMnemonic(
            listOf(
                "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
                "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
            ),
            epoch = 1
        )
        val truncated = identity.yggNodePublicKeyHex.dropLast(1)
        assertEquals(63, truncated.length)

        val brokenCard = YggQrCodeCodec.parseContactString(
            "ygggram://${identity.yggIpv6Address}?pub=$truncated&name=Y"
        )
        assertNotNull(brokenCard)
        assertEquals("", brokenCard?.ed25519PubKeyHex)
        assertTrue(brokenCard?.nodeKeyProblem?.contains("63") == true)

        val cleanCard = YggQrCodeCodec.parseContactString(
            "ygggram://${identity.yggIpv6Address}?pub=${identity.yggNodePublicKeyHex}&name=Y"
        )
        assertEquals("", cleanCard?.nodeKeyProblem)
        assertEquals(identity.yggNodePublicKeyHex.lowercase(), cleanCard?.ed25519PubKeyHex)
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
                if (pkt.cipherPayloadHex == "f099887766554433221100ffeeddccbb" && !mainReceivedFromClone.isCompleted) {
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
                if (pkt.cipherPayloadHex == "c0112233445566778899aabbccddeeff" && !cloneReceivedFromMain.isCompleted) {
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

            val deliveredToClone = mainNodeBridge.dispatchOutgoingMessageThroughYggdrasil(
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

            assertTrue("Dispatch to clone must be delivered over the direct P2P socket", deliveredToClone)

            val receivedAtClone = withTimeout(3000L) { cloneReceivedFromMain.await() }
            assertEquals("c0112233445566778899aabbccddeeff", receivedAtClone.cipherPayloadHex)

            val deliveredToMain = cloneNodeBridge.dispatchOutgoingMessageThroughYggdrasil(
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

            assertTrue("Dispatch back to main node must be delivered", deliveredToMain)

            val receivedAtMain = withTimeout(3000L) { mainReceivedFromClone.await() }
            assertEquals("f099887766554433221100ffeeddccbb", receivedAtMain.cipherPayloadHex)
        } finally {
            mainNodeBridge.stop()
            cloneNodeBridge.stop()
        }
    }

    @Test
    fun sni_is_only_appended_where_the_core_can_act_on_it() {
        // tls:// is the only link type in yggdrasil that reads ?sni=, and Go's crypto/tls
        // drops an IP literal from SNI, so an IP sniDomain must not be smuggled in.
        assertEquals(
            "tls://95.217.35.148:443?sni=ya.ru",
            YggNativeCoreBridge.corePeerUri("tls://95.217.35.148:443", "YA.RU")
        )
        assertEquals(
            "tls://185.22.172.110:443",
            YggNativeCoreBridge.corePeerUri("tls://185.22.172.110:443", "185.22.172.110")
        )
        assertEquals(
            "wss://ygg.example:443",
            YggNativeCoreBridge.corePeerUri("wss://ygg.example:443", "ya.ru")
        )
        assertEquals(
            "tls://ygg.example:443?maxbackoff=20s",
            YggNativeCoreBridge.corePeerUri("tls://ygg.example:443?maxbackoff=20s", "ya.ru")
        )
        for (junk in listOf("", "not a host", "bad host", "-dash.example", "trailing-.")) {
            assertEquals(
                "SNI must never carry $junk",
                "tls://185.22.172.110:443",
                YggNativeCoreBridge.corePeerUri("tls://185.22.172.110:443", junk)
            )
        }
    }

    @Test
    fun the_first_links_a_node_dials_are_the_ones_that_name_a_host() {
        assertFalse(
            "tls://IP with an IP sniDomain still has no server name",
            YggNativeCoreBridge.sendsHostnameSni("tls://95.217.35.148:443", "95.217.35.148")
        )
        assertTrue(
            "tls://IP with a hostname sniDomain names that host",
            YggNativeCoreBridge.sendsHostnameSni("tls://95.217.35.148:443", "ygg.example.net")
        )
        assertTrue(
            "tls:// by domain already sends the name as ServerName",
            YggNativeCoreBridge.sendsHostnameSni("tls://ygg.zbin.eu:7743", "ygg.zbin.eu")
        )
        assertTrue(
            "wss:// by domain does too",
            YggNativeCoreBridge.sendsHostnameSni("wss://host.example:443/api/v2/socket", "")
        )
        assertFalse(
            "tcp:// has no TLS at all",
            YggNativeCoreBridge.sendsHostnameSni("tcp://95.217.35.148:9317", "ygg.example.net")
        )
        assertFalse(
            "an IPv6 literal in brackets is not a hostname",
            YggNativeCoreBridge.sendsHostnameSni("tls://[2a00:1748:100:252::a]:443", "")
        )

        // A node only dials the first sixteen peers, so this ordering is what decides
        // whether the uplink looks like HTTPS or like an unnamed TLS hello.
        val dialOrder = YggPeerScannerCatalog.initialCatalog
            .sortedByDescending { YggNativeCoreBridge.sendsHostnameSni(it.endpointUri, it.sniDomain) }
            .take(16)
        val naming = dialOrder.count { YggNativeCoreBridge.sendsHostnameSni(it.endpointUri, it.sniDomain) }
        assertEquals("the whole dial list must name a host", 16, naming)
    }
}
