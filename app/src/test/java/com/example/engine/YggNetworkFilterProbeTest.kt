// File: app/src/test/java/com/example/engine/YggNetworkFilterProbeTest.kt
package com.example.engine

import java.net.ConnectException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict has to come from the pattern of answers, because a single failure means nothing:
 * a peer can be down, a port can be filtered, and the whole link can be dead. These cases are
 * The ones measured on a real phone on an allow-listed uplink, where three of eight reference
 * addresses answered and the rest were refused in tens of milliseconds.
 */
class YggNetworkFilterProbeTest {

    private fun probe(
        label: String,
        byName: Boolean,
        connected: Boolean,
        detail: String,
        stage: YggNetworkFilterProbe.FailureStage? = null
    ) = YggNetworkFilterProbe.Probe(
        YggNetworkFilterProbe.Reference(label, "10.0.0.1", 443, byName),
        connected,
        elapsedMs = 40,
        detail = detail,
        stage = stage
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
    fun `every connect going silent is not reported as no internet`() {
        // Measured on the phone: every reference ran its timeout out while DNS kept answering.
        // Nothing answered and nothing was refused, which is a third behaviour and has to read as
        // one - an absent route and a filter that drops everything look identical from a timeout.
        val probes = YggNetworkFilterProbe.references.map {
            probe(it.label, it.byName, false, "SocketTimeoutException • Read timed out")
        }
        assertEquals(YggNetworkFilterProbe.Verdict.SILENTLY_DROPPED, YggNetworkFilterProbe.classify(probes))
        val summary = YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.SILENTLY_DROPPED, probes)
        assertTrue("Must say nothing was refused: $summary", summary.contains("не был отклонён"))
        assertTrue("Must keep the route case open: $summary", summary.contains("без маршрута"))
        assertTrue(
            "Silence must not be sold as a proven allow-list: $summary",
            !summary.contains("Сеть фильтрует") && summary.contains("нельзя утверждать")
        )
    }

    @Test
    fun `everything explicitly refused is a link with no route, not a silence`() {
        val probes = YggNetworkFilterProbe.references.map {
            probe(it.label, it.byName, false, "ConnectException • Connection refused")
        }
        assertEquals(YggNetworkFilterProbe.Verdict.NO_LINK, YggNetworkFilterProbe.classify(probes))
        val summary = YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.NO_LINK, probes)
        assertTrue("Must not invent a filter verdict: $summary", summary.contains("нет выхода в интернет"))
        assertTrue("Must name the mechanism seen: $summary", summary.contains("8 — отклонено сетью"))
    }

    @Test
    fun `one answered refusal among silences stops being called pure silence`() {
        // Mixed evidence is not silence: something in the network did answer about an address, and
        // the honest reading is that the link carries nothing usable, whatever the timeouts say.
        val probes = YggNetworkFilterProbe.references.mapIndexed { index, ref ->
            if (index == 0) probe(ref.label, ref.byName, false, "ConnectException • Connection refused")
            else probe(ref.label, ref.byName, false, "SocketTimeoutException • Read timed out")
        }
        assertEquals(YggNetworkFilterProbe.Verdict.NO_LINK, YggNetworkFilterProbe.classify(probes))
        val summary = YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.NO_LINK, probes)
        assertTrue("Must count both mechanisms: $summary",
            summary.contains("7 — молчит до таймаута") && summary.contains("1 — отклонено сетью"))
    }

    @Test
    fun `a run with no measurements makes no claim about the network`() {
        assertEquals(YggNetworkFilterProbe.Verdict.NOT_MEASURED, YggNetworkFilterProbe.classify(emptyList()))
        val summary = YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.NOT_MEASURED, emptyList())
        assertTrue("Must not guess: $summary", summary.contains("не утверждается ничего"))
    }

    @Test
    fun `the reason a reference gave is read off the failure, not invented`() {
        assertEquals(
            YggNetworkFilterProbe.Reason.DROPPED,
            YggNetworkFilterProbe.Probe.reasonOf(false, "SocketTimeoutException • Read timed out")
        )
        assertEquals(
            YggNetworkFilterProbe.Reason.REFUSED,
            YggNetworkFilterProbe.Probe.reasonOf(false, "ConnectException • Connection refused")
        )
        assertEquals(
            YggNetworkFilterProbe.Reason.UNRESOLVED,
            YggNetworkFilterProbe.Probe.reasonOf(false, "имя не разрешилось (нет ответа DNS)")
        )
        assertEquals(YggNetworkFilterProbe.Reason.ANSWERED, YggNetworkFilterProbe.Probe.reasonOf(true, "порт открыт"))
    }

    @Test
    fun `evidence lines say which mechanism each address showed`() {
        val probes = YggNetworkFilterProbe.references.mapIndexed { index, ref ->
            if (index == 0) probe(ref.label, ref.byName, true, "порт открыт")
            else probe(ref.label, ref.byName, false, "SocketTimeoutException • Read timed out")
        }
        val lines = YggNetworkFilterProbe.evidenceLines(
            YggNetworkFilterProbe.Report(YggNetworkFilterProbe.Verdict.OPEN, "", probes)
        )
        assertTrue(lines.any { it.contains("не проходит") && it.contains(YggNetworkFilterProbe.Reason.DROPPED.labelRu) })
        assertTrue(
            "An answered address must not be labelled with a failure mechanism",
            lines.first { it.startsWith("проходит") }.let {
                !it.contains(YggNetworkFilterProbe.Reason.ANSWERED.labelRu) &&
                    !it.contains(YggNetworkFilterProbe.Reason.DROPPED.labelRu)
            }
        )
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
            assertTrue(
                "A refused connect is recorded as a connect-stage failure, not a payload one",
                report.probes.filterNot { it.connected }
                    .all { it.stage == YggNetworkFilterProbe.FailureStage.CONNECT }
            )
        } finally {
            YggNetworkFilterProbe.connector = { host, port, timeoutMs ->
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs) }
            }
        }
    }

    @Test
    fun `a refusal during connect says no payload was ever sent`() {
        val probes = YggNetworkFilterProbe.references.map { ref ->
            if (ref.host == "1.1.1.1" || ref.host == "8.8.8.8") {
                probe(ref.label, ref.byName, true, "порт открыт")
            } else {
                probe(
                    ref.label, ref.byName, false, "ConnectException • Connection refused",
                    stage = YggNetworkFilterProbe.FailureStage.CONNECT
                )
            }
        }
        val summary = YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.ADDRESS_FILTERED, probes)
        assertTrue(
            "Must say the refusal came before any byte left: $summary",
            summary.contains("ни байта")
        )
    }

    @Test
    fun `connections that break after the first bytes are not reported as pre-payload refusals`() {
        // The other shape of filtering: the session is established and then reset. There masking
        // is a real question, and the app must not claim the network refused before payload.
        val probes = YggNetworkFilterProbe.references.map { ref ->
            if (ref.host == "1.1.1.1") {
                probe(ref.label, ref.byName, true, "порт открыт")
            } else {
                probe(
                    ref.label, ref.byName, false, "SSLHandshakeException • connection reset",
                    stage = YggNetworkFilterProbe.FailureStage.AFTER_CONNECT
                )
            }
        }
        val summary = YggNetworkFilterProbe.summary(YggNetworkFilterProbe.Verdict.ADDRESS_FILTERED, probes)
        assertTrue("Must name the post-connect stage: $summary", summary.contains("после первых байт"))
        assertTrue("Must not claim bytes stayed unsent: $summary", !summary.contains("ни байта"))
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

    private fun udpProbe(
        referenceIndex: Int,
        outcome: YggNetworkFilterProbe.UdpOutcome,
        replyBytes: Int = 0
    ) = YggNetworkFilterProbe.UdpProbe(
        YggNetworkFilterProbe.udpReferences[referenceIndex],
        outcome,
        replyBytes,
        elapsedMs = 40,
        detail = outcome.labelRu
    )

    @Test
    fun `one answering udp reference proves the path carries`() {
        val probes = listOf(
            udpProbe(0, YggNetworkFilterProbe.UdpOutcome.ANSWERED, replyBytes = 1200),
            udpProbe(1, YggNetworkFilterProbe.UdpOutcome.SILENT),
            udpProbe(2, YggNetworkFilterProbe.UdpOutcome.SILENT)
        )
        val verdict = YggNetworkFilterProbe.classifyUdp(probes)
        assertEquals(YggNetworkFilterProbe.UdpVerdict.ANSWERED, verdict)
        assertTrue(verdict.carriesUdp)
        val summary = YggNetworkFilterProbe.udpSummary(verdict, probes)
        assertTrue("Must say the transport path exists: $summary", summary.contains("UDP/443 проходит"))
        // Peer liveness is a separate fact and the summary must not invent it.
        assertTrue("Must not promise the peer is alive: $summary", !summary.contains("пир жив"))
    }

    @Test
    fun `an icmp port unreachable is evidence about the path not the peer`() {
        val probes = listOf(
            udpProbe(0, YggNetworkFilterProbe.UdpOutcome.NO_LISTENER),
            udpProbe(1, YggNetworkFilterProbe.UdpOutcome.SILENT),
            udpProbe(2, YggNetworkFilterProbe.UdpOutcome.SILENT)
        )
        val verdict = YggNetworkFilterProbe.classifyUdp(probes)
        assertEquals(YggNetworkFilterProbe.UdpVerdict.PATH_OPEN, verdict)
        assertTrue(verdict.carriesUdp)
        val summary = YggNetworkFilterProbe.udpSummary(verdict, probes)
        assertTrue("Must name the ICMP answer: $summary", summary.contains("ICMP"))
        assertTrue("Must not read a silent port as a blocked one: $summary", summary.contains("слушать некому"))
    }

    @Test
    fun `silence on udp is never reported as a blocked port`() {
        val probes = YggNetworkFilterProbe.udpReferences.indices.map {
            udpProbe(it, YggNetworkFilterProbe.UdpOutcome.SILENT)
        }
        val verdict = YggNetworkFilterProbe.classifyUdp(probes)
        assertEquals(YggNetworkFilterProbe.UdpVerdict.SILENT, verdict)
        assertFalse("Unanswered probes must not be claimed as a carried path", verdict.carriesUdp)
        val summary = YggNetworkFilterProbe.udpSummary(verdict, probes)
        // The whole point of the honest wording: from inside the phone a dropped datagram and an
        // ignored one are the same observation, and the sentence has to keep both open.
        assertTrue("Must admit the ambiguity: $summary", summary.contains("нельзя"))
        assertFalse("Must not claim UDP is closed: $summary", summary.contains("закрыт"))
        assertTrue("quic must stay unjudged, not rejected: $summary", summary.contains("непроверенными"))
    }

    @Test
    fun `a socket that will not send is about the device not the network`() {
        val probes = YggNetworkFilterProbe.udpReferences.indices.map {
            udpProbe(it, YggNetworkFilterProbe.UdpOutcome.FAILED)
        }
        assertEquals(YggNetworkFilterProbe.UdpVerdict.LOCAL_FAILURE, YggNetworkFilterProbe.classifyUdp(probes))
        val summary = YggNetworkFilterProbe.udpSummary(YggNetworkFilterProbe.UdpVerdict.LOCAL_FAILURE, probes)
        assertTrue("Must point at the device: $summary", summary.contains("этого устройства"))
        assertFalse(YggNetworkFilterProbe.UdpVerdict.LOCAL_FAILURE.carriesUdp)
    }

    @Test
    fun `an unmeasured udp path stays unmeasured`() {
        assertEquals(
            YggNetworkFilterProbe.UdpVerdict.NOT_MEASURED,
            YggNetworkFilterProbe.classifyUdp(emptyList())
        )
        assertFalse(YggNetworkFilterProbe.UdpVerdict.NOT_MEASURED.carriesUdp)
    }

    @Test
    fun `udp references are literal addresses so dns never enters the measurement`() {
        assertTrue(
            "UDP-референсы обязаны быть адресами: иначе замер смешает DNS и UDP",
            YggNetworkFilterProbe.udpReferences.all {
                it.host.split('.').all { part -> part.toIntOrNull() != null } &&
                    it.host.count { ch -> ch == '.' } == 3
            }
        )
        assertTrue(YggNetworkFilterProbe.udpReferences.all { it.port == 443 })
        assertTrue(YggNetworkFilterProbe.udpReferences.all { it.serverName.isNotBlank() })
    }

    @Test
    fun `probe sends a spec sized initial and reports what came back`() {
        val sent = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, ByteArray>>()
        YggNetworkFilterProbe.udpQuerier = { reference, packet, _ ->
            sent[reference.host] = packet.size to packet
            if (reference.host == "8.8.8.8") {
                YggNetworkFilterProbe.UdpObservation(YggNetworkFilterProbe.UdpOutcome.ANSWERED, 1200, "тест")
            } else {
                YggNetworkFilterProbe.UdpObservation(YggNetworkFilterProbe.UdpOutcome.SILENT, 0, "тест")
            }
        }
        try {
            val report = YggNetworkFilterProbe.probeUdp()
            assertEquals(YggNetworkFilterProbe.udpReferences.size, sent.size)
            assertEquals(YggNetworkFilterProbe.UdpVerdict.ANSWERED, report.verdict)
            sent.values.forEach { (size, packet) ->
                // Below the floor a compliant server must ignore the Initial, which would make the
                // silence mean nothing. The length is therefore part of the measurement's validity.
                assertTrue("пакет $size байт меньше 1200", size >= 1200)
                assertEquals(0xC0, packet[0].toInt() and 0xF0)
            }
            val lines = YggNetworkFilterProbe.udpEvidenceLines(report)
            assertEquals(YggNetworkFilterProbe.udpReferences.size, lines.size)
            assertTrue(lines.first().contains("h3"))
            assertTrue(lines.first().contains("www.google.com"))
        } finally {
            YggNetworkFilterProbe.udpQuerier = { reference, packet, timeoutMs ->
                val socket = java.net.DatagramSocket()
                try {
                    socket.soTimeout = timeoutMs
                    val address = java.net.InetAddress.getByName(reference.host)
                    socket.connect(address, reference.port)
                    socket.send(java.net.DatagramPacket(packet, packet.size))
                    val reply = java.net.DatagramPacket(ByteArray(2048), 2048)
                    socket.receive(reply)
                    YggNetworkFilterProbe.UdpObservation(
                        YggNetworkFilterProbe.UdpOutcome.ANSWERED,
                        reply.length,
                        "restore"
                    )
                } finally {
                    socket.close()
                }
            }
        }
    }
}
