// File: app/src/test/java/com/example/engine/YggCoverTransportTest.kt
package com.example.engine

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the whitelist mask is allowed to claim, checked without a network.
 *
 * The cover transport only re-homes the TLS handshake, so these tests pin the two things that
 * would otherwise be guesses on a device: which peer URIs count as coverable, and that a byte
 * stream through the loopback endpoint arrives unaltered in both directions. The ClientHello
 * itself is proven on the wire, by the capture in the release notes.
 */
class YggCoverTransportTest {

    private val requested = mutableListOf<Triple<String, Int, List<String>>>()
    private lateinit var remoteEcho: ServerSocket

    @Before
    fun setUp() {
        YggCoverTransport.enabled = true
        YggCoverTransport.whitelistMode = false
        YggCoverTransport.whitelistSni = "static-mon.yandex.net"
        YggCoverTransport.lastError = null
        YggCoverTransport.releaseAll()
        requested.clear()
        remoteEcho = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        Thread({
            while (!remoteEcho.isClosed) {
                val peer = try {
                    remoteEcho.accept()
                } catch (_: Throwable) {
                    break
                }
                Thread {
                    // Answer with a marker first so the inbound direction is proven too.
                    runCatching { peer.getOutputStream().write(MARKER) }
                    runCatching { peer.inputStream.copyTo(peer.outputStream) }
                    runCatching { peer.close() }
                }.start()
            }
        }).start()
    }

    @After
    fun tearDown() {
        YggCoverTransport.releaseAll()
        runCatching { remoteEcho.close() }
        YggCoverTransport.enabled = true
        YggCoverTransport.whitelistMode = false
        YggCoverTransport.whitelistSni = ""
        YggCoverTransport.opener = { host, port, sni, offered ->
            YggCoverTransport.openTlsSocket(host, port, sni, offered)
        }
    }

    /** Stands in for a yggdrasil peer: a plain socket, so no TLS listener is needed here. */
    private fun recordingOpener(): (String, Int, String, List<String>) -> Socket =
        { host, port, sni, offered ->
            requested += Triple(host, port, offered)
            assertEquals(YggCoverTransport.whitelistSni, sni)
            Socket().apply { connect(InetSocketAddress("127.0.0.1", remoteEcho.localPort), 2000) }
        }

    @Test
    fun tlsPeerKeepsItsOwnHostAndPortAndTakesTheWhitelistSni() {
        val plan = YggCoverTransport.planFor("tls://95.217.35.148:443")
        assertNotNull(plan)
        assertEquals("95.217.35.148", plan!!.host)
        assertEquals(443, plan.port)
        assertEquals("static-mon.yandex.net", plan.sni)
        assertEquals(false, plan.upgradedFromTcp)
    }

    @Test
    fun uriLevelSniWinsOverTheProfileDomain() {
        val plan = YggCoverTransport.planFor("tls://ygg.zbin.eu:7743?sni=habr.com")
        assertEquals("habr.com", plan!!.sni)
        assertEquals(7743, plan.port)
    }

    @Test
    fun namedTlsPeerWithoutAnyOverrideNamesItself() {
        YggCoverTransport.whitelistSni = ""
        val plan = YggCoverTransport.planFor("tls://ygg.mkg20001.io:443")
        assertEquals("ygg.mkg20001.io", plan!!.sni)
    }

    @Test
    fun cleartextPeerIsOnlyLiftedTo443WhileTheWhitelistProfileIsOn() {
        assertNull(YggCoverTransport.planFor("tcp://139.180.136.252:23901"))
        YggCoverTransport.whitelistMode = true
        val plan = YggCoverTransport.planFor("tcp://139.180.136.252:23901")
        assertNotNull(plan)
        assertEquals(443, plan!!.port)
        assertTrue(plan.upgradedFromTcp)
        assertEquals("139.180.136.252", plan.host)
        assertEquals("static-mon.yandex.net", plan.sni)
    }

    @Test
    fun transportsTheCoreAlreadyCoversItselfAreLeftAlone() {
        YggCoverTransport.whitelistMode = true
        assertNull(YggCoverTransport.planFor("quic://2001:cafe::42:5868"))
        assertNull(YggCoverTransport.planFor("wss://relay.example.ru:443/ygg"))
        YggCoverTransport.enabled = false
        assertNull(YggCoverTransport.planFor("tls://ygg.zbin.eu:443"))
    }

    @Test
    fun otherPeerOptionsDoNotConfuseTheAddress() {
        val plan = YggCoverTransport.planFor("tls://139.180.136.252:23901?maxbackoff=1m")
        assertNotNull(plan)
        assertEquals("139.180.136.252", plan!!.host)
        assertEquals(23901, plan.port)
        assertEquals("static-mon.yandex.net", plan.sni)
    }

    @Test
    fun anIpv6PeerUriIsStillOneHost() {
        val plan = YggCoverTransport.planFor("tls://[2001:db8::1]:443")
        assertNotNull(plan)
        assertEquals("2001:db8::1", plan!!.host)
        assertEquals(443, plan.port)
    }

    @Test
    fun endpointIsStableWhileThePeerLivesAndGoneAfterRelease() {
        YggCoverTransport.opener = recordingOpener()
        val plan = YggCoverTransport.planFor("tls://139.180.136.252:443")!!
        val first = YggCoverTransport.coverEndpoint(plan)
        val second = YggCoverTransport.coverEndpoint(plan)
        // The core identifies a link by scheme/host/port, so a re-dial must be offered the
        // same loopback endpoint or removePeer would no longer find the link it means.
        assertEquals(first, second)
        assertEquals(first, YggCoverTransport.coveredUri(plan.realUri))
        assertEquals(1, YggCoverTransport.activeEndpoints())
        YggCoverTransport.release(plan.realUri)
        assertNull(YggCoverTransport.coveredUri(plan.realUri))
        assertEquals(0, YggCoverTransport.activeEndpoints())
    }

    @Test
    fun bytesReachThePeerUnalteredAndTheAnswerComesBack() {
        YggCoverTransport.opener = recordingOpener()
        val plan = YggCoverTransport.planFor("tls://139.180.136.252:443")!!
        val endpoint = YggCoverTransport.coverEndpoint(plan)!!
        val port = endpoint.substringAfterLast(':').toInt()
        val payload = ByteArray(70_000) { (it % 251).toByte() }

        Socket().use { coreSide ->
            coreSide.connect(InetSocketAddress("127.0.0.1", port), 2000)
            coreSide.outputStream.write(payload)
            coreSide.outputStream.flush()
            val echoed = coreSide.inputStream.readNBytes(MARKER.size + payload.size)
            assertEquals("the peer answer is missing", MARKER.size + payload.size, echoed.size)
            assertEquals("from-peer", String(echoed, 0, MARKER.size))
            assertTrue(
                "the proxy altered the stream",
                payload.contentEquals(echoed.copyOfRange(MARKER.size, echoed.size))
            )
        }
        assertEquals(1, requested.size)
        assertEquals("139.180.136.252", requested[0].first)
        assertEquals(443, requested[0].second)
        assertEquals(listOf("h2", "http/1.1"), requested[0].third)
    }

    @Test
    fun refusedHandshakeIsQuotedAndLeavesTheEndpointReadyForTheNextTry() {
        YggCoverTransport.opener = { _, port, _, _ ->
            throw java.net.ConnectException("peer has no TLS listener on $port")
        }
        val plan = YggCoverTransport.planFor("tls://203.0.113.9:443")!!
        val endpoint = YggCoverTransport.coverEndpoint(plan)!!
        val port = endpoint.substringAfterLast(':').toInt()
        Socket().use { coreSide ->
            coreSide.connect(InetSocketAddress("127.0.0.1", port), 2000)
            // The accepted side has to be closed by the failed handshake: otherwise the core
            // sits on a half-open loopback instead of backing off and dialing again.
            assertEquals(-1, coreSide.inputStream.read())
        }
        assertTrue(YggCoverTransport.lastError.orEmpty().contains("203.0.113.9:443"))
        assertEquals(1, YggCoverTransport.activeEndpoints())
        YggCoverTransport.opener = recordingOpener()
        Socket().use { coreSide ->
            coreSide.connect(InetSocketAddress("127.0.0.1", port), 2000)
            coreSide.outputStream.write(MARKER)
            coreSide.outputStream.flush()
            assertEquals("from-peer", String(coreSide.inputStream.readNBytes(MARKER.size), 0, MARKER.size))
        }
    }

    @Test
    fun probeAnswersPerPeerWithWhatTheHandshakeActuallyDid() {
        var opened = 0
        YggCoverTransport.opener = { host, port, _, _ ->
            if (host == "203.0.113.9") {
                throw java.net.ConnectException("peer has no TLS listener on $port")
            }
            opened++
            Socket().apply { connect(InetSocketAddress("127.0.0.1", remoteEcho.localPort), 2000) }
        }
        val rows = YggCoverTransport.probeMask(
            listOf(
                "tls://95.217.35.148:443",
                "tls://203.0.113.9:443",
                "wss://relay.example.ru:443/ygg"
            )
        )
        // The point of the self-test is that a refusal stays visible: one peer answering is
        // never reported as the mask working in general.
        assertEquals(listOf(true, false, false), rows.map { it.accepted })
        assertTrue(rows[0].detail, rows[0].detail.contains("SNI static-mon.yandex.net"))
        assertTrue(rows[1].detail, rows[1].detail.contains("203.0.113.9:443"))
        assertTrue(rows[2].detail, rows[2].detail.contains("не берётся"))
        assertEquals(1, opened)
        assertEquals("the probe must not keep sessions", 0, YggCoverTransport.activeEndpoints())
    }

    @Test
    fun probeSaysTheMaskIsOffInsteadOfInventingRefusals() {
        assertTrue(YggCoverTransport.probeMask(emptyList()).isEmpty())
        YggCoverTransport.enabled = false
        val rows = YggCoverTransport.probeMask(listOf("tls://95.217.35.148:443"))
        assertEquals(1, rows.size)
        assertTrue(rows[0].detail, rows[0].detail.contains("маска выключена"))
    }

    private companion object {
        val MARKER = "from-peer".toByteArray()
    }
}
