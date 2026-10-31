package com.example.engine

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Measures what the *current network* is willing to carry, independent of Yggdrasil.
 *
 * The reason this exists is a measured one. On a mobile uplink inside an address allow-list the
 * node dials nothing, and the app used to report that as «пиры не отвечают» - which sent the
 * autopilot hunting for spare nodes forever, each of which was refused before a single byte of
 * masking mattered. Such networks refuse by *destination address*, so no SNI, ALPN, WebSocket or
 * QUIC reshaping can help: the decision is taken before the payload is looked at. The only
 * honest thing an app can do is tell the two situations apart and stop pretending otherwise.
 *
 * The measurement is a set of plain TCP connects to reference addresses that have nothing to do
 * with this app. A reference is any well-known service on 443, some by hostname and some by
 * literal address, deliberately spread across different hosting so that an open network answers
 * all of them while a filtered network answers a subset. The subset itself is the proof: if the
 * same link reaches some addresses and refuses others, the network is choosing by address.
 */
object YggNetworkFilterProbe {

    enum class Verdict(val labelRu: String) {
        /** Every reference answered: the link is unrestricted, so peer failures are about peers. */
        OPEN("сеть открыта"),

        /** Some references answered and some were refused: the network filters by destination. */
        ADDRESS_FILTERED("сеть фильтрует адреса"),

        /** Hostnames would not resolve while literal addresses still answered: DNS is filtered. */
        DNS_FILTERED("сеть фильтрует DNS"),

        /**
         * Nothing answered, and nothing was refused either: every connect ran out its timeout.
         * Packets left the phone and no reply came back, which is a different behaviour from an
         * explicit refusal and from a link with no route at all.
         */
        SILENTLY_DROPPED("сеть молча дропает соединения"),

        /** Nothing answered and the network said why: refused, no route, no DNS. */
        NO_LINK("нет выхода в интернет"),

        /** No reference was measured at all, so no claim about the network is available. */
        NOT_MEASURED("измерение не выполнено")
    }

    /**
     * How one reference failed, named by the mechanism the socket layer showed.
     *
     * A refusal and a silence are not the same observation: a refusal is the network answering
     * about an address, a timeout is the address never answering. The verdict needs that split,
     * and so does the autopilot, which should not spend a directory fetch on a link that carries
     * nothing at all.
     */
    enum class Reason(val labelRu: String) {
        ANSWERED("адрес ответил"),
        REFUSED("отклонено сетью"),
        DROPPED("молчит до таймаута"),
        UNRESOLVED("имя не разрешилось")
    }

    /** One reference destination. [byName] marks whether the host has to be resolved first. */
    data class Reference(val label: String, val host: String, val port: Int, val byName: Boolean)

    /**
     * When a reference failed.
     *
     * The difference decides whether masking is a question at all on this link: a refusal that
     * arrives while the connection is still being set up means the app never put a single byte of
     * ClientHello on the wire, so there is no payload for a filter to have looked at.
     */
    enum class FailureStage {
        /** Rejected while connecting: refused, dropped, unreachable or unresolvable. */
        CONNECT,

        /** The connection was established and failed afterwards - only then is there payload. */
        AFTER_CONNECT
    }

    /** What one reference did: connected, refused, fell silent, or would not resolve. */
    data class Probe(
        val reference: Reference,
        val connected: Boolean,
        val elapsedMs: Long,
        val detail: String,
        val stage: FailureStage? = null,
        // probeOne always passes this; the fallback reads the detail string so a hand-built
        // probe in a test cannot silently default to the wrong mechanism.
        val reason: Reason = reasonOf(connected, detail)
    ) {
        companion object {
            internal fun reasonOf(connected: Boolean, detail: String): Reason = when {
                connected -> Reason.ANSWERED
                detail.startsWith("имя не разрешилось") -> Reason.UNRESOLVED
                detail.startsWith("SocketTimeoutException") -> Reason.DROPPED
                else -> Reason.REFUSED
            }
        }
    }

    data class Report(
        val verdict: Verdict,
        val summary: String,
        val probes: List<Probe>,
        /** Wall clock of the measurement: an old verdict about a different link is not a verdict. */
        val measuredAtMs: Long = System.currentTimeMillis()
    ) {
        /** Addresses the network let through - the set a node has to live in to be dialable here. */
        val reachableLabels: List<String> get() = probes.filter { it.connected }.map { it.reference.label }
    }

    internal const val CONNECT_TIMEOUT_MS = 1500

    /**
     * Reference addresses, port 443.
     *
     * Every entry is infrastructure that answers on almost any normal link, because a reference
     * that is down on its own would be read as a filter. Mixed on purpose: hosting providers, DNS
     * infrastructure, a CDN edge, some by hostname and some by literal address. An allow-list
     * picks a subset of these, an open network answers all of them, and the difference between
     * the two is what the verdict is built from. Yandex is in the list because on the networks
     * where this matters it is exactly the address that stays permitted - it is never the one that
     * decides the verdict alone.
     */
    internal val references = listOf(
        Reference("GitHub (домен)", "github.com", 443, byName = true),
        Reference("Wikipedia (домен)", "wikipedia.org", 443, byName = true),
        Reference("Cloudflare (домен)", "cloudflare.com", 443, byName = true),
        Reference("Yandex (домен)", "static-mon.yandex.net", 443, byName = true),
        Reference("GitHub (адрес)", "140.82.121.4", 443, byName = false),
        Reference("Cloudflare (адрес)", "104.16.132.229", 443, byName = false),
        Reference("Google DNS (адрес)", "8.8.8.8", 443, byName = false),
        Reference("Cloudflare DNS (адрес)", "1.1.1.1", 443, byName = false)
    )

    /** Opened for tests; production connects a plain TCP socket with the timeout below. */
    internal var connector: (String, Int, Int) -> Unit = { host, port, timeoutMs ->
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
    }

    /** Runs every reference in parallel so one refused address does not stretch the whole probe. */
    fun probe(): Report {
        val pool = Executors.newFixedThreadPool(references.size)
        val probes = try {
            pool.invokeAll(
                references.map { reference ->
                    java.util.concurrent.Callable { probeOne(reference) }
                }
            ).map { it.get() }
        } finally {
            pool.shutdown()
        }
        val verdict = classify(probes)
        return Report(verdict, summary(verdict, probes), probes)
    }

    private fun probeOne(reference: Reference): Probe {
        val startedAt = System.nanoTime()
        return try {
            connector(reference.host, reference.port, CONNECT_TIMEOUT_MS)
            Probe(reference, true, elapsed(startedAt), "порт открыт")
        } catch (e: Throwable) {
            val detail = when (e) {
                is UnknownHostException -> "имя не разрешилось (${e.message ?: "нет ответа DNS"})"
                else -> e.javaClass.simpleName + (e.message?.let { " • $it" } ?: "")
            }
            Probe(reference, false, elapsed(startedAt), detail, stageOf(e), reasonOf(e))
        }
    }

    /** The mechanism the socket layer showed, read off the exception rather than its wording. */
    private fun reasonOf(e: Throwable): Reason = when (e) {
        is UnknownHostException -> Reason.UNRESOLVED
        is SocketTimeoutException -> Reason.DROPPED
        else -> Reason.REFUSED
    }

    /**
     * A probe only ever connects, never writes, so anything the socket layer reports here arrived
     * before there was payload to inspect. A timeout counts as the same kind of answer: the SYN
     * went out, nothing came back, no byte of a handshake was ever acknowledged.
     */
    private fun stageOf(e: Throwable): FailureStage = when (e) {
        is UnknownHostException, is SocketException, is SocketTimeoutException -> FailureStage.CONNECT
        else -> FailureStage.AFTER_CONNECT
    }

    private fun elapsed(startedAtNs: Long): Long = (System.nanoTime() - startedAtNs) / 1_000_000L

    /**
     * Decides from the pattern of answers, not from a single failure.
     *
     * Any subset of references answering is already the proof the user needs, because the same
     * phone on the same link reaches some addresses and is refused others. Only the all-or-none
     * cases are ambiguous, and they are named for what they are: no answers at all is not a
     * filter, it is a link that carries nothing.
     */
    internal fun classify(probes: List<Probe>): Verdict {
        if (probes.isEmpty()) return Verdict.NOT_MEASURED
        val connected = probes.filter { it.connected }
        if (connected.isEmpty()) {
            // Silence is not a refusal. When every connect ran out its timeout, nothing in the
            // network ever answered - that is a route carrying nothing, which is a different thing
            // to measure than an address space the network says no to. Neither one proves an
            // allow-list, and neither one is a link a spare node can be dialled over.
            return if (probes.all { it.reason == Reason.DROPPED }) Verdict.SILENTLY_DROPPED
            else Verdict.NO_LINK
        }
        val nameProbes = probes.filter { it.reference.byName }
        val namesAllFailed = nameProbes.isNotEmpty() && nameProbes.none { it.connected } &&
            nameProbes.all { it.reason == Reason.UNRESOLVED }
        val literalsAnswered = connected.any { !it.reference.byName }
        if (connected.size == probes.size) return Verdict.OPEN
        // One silent destination is a fact about that destination, not about the link. Calling
        // it a filter would be exactly the kind of overclaim this measurement exists to avoid.
        if (probes.size - connected.size <= 1) return Verdict.OPEN
        if (namesAllFailed && literalsAnswered) return Verdict.DNS_FILTERED
        return Verdict.ADDRESS_FILTERED
    }

    internal fun summary(verdict: Verdict, probes: List<Probe>): String {
        val up = probes.count { it.connected }
        val total = probes.size
        val refused = probes.filterNot { it.connected }
        val fastestRefusal = refused.minByOrNull { it.elapsedMs }?.elapsedMs ?: -1
        return when (verdict) {
            Verdict.OPEN -> {
                val missing = total - up
                if (missing == 0) {
                    "Сеть открыта: все $total контрольных адресов отвечают, маскировать трафик " +
                        "для прохождения этой сети не нужно"
                } else {
                    "Сеть открыта: $up из $total контрольных адресов отвечают; $missing не ответил - " +
                        "на признак фильтра это не тянет, сети режут сразу многие адреса"
                }
            }
            Verdict.ADDRESS_FILTERED ->
                "Сеть фильтрует по адресу назначения: $up из $total контрольных адресов проходят, " +
                    "остальные отклоняются сетью (${refusalStageNote(refused)}; быстрее всех за " +
                    "${fastestRefusal} мс). SNI, ALPN и WebSocket тут ничего не меняют: решение " +
                    "принимается по адресу, до того как посмотрели на содержимое. Обход возможен " +
                    "только узлом по адресу из списка этой сети либо через сеть без фильтра."
            Verdict.DNS_FILTERED ->
                "Сеть фильтрует DNS: имена вне списка не разрешаются ($up из $total проходят по " +
                    "адресу). Узел должен быть доступен адресом из белого списка, а не доменом."
            Verdict.NO_LINK ->
                "Ни один из $total контрольных адресов не отвечает (${answerNote(refused)}): по этой " +
                    "связи нет выхода в интернет, классифицировать фильтр невозможно."
            Verdict.SILENTLY_DROPPED ->
                "Молчание вместо ответа: все $total контрольных адресов не ответили за " +
                    "${CONNECT_TIMEOUT_MS} мс, и ни один не был отклонён явно. Для приложения это " +
                    "наблюдение неотличимо от связи без маршрута: ответа нет, и по одному лишь " +
                    "таймауту нельзя утверждать ни белый список, ни то, что запрос вообще вышел за " +
                    "пределы телефона. Резервные узлы на такой связи проверяются редко - не потому, " +
                    "что надежда есть, а чтобы не тратить трафик на заведомо немой путь."
            Verdict.NOT_MEASURED ->
                "Контрольные адреса не проверялись: измерение не выполнено, поэтому об этой сети " +
                    "не утверждается ничего."
        }
    }

    /** Per-address evidence, one line each, for the self-check report on the network screen. */
    fun evidenceLines(report: Report): List<String> = report.probes.map {
        "${if (it.connected) "проходит" else "не проходит"} • ${it.reference.label} " +
            "${it.reference.host}:${it.reference.port} • ${it.detail} • ${it.elapsedMs} мс" +
            (if (it.connected) "" else " • ${it.reason.labelRu}")
    }

    /**
     * How the network said no, in one clause - only for the verdicts where every address failed.
     *
     * The mechanisms are not interchangeable and the user should not have to guess which one the
     * measurement actually saw: a refusal is an answer about an address, a timeout is no answer,
     * and an unresolved name is a statement about DNS.
     */
    private fun answerNote(refused: List<Probe>): String =
        refused.groupBy { it.reason }
            .toSortedMap(compareBy { it.ordinal })
            .entries.joinToString(", ") { (reason, probes) ->
                "${probes.size} — ${reason.labelRu.lowercase()}"
            }

    /**
     * Where the refusals landed, in one clause.
     *
     * Naming the stage is the whole point of the measurement: a refusal that arrives while the
     * connection is still being set up is the network saying no to an address, and no reshaping of
     * a ClientHello can be relevant to bytes that were never sent.
     */
    private fun refusalStageNote(refused: List<Probe>): String {
        val stages = refused.map { it.stage ?: FailureStage.CONNECT }.distinct()
        return when (stages.size) {
            1 -> if (stages.first() == FailureStage.CONNECT) {
                "отказ приходит на установлении соединения, в сеть не ушло ни байта"
            } else {
                "соединения устанавливаются и рвутся уже после первых байт"
            }
            else -> "часть адресов отклоняется при установлении соединения, часть рвётся после первых байт"
        }
    }

    // --- UDP/443: the same question, asked of the path quic:// peers actually ride. ---

    /** What one UDP reference did. The four cases are not interchangeable as evidence. */
    enum class UdpOutcome(val labelRu: String) {
        /** A datagram came back: this network carries UDP to 443 and the answer finds its way home. */
        ANSWERED("адрес ответил по UDP"),

        /**
         * ICMP port unreachable. Ugly looking, but it is the second-strongest proof there is: the
         * datagram reached the far host and its reply reached this socket, so the path carries UDP
         * even though nothing was listening on that port.
         */
        NO_LISTENER("вернулась ICMP-недоступность порта"),

        /** Nothing at all within the timeout - the case this probe cannot interpret alone. */
        SILENT("тишина до таймаута"),

        /** The socket itself refused to send or receive on this device. */
        FAILED("отправка UDP не удалась на устройстве")
    }

    enum class UdpVerdict(val labelRu: String, val carriesUdp: Boolean) {
        ANSWERED("UDP/443 проходит, адрес отвечает", carriesUdp = true),
        PATH_OPEN("UDP/443 проходит, слушателя на порту нет", carriesUdp = true),
        SILENT("UDP/443 молчит", carriesUdp = false),
        LOCAL_FAILURE("UDP не проходит на этом устройстве", carriesUdp = false),
        NOT_MEASURED("UDP не измерялся", carriesUdp = false)
    }

    /**
     * A UDP reference plus the identity its TLS hello should name.
     *
     * A QUIC server that has no reason to answer a hello for an unknown name is not evidence about
     * the network, so the probe targets endpoints that publicly serve HTTP/3 on 443/udp and asks
     * for that ALPN. Whether they answer from this link is precisely the unknown being measured.
     */
    data class UdpReference(
        val label: String,
        val host: String,
        val port: Int,
        val serverName: String,
        val alpn: String
    )

    data class UdpObservation(val outcome: UdpOutcome, val replyBytes: Int, val detail: String)

    data class UdpProbe(
        val reference: UdpReference,
        val outcome: UdpOutcome,
        val replyBytes: Int,
        val elapsedMs: Long,
        val detail: String
    )

    data class UdpReport(
        val verdict: UdpVerdict,
        val summary: String,
        val probes: List<UdpProbe>,
        val measuredAtMs: Long = System.currentTimeMillis()
    )

    internal const val UDP_TIMEOUT_MS = 2000

    /**
     * Literal addresses only: resolving a name here would mix a DNS question into a UDP question,
     * and the TCP half of this probe already measures DNS separately.
     */
    internal val udpReferences = listOf(
        UdpReference("Google (UDP/443)", "8.8.8.8", 443, "www.google.com", "h3"),
        UdpReference("Cloudflare (UDP/443)", "1.1.1.1", 443, "cloudflare.com", "h3"),
        UdpReference("Quad9 (UDP/443)", "9.9.9.9", 443, "dns.quad9.net", "h3")
    )

    /** Opened for tests: sends one datagram and reports what, if anything, came back. */
    internal var udpQuerier: (UdpReference, ByteArray, Int) -> UdpObservation =
        { reference, packet, timeoutMs -> queryUdp(reference, packet, timeoutMs) }

    fun probeUdp(): UdpReport {
        val pool = Executors.newFixedThreadPool(udpReferences.size)
        val probes = try {
            pool.invokeAll(
                udpReferences.map { reference ->
                    java.util.concurrent.Callable { probeUdpOne(reference) }
                }
            ).map { it.get() }
        } finally {
            pool.shutdown()
        }
        val verdict = classifyUdp(probes)
        return UdpReport(verdict, udpSummary(verdict, probes), probes)
    }

    private fun probeUdpOne(reference: UdpReference): UdpProbe {
        // A fresh connection id per probe: the Initial keys come from it, and reusing one would
        // make every measurement look like a retransmit of the same connection to the same server.
        val connectionId = randomBytes(8)
        val packet = YggQuicInitial.build(
            destinationConnectionId = connectionId,
            sourceConnectionId = ByteArray(0),
            packetNumber = 0,
            clientHello = YggQuicInitial.clientHello(
                serverName = reference.serverName,
                alpn = reference.alpn,
                keySharePublic = randomBytes(32)
            )
        )
        val startedAt = System.nanoTime()
        val observation = try {
            udpQuerier(reference, packet, UDP_TIMEOUT_MS)
        } catch (e: Throwable) {
            UdpObservation(UdpOutcome.FAILED, 0, "${e.javaClass.simpleName} • ${e.message ?: "без сообщения"}")
        }
        return UdpProbe(
            reference,
            observation.outcome,
            observation.replyBytes,
            (System.nanoTime() - startedAt) / 1_000_000L,
            observation.detail
        )
    }

    private fun queryUdp(reference: UdpReference, packet: ByteArray, timeoutMs: Int): UdpObservation =
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(reference.host)
                // Connected on purpose: an unconnected UDP socket drops the ICMP error for a
                // destination this app never sent to, while a connected one reports ECONNREFUSED
                // back to receive() - which is the difference between "no listener" and "no answer".
                socket.connect(address, reference.port)
                socket.send(DatagramPacket(packet, packet.size))
                val buffer = ByteArray(2048)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                UdpObservation(
                    UdpOutcome.ANSWERED,
                    reply.length,
                    "ответ ${reply.length} байт от ${reference.host}:${reference.port}"
                )
            }
        } catch (e: SocketTimeoutException) {
            UdpObservation(UdpOutcome.SILENT, 0, "за ${timeoutMs} мс не вернулось ничего")
        } catch (e: IOException) {
            // Android spells these as "send failed: ECONNREFUSED (Connection refused)" and older
            // API levels have no PortUnreachableException at all, so the mechanism is read off the
            // text rather than off a class the device may not ship.
            val text = (e.javaClass.simpleName + " " + (e.message ?: "")).lowercase()
            val portRefused = text.contains("portunreachable") || text.contains("econnrefused") ||
                text.contains("connection refused") || text.contains("port unreachable")
            if (portRefused) {
                UdpObservation(UdpOutcome.NO_LISTENER, 0, "${e.javaClass.simpleName} • ${e.message ?: ""}")
            } else {
                UdpObservation(UdpOutcome.FAILED, 0, "${e.javaClass.simpleName} • ${e.message ?: ""}")
            }
        }

    private fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also { SecureRandom().nextBytes(it) }

    /**
     * Decides from the outcomes, and never from silence alone.
     *
     * One answer proves the path; one port-unreachable proves it too, because an ICMP error is a
     * packet that made the round trip. All-silence proves nothing: a network that drops UDP and a
     * network whose far side simply ignored this hello are the same observation from inside the
     * phone, and the wording has to keep them the same rather than pick the scarier one.
     */
    internal fun classifyUdp(probes: List<UdpProbe>): UdpVerdict {
        if (probes.isEmpty()) return UdpVerdict.NOT_MEASURED
        val answered = probes.count { it.outcome == UdpOutcome.ANSWERED }
        val unreachable = probes.count { it.outcome == UdpOutcome.NO_LISTENER }
        return when {
            answered > 0 -> UdpVerdict.ANSWERED
            unreachable > 0 -> UdpVerdict.PATH_OPEN
            probes.all { it.outcome == UdpOutcome.FAILED } -> UdpVerdict.LOCAL_FAILURE
            else -> UdpVerdict.SILENT
        }
    }

    internal fun udpSummary(verdict: UdpVerdict, probes: List<UdpProbe>): String {
        val total = probes.size
        val fastest = probes.filter { it.outcome == UdpOutcome.ANSWERED }.minByOrNull { it.elapsedMs }
        val refusedNote = probes.filter { it.outcome == UdpOutcome.NO_LISTENER }
            .maxByOrNull { it.elapsedMs }?.elapsedMs
        return when (verdict) {
            UdpVerdict.ANSWERED ->
                "UDP/443 проходит: ${fastest?.reference?.label ?: "адрес"} ответил за " +
                    "${fastest?.elapsedMs ?: 0} мс (ответ ${fastest?.replyBytes ?: 0} байт). " +
                    "quic:// пиры на этой сети достижимы по транспорту — жив ли сам узел, скажет " +
                    "рукопожатие оверлея."
            UdpVerdict.PATH_OPEN ->
                "UDP/443 проходит: датаграмма дошла и вернулась ICMP-недоступностью порта " +
                    "(быстрее всех за ${refusedNote ?: 0} мс). Путь открыт, но на 443/udp у этих " +
                    "адресов слушать некому — для quic:// пира это значит «сеть пускает», а не " +
                    "«пир жив»."
            UdpVerdict.SILENT ->
                "UDP/443 молчит: ни от одного из $total адресов не пришло ничего за " +
                    "${UDP_TIMEOUT_MS} мс и ни один не ответил недоступностью порта. Для телефона " +
                    "это одно и то же наблюдение в двух разных ситуациях: сеть режет UDP, или " +
                    "адрес проигнорировал наш пакет. Утверждать, что UDP не проходит, по этому " +
                    "замеру нельзя; quic:// пиры на такой связи остаются непроверенными, а не отвергнутыми."
            UdpVerdict.LOCAL_FAILURE ->
                "UDP не отправился с этого устройства: ${probes.first().detail}. Это наблюдение " +
                    "про само приложение, а не про сеть: пока зонд не может послать датаграмму, " +
                    "про quic:// не утверждается ничего."
            UdpVerdict.NOT_MEASURED ->
                "UDP-адреса не проверялись: измерения нет, поэтому про путь quic:// ничего не " +
                    "утверждается."
        }
    }

    /** Per-address evidence for the network screen, one line each. */
    fun udpEvidenceLines(report: UdpReport): List<String> = report.probes.map {
        "${it.outcome.labelRu} • ${it.reference.label} ${it.reference.host}:${it.reference.port} " +
            "(${it.reference.alpn}, ${it.reference.serverName}) • ${it.detail} • ${it.elapsedMs} мс"
    }
}
