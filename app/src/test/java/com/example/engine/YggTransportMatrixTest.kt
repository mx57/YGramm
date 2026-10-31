// File: app/src/test/java/com/example/engine/YggTransportMatrixTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matrix is where a reader decides whether a quic:// peer is worth adding, so its rows must
 * not borrow evidence from each other. These cases hold the two rules the screen exists for: a TCP
 * verdict never speaks for UDP, and a scheme nobody measured says so out loud.
 */
class YggTransportMatrixTest {

    private fun listener(scheme: String, bound: Boolean, address: String = "", error: String = "") =
        YggNativeCoreBridge.CoreListener(
            scheme = scheme,
            uri = "$scheme://0.0.0.0:443",
            bound = bound,
            address = address,
            error = error,
            recordedAtMs = 1L
        )

    private val fullListeners = listOf(
        listener("tcp", bound = true, address = "[::]:5985"),
        listener("tls", bound = false, error = "permission denied"),
        listener("wss", bound = false, error = "WSS listener not supported"),
        listener("quic", bound = false, error = "bind udp :443: permission denied")
    )

    @Test
    fun `every scheme gets its own row`() {
        val rows = YggTransportMatrix.rows(
            YggNetworkFilterProbe.Verdict.OPEN,
            YggNetworkFilterProbe.UdpVerdict.ANSWERED,
            fullListeners,
            whitelistMode = false
        )
        assertEquals(4, rows.size)
        assertEquals(
            listOf("tcp:// (у публичных узлов обычно 5985)", "tls://443", "wss://443", "quic://443 по UDP"),
            rows.map { it.schemeRu }
        )
    }

    @Test
    fun `tcp verdict does not leak into the quic row`() {
        val rows = YggTransportMatrix.rows(
            YggNetworkFilterProbe.Verdict.OPEN,
            YggNetworkFilterProbe.UdpVerdict.SILENT,
            fullListeners,
            whitelistMode = false
        )
        val quic = rows.first { it.schemeRu.startsWith("quic") }
        assertTrue("Ряд quic обязан опираться на UDP-замер: ${quic.outboundRu}", quic.outboundRu.contains("молчит"))
        assertFalse(
            "Открытый TCP не имеет права выглядеть как открытые UDP: ${quic.outboundRu}",
            quic.outboundRu.contains("сеть открыта")
        )
    }

    @Test
    fun `an unmeasured udp path is admitted instead of judged`() {
        val rows = YggTransportMatrix.rows(
            YggNetworkFilterProbe.Verdict.ADDRESS_FILTERED,
            null,
            fullListeners,
            whitelistMode = true
        )
        assertEquals("UDP/443 не измерялся", rows.last().outboundRu)
    }

    @Test
    fun `port five nine eight five says it was never probed`() {
        val rows = YggTransportMatrix.rows(
            YggNetworkFilterProbe.Verdict.OPEN,
            YggNetworkFilterProbe.UdpVerdict.PATH_OPEN,
            fullListeners,
            whitelistMode = false
        )
        val tcp = rows.first { it.schemeRu.startsWith("tcp") }
        assertTrue("Должно быть видно, что 5985 не замерялся: ${tcp.outboundRu}", tcp.outboundRu.contains("не замерялся"))
        // The 443 row may claim the measurement, because that is where the references sit.
        val tls = rows.first { it.schemeRu.startsWith("tls") }
        assertTrue(tls.outboundRu.contains("замер контрольными адресами сделан именно на 443"))
    }

    @Test
    fun `whitelist profile announces that it withholds the non four hundred forty three dial`() {
        val rows = YggTransportMatrix.rows(
            YggNetworkFilterProbe.Verdict.ADDRESS_FILTERED,
            YggNetworkFilterProbe.UdpVerdict.ANSWERED,
            fullListeners,
            whitelistMode = true
        )
        assertTrue(rows.first { it.schemeRu.startsWith("tcp") }.outboundRu.contains("не набирает"))
        assertFalse(rows.first { it.schemeRu.startsWith("tls") }.outboundRu.contains("не набирает"))
    }

    @Test
    fun `inbound column quotes the core rather than guessing`() {
        val rows = YggTransportMatrix.rows(
            YggNetworkFilterProbe.Verdict.OPEN,
            YggNetworkFilterProbe.UdpVerdict.ANSWERED,
            fullListeners,
            whitelistMode = false
        )
        assertEquals("tcp слушает [::]:5985", rows[0].inboundRu)
        assertTrue(rows[2].inboundRu.contains("WSS listener not supported"))
        assertTrue(rows[3].inboundRu.contains("permission denied"))

        val noCore = YggTransportMatrix.rows(
            YggNetworkFilterProbe.Verdict.OPEN,
            YggNetworkFilterProbe.UdpVerdict.ANSWERED,
            emptyList(),
            whitelistMode = false
        )
        assertTrue(noCore.all { it.inboundRu.contains("ядро не отчиталось") })
    }

    @Test
    fun `lines render all three facts per scheme`() {
        val lines = YggTransportMatrix.lines(
            YggNetworkFilterProbe.Verdict.NO_LINK,
            YggNetworkFilterProbe.UdpVerdict.LOCAL_FAILURE,
            fullListeners,
            whitelistMode = false
        )
        assertEquals(4, lines.size)
        lines.forEach { line ->
            assertTrue("в строке нужен исходящий вердикт: $line", line.contains("наружу:"))
            assertTrue("в строке нужен листнер ядра: $line", line.contains("внутрь:"))
        }
        assertTrue(lines.last().contains("UDP не отправился с устройства"))
    }
}
