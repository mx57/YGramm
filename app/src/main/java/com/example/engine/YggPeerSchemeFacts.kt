// File: app/src/main/java/com/example/engine/YggPeerSchemeFacts.kt
package com.example.engine

/**
 * What a peer endpoint means on the wire, stated as the bundled core actually behaves.
 *
 * This replaces the transport labels the scanner used to invent. Each line here is traceable
 * to yggdrasil-go v0.5.14 (src/core) or to a measurement made against this build:
 *
 *  - `?sni=` is read once, in link.go, and only link_tls.go and link_socks.go consume it. A
 *    `wss://` or `quic://` URI with the option is dialled as though it were absent, so the
 *    app must not offer it there.
 *  - link_wss.go copies the URI's own host into ServerName, so a WebSocket peer with a
 *    hostname already names one and an IP literal names none.
 *  - link_quic.go assigns ServerName to a clone of the config and then calls
 *    quic.DialAddr with the shared original, which never had a name set. A loopback QUIC
 *    handshake against this build confirms it: the server side reads `server_name = ""`.
 *  - The same handshake completes with no ALPN on either side, which is what the core
 *    configures, so QUIC is a working scheme here rather than a promise.
 *  - yggdrasil resolves a dial through net.SplitHostPort on the URI's host, so an endpoint
 *    without an explicit port is never dialled at all. The port is therefore reported as
 *    missing, never assumed from the scheme.
 */
object YggPeerSchemeFacts {

    data class Facts(
        val scheme: String,
        /** Explicit port from the URI; null when the core has nothing to dial. */
        val port: Int?,
        /** Human-readable transport, said in terms of what the link actually is. */
        val transportRu: String,
        val dialableByCore: Boolean,
        /** Sits on the port an allow-list forwards. */
        val onWhitelistPort: Boolean,
        /** Whether the TLS/QUIC hello this peer produces carries a hostname. */
        val sendsHostnameSni: Boolean,
        /** The phone's own TLS stack can take this handshake over, putting a name on 443. */
        val canBeMasked: Boolean,
        val verdictRu: String
    ) {
        /** Имя транспорта для записи в базу — то же, что расставляет сканер каталога. */
        fun transportLabel(): String = transportLabelFor(scheme)
    }

    fun of(endpointUri: String, sniDomain: String): Facts {
        val uri = endpointUri.trim()
        val scheme = uri.substringBefore("://").lowercase()
        val authority = uri.substringAfter("://", "").substringBefore("/").substringBefore("?")
        val maybePort = authority.substringAfterLast(':', "")
        val port = maybePort.toIntOrNull()
        val host = (if (port != null) authority.dropLast(maybePort.length + 1) else authority)
            .trim('[', ']').lowercase()
        val dialable = port != null && host.isNotBlank()
        // The mask is judged on the URI the core will really dial, not on the stored endpoint:
        // for tls:// that URI carries the `?sni=` the peer's allowed domain produced, and the
        // cover transport refuses to build a plan without a hostname in the hello.
        val maskable = YggCoverTransport.planFor(YggNativeCoreBridge.corePeerUri(uri, sniDomain)) != null
        val on443 = YggNativeCoreBridge.isWhitelistEndpoint(uri)
        val namesHost = YggNativeCoreBridge.sendsHostnameSni(uri, sniDomain)
        return when (scheme) {
            "tcp" -> Facts(
                scheme = scheme,
                port = port,
                transportRu = "tcp:// — без TLS, рукопожатие оверлея идёт открытым текстом",
                dialableByCore = dialable,
                onWhitelistPort = on443,
                sendsHostnameSni = false,
                canBeMasked = maskable,
                verdictRu = if (YggCoverTransport.whitelistMode && maskable) {
                    "Ядро набирает этот пир как TLS на 443 поверх маски; исходный диал " +
                        "снимается, пока включён профиль белых списков."
                } else {
                    "Открытый текст: пройдёт только если этот порт есть в белом списке."
                }
            )
            "tls" -> Facts(
                scheme = scheme,
                port = port,
                transportRu = "tls:// — TLS поверх TCP",
                dialableByCore = dialable,
                onWhitelistPort = on443,
                sendsHostnameSni = namesHost,
                canBeMasked = maskable,
                verdictRu = when {
                    namesHost -> "ClientHello с именем хоста — на проводе выглядит как HTTPS."
                    else -> "SNI нет: адрес без имени и разрешённый домен не задан."
                }
            )
            "wss" -> Facts(
                scheme = scheme,
                port = port,
                transportRu = "wss:// — TLS поверх TCP + WebSocket",
                dialableByCore = dialable,
                onWhitelistPort = on443,
                sendsHostnameSni = namesHost,
                canBeMasked = false,
                verdictRu = if (namesHost) {
                    "Само похоже на HTTPS: ядро копирует имя из URI в SNI. `?sni=` эта " +
                        "схема не читает, подменять ничего не нужно."
                } else {
                    "WebSocket на IP-адресе: SNI не отправляется, имя в этой схеме не " +
                        "подставить — пир выглядит как TLS без имени."
                }
            )
            "quic" -> Facts(
                scheme = scheme,
                port = port,
                transportRu = "quic:// — QUIC поверх UDP",
                dialableByCore = dialable,
                onWhitelistPort = on443,
                sendsHostnameSni = false,
                canBeMasked = false,
                verdictRu = "Рукопожатие QUIC проходит, но имени в нём ядро не отправляет и " +
                    "`?sni=` игнорирует: маски нет. Путь UDP/443 замеряется на экране сети — " +
                    "там же видно, носит ли эта связь датаграммы."
            )
            else -> Facts(
                scheme = scheme,
                port = port,
                transportRu = if (scheme.isBlank()) "адрес без схемы" else "$scheme:// — ядро такой не набирает",
                dialableByCore = false,
                onWhitelistPort = on443,
                sendsHostnameSni = false,
                canBeMasked = false,
                verdictRu = "yggdrasil v0.5.14 знает tcp, tls, wss, quic, unix и socks; " +
                    "этот URI ядро не диалит."
            )
        }
    }

    /**
     * The label written into the peer row, spelled the same way everywhere it is written.
     * A scheme the core does not dial keeps its own name rather than being filed under TCP.
     */
    fun transportLabelFor(scheme: String): String = when (scheme.lowercase()) {
        "wss" -> "WSS_YGGDRASIL"
        "quic" -> "QUIC_YGGDRASIL"
        "tls" -> "TLS_YGGDRASIL"
        "tcp" -> "TCP_YGGDRASIL"
        else -> "НЕ_ДИАЛИТСЯ_${scheme.uppercase().ifBlank { "БЕЗ_СХЕМЫ" }}"
    }

    /** Порт из URI без предположений: «443» только когда он там действительно написан. */
    fun portLabelRu(endpointUri: String, whitelistCompatible: Boolean): String {
        val facts = of(endpointUri, "")
        val port = facts.port
        val named = if (port == null) "порт не указан" else "порт $port"
        if (!whitelistCompatible || port != 443) return named
        // The allow-list verdict was measured over TCP. A QUIC peer reaches 443 over UDP and
        // that path was never probed here, so its label must not borrow the TCP verdict.
        return if (facts.scheme == "quic") "$named по UDP (белый список по TCP его не проверял)"
        else "$named (под белый список)"
    }

    /** Одна строка для карточки пира: транспорт, имя в hello, маска, и то, что это значит. */
    fun summaryRu(endpointUri: String, sniDomain: String): String {
        val facts = of(endpointUri, sniDomain)
        val parts = buildList {
            // The port is its own bullet: glued to the end of the sentence it read as part of
            // the description ("рукопожатие оверлея идёт открытым текстом:65535").
            add(facts.transportRu)
            add(facts.port?.let { "порт $it" } ?: "порт не указан")
            if (!facts.dialableByCore) add("ядро не диалит без порта")
            add(if (facts.sendsHostnameSni) "SNI есть" else "SNI нет")
            if (facts.canBeMasked) add("маска через TLS-стек телефона")
        }
        return parts.joinToString(" • ") + ". " + facts.verdictRu
    }
}
