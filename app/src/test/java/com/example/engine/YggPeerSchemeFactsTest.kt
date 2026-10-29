// File: app/src/test/java/com/example/engine/YggPeerSchemeFactsTest.kt
package com.example.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the app is allowed to claim about a peer's transport, checked against the behaviour of
 * the vendored core rather than against a label.
 *
 * Each expectation here is traceable to yggdrasil-go v0.5.14 (src/core/link*.go) or to the
 * loopback QUIC measurement taken while this file was written: the hello the core sends over
 * QUIC carries no server name, `?sni=` is read only by the tls and socks links, and a URI
 * without an explicit port is never dialled because findSuitableIP splits host from port.
 */
class YggPeerSchemeFactsTest {

    private val previousMode = YggCoverTransport.whitelistMode

    @Before
    fun enableWhitelist() {
        YggCoverTransport.whitelistMode = true
    }

    @After
    fun restoreWhitelist() {
        YggCoverTransport.whitelistMode = previousMode
    }

    @Test
    fun `tls peer with a hostname sni names a host and sits on the allowed port`() {
        val facts = YggPeerSchemeFacts.of("tls://95.217.35.148:443", "static-mon.yandex.net")
        assertEquals("tls", facts.scheme)
        assertEquals(443, facts.port)
        assertTrue(facts.dialableByCore)
        assertTrue(facts.onWhitelistPort)
        assertTrue(facts.sendsHostnameSni)
        assertTrue(facts.canBeMasked)
    }

    @Test
    fun `tls peer on an ip with no allowed domain sends no name`() {
        val facts = YggPeerSchemeFacts.of("tls://95.217.35.148:443", "")
        assertFalse(facts.sendsHostnameSni)
        assertTrue(facts.verdictRu.contains("SNI нет"))
    }

    @Test
    fun `portless uri is not dialable and no default port is invented`() {
        // yggdrasil calls net.SplitHostPort on the URI host: without a written port there is
        // nothing to dial, so the app must not fill in 443 to look nicer.
        val tls = YggPeerSchemeFacts.of("tls://[2a00:1748:100:252::a]", "static-mon.yandex.net")
        assertFalse(tls.dialableByCore)
        assertEquals(null, tls.port)
        assertFalse(tls.onWhitelistPort)
    }

    @Test
    fun `wss peer copies its own host into sni and needs no mask`() {
        val named = YggPeerSchemeFacts.of("wss://ws.example.org:443/api", "")
        assertTrue(named.sendsHostnameSni)
        assertFalse(named.canBeMasked)
        assertTrue(named.verdictRu.contains("не читает"))

        val literal = YggPeerSchemeFacts.of("wss://203.0.113.9:443", "static-mon.yandex.net")
        assertFalse(literal.sendsHostnameSni)
    }

    @Test
    fun `quic peer is a working scheme that sends no name and cannot be masked`() {
        val facts = YggPeerSchemeFacts.of("quic://104.16.132.229:443", "static-mon.yandex.net")
        assertEquals("quic", facts.scheme)
        assertTrue(facts.dialableByCore)
        assertTrue(facts.onWhitelistPort)
        // Measured: link_quic.go sets ServerName on a clone it never passes to DialAddr, so
        // the peer reads an empty server_name even when the profile has an allowed domain.
        assertFalse(facts.sendsHostnameSni)
        assertFalse(facts.canBeMasked)
        assertTrue(facts.verdictRu.contains("маски нет"))
    }

    @Test
    fun `tcp peer is cleartext and the whitelist profile moves it onto masked tls`() {
        val facts = YggPeerSchemeFacts.of("tcp://203.0.113.9:5985", "static-mon.yandex.net")
        assertFalse(facts.sendsHostnameSni)
        assertFalse(facts.onWhitelistPort)
        assertTrue(facts.canBeMasked)

        YggCoverTransport.whitelistMode = false
        val open = YggPeerSchemeFacts.of("tcp://203.0.113.9:5985", "static-mon.yandex.net")
        assertFalse(open.canBeMasked)
        assertTrue(open.verdictRu.contains("Открытый текст"))
    }

    @Test
    fun `a websocket peer off port 443 is not called whitelist compatible`() {
        // The scheme name proves nothing: wss://relay:9999 rides a port an allow-list drops,
        // exactly like tcp:// on 5985, and the boot gate has to withhold it the same way.
        val facts = YggPeerSchemeFacts.of("wss://relay.example.org:9999", "")
        assertTrue(facts.dialableByCore)
        assertTrue(facts.sendsHostnameSni)
        assertFalse(facts.onWhitelistPort)
        assertFalse(YggNativeCoreBridge.isWhitelistEndpoint("wss://relay.example.org:9999"))
        assertTrue(YggNativeCoreBridge.isWhitelistEndpoint("wss://relay.example.org:443/api"))
    }

    @Test
    fun `scheme the core does not dial is reported as undialable`() {
        val facts = YggPeerSchemeFacts.of("shadowtls://203.0.113.9:443", "")
        assertFalse(facts.dialableByCore)
        assertFalse(facts.canBeMasked)
        assertTrue(facts.verdictRu.contains("не диалит"))
    }

    @Test
    fun `port label only calls a peer whitelisted when the port is written`() {
        assertEquals("порт 443 (под белый список)", YggPeerSchemeFacts.portLabelRu("tls://h.example:443", true))
        assertEquals("порт 5985", YggPeerSchemeFacts.portLabelRu("tcp://h.example:5985", false))
        assertEquals("порт не указан", YggPeerSchemeFacts.portLabelRu("tls://h.example", true))
    }

    @Test
    fun `a quic peer does not inherit the tcp whitelist verdict`() {
        // UDP/443 was never measured, so the label has to say which port the measurement covered.
        assertEquals(
            "порт 443 по UDP (белый список по TCP его не проверял)",
            YggPeerSchemeFacts.portLabelRu("quic://168.119.153.26:443", true)
        )
    }

    @Test
    fun `summary names the transport the port and the name in the hello`() {
        val summary = YggPeerSchemeFacts.summaryRu("tls://95.217.35.148:443", "static-mon.yandex.net")
        assertTrue(summary.startsWith("tls:// — TLS поверх TCP:443"))
        assertTrue(summary.contains("SNI есть"))
    }
}
