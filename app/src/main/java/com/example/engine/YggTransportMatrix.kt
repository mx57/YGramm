// File: app/src/main/java/com/example/engine/YggTransportMatrix.kt
package com.example.engine

/**
 * Per-scheme verdicts on one screen, assembled only from measurements this app actually holds.
 *
 * The network screen used to answer "what does my network allow" with a single sentence about TCP
 * on port 443, and a reader with a `quic://` peer had to guess whether that sentence applied to
 * them. It did not: QUIC rides UDP, WSS is dial-only, and the core's own listeners say something
 * different again from what the uplink carries outward. This object keeps those four questions
 * apart and refuses to let one verdict borrow another's evidence - which is why a row can honestly
 * read "not measured" instead of implying a guess.
 *
 * Nothing here probes: [rows] is a pure view over a TCP verdict, a UDP verdict and the listener
 * report the core produced.
 */
object YggTransportMatrix {

    data class Row(
        /** The scheme and the port it is used on, as the user sees it in a peer URI. */
        val schemeRu: String,
        val outboundRu: String,
        val inboundRu: String
    ) {
        fun lineRu(): String = "$schemeRu — наружу: $outboundRu • внутрь: $inboundRu"
    }

    fun rows(
        tcp: YggNetworkFilterProbe.Verdict?,
        udp: YggNetworkFilterProbe.UdpVerdict?,
        listeners: List<YggNativeCoreBridge.CoreListener>,
        whitelistMode: Boolean
    ): List<Row> = listOf(
        Row(
            schemeRu = "tcp:// (у публичных узлов обычно 5985)",
            outboundRu = tcpOutbound(tcp, port = 5985, whitelistMode = whitelistMode),
            inboundRu = listenerNote(listeners, "tcp")
        ),
        Row(
            schemeRu = "tls://443",
            outboundRu = tcpOutbound(tcp, port = 443, whitelistMode = whitelistMode),
            inboundRu = listenerNote(listeners, "tls")
        ),
        Row(
            schemeRu = "wss://443",
            outboundRu = tcpOutbound(tcp, port = 443, whitelistMode = whitelistMode) +
                "; маска к WebSocket не применяется, `?sni=` в этой схеме ядром не читается",
            inboundRu = listenerNote(listeners, "wss")
        ),
        Row(
            schemeRu = "quic://443 по UDP",
            outboundRu = udpOutbound(udp),
            inboundRu = listenerNote(listeners, "quic")
        )
    )

    fun lines(
        tcp: YggNetworkFilterProbe.Verdict?,
        udp: YggNetworkFilterProbe.UdpVerdict?,
        listeners: List<YggNativeCoreBridge.CoreListener>,
        whitelistMode: Boolean
    ): List<String> = rows(tcp, udp, listeners, whitelistMode).map { it.lineRu() }

    /**
     * The TCP verdict, spoken about the port this row is about.
     *
     * The reference addresses all sit on 443, because that is the port an allow-list forwards.
     * Saying so is the difference between a verdict and an assumption: 5985 was never probed, so
     * the row has to hand over the reasoning instead of the number.
     */
    private fun tcpOutbound(
        verdict: YggNetworkFilterProbe.Verdict?,
        port: Int,
        whitelistMode: Boolean
    ): String {
        val gate = if (whitelistMode && port != 443) {
            " — в профиле белых списков ядро такой диал не набирает"
        } else {
            ""
        }
        val measured = if (port == 443) "замер контрольными адресами сделан именно на 443" else
            "порт $port отдельно не замерялся, замер сделан на 443"
        return when (verdict) {
            null -> "TCP не измерялся ($measured)"
            YggNetworkFilterProbe.Verdict.OPEN ->
                "сеть открыта ($measured): путь по TCP до 443 проходит$gate"
            YggNetworkFilterProbe.Verdict.ADDRESS_FILTERED ->
                "сеть фильтрует адреса: пройдёт только адрес из её списка ($measured)$gate"
            YggNetworkFilterProbe.Verdict.DNS_FILTERED ->
                "сеть фильтрует DNS: пир должен быть адресом из списка ($measured)$gate"
            YggNetworkFilterProbe.Verdict.SILENTLY_DROPPED ->
                "сеть молча дропает TCP-соединения: наружу ничего не уходит$gate"
            YggNetworkFilterProbe.Verdict.NO_LINK ->
                "выхода в интернет по TCP нет$gate"
            YggNetworkFilterProbe.Verdict.NOT_MEASURED ->
                "TCP не измерялся ($measured)"
        }
    }

    /**
     * The UDP verdict, spoken about the path quic:// needs.
     *
     * Silence keeps its own wording here on purpose: an unanswered probe is not a blocked one, and
     * a row that claimed otherwise would tell the user to abandon a transport that may work fine.
     */
    private fun udpOutbound(verdict: YggNetworkFilterProbe.UdpVerdict?): String = when (verdict) {
        null, YggNetworkFilterProbe.UdpVerdict.NOT_MEASURED -> "UDP/443 не измерялся"
        YggNetworkFilterProbe.UdpVerdict.ANSWERED ->
            "UDP/443 проходит и адрес отвечает: путь для quic:// есть"
        YggNetworkFilterProbe.UdpVerdict.PATH_OPEN ->
            "UDP/443 проходит (вернулась ICMP-недоступность порта): сеть носит датаграммы, " +
                "слушателя на порту нет"
        YggNetworkFilterProbe.UdpVerdict.SILENT ->
            "UDP/443 молчит: ни проходит, ни нет — по одной тишине не доказать"
        YggNetworkFilterProbe.UdpVerdict.LOCAL_FAILURE ->
            "UDP не отправился с устройства: про путь ничего не известно"
    }

    /** The listener the core really bound, or the core's own refusal, quoted from its report. */
    private fun listenerNote(listeners: List<YggNativeCoreBridge.CoreListener>, scheme: String): String {
        if (listeners.isEmpty()) return "ядро не отчиталось: профиль не запущен или листнеры не прочитаны"
        val row = listeners.firstOrNull { it.scheme == scheme }
            ?: return "ядро не сообщало о листнере $scheme"
        return row.describeRu()
    }
}
