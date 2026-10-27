// File: app/src/test/java/com/example/engine/YggNetworkFilterProbeTest.kt
package com.example.engine

import java.net.ConnectException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict has to come from the pattern of answers, because a single failure means nothing:
 * a peer can be down, a port can be filtered, and the whole link can be dead. These cases are
 * the ones measured on a real phone on an allow-listed uplink, where two of eight reference
 * addresses answered and the rest were refused in tens of milliseconds.
 */
class YggNetworkFilterProbeTest {

    private fun probe(label: String, byName: Boolean, connected: Boolean, detail: String) =
        YggNetworkFilterProbe.Probe(
            YggNetworkFilterProbe.Reference(label, "10.0.0.1", 443, byName),
            connected,
            elapsedMs = 40,
            detail = detail
        )

    @Test
    fun `every reference answering is an open network`() {
        val probes = YggNetworkFilterProbe.references.map {
            probe(it.label, it.byName, connected = true, detail = "порт открыт")
        }
        assertEquals(YggNetworkFilterProbe.Verdict.OPEN, YggNetworkFilterProbe.classify(probes))
    }

    @Test
    fun `a subset of answers is the network choosing by address`() {
        // Exactly the phone's shape: DNS infrastructure and a Yandex edge answer, hosting
        // providers and public nodes get refused before any payload is sent.
        val probes = YggNetworkFilterProbe.references.map { ref ->
            when {
                ref.host == "8.8.8.8" || ref.label.startsWith("Yandex") ->
                    probe(ref.label, ref.byName, true, "порт открыт")
                else -> probe(ref.label, ref.byName, false, "ConnectException • Connection refused")
            }
        }
        assertEquals(YggNetworkFilterProbe.Verdict.ADDRESS_FILTERED, YggNetworkFilterProbe.classify(probes))
        val summary = YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.ADDRESS_FILTERED, probes)
        assertTrue("Must name the filter as address-based: $summary", summary.contains("по адресу назначения"))
        assertTrue("Must say masking changes nothing here: $summary", summary.contains("ничего не меняют"))
    }

    @Test
    fun `names refusing to resolve while addresses answer is a DNS filter`() {
        val probes = YggNetworkFilterProbe.references.map { ref ->
            if (ref.byName) {
                probe(ref.label, ref.byName, false, "имя не разрешилось (nodename nor servname provided)")
            } else {
                probe(ref.label, ref.byName, true, "порт открыт")
            }
        }
        assertEquals(YggNetworkFilterProbe.Verdict.DNS_FILTERED, YggNetworkFilterProbe.classify(probes))
    }

    @Test
    fun `nothing answering is reported as no link, not as a filter`() {
        val probes = YggNetworkFilterProbe.references.map {
            probe(it.label, it.byName, false, "SocketTimeoutException")
        }
        assertEquals(YggNetworkFilterProbe.Verdict.NO_LINK, YggNetworkFilterProbe.classify(probes))
        val summary = YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.NO_LINK, probes)
        assertTrue("Must not invent a filter verdict: $summary", summary.contains("нет выхода в интернет"))
    }

    @Test
    fun `one silent reference is not called a filter`() {
        // A dead destination would otherwise turn an ordinary network into a scary verdict.
        val probes = YggNetworkFilterProbe.references.mapIndexed { index, ref ->
            if (index == 0) probe(ref.label, ref.byName, false, "SocketTimeoutException")
            else probe(ref.label, ref.byName, true, "порт открыт")
        }
        assertEquals(YggNetworkFilterProbe.Verdict.OPEN, YggNetworkFilterProbe.classify(probes))
        assertTrue(
            YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.OPEN, probes)
                .contains("на признак фильтра это не тянет")
        )
    }

    @Test
    fun `a probe run records the reason each reference gave`() {
        val refusals = setOf("github.com", "140.82.121.4")
        YggNetworkFilterProbe.connector = { host, _, _ ->
            if (host in refusals) throw ConnectException("Connection refused")
        }
        try {
            val report = YggNetworkFilterProbe.probe()
            assertEquals(YggNetworkFilterProbe.references.size, report.probes.size)
            assertEquals(
                "Refused addresses are named with their error, answered ones are counted",
                YggNetworkFilterProbe.references.size - refusals.size,
                report.probes.count { it.connected }
            )
            val lines = YggNetworkFilterProbe.evidenceLines(report)
            assertTrue(lines.any { it.startsWith("не проходит") && it.contains("github.com") })
            assertTrue(lines.any { it.startsWith("проходит") })
            assertTrue(
                "A partly-permitted link is an address filter, whatever the app would like to believe",
                report.verdict == YggNetworkFilterProbe.Verdict.ADDRESS_FILTERED
            )
        } finally {
            YggNetworkFilterProbe.connector = { host, port, timeoutMs ->
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs) }
            }
        }
    }

    @Test
    fun `a hostname that will not resolve is not counted as a closed port`() {
        YggNetworkFilterProbe.connector = { _, _, _ -> throw UnknownHostException("no answer from DNS server") }
        try {
            val report = YggNetworkFilterProbe.probe()
            assertEquals(YggNetworkFilterProbe.Verdict.NO_LINK, report.verdict)
            assertTrue(
                report.probes.filter { it.reference.byName }.all { it.detail.startsWith("имя не разрешилось") }
            )
        } finally {
            YggNetworkFilterProbe.connector = { host, port, timeoutMs ->
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs) }
            }
        }
    }
}
