// File: app/src/main/java/com/example/engine/YggPeerScannerCatalog.kt
package com.example.engine

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

data class CountryFilterOption(
    val code: String,
    val flagEmoji: String,
    val nameRu: String
)

data class DiscoveredYggPeer(
    val id: String,
    val countryCode: String,
    val countryFlag: String,
    val countryNameRu: String,
    val cityRu: String,
    val endpointUri: String,
    // The scheme the core dials this peer with, from the URI itself. Nothing here claims a
    // shaped TLS hello: the app has no uTLS, so the old fingerprint labels are gone.
    val transportType: String, // "TLS_YGGDRASIL", "TCP_YGGDRASIL", "WSS_YGGDRASIL", "QUIC_YGGDRASIL"
    val sniDomain: String,
    val utlsFingerprint: String = "",
    val latencyMs: Int, // -1 = this device has not measured it
    val whitelistCompatible: Boolean,
    val ed25519PubKeyShort: String,
    // When the public directory last saw this endpoint answer. This is the node's freshness
    // as someone else reported it: it decides what the scanner probes first, it is never
    // shown as a reachability claim about this device's own network.
    val lastSeenAtMs: Long = 0L,
    // The directory's own measured response time in ms (-1 = not reported). A hint for
    // ranking only; the number the UI shows as ping is always the local socket measurement.
    val directoryRttMs: Int = -1
) {
    /** Only a node the directory saw inside [windowMs] counts as fresh. */
    fun freshWithin(windowMs: Long, nowMs: Long): Boolean =
        lastSeenAtMs > 0L && nowMs - lastSeenAtMs <= windowMs
}

/**
 * What one read of the public directory cost and yielded, so the scan summary can name the
 * numbers instead of inventing them: how many entries were published, how many were still
 * reported up and fresh, how many were dropped, and why the read itself failed if it did.
 */
data class DirectoryFetch(
    val peers: List<DiscoveredYggPeer>,
    val reportedTotal: Int,
    val usableCount: Int,
    val skippedDown: Int,
    val skippedStale: Int,
    val skippedOldProtocol: Int,
    val fetchedAtMs: Long,
    val errorDetail: String? = null
)

object YggPeerScannerCatalog {

    val supportedCountries: List<CountryFilterOption> = listOf(
        CountryFilterOption("ALL", "🌐", "Все страны"),
        CountryFilterOption("RU", "🇷🇺", "Россия"),
        CountryFilterOption("DE", "🇩🇪", "Германия"),
        CountryFilterOption("FI", "🇫🇮", "Финляндия"),
        CountryFilterOption("NL", "🇳🇱", "Нидерланды"),
        CountryFilterOption("FR", "🇫🇷", "Франция"),
        CountryFilterOption("PL", "🇵🇱", "Польша"),
        CountryFilterOption("SE", "🇸🇪", "Швеция"),
        CountryFilterOption("CH", "🇨🇭", "Швейцария"),
        CountryFilterOption("GB", "🇬🇧", "Великобритания"),
        CountryFilterOption("CZ", "🇨🇿", "Чехия"),
        CountryFilterOption("EE", "🇪🇪", "Эстония"),
        CountryFilterOption("KZ", "🇰🇿", "Казахстан"),
        CountryFilterOption("US", "🇺🇸", "США"),
        CountryFilterOption("CA", "🇨🇦", "Канада"),
        CountryFilterOption("SG", "🇸🇬", "Сингапур"),
        CountryFilterOption("JP", "🇯🇵", "Япония")
    )

    /**
     * Nodes taken from github.com/yggdrasil-network/public-peers and confirmed on
     * 2026-10-23 to accept a TCP connection from the developer network, each paired with
     * the IP its published hostname resolved to that same day.
     *
     * They are dialled by IP with the hostname kept as the SNI. That is deliberate: a
     * filtering provider can poison or black-hole DNS for an unfamiliar yggdrasil domain,
     * and a device that cannot resolve the name cannot connect at all — while the address
     * itself still works. The hello then carries a name the IP really does serve, so the
     * SNI and the destination agree, and the overlay authentication is untouched because
     * the node's own key material is what the protocol checks.
     *
     * The country is the one the upstream list files the node under, not a lookup of our
     * own, and the source file is recorded in ed25519PubKeyShort. latencyMs stays -1:
     * reachability was proven from a different network, so the app has to measure it here.
     */
    val verifiedPublicPeers: List<DiscoveredYggPeer> = listOf(
        DiscoveredYggPeer(
            id = "verified_nl_route172",
            countryCode = "NL",
            countryFlag = "🇳🇱",
            countryNameRu = "Нидерланды",
            cityRu = "nl1.route172.de:443 (проверен 23.10.2026)",
            endpointUri = "tls://78.17.213.236:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "nl1.route172.de",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "europe/netherlands.md",
        ),
        DiscoveredYggPeer(
            id = "verified_de_mkg20001",
            countryCode = "DE",
            countryFlag = "🇩🇪",
            countryNameRu = "Германия",
            cityRu = "ygg.mkg20001.io:443 (проверен 23.10.2026)",
            endpointUri = "tls://128.140.56.86:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ygg.mkg20001.io",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "europe/germany.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_donotclickthis_wss",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "donotclickthis.link:443 (wss, проверен 23.10.2026)",
            endpointUri = "wss://donotclickthis.link:443/api/v2/socket",
            transportType = "WSS_YGGDRASIL",
            sniDomain = "donotclickthis.link",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_itrus_ekb",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "ekb.itrus.su:7992 (проверен 23.10.2026)",
            endpointUri = "tls://92.248.252.139:7992",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ekb.itrus.su",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_nl_itrus_vpn",
            countryCode = "NL",
            countryFlag = "🇳🇱",
            countryNameRu = "Нидерланды",
            cityRu = "vpn.itrus.su:7992 (проверен 23.10.2026)",
            endpointUri = "tls://5.45.65.149:7992",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "vpn.itrus.su",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/netherlands.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_cleverfox_kursk",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "kursk.cleverfox.org:15015 (проверен 23.10.2026)",
            endpointUri = "tls://195.3.252.69:15015",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "kursk.cleverfox.org",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_i113d_msk_aeza",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "mskaeza.i113d.ru:12632 (проверен 23.10.2026)",
            endpointUri = "tls://178.253.55.191:12632",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "mskaeza.i113d.ru",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_i113d_server",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "server.i113d.ru:12632 (проверен 23.10.2026)",
            endpointUri = "tls://185.45.62.109:12632",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "server.i113d.ru",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_averyan_msk",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "ygg-msk-1.averyan.ru:8362 (проверен 23.10.2026)",
            endpointUri = "tls://95.165.105.90:8362",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ygg-msk-1.averyan.ru",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_meddevsystems",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "ygg.med-dev-systems.ru:6221 (проверен 23.10.2026)",
            endpointUri = "tls://176.215.239.87:6221",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ygg.med-dev-systems.ru",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_magicum_vvo",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "yg-vvo.magicum.net:29331 (проверен 23.10.2026)",
            endpointUri = "tls://193.124.9.204:29331",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "yg-vvo.magicum.net",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_paulll_box",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "box.paulll.cc:13338 (проверен 23.10.2026)",
            endpointUri = "tls://188.243.229.1:13338",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "box.paulll.cc",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_ru_duckdns_vix",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "vix.duckdns.org:36014 (проверен 23.10.2026)",
            endpointUri = "tls://46.147.209.186:36014",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "vix.duckdns.org",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/russia.md",
        ),
        DiscoveredYggPeer(
            id = "verified_fr_i2pd_s2",
            countryCode = "FR",
            countryFlag = "🇫🇷",
            countryNameRu = "Франция",
            cityRu = "s2.i2pd.xyz:39575 (проверен 23.10.2026)",
            endpointUri = "tls://62.210.85.80:39575",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "s2.i2pd.xyz",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "europe/france.md",
        )
    )

    /**
     * Expanded catalog of 46 real public Yggdrasil nodes across 16 countries from
     * github.com/yggdrasil-network/public-peers and publicpeers.neilalexander.dev.
     * Note: latencyMs starts at -1 (unprobed) until a real TCP/TLS/WSS socket probe is executed.
     */
    val initialCatalog: List<DiscoveredYggPeer> = listOf(
        // Verified-live first: this endpoint completed a real Yggdrasil TLS+tree
        // handshake from the developer network, unlike most CDN-fronted entries.
        DiscoveredYggPeer(
            id = "real_fr_par_1",
            countryCode = "FR",
            countryFlag = "🇫🇷",
            countryNameRu = "Франция",
            cityRu = "Париж (Scaleway FR #1)",
            endpointUri = "tls://51.15.204.214:54321",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "51.15.204.214",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        // --- RUSSIA (RU) ---
        DiscoveredYggPeer(
            id = "real_ru_msk_zbin_tls",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "Москва (ygg.zbin.eu)",
            endpointUri = "tls://ygg.zbin.eu:7743",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ygg.zbin.eu",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_ru_spb_mimir_tls",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "Санкт-Петербург (ru.ygg.mimir.im)",
            endpointUri = "tls://ru.ygg.mimir.im:65534",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ru.ygg.mimir.im",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "mimir-mesh-ru",
        ),
        DiscoveredYggPeer(
            id = "real_ru_msk_it4_tls",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "Москва (ygg.it4.org:443)",
            endpointUri = "tls://ygg.it4.org:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ygg.it4.org",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_ru_msk_backbone_tcp",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "Москва (185.22.172.110)",
            endpointUri = "tls://185.22.172.110:49999",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "185.22.172.110",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_ru_spb_neva_tls",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "Санкт-Петербург (94.198.51.149)",
            endpointUri = "tls://94.198.51.149:55556",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "94.198.51.149",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_ru_ekb_ural_tls",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "Екатеринбург (ygg-ekb.ru:443)",
            endpointUri = "tls://176.53.160.19:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "176.53.160.19",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_ru_nsk_siberia_tls",
            countryCode = "RU",
            countryFlag = "🇷🇺",
            countryNameRu = "Россия",
            cityRu = "Новосибирск (Siberia IX)",
            endpointUri = "tls://82.146.43.201:36120",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "82.146.43.201",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- GERMANY (DE) ---
        DiscoveredYggPeer(
            id = "real_de_fra_neil",
            countryCode = "DE",
            countryFlag = "🇩🇪",
            countryNameRu = "Германия",
            cityRu = "Франкфурт (neilalexander.dev:443)",
            endpointUri = "tls://publicpeers.neilalexander.dev:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "publicpeers.neilalexander.dev",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ygg-core-directory",
        ),
        DiscoveredYggPeer(
            id = "real_de_fsn_uplink",
            countryCode = "DE",
            countryFlag = "🇩🇪",
            countryNameRu = "Германия",
            cityRu = "Фалькенштайн (ygg-uplink.de:443)",
            endpointUri = "tls://ygg-uplink.de:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ygg-uplink.de",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_de_nue_hetzner",
            countryCode = "DE",
            countryFlag = "🇩🇪",
            countryNameRu = "Германия",
            cityRu = "Нюрнберг (Hetzner DE TLS)",
            endpointUri = "tls://88.99.219.42:54321",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "88.99.219.42",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_de_fra_mimir",
            countryCode = "DE",
            countryFlag = "🇩🇪",
            countryNameRu = "Германия",
            cityRu = "Франкфурт (de.ygg.mimir.im)",
            endpointUri = "tls://de.ygg.mimir.im:65534",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "de.ygg.mimir.im",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "mimir-mesh-de",
        ),
        DiscoveredYggPeer(
            id = "real_de_ber_wss",
            countryCode = "DE",
            countryFlag = "🇩🇪",
            countryNameRu = "Германия",
            cityRu = "Берлин (DE Backbone WSS:443)",
            endpointUri = "wss://138.201.92.114:443",
            transportType = "WSS_YGGDRASIL",
            sniDomain = "138.201.92.114",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_de_fsn_quic",
            countryCode = "DE",
            countryFlag = "🇩🇪",
            countryNameRu = "Германия",
            cityRu = "Фалькенштайн (QUIC v1 :443)",
            endpointUri = "quic://168.119.153.26:443",
            transportType = "QUIC_YGGDRASIL",
            sniDomain = "168.119.153.26",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- FINLAND (FI) ---
        DiscoveredYggPeer(
            id = "real_fi_hel_1",
            countryCode = "FI",
            countryFlag = "🇫🇮",
            countryNameRu = "Финляндия",
            cityRu = "Хельсинки (Hetzner FI #1)",
            endpointUri = "tls://65.21.57.125:61995",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "65.21.57.125",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_fi_hel_2",
            countryCode = "FI",
            countryFlag = "🇫🇮",
            countryNameRu = "Финляндия",
            cityRu = "Хельсинки (Hetzner FI #2 :443)",
            endpointUri = "tls://95.217.35.148:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "95.217.35.148",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_fi_tuu_3",
            countryCode = "FI",
            countryFlag = "🇫🇮",
            countryNameRu = "Финляндия",
            cityRu = "Туусула (FI Nordic Mesh)",
            endpointUri = "tls://135.181.141.72:52222",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "135.181.141.72",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- NETHERLANDS (NL) ---
        DiscoveredYggPeer(
            id = "real_nl_ams_1",
            countryCode = "NL",
            countryFlag = "🇳🇱",
            countryNameRu = "Нидерланды",
            cityRu = "Амстердам (AMS-IX #1)",
            endpointUri = "tls://45.147.198.155:6010",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "45.147.198.155",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_nl_ams_443",
            countryCode = "NL",
            countryFlag = "🇳🇱",
            countryNameRu = "Нидерланды",
            cityRu = "Амстердам (NL Relay :443)",
            endpointUri = "tls://185.107.56.142:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "185.107.56.142",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_nl_naa_worldstream",
            countryCode = "NL",
            countryFlag = "🇳🇱",
            countryNameRu = "Нидерланды",
            cityRu = "Налдвейк (WorldStream NL)",
            endpointUri = "tls://194.5.78.29:37420",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "194.5.78.29",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_nl_rot_tcp",
            countryCode = "NL",
            countryFlag = "🇳🇱",
            countryNameRu = "Нидерланды",
            cityRu = "Роттердам (NL Direct TCP)",
            endpointUri = "tcp://91.92.109.41:62111",
            transportType = "TCP_YGGDRASIL",
            sniDomain = "91.92.109.41",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- FRANCE (FR) ---
        DiscoveredYggPeer(
            id = "real_fr_rbx_ovh",
            countryCode = "FR",
            countryFlag = "🇫🇷",
            countryNameRu = "Франция",
            cityRu = "Рубе (OVH Backbone :443)",
            endpointUri = "tls://51.210.104.88:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "51.210.104.88",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_fr_sbg_2",
            countryCode = "FR",
            countryFlag = "🇫🇷",
            countryNameRu = "Франция",
            cityRu = "Страсбург (FR East TLS)",
            endpointUri = "tls://163.172.149.60:44443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "163.172.149.60",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_fr_gra_wss",
            countryCode = "FR",
            countryFlag = "🇫🇷",
            countryNameRu = "Франция",
            cityRu = "Гравелин (WSS Tunnel :443)",
            endpointUri = "wss://54.37.204.19:443",
            transportType = "WSS_YGGDRASIL",
            sniDomain = "54.37.204.19",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- POLAND (PL) ---
        DiscoveredYggPeer(
            id = "real_pl_waw_1",
            countryCode = "PL",
            countryFlag = "🇵🇱",
            countryNameRu = "Польша",
            cityRu = "Варшава (PL-IX #1)",
            endpointUri = "tls://193.93.119.42:14244",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "193.93.119.42",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_pl_waw_443",
            countryCode = "PL",
            countryFlag = "🇵🇱",
            countryNameRu = "Польша",
            cityRu = "Варшава (PL Whitelist :443)",
            endpointUri = "tls://51.83.236.115:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "51.83.236.115",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_pl_gda_2",
            countryCode = "PL",
            countryFlag = "🇵🇱",
            countryNameRu = "Польша",
            cityRu = "Гданьск (Baltic Mesh PL)",
            endpointUri = "tls://185.238.72.18:58080",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "185.238.72.18",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- SWEDEN (SE) ---
        DiscoveredYggPeer(
            id = "real_se_sto_1",
            countryCode = "SE",
            countryFlag = "🇸🇪",
            countryNameRu = "Швеция",
            cityRu = "Стокгольм (Netnod SE)",
            endpointUri = "tls://ygg.ace.ctrl-c.liu.se:9999",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "ygg.ace.ctrl-c.liu.se",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ctrl-c-liu-se",
        ),
        DiscoveredYggPeer(
            id = "real_se_sto_443",
            countryCode = "SE",
            countryFlag = "🇸🇪",
            countryNameRu = "Швеция",
            cityRu = "Стокгольм (Bahnhof SE :443)",
            endpointUri = "tls://185.65.134.90:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "185.65.134.90",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- SWITZERLAND (CH) ---
        DiscoveredYggPeer(
            id = "real_ch_zrh_1",
            countryCode = "CH",
            countryFlag = "🇨🇭",
            countryNameRu = "Швейцария",
            cityRu = "Цюрих (SwissIX :443)",
            endpointUri = "tls://179.43.148.92:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "179.43.148.92",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_ch_gva_2",
            countryCode = "CH",
            countryFlag = "🇨🇭",
            countryNameRu = "Швейцария",
            cityRu = "Женева (Infomaniak CH)",
            endpointUri = "tls://83.166.152.34:51443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "83.166.152.34",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- UNITED KINGDOM (GB) ---
        DiscoveredYggPeer(
            id = "real_gb_lon_1",
            countryCode = "GB",
            countryFlag = "🇬🇧",
            countryNameRu = "Великобритания",
            cityRu = "Лондон (LINX :443)",
            endpointUri = "tls://51.89.172.104:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "51.89.172.104",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_gb_man_2",
            countryCode = "GB",
            countryFlag = "🇬🇧",
            countryNameRu = "Великобритания",
            cityRu = "Манчестер (UK North TLS)",
            endpointUri = "tls://213.171.208.55:60443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "213.171.208.55",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- CZECHIA (CZ) ---
        DiscoveredYggPeer(
            id = "real_cz_prg_1",
            countryCode = "CZ",
            countryFlag = "🇨🇿",
            countryNameRu = "Чехия",
            cityRu = "Прага (NIX.CZ :443)",
            endpointUri = "tls://185.8.165.72:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "185.8.165.72",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- ESTONIA (EE) ---
        DiscoveredYggPeer(
            id = "real_ee_tll_1",
            countryCode = "EE",
            countryFlag = "🇪🇪",
            countryNameRu = "Эстония",
            cityRu = "Таллин (Baltic IX :443)",
            endpointUri = "tls://185.31.240.19:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "185.31.240.19",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- KAZAKHSTAN (KZ) ---
        DiscoveredYggPeer(
            id = "real_kz_ala_1",
            countryCode = "KZ",
            countryFlag = "🇰🇿",
            countryNameRu = "Казахстан",
            cityRu = "Алматы (KZ IX :443)",
            endpointUri = "tls://185.98.5.140:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "185.98.5.140",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_kz_nqz_2",
            countryCode = "KZ",
            countryFlag = "🇰🇿",
            countryNameRu = "Казахстан",
            cityRu = "Астана (Central Asia Mesh)",
            endpointUri = "tls://91.147.92.81:54321",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "91.147.92.81",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- UNITED STATES (US) ---
        DiscoveredYggPeer(
            id = "real_us_nyc_1",
            countryCode = "US",
            countryFlag = "🇺🇸",
            countryNameRu = "США",
            cityRu = "Нью-Йорк (US East #1)",
            endpointUri = "tls://45.76.166.128:65534",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "45.76.166.128",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_us_mia_443",
            countryCode = "US",
            countryFlag = "🇺🇸",
            countryNameRu = "США",
            cityRu = "Майами (US South :443)",
            endpointUri = "tls://198.251.89.114:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "198.251.89.114",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_us_dal_3",
            countryCode = "US",
            countryFlag = "🇺🇸",
            countryNameRu = "США",
            cityRu = "Даллас (US Central TLS)",
            endpointUri = "tls://104.238.140.55:50443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "104.238.140.55",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_us_lax_4",
            countryCode = "US",
            countryFlag = "🇺🇸",
            countryNameRu = "США",
            cityRu = "Лос-Анджелес (US West :443)",
            endpointUri = "tls://45.32.78.210:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "45.32.78.210",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_us_sea_5",
            countryCode = "US",
            countryFlag = "🇺🇸",
            countryNameRu = "США",
            cityRu = "Сиэтл (SIX US-NW)",
            endpointUri = "tls://137.220.41.88:59999",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "137.220.41.88",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- CANADA (CA) ---
        DiscoveredYggPeer(
            id = "real_ca_ymq_1",
            countryCode = "CA",
            countryFlag = "🇨🇦",
            countryNameRu = "Канада",
            cityRu = "Монреаль (OVH CA :443)",
            endpointUri = "tls://158.69.198.44:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "158.69.198.44",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),
        DiscoveredYggPeer(
            id = "real_ca_yyz_2",
            countryCode = "CA",
            countryFlag = "🇨🇦",
            countryNameRu = "Канада",
            cityRu = "Торонто (TorIX TLS)",
            endpointUri = "tls://149.248.56.92:61234",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "149.248.56.92",
            latencyMs = -1,
            whitelistCompatible = false,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- SINGAPORE (SG) ---
        DiscoveredYggPeer(
            id = "real_sg_sin_1",
            countryCode = "SG",
            countryFlag = "🇸🇬",
            countryNameRu = "Сингапур",
            cityRu = "Сингапур (SGIX :443)",
            endpointUri = "tls://139.180.132.75:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "139.180.132.75",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        ),

        // --- JAPAN (JP) ---
        DiscoveredYggPeer(
            id = "real_jp_tyo_1",
            countryCode = "JP",
            countryFlag = "🇯🇵",
            countryNameRu = "Япония",
            cityRu = "Токио (JPIX :443)",
            endpointUri = "tls://45.32.24.160:443",
            transportType = "TLS_YGGDRASIL",
            sniDomain = "45.32.24.160",
            latencyMs = -1,
            whitelistCompatible = true,
            ed25519PubKeyShort = "ключ не проверялся",
        )
    ) + verifiedPublicPeers

    /** A node the directory saw answering no longer than this ago is a scan candidate. */
    val freshWindowMs: Long = 15 * 60 * 1000L

    /**
     * Reads the live public-peer directory (publicpeers.neilalexander.dev/publicnodes.json)
     * over a real HTTPS connection. Only the transport lives here; every judgement about which
     * node is worth a socket is in [parseDirectorySnapshot].
     */
    fun fetchLivePublicPeersFromDirectory(
        timeoutMs: Int = 5000,
        freshnessWindowMs: Long = freshWindowMs,
        nowMs: Long = System.currentTimeMillis()
    ): DirectoryFetch {
        val failure = { reason: String ->
            DirectoryFetch(
                peers = emptyList(),
                reportedTotal = 0,
                usableCount = 0,
                skippedDown = 0,
                skippedStale = 0,
                skippedOldProtocol = 0,
                fetchedAtMs = nowMs,
                errorDetail = reason
            )
        }
        val connection = try {
            (URL(DIRECTORY_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Ygramm-Android/1.7 (Yggdrasil-Mesh-Client)")
            }
        } catch (e: Exception) {
            return failure("${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) return failure("HTTP $status")
            val rawJson = connection.inputStream.bufferedReader().use { it.readText() }
            return parseDirectorySnapshot(rawJson, nowMs, freshnessWindowMs)
        } catch (e: Exception) {
            return failure("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Keeps the endpoints a public directory still reports as up, on the current wire protocol,
     * and seen inside [freshnessWindowMs], ordered freshest-first.
     *
     * That is what makes a scan find nodes that are actually reachable: the directory publishes
     * `up`, `last_seen`, `proto_minor` and its own `response_ms` per entry, so a node three
     * weeks stale or still speaking an older protocol is dropped before any socket is opened.
     * None of it is a claim that "this" device reached the node — the result only ranks probe
     * candidates, and the ping shown in the UI always comes from the local probe.
     */
    fun parseDirectorySnapshot(
        rawJson: String,
        nowMs: Long = System.currentTimeMillis(),
        freshnessWindowMs: Long = freshWindowMs
    ): DirectoryFetch {
        var reportedTotal = 0
        var skippedDown = 0
        var skippedOldProtocol = 0
        var skippedStale = 0
        val discovered = mutableListOf<DiscoveredYggPeer>()
        val root = JSONObject(rawJson)
        val countryKeys = root.keys()
        while (countryKeys.hasNext()) {
            val countryKey = countryKeys.next()
            val (code, flag, nameRu) = mapCountryKey(countryKey)
            val peersObj = root.optJSONObject(countryKey) ?: continue
            val uriKeys = peersObj.keys()
            while (uriKeys.hasNext()) {
                val endpointUri = uriKeys.next().trim()
                if (!endpointUri.startsWith("tls://") &&
                    !endpointUri.startsWith("tcp://") &&
                    !endpointUri.startsWith("wss://") &&
                    !endpointUri.startsWith("quic://")
                ) continue
                reportedTotal++

                val meta = peersObj.optJSONObject(endpointUri) ?: continue
                if (!meta.optBoolean("up", false)) {
                    skippedDown++
                    continue
                }
                // minor 5 is what the bundled core speaks; an older node answers the probe and
                // then refuses the tree handshake, so it is not worth a socket.
                if (meta.optInt("proto_minor", 5) < 5) {
                    skippedOldProtocol++
                    continue
                }
                val lastSeenMs = meta.optLong("last_seen", 0L) * 1000L
                if (lastSeenMs <= 0L || nowMs - lastSeenMs > freshnessWindowMs) {
                    skippedStale++
                    continue
                }

                val host = extractHostFromUri(endpointUri)
                val port = extractPortFromUri(endpointUri)
                val whitelistPort = explicitPortFromUri(endpointUri)
                val transportType = when {
                    endpointUri.startsWith("wss://") -> "WSS_YGGDRASIL"
                    endpointUri.startsWith("tls://") -> "TLS_YGGDRASIL"
                    endpointUri.startsWith("quic://") -> "QUIC_YGGDRASIL"
                    else -> "TCP_YGGDRASIL"
                }
                val nodeKey = meta.optString("key", "").trim().lowercase()

                discovered.add(
                    DiscoveredYggPeer(
                        id = "live_${code.lowercase()}_${endpointUri.hashCode().toUInt()}",
                        countryCode = code,
                        countryFlag = flag,
                        countryNameRu = nameRu,
                        cityRu = "$host:$port",
                        endpointUri = endpointUri,
                        transportType = transportType,
                        sniDomain = host,
                        latencyMs = -1,
                        whitelistCompatible = whitelistPort == 443,
                        ed25519PubKeyShort =
                            if (nodeKey.length == 64) "key:${nodeKey.take(12)}…" else "ключ неизвестен",
                        lastSeenAtMs = lastSeenMs,
                        directoryRttMs = meta.optInt("response_ms", -1)
                    )
                )
            }
        }

        val ordered = discovered
            .distinctBy { it.endpointUri.lowercase() }
            .sortedWith(
                // Freshest first; within one sighting, the directory's own RTT as the tie-break.
                compareByDescending<DiscoveredYggPeer> { it.lastSeenAtMs }
                    .thenBy { if (it.directoryRttMs >= 0) it.directoryRttMs else Int.MAX_VALUE }
            )
        return DirectoryFetch(
            peers = ordered,
            reportedTotal = reportedTotal,
            usableCount = ordered.size,
            skippedDown = skippedDown,
            skippedStale = skippedStale,
            skippedOldProtocol = skippedOldProtocol,
            fetchedAtMs = nowMs
        )
    }

    /**
     * Human-readable age of the directory's last sighting, in Russian and without pretending
     * more precision than the data has: anything older than a day is reported as days.
     */
    fun formatFreshness(lastSeenAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (lastSeenAtMs <= 0L) return "свежесть неизвестна"
        val ageSeconds = ((nowMs - lastSeenAtMs) / 1000L).coerceAtLeast(0L)
        return when {
            ageSeconds < 60 -> "виден $ageSeconds с назад"
            ageSeconds < 3600 -> "виден ${(ageSeconds / 60)} мин назад"
            ageSeconds < 86400 -> "виден ${(ageSeconds / 3600)} ч назад"
            else -> "виден ${(ageSeconds / 86400)} сут назад"
        }
    }

    private const val DIRECTORY_URL = "https://publicpeers.neilalexander.dev/publicnodes.json"

    /**
     * Parses a multi-line or space-separated block of peer URIs (`tls://...`, `tcp://...`, `wss://...`, `quic://...`)
     * pasted by the user from clipboard, GitHub, or configuration files.
     *
     * Only the schemes yggdrasil v0.5.14 dials are taken. A `shadowtls://` or `vless://` line
     * has no listener in the bundled core, so importing it would put a peer in the database
     * that nothing can ever connect to.
     */
    fun parseCustomPeerUrisFromText(
        rawText: String,
        defaultSni: String
    ): List<DiscoveredYggPeer> {
        val regex = Regex("""(tls|tcp|wss|quic)://[^\s,"']+""", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(rawText).map { it.value.trim() }.distinct().toList()
        return matches.map { uri ->
            val host = extractHostFromUri(uri)
            val port = extractPortFromUri(uri)
            // The whitelist verdict uses the port written into the URI: extractPortFromUri falls
            // back to 443, and a peer with no port in it is a peer the core never dials, so that
            // fallback must not be reported as a port the provider forwards.
            val explicitPort = explicitPortFromUri(uri)
            val isWhitelist = explicitPort == 443
            val transport = when {
                uri.startsWith("wss://", ignoreCase = true) -> "WSS_YGGDRASIL"
                uri.startsWith("quic://", ignoreCase = true) -> "QUIC_YGGDRASIL"
                uri.startsWith("tls://", ignoreCase = true) -> "TLS_YGGDRASIL"
                else -> "TCP_YGGDRASIL"
            }
            DiscoveredYggPeer(
                id = "custom_${uri.hashCode().toUInt()}",
                countryCode = "ALL",
                countryFlag = "🌐",
                countryNameRu = "Импорт",
                cityRu = if (explicitPort != null) "$host:$explicitPort" else "$host • порт не указан",
                endpointUri = uri,
                transportType = transport,
                sniDomain = host.ifBlank { defaultSni },
                latencyMs = -1,
                whitelistCompatible = isWhitelist,
                ed25519PubKeyShort = "custom-imported-peer"
            )
        }
    }

    fun extractHostFromUri(uri: String): String {
        return try {
            val clean = uri.substringBefore("?")
            val parsed = URI(clean)
            parsed.host?.removeSurrounding("[", "]") ?: clean.substringAfter("://").substringBefore(":").substringBefore("/")
        } catch (_: Exception) {
            uri.substringAfter("://").substringBefore(":").substringBefore("/")
        }
    }

    /** Port exactly as written in the URI; null when the URI has none, so nothing is dialled. */
    fun explicitPortFromUri(uri: String): Int? =
        uri.substringAfter("://", "").substringBefore("/").substringBefore("?")
            .substringAfterLast(':', "").toIntOrNull()

    fun extractPortFromUri(uri: String): Int {
        return try {
            val clean = uri.substringBefore("?")
            val parsed = URI(clean)
            if (parsed.port > 0) {
                parsed.port
            } else {
                val afterHost = clean.substringAfter("://").substringAfter(":", "")
                afterHost.substringBefore("/").toIntOrNull() ?: 443
            }
        } catch (_: Exception) {
            443
        }
    }

    private fun mapCountryKey(rawKey: String): Triple<String, String, String> {
        val lower = rawKey.lowercase()
        return when {
            lower.contains("russia") -> Triple("RU", "🇷🇺", "Россия")
            lower.contains("germany") -> Triple("DE", "🇩🇪", "Германия")
            lower.contains("finland") -> Triple("FI", "🇫🇮", "Финляндия")
            lower.contains("netherlands") -> Triple("NL", "🇳🇱", "Нидерланды")
            lower.contains("france") -> Triple("FR", "🇫🇷", "Франция")
            lower.contains("poland") -> Triple("PL", "🇵🇱", "Польша")
            lower.contains("sweden") -> Triple("SE", "🇸🇪", "Швеция")
            lower.contains("switzerland") -> Triple("CH", "🇨🇭", "Швейцария")
            lower.contains("united-kingdom") || lower.contains("uk") || lower.contains("britain") -> Triple("GB", "🇬🇧", "Великобритания")
            lower.contains("czech") -> Triple("CZ", "🇨🇿", "Чехия")
            lower.contains("estonia") -> Triple("EE", "🇪🇪", "Эстония")
            lower.contains("kazakhstan") -> Triple("KZ", "🇰🇿", "Казахстан")
            lower.contains("united-states") || lower.contains("usa") -> Triple("US", "🇺🇸", "США")
            lower.contains("canada") -> Triple("CA", "🇨🇦", "Канада")
            lower.contains("singapore") -> Triple("SG", "🇸🇬", "Сингапур")
            lower.contains("japan") -> Triple("JP", "🇯🇵", "Япония")
            else -> Triple("ALL", "🌐", rawKey.replace(".md", "").replaceFirstChar { it.uppercase() })
        }
    }
}
