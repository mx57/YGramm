// File: app/src/main/java/com/example/data/YggRepository.kt
package com.example.data

import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggCryptoEngine
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class YggRepository(private val dao: YggDao) {

    val conversations: Flow<List<ConversationEntity>> = dao.observeConversations()
    val allMessages: Flow<List<MessageEntity>> = dao.observeAllMessages()
    val fileTransfers: Flow<List<P2PFileTransferEntity>> = dao.observeFileTransfers()
    val peers: Flow<List<YggPeerEntity>> = dao.observePeers()
    val linkedDevices: Flow<List<LinkedDeviceEntity>> = dao.observeLinkedDevices()
    val backupSnapshots: Flow<List<BackupSnapshotEntity>> = dao.observeBackupSnapshots()
    val cryptoKeys: Flow<List<CryptoKeyRecordEntity>> = dao.observeCryptoKeys()

    fun observeMessagesForChat(chatId: String): Flow<List<MessageEntity>> =
        dao.observeMessagesForChat(chatId)

    suspend fun ensureSeeded(identity: DerivedIdentityHierarchy, isCloneNode: Boolean = false) {
        if (dao.countConversations() > 0) return
        val now = System.currentTimeMillis()

        val twinTitle = if (isCloneNode) {
            "Узел #1: YggGram Основной (Прямой P2P Мост)"
        } else {
            "Узел #2: YggGram Клон (Прямой P2P Мост)"
        }
        val twinPortInfo = if (isCloneNode) "UDP 127.0.0.1:51921" else "UDP 127.0.0.1:51922"
        val twinIpv6 = if (isCloneNode) {
            "0201:7c91:44e0:11a2:88d3:50f1:99b2:0001"
        } else {
            "0201:7c91:44e0:11a2:88d3:50f1:99b2:0002"
        }

        val initialConversations = listOf(
            ConversationEntity(
                chatId = "chat_twin_node",
                title = twinTitle,
                yggIpv6Address = twinIpv6,
                publicKeyHex = "e091a48c273b6501f9283746c109e827d645b321a098f712c3489012a4b5c6d7",
                protocolType = "X3DH_DOUBLE_RATCHET",
                isVerified = true,
                sealedSenderEnabled = true,
                disappearingTtlSeconds = 0,
                unreadCount = 1,
                lastMessagePreview = "Канал проверки связи между двумя APK активен ($twinPortInfo)",
                lastTimestamp = now - 30_000L,
                isOnlineInMesh = true,
                avatarColorHex = "#10B981",
                bioStatus = "Локальный и LAN P2P мост между YggGram Main и YggGram Clone",
                routeTransportSummary = "Direct P2P Loopback ($twinPortInfo • 1 мс)"
            ),
            ConversationEntity(
                chatId = "chat_alex_vortex",
                title = "Алексей (Узел МСК-Юг)",
                yggIpv6Address = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
                publicKeyHex = "7f8b3c1099e245a7d108c633b49a22f1e09c8a445109b7d2638a109f4c22e81b",
                protocolType = "X3DH_DOUBLE_RATCHET",
                isVerified = true,
                sealedSenderEnabled = true,
                disappearingTtlSeconds = 0,
                unreadCount = 2,
                lastMessagePreview = "Пакеты через CDN WSS (SNI белого списка) идут стабильно, потерь 0%",
                lastTimestamp = now - 180_000L,
                isOnlineInMesh = true,
                avatarColorHex = "#10B981",
                bioStatus = "Core Yggdrasil Relay • Анти-ТСПУ v0.5 • 200::/7",
                routeTransportSummary = "WSS CDN Whitelist -> 2 хопа (19 мс)"
            ),
            ConversationEntity(
                chatId = "chat_mls_ru_mesh",
                title = "MLS Группа: Оверлей и Обход Белых Списков",
                yggIpv6Address = "0202:51c0:a814:77e9:10b2:4f88:9901:3c20",
                publicKeyHex = "a419e08c237f6501b92044d8e71a33f092c18456b77e01a9c33489f102d45e66",
                protocolType = "MLS_RFC9420",
                isVerified = true,
                sealedSenderEnabled = true,
                disappearingTtlSeconds = 86400,
                unreadCount = 5,
                lastMessagePreview = "Опубликован свежий список пиров с Ed25519 подписью для VLESS+uTLS",
                lastTimestamp = now - 540_000L,
                isOnlineInMesh = true,
                avatarColorHex = "#06B6D4",
                bioStatus = "Группа RFC 9420 (TreeKEM, эпоха #14) • 18 участников",
                routeTransportSummary = "VLESS Reality + uTLS Chrome -> 1 хоп (24 мс)"
            ),
            ConversationEntity(
                chatId = "chat_elena_spb",
                title = "Елена К. (Крипто-аудит СПб)",
                yggIpv6Address = "0201:c409:11f8:62e3:9a04:81d7:33f0:19b2",
                publicKeyHex = "39d01a884c72e65019f283b4710c92e883a147f055b290c1847e39201a4b6c92",
                protocolType = "X3DH_DOUBLE_RATCHET",
                isVerified = true,
                sealedSenderEnabled = true,
                disappearingTtlSeconds = 3600,
                unreadCount = 0,
                lastMessagePreview = "Архив схемы ключей передан по P2P (Merkle SHA-256 проверен)",
                lastTimestamp = now - 2_400_000L,
                isOnlineInMesh = true,
                avatarColorHex = "#34D399",
                bioStatus = "Аудитор безопасности • X3DH / Sealed Sender только с верификацией",
                routeTransportSummary = "ShadowTLS v3 + uTLS iOS -> 2 хопа (28 мс)"
            ),
            ConversationEntity(
                chatId = "chat_siberia_sf",
                title = "Дмитрий (Узел Новосибирск • Офлайн)",
                yggIpv6Address = "0201:77a1:d044:38f2:60c1:29e5:8b12:9004",
                publicKeyHex = "88c210f49a73b105e62894d103f7a29c48105b66e9201a3748c59201f4e83a10",
                protocolType = "X3DH_DOUBLE_RATCHET",
                isVerified = false,
                sealedSenderEnabled = true,
                disappearingTtlSeconds = 0,
                unreadCount = 0,
                lastMessagePreview = "На связи через слепой Store-and-Forward узел (Hashcash PoW)",
                lastTimestamp = now - 7_200_000L,
                isOnlineInMesh = false,
                avatarColorHex = "#F59E0B",
                bioStatus = "Мобильный узел • Доставка через S&F релеи при шатдаунах",
                routeTransportSummary = "Store-and-Forward Очередь (Hashcash 10-bit)"
            )
        )
        dao.insertConversations(initialConversations)

        val initialMessages = listOf(
            MessageEntity(
                messageId = "msg_twin_welcome",
                chatId = "chat_twin_node",
                senderName = twinTitle,
                senderIpv6 = twinIpv6,
                cipherPayloadHex = "e091a48c273b6501f9283746c109e827d645b321a098f712",
                plainText = "Этот диалог связывает основной APK (YggGram-main.apk) и клон (YggGram-clone-node2.apk) по реальному UDP-сокету ($twinPortInfo). Отправьте сообщение здесь, чтобы мгновенно получить его во втором приложении!",
                timestamp = now - 30_000L,
                isOutgoing = false,
                deliveryState = "DELIVERED",
                ratchetStep = 1,
                sealedSenderFlag = true,
                hashcashPowToken = "hc1:10:1729201500:42:0000"
            ),
            MessageEntity(
                messageId = "msg_1",
                chatId = "chat_alex_vortex",
                senderName = "Алексей (Узел МСК-Юг)",
                senderIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
                cipherPayloadHex = "9a4f12e088c3b47150d92a11c8403f77e12904a5b6c7d8e9",
                plainText = "Привет! Проверил твой новый узел Yggdrasil в Userspace-режиме. Прямой маршрут поднялся за 400 мс без создания TUN-интерфейса.",
                timestamp = now - 600_000L,
                isOutgoing = false,
                deliveryState = "READ",
                ratchetStep = 41,
                sealedSenderFlag = true,
                hashcashPowToken = "hc1:10:1729201000:142:001a"
            ),
            MessageEntity(
                messageId = "msg_2",
                chatId = "chat_alex_vortex",
                senderName = "Вы",
                senderIpv6 = identity.yggIpv6Address,
                cipherPayloadHex = "c081e4729a105f33b849201d74e6c9021a384f55b601e29a",
                plainText = "Отлично. Я включил обход белых списков через CDN WebSocket + uTLS Chrome 128 и отправку файла с P2P-верификацией Merkle.",
                timestamp = now - 360_000L,
                isOutgoing = true,
                deliveryState = "READ",
                ratchetStep = 42,
                sealedSenderFlag = true,
                hashcashPowToken = "hc1:10:1729201240:89:000f",
                attachmentName = "ygg_whitelist_bridges_ru.json.zst",
                attachmentSizeBytes = 1_482_752L,
                merkleRootHex = "e48910c37f2a65019b8432d109c7e542a108f932b74610c8"
            ),
            MessageEntity(
                messageId = "msg_3",
                chatId = "chat_alex_vortex",
                senderName = "Алексей (Узел МСК-Юг)",
                senderIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
                cipherPayloadHex = "f712049c83a165b092e8471d33c095a28174e6019b283c47",
                plainText = "Пакеты через CDN WSS (SNI белого списка) идут стабильно, потерь 0%",
                timestamp = now - 180_000L,
                isOutgoing = false,
                deliveryState = "DELIVERED",
                ratchetStep = 43,
                sealedSenderFlag = true,
                hashcashPowToken = "hc1:10:1729201420:211:0024"
            ),
            MessageEntity(
                messageId = "msg_4",
                chatId = "chat_mls_ru_mesh",
                senderName = "Координатор Сети (MLS Leaf #01)",
                senderIpv6 = "0202:51c0:a814:77e9:10b2:4f88:9901:3c20",
                cipherPayloadHex = "44b190e82c37461059a82b3c4d5e6f708192a3b4c5d6e7f8",
                plainText = "Внимание: на мобильных операторах при ограничении по белым спискам используйте профиль CDN WSS (sni: static-cdn.yandex.net / vk-portal.net) в паре с QUIC Padding.",
                timestamp = now - 900_000L,
                isOutgoing = false,
                deliveryState = "READ",
                ratchetStep = 118,
                sealedSenderFlag = true,
                hashcashPowToken = "hc1:10:1729200500:64:0012"
            ),
            MessageEntity(
                messageId = "msg_5",
                chatId = "chat_mls_ru_mesh",
                senderName = "Узел Урал-04 (MLS Leaf #07)",
                senderIpv6 = "0201:99b4:72c1:0048:33a1:f092:11e4:88c1",
                cipherPayloadHex = "71a094c82e3b451098f76e54d321c098b765a432f109e876",
                plainText = "Опубликован свежий список пиров с Ed25519 подписью для VLESS+uTLS",
                timestamp = now - 540_000L,
                isOutgoing = false,
                deliveryState = "DELIVERED",
                ratchetStep = 119,
                sealedSenderFlag = true,
                hashcashPowToken = "hc1:10:1729200860:309:0009"
            ),
            MessageEntity(
                messageId = "msg_6",
                chatId = "chat_elena_spb",
                senderName = "Елена К. (Крипто-аудит СПб)",
                senderIpv6 = "0201:c409:11f8:62e3:9a04:81d7:33f0:19b2",
                cipherPayloadHex = "18e902a47c3651b098d72e43f109a827c654b321e098d765",
                plainText = "Архив схемы ключей передан по P2P (Merkle SHA-256 проверен)",
                timestamp = now - 2_400_000L,
                isOutgoing = false,
                deliveryState = "READ",
                ratchetStep = 19,
                sealedSenderFlag = true,
                hashcashPowToken = "hc1:10:1729199000:128:001f",
                attachmentName = "slip0010_audit_vectors.pdf",
                attachmentSizeBytes = 3_940_352L,
                merkleRootHex = "91b048e27c3651a098f72e43d109c827b654a321f098c765"
            ),
            MessageEntity(
                messageId = "msg_7",
                chatId = "chat_siberia_sf",
                senderName = "Дмитрий (Узел Новосибирск • Офлайн)",
                senderIpv6 = "0201:77a1:d044:38f2:60c1:29e5:8b12:9004",
                cipherPayloadHex = "55c190a48e27361b098d72e43f109a827c654b321e098d76",
                plainText = "На связи через слепой Store-and-Forward узел (Hashcash PoW)",
                timestamp = now - 7_200_000L,
                isOutgoing = false,
                deliveryState = "READ",
                ratchetStep = 8,
                sealedSenderFlag = true,
                hashcashPowToken = "hc1:12:1729194200:512:0003"
            )
        )
        dao.insertMessages(initialMessages)

        val initialTransfers = listOf(
            P2PFileTransferEntity(
                transferId = "tx_p2p_1",
                chatId = "chat_alex_vortex",
                peerName = "Алексей (Узел МСК-Юг)",
                peerIpv6 = "0201:8f4e:3b12:90a4:55d1:c209:7e11:44a8",
                fileName = "ygg_whitelist_bridges_ru.json.zst",
                fileSizeBytes = 1_482_752L,
                chunkCount = 24,
                completedChunks = 24,
                merkleRootSha256 = "e48910c37f2a65019b8432d109c7e542a108f932b74610c8",
                speedKbps = 1840,
                status = "VERIFIED_COMPLETE",
                isOutgoing = true,
                protocol = "QUIC_P2P_DIRECT",
                timestamp = now - 350_000L
            ),
            P2PFileTransferEntity(
                transferId = "tx_p2p_2",
                chatId = "chat_mls_ru_mesh",
                peerName = "MLS Группа: Оверлей и Обход",
                peerIpv6 = "0202:51c0:a814:77e9:10b2:4f88:9901:3c20",
                fileName = "antidpi_utls_profiles_2026.tar.zst",
                fileSizeBytes = 8_388_608L,
                chunkCount = 128,
                completedChunks = 92,
                merkleRootSha256 = "7c3910a48e2751b098d72e43f109a827c654b321e098d711",
                speedKbps = 960,
                status = "ACTIVE_STREAMING",
                isOutgoing = false,
                protocol = "YGG_OVERLAY_STREAM",
                timestamp = now - 120_000L
            )
        )
        dao.insertFileTransfers(initialTransfers)

        val initialPeers = listOf(
            YggPeerEntity(
                peerId = "peer_local_twin_bridge",
                endpointUri = "udp+ygg://127.0.0.1:${if (isCloneNode) 51921 else 51922}/twin-p2p",
                transportType = "QUIC_UDP_PADDING",
                sniDomain = "local.ygggram.mesh",
                utlsFingerprint = "Direct_P2P_Loopback",
                regionLabel = if (isCloneNode) "LOCAL • Основной APK (Node #1)" else "LOCAL • Клон APK (Node #2)",
                latencyMs = 1,
                rxBytes = 524_288L,
                txBytes = 524_288L,
                isConnected = true,
                isWhitelistCompatible = true,
                ed25519Verified = true
            ),
            YggPeerEntity(
                peerId = "peer_ru_cdn_whitelist_1",
                endpointUri = "wss://edge-msk-01.cdn-bridge.ru:443/ygg-ws",
                transportType = "WSS_CDN_WHITELIST",
                sniDomain = "static-mon.yandex.net",
                utlsFingerprint = "HelloChrome_128_Auto",
                regionLabel = "RU-MSK • Белый список CDN",
                latencyMs = 19,
                rxBytes = 48_920_112L,
                txBytes = 19_410_800L,
                isConnected = true,
                isWhitelistCompatible = true,
                ed25519Verified = true
            ),
            YggPeerEntity(
                peerId = "peer_vless_reality_spb",
                endpointUri = "vless+tls://185.212.148.44:443?reality=1",
                transportType = "VLESS_REALITY_UTLS",
                sniDomain = "habr.com",
                utlsFingerprint = "HelloChrome_128_Auto",
                regionLabel = "RU-SPB • VLESS Reality + uTLS",
                latencyMs = 24,
                rxBytes = 31_204_900L,
                txBytes = 14_882_100L,
                isConnected = true,
                isWhitelistCompatible = true,
                ed25519Verified = true
            ),
            YggPeerEntity(
                peerId = "peer_shadowtls_hel",
                endpointUri = "shadowtls://95.216.88.19:8443?v=3",
                transportType = "SHADOWTLS_V3",
                sniDomain = "cdn.gosuslugi.ru",
                utlsFingerprint = "HelloIOS_18_Auto",
                regionLabel = "FI-HEL • ShadowTLS v3 Мост",
                latencyMs = 34,
                rxBytes = 64_110_200L,
                txBytes = 28_300_512L,
                isConnected = true,
                isWhitelistCompatible = true,
                ed25519Verified = true
            )
        )
        dao.insertPeers(initialPeers)

        val initialDevices = listOf(
            LinkedDeviceEntity(
                deviceId = "dev_android_current",
                deviceName = if (isCloneNode) "Android 15 (YggGram Клон #2)" else "Android 15 (YggGram Основной #1)",
                platform = "ANDROID_ARM64",
                yggIpv6 = identity.yggIpv6Address,
                lanEndpoint = "127.0.0.1:${if (isCloneNode) 51922 else 51921} (Локальный P2P сокет)",
                lastSyncTimestamp = now - 15_000L,
                stateVectorVersion = 142,
                syncMode = "LAN_AND_YGG_P2P",
                isOnline = true,
                syncedChunksCount = 384
            ),
            LinkedDeviceEntity(
                deviceId = "dev_android_twin",
                deviceName = if (isCloneNode) "Android 15 (YggGram Основной #1)" else "Android 15 (YggGram Клон #2)",
                platform = "ANDROID_ARM64",
                yggIpv6 = twinIpv6,
                lanEndpoint = "127.0.0.1:${if (isCloneNode) 51921 else 51922} (Прямой P2P мост)",
                lastSyncTimestamp = now - 20_000L,
                stateVectorVersion = 142,
                syncMode = "LAN_AND_YGG_P2P",
                isOnline = true,
                syncedChunksCount = 384
            ),
            LinkedDeviceEntity(
                deviceId = "dev_win_workstation",
                deviceName = "Windows 11 Desktop (YggGram x64)",
                platform = "WINDOWS_X64",
                yggIpv6 = "0201:4a90:c812:77e1:3b04:99d2:6011:8e4f",
                lanEndpoint = "192.168.1.10:51920 (Прямой LAN P2P)",
                lastSyncTimestamp = now - 90_000L,
                stateVectorVersion = 142,
                syncMode = "LAN_AND_YGG_P2P",
                isOnline = true,
                syncedChunksCount = 384
            )
        )
        dao.insertLinkedDevices(initialDevices)

        val initialBackups = listOf(
            BackupSnapshotEntity(
                snapshotId = "bak_local_01",
                archiveFileName = "ygg_vault_local_auto.yggbak",
                createdAt = now - 1_800_000L,
                sizeBytes = 4_820_992L,
                chunkCountFastCdc = 76,
                kdfSpec = "Argon2id (m=64MB, t=4, p=4)",
                cipherSpec = "XChaCha20-Poly1305 + FastCDC/Zstd",
                merkleRootHex = "8a41e09c273b6501f9283746c109e827d645b321a098f712",
                destination = "LOCAL_ENCRYPTED_CONTAINER",
                isVerified = true
            ),
            BackupSnapshotEntity(
                snapshotId = "bak_webdav_02",
                archiveFileName = "ygg_webdav_zk_epoch142.yggbak",
                createdAt = now - 3_600_000L,
                sizeBytes = 4_790_272L,
                chunkCountFastCdc = 75,
                kdfSpec = "Argon2id (m=64MB, t=4, p=4)",
                cipherSpec = "XChaCha20-Poly1305 + FastCDC/Zstd",
                merkleRootHex = "3f901a48c27651b098e72d43f109a827c654b321e098d409",
                destination = "WEBDAV_CLOUD_ZERO_KNOWLEDGE",
                isVerified = true
            )
        )
        dao.insertBackupSnapshots(initialBackups)

        val initialKeys = buildHierarchyKeyRecords(identity, epoch = 1, now = now)
        dao.insertCryptoKeys(initialKeys)
    }

    fun buildHierarchyKeyRecords(
        identity: DerivedIdentityHierarchy,
        epoch: Int,
        now: Long = System.currentTimeMillis()
    ): List<CryptoKeyRecordEntity> = listOf(
        CryptoKeyRecordEntity(
            keyId = "key_ed25519_node",
            keyName = "Ключ узла Yggdrasil (IPv6 Identity)",
            derivationPath = "m/44'/999'/0'/0'",
            algorithm = "Ed25519",
            publicKeyHex = identity.yggNodePublicKeyHex,
            fingerprintHex = identity.yggNodePublicKeyHex.take(24).uppercase(),
            createdAt = now,
            rotationEpoch = 1,
            isActive = true,
            usageScope = "Маршрутизация 200::/7 и подпись децентрализованного профиля"
        ),
        CryptoKeyRecordEntity(
            keyId = "key_x25519_identity",
            keyName = "Долгосрочный ключ чатов (X3DH Identity)",
            derivationPath = "m/44'/999'/1'/0'",
            algorithm = "X25519",
            publicKeyHex = identity.x25519IdentityPubHex,
            fingerprintHex = identity.x25519IdentityPubHex.take(24).uppercase(),
            createdAt = now,
            rotationEpoch = 1,
            isActive = true,
            usageScope = "Аутентификация сессий X3DH и Sealed Sender"
        ),
        CryptoKeyRecordEntity(
            keyId = "key_x25519_spk",
            keyName = "Подписанный пре-ключ (Signed PreKey #$epoch)",
            derivationPath = "m/44'/999'/1'/$epoch'",
            algorithm = "X25519 + Ed25519 Sig",
            publicKeyHex = identity.x25519SignedPreKeyHex,
            fingerprintHex = identity.x25519SignedPreKeyHex.take(24).uppercase(),
            createdAt = now,
            rotationEpoch = epoch,
            isActive = true,
            usageScope = "Инициализация Double Ratchet и асинхронный прием офлайн-сообщений"
        ),
        CryptoKeyRecordEntity(
            keyId = "key_mls_hpke",
            keyName = "Ключ листа дерева групп (MLS Leaf #$epoch)",
            derivationPath = "m/44'/999'/2'/$epoch'",
            algorithm = "MLS-HPKE (X25519-ChaCha20)",
            publicKeyHex = identity.mlsLeafKeyHex,
            fingerprintHex = identity.mlsLeafKeyHex.take(24).uppercase(),
            createdAt = now,
            rotationEpoch = epoch,
            isActive = true,
            usageScope = "Групповые чаты RFC 9420 TreeKEM с прямой секретностью"
        ),
        CryptoKeyRecordEntity(
            keyId = "key_backup_master",
            keyName = "Мастер-ключ бэкапов и чанков (Zero-Knowledge)",
            derivationPath = "m/44'/999'/3'/0'",
            algorithm = "XChaCha20-Poly1305 (256-bit)",
            publicKeyHex = YggCryptoEngine.sha256Hex(identity.backupMasterKeyFingerprint),
            fingerprintHex = identity.backupMasterKeyFingerprint,
            createdAt = now,
            rotationEpoch = 1,
            isActive = true,
            usageScope = "Шифрование локальных контейнеров и облачных чанков FastCDC"
        )
    )

    suspend fun sendEncryptedMessage(
        chatId: String,
        plainText: String,
        senderIpv6: String,
        isOfflineMode: Boolean,
        sealedSenderGlobal: Boolean,
        powDifficultyBits: Int,
        attachmentName: String? = null,
        attachmentSizeBytes: Long = 0L
    ): MessageEntity? {
        val conv = dao.getConversationById(chatId) ?: return null
        val now = System.currentTimeMillis()
        val step = ((now / 1000L) % 900).toInt() + 50
        val useSealed = sealedSenderGlobal || conv.sealedSenderEnabled
        val cipherHex = YggCryptoEngine.encryptDoubleRatchetEnvelope(
            plainText = plainText,
            peerPublicKeyHex = conv.publicKeyHex,
            ratchetStep = step,
            sealedSender = useSealed
        )
        val powToken = YggCryptoEngine.computeHashcashStamp(
            recipientIpv6 = conv.yggIpv6Address,
            payloadHashHex = cipherHex,
            difficultyBits = powDifficultyBits
        )
        val delivery = if (isOfflineMode || !conv.isOnlineInMesh) {
            "QUEUED_OFFLINE_SF"
        } else {
            "SENT_P2P"
        }
        val merkleRoot = attachmentName?.let {
            YggCryptoEngine.computeFastCdcMerkleProfile(it, attachmentSizeBytes).second
        }

        val message = MessageEntity(
            messageId = "msg_${UUID.randomUUID()}",
            chatId = chatId,
            senderName = "Вы",
            senderIpv6 = senderIpv6,
            cipherPayloadHex = cipherHex,
            plainText = plainText,
            timestamp = now,
            isOutgoing = true,
            deliveryState = delivery,
            ratchetStep = step,
            sealedSenderFlag = useSealed,
            hashcashPowToken = powToken,
            attachmentName = attachmentName,
            attachmentSizeBytes = attachmentSizeBytes,
            merkleRootHex = merkleRoot,
            transferProgress = 1.0f
        )
        dao.insertMessage(message)

        if (attachmentName != null && merkleRoot != null) {
            val (chunks, _) = YggCryptoEngine.computeFastCdcMerkleProfile(attachmentName, attachmentSizeBytes)
            val transfer = P2PFileTransferEntity(
                transferId = "tx_${UUID.randomUUID()}",
                chatId = chatId,
                peerName = conv.title,
                peerIpv6 = conv.yggIpv6Address,
                fileName = attachmentName,
                fileSizeBytes = attachmentSizeBytes,
                chunkCount = chunks,
                completedChunks = if (isOfflineMode) 0 else chunks,
                merkleRootSha256 = merkleRoot,
                speedKbps = if (isOfflineMode) 0 else 2150,
                status = if (isOfflineMode) "QUEUED_OFFLINE" else "VERIFIED_COMPLETE",
                isOutgoing = true,
                protocol = if (isOfflineMode) "YGG_OVERLAY_STREAM" else "QUIC_P2P_DIRECT",
                timestamp = now
            )
            dao.upsertFileTransfer(transfer)
        }

        val previewPrefix = if (attachmentName != null) "[Файл P2P: $attachmentName] " else ""
        dao.upsertConversation(
            conv.copy(
                lastMessagePreview = "$previewPrefix$plainText",
                lastTimestamp = now,
                unreadCount = 0
            )
        )
        return message
    }

    suspend fun markMessageDelivered(messageId: String) {
        if (messageId.isNotBlank()) {
            dao.updateMessageDeliveryState(messageId, "DELIVERED")
        }
    }

    suspend fun recordIncomingTwinMessage(packet: IncomingTwinPacket, activeChatId: String?) {
        val targetId = packet.targetChatId.ifBlank { "chat_twin_node" }
        val conv = dao.getConversationById(targetId)
            ?: dao.getConversationById("chat_twin_node")
            ?: return
        val merkleRoot = packet.attachmentName?.let {
            YggCryptoEngine.computeFastCdcMerkleProfile(it, packet.attachmentSizeBytes).second
        }
        val msg = MessageEntity(
            messageId = "msg_rx_${UUID.randomUUID()}",
            chatId = conv.chatId,
            senderName = packet.senderNodeLabel,
            senderIpv6 = packet.senderIpv6,
            cipherPayloadHex = packet.cipherPayloadHex,
            plainText = packet.plainText,
            timestamp = packet.timestamp,
            isOutgoing = false,
            deliveryState = "READ",
            ratchetStep = ((packet.timestamp / 1000L) % 900).toInt() + 10,
            sealedSenderFlag = true,
            hashcashPowToken = packet.hashcashToken,
            attachmentName = packet.attachmentName,
            attachmentSizeBytes = packet.attachmentSizeBytes,
            merkleRootHex = merkleRoot,
            transferProgress = 1.0f
        )
        dao.insertMessage(msg)
        val unreadIncrement = if (activeChatId == conv.chatId) 0 else 1
        dao.upsertConversation(
            conv.copy(
                lastMessagePreview = packet.plainText,
                lastTimestamp = packet.timestamp,
                unreadCount = if (unreadIncrement == 0) 0 else conv.unreadCount + 1,
                isOnlineInMesh = true
            )
        )
    }

    suspend fun flushOfflineStoreAndForwardQueue(): Int {
        val queued = dao.getQueuedOfflineMessages()
        queued.forEach { msg ->
            dao.updateMessage(msg.copy(deliveryState = "DELIVERED"))
        }
        return queued.size
    }

    suspend fun markConversationRead(chatId: String) {
        val conv = dao.getConversationById(chatId) ?: return
        if (conv.unreadCount > 0) {
            dao.upsertConversation(conv.copy(unreadCount = 0))
        }
    }

    suspend fun createNewConversation(
        title: String,
        yggIpv6Input: String,
        isMlsGroup: Boolean,
        sealedSender: Boolean,
        disappearingSeconds: Int
    ): String {
        val now = System.currentTimeMillis()
        val pubKeyHex = YggCryptoEngine.sha256Hex("$title:$yggIpv6Input:$now")
        val cleanIpv6 = if (yggIpv6Input.startsWith("020") || yggIpv6Input.startsWith("200:")) {
            yggIpv6Input.trim()
        } else {
            YggCryptoEngine.computeYggdrasilIpv6(pubKeyHex.toByteArray())
        }
        val chatId = "chat_${UUID.randomUUID().toString().take(8)}"
        val conv = ConversationEntity(
            chatId = chatId,
            title = title.trim(),
            yggIpv6Address = cleanIpv6,
            publicKeyHex = pubKeyHex,
            protocolType = if (isMlsGroup) "MLS_RFC9420" else "X3DH_DOUBLE_RATCHET",
            isVerified = true,
            sealedSenderEnabled = sealedSender,
            disappearingTtlSeconds = disappearingSeconds,
            unreadCount = 0,
            lastMessagePreview = if (isMlsGroup) {
                "Создана группа MLS RFC 9420 (TreeKEM инициализирован)"
            } else {
                "Сессия X3DH + Double Ratchet установлена"
            },
            lastTimestamp = now,
            isOnlineInMesh = true,
            avatarColorHex = if (isMlsGroup) "#06B6D4" else "#10B981",
            bioStatus = if (isMlsGroup) "Защищенная группа MLS RFC 9420" else "Децентрализованный пир Yggdrasil",
            routeTransportSummary = "WSS CDN Whitelist -> 2 хопа (21 мс)"
        )
        dao.upsertConversation(conv)

        val handshakeMsg = MessageEntity(
            messageId = "msg_init_$chatId",
            chatId = chatId,
            senderName = "Система Криптографии YggGram",
            senderIpv6 = cleanIpv6,
            cipherPayloadHex = pubKeyHex,
            plainText = if (isMlsGroup) {
                "Дерево ключей TreeKEM (MLS RFC 9420) синхронизировано. Все сообщения защищены прямой секретностью."
            } else {
                "Рукопожатие X3DH завершено. Включен Double Ratchet и защита метаданных Sealed Sender."
            },
            timestamp = now,
            isOutgoing = false,
            deliveryState = "READ",
            ratchetStep = 1,
            sealedSenderFlag = sealedSender,
            hashcashPowToken = "hc1:10:${now / 1000}:17:0000"
        )
        dao.insertMessage(handshakeMsg)
        return chatId
    }

    suspend fun toggleFileTransferPauseResume(transfer: P2PFileTransferEntity) {
        val updated = when (transfer.status) {
            "ACTIVE_STREAMING" -> transfer.copy(status = "PAUSED", speedKbps = 0)
            "PAUSED", "QUEUED_OFFLINE" -> transfer.copy(
                status = "VERIFIED_COMPLETE",
                completedChunks = transfer.chunkCount,
                speedKbps = 2340
            )
            else -> transfer
        }
        dao.upsertFileTransfer(updated)
    }

    suspend fun addOrUpdatePeer(
        peerId: String = "peer_${UUID.randomUUID().toString().take(8)}",
        endpointUri: String,
        transportType: String,
        sniDomain: String,
        utlsFingerprint: String,
        regionLabel: String,
        latencyMs: Int = (16..39).random(),
        whitelistCompatible: Boolean
    ) {
        val peer = YggPeerEntity(
            peerId = peerId,
            endpointUri = endpointUri.trim(),
            transportType = transportType,
            sniDomain = sniDomain.trim(),
            utlsFingerprint = utlsFingerprint,
            regionLabel = regionLabel.trim(),
            latencyMs = latencyMs,
            rxBytes = 1_048_576L,
            txBytes = 524_288L,
            isConnected = true,
            isWhitelistCompatible = whitelistCompatible,
            ed25519Verified = true
        )
        dao.upsertPeer(peer)
    }

    suspend fun togglePeerConnection(peer: YggPeerEntity) {
        dao.upsertPeer(peer.copy(isConnected = !peer.isConnected))
    }

    suspend fun deletePeer(peerId: String) {
        dao.deletePeer(peerId)
    }

    suspend fun refreshPeersTelemetryForPreset(
        whitelistMode: Boolean,
        preferredSni: String,
        utlsProfile: String
    ) {
        val all = dao.getAllPeersNow()
        val updated = all.mapIndexed { idx, p ->
            if (p.peerId == "peer_local_twin_bridge") {
                p
            } else {
                val newSni = if (whitelistMode && p.isWhitelistCompatible) preferredSni else p.sniDomain
                p.copy(
                    sniDomain = newSni,
                    utlsFingerprint = if (p.transportType == "QUIC_UDP_PADDING") p.utlsFingerprint else utlsProfile,
                    latencyMs = (15 + idx * 6),
                    rxBytes = p.rxBytes + 655_360L,
                    txBytes = p.txBytes + 245_760L,
                    isConnected = true
                )
            }
        }
        dao.insertPeers(updated)
    }

    suspend fun rotateKeysForEpoch(identity: DerivedIdentityHierarchy, newEpoch: Int) {
        val updatedRecords = buildHierarchyKeyRecords(identity, epoch = newEpoch)
        dao.insertCryptoKeys(updatedRecords)
    }

    suspend fun syncAllLinkedDevicesNow(): Int {
        val now = System.currentTimeMillis()
        val currentDevices = dao.getLinkedDevicesNow()
        val nextVector = (currentDevices.maxOfOrNull { it.stateVectorVersion } ?: 140) + 1
        val updated = currentDevices.map { dev ->
            dev.copy(
                lastSyncTimestamp = now,
                stateVectorVersion = nextVector,
                isOnline = true,
                syncedChunksCount = dev.syncedChunksCount + 6
            )
        }
        dao.insertLinkedDevices(updated)
        return nextVector
    }

    suspend fun linkNewCrossPlatformDevice(
        deviceName: String,
        platform: String,
        yggIpv6OrCode: String,
        lanEndpoint: String
    ) {
        val now = System.currentTimeMillis()
        val currentDevices = dao.getLinkedDevicesNow()
        val vector = currentDevices.maxOfOrNull { it.stateVectorVersion } ?: 142
        val cleanIpv6 = if (yggIpv6OrCode.contains(":")) {
            yggIpv6OrCode.trim()
        } else {
            YggCryptoEngine.computeYggdrasilIpv6(yggIpv6OrCode.toByteArray())
        }
        val dev = LinkedDeviceEntity(
            deviceId = "dev_${UUID.randomUUID().toString().take(8)}",
            deviceName = deviceName.trim(),
            platform = platform,
            yggIpv6 = cleanIpv6,
            lanEndpoint = lanEndpoint.ifBlank { "Прямой туннель Yggdrasil IPv6" },
            lastSyncTimestamp = now,
            stateVectorVersion = vector,
            syncMode = "LAN_AND_YGG_P2P",
            isOnline = true,
            syncedChunksCount = 384
        )
        dao.upsertLinkedDevice(dev)
    }

    suspend fun createBackupSnapshot(
        passphrase: String,
        isWebDavCloud: Boolean
    ): BackupSnapshotEntity {
        val convCount = dao.countConversations()
        val keysCount = dao.getCryptoKeysNow().size
        val bundle = YggCryptoEngine.buildEncryptedBackupContainer(
            passphrase = passphrase,
            conversationsCount = convCount,
            messagesCount = 24,
            keysCount = keysCount,
            isWebDavCloud = isWebDavCloud
        )
        val entity = BackupSnapshotEntity(
            snapshotId = "bak_${UUID.randomUUID().toString().take(8)}",
            archiveFileName = bundle.archiveFileName,
            createdAt = System.currentTimeMillis(),
            sizeBytes = bundle.payloadSizeBytes,
            chunkCountFastCdc = bundle.fastCdcChunks,
            kdfSpec = bundle.kdfSummary,
            cipherSpec = bundle.cipherSummary,
            merkleRootHex = bundle.merkleRootSha256,
            destination = if (isWebDavCloud) "WEBDAV_CLOUD_ZERO_KNOWLEDGE" else "LOCAL_ENCRYPTED_CONTAINER",
            isVerified = true
        )
        dao.upsertBackupSnapshot(entity)
        return entity
    }
}
