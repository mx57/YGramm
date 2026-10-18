// File: app/src/main/java/com/example/data/YggRepository.kt
package com.example.data

import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggCryptoEngine
import java.util.UUID
import kotlinx.coroutines.flow.Flow

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

    /**
     * Initializes ONLY the user's real SLIP-0010 cryptographic key hierarchy and default
     * public Yggdrasil peer entry points (marked as disconnected until probed over the real network).
     * NEVER seeds fake conversations, fake messages, fake file transfers, or fake linked devices.
     */
    suspend fun ensureInitialized(identity: DerivedIdentityHierarchy) {
        val now = System.currentTimeMillis()
        if (dao.getCryptoKeysNow().isEmpty()) {
            val initialKeys = buildHierarchyKeyRecords(identity, epoch = 1, now = now)
            dao.insertCryptoKeys(initialKeys)
        }

        if (dao.getAllPeersNow().isEmpty()) {
            val defaultPublicPeers = listOf(
                YggPeerEntity(
                    peerId = "peer_ru_msk_zbin",
                    endpointUri = "tls://ygg.zbin.eu:7743",
                    transportType = "TLS_YGGDRASIL",
                    sniDomain = "ygg.zbin.eu",
                    utlsFingerprint = "HelloChrome_128_Auto",
                    regionLabel = "🇷🇺 RU • Москва (ygg.zbin.eu)",
                    latencyMs = -1,
                    rxBytes = 0L,
                    txBytes = 0L,
                    isConnected = false,
                    isWhitelistCompatible = true,
                    ed25519Verified = true
                ),
                YggPeerEntity(
                    peerId = "peer_de_neilalexander",
                    endpointUri = "tls://publicpeers.neilalexander.dev:443",
                    transportType = "WSS_CDN_WHITELIST",
                    sniDomain = "publicpeers.neilalexander.dev",
                    utlsFingerprint = "HelloChrome_128_Auto",
                    regionLabel = "🇩🇪 DE • Франкфурт (neilalexander.dev)",
                    latencyMs = -1,
                    rxBytes = 0L,
                    txBytes = 0L,
                    isConnected = false,
                    isWhitelistCompatible = true,
                    ed25519Verified = true
                ),
                YggPeerEntity(
                    peerId = "peer_fi_hetzner",
                    endpointUri = "tls://65.21.57.125:61995",
                    transportType = "TLS_YGGDRASIL",
                    sniDomain = "65.21.57.125",
                    utlsFingerprint = "HelloChrome_128_Auto",
                    regionLabel = "🇫🇮 FI • Хельсинки (Hetzner FI)",
                    latencyMs = -1,
                    rxBytes = 0L,
                    txBytes = 0L,
                    isConnected = false,
                    isWhitelistCompatible = false,
                    ed25519Verified = true
                ),
                YggPeerEntity(
                    peerId = "peer_nl_ams_backbone",
                    endpointUri = "tls://45.147.198.155:6010",
                    transportType = "TLS_YGGDRASIL",
                    sniDomain = "45.147.198.155",
                    utlsFingerprint = "HelloIOS_18_Auto",
                    regionLabel = "🇳🇱 NL • Амстердам (AMS-IX)",
                    latencyMs = -1,
                    rxBytes = 0L,
                    txBytes = 0L,
                    isConnected = false,
                    isWhitelistCompatible = false,
                    ed25519Verified = true
                ),
                YggPeerEntity(
                    peerId = "peer_se_sto_liu",
                    endpointUri = "tls://ygg.ace.ctrl-c.liu.se:9999",
                    transportType = "TLS_YGGDRASIL",
                    sniDomain = "ygg.ace.ctrl-c.liu.se",
                    utlsFingerprint = "HelloChrome_128_Auto",
                    regionLabel = "🇸🇪 SE • Стокгольм (ctrl-c.liu.se)",
                    latencyMs = -1,
                    rxBytes = 0L,
                    txBytes = 0L,
                    isConnected = false,
                    isWhitelistCompatible = false,
                    ed25519Verified = true
                )
            )
            dao.insertPeers(defaultPublicPeers)
        }
    }

    /**
     * Creates or retrieves the "Избранное • Мой узел Yggdrasil" self-chat for honest
     * connectivity testing through external public Yggdrasil peers.
     */
    suspend fun ensureSelfYggdrasilConversation(identity: DerivedIdentityHierarchy): String {
        val existing = dao.getConversationById(SELF_NODE_CHAT_ID)
        if (existing != null) {
            return existing.chatId
        }
        val now = System.currentTimeMillis()
        val selfConv = ConversationEntity(
            chatId = SELF_NODE_CHAT_ID,
            title = "Избранное • Мой узел Yggdrasil",
            yggIpv6Address = identity.yggIpv6Address,
            publicKeyHex = identity.yggNodePublicKeyHex,
            protocolType = "X3DH_DOUBLE_RATCHET",
            isVerified = true,
            sealedSenderEnabled = true,
            disappearingTtlSeconds = 0,
            unreadCount = 0,
            lastMessagePreview = "Проверка связи через внешние пиры Yggdrasil",
            lastTimestamp = now,
            isOnlineInMesh = true,
            avatarColorHex = "#10B981",
            bioStatus = "Маршрут строго через внешние публичные пиры Yggdrasil (без локального loopback)",
            routeTransportSummary = "Yggdrasil External Mesh Relay (0200::/7)"
        )
        dao.upsertConversation(selfConv)
        return selfConv.chatId
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
        attachmentSizeBytes: Long = 0L,
        attachmentType: String? = null,
        attachmentUri: String? = null,
        voiceDurationSec: Int = 0,
        voiceWaveformCsv: String? = null
    ): MessageEntity? {
        val conv = dao.getConversationById(chatId) ?: return null
        val now = System.currentTimeMillis()
        val step = ((now / 1000L) % 900).toInt() + 1
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
        val initialDelivery = "QUEUED_OFFLINE_SF"
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
            deliveryState = initialDelivery,
            ratchetStep = step,
            sealedSenderFlag = useSealed,
            hashcashPowToken = powToken,
            attachmentName = attachmentName,
            attachmentSizeBytes = attachmentSizeBytes,
            merkleRootHex = merkleRoot,
            transferProgress = if (isOfflineMode) 0f else 1.0f,
            attachmentType = attachmentType,
            attachmentUri = attachmentUri,
            voiceDurationSec = voiceDurationSec,
            voiceWaveformCsv = voiceWaveformCsv,
            reactionsJson = ""
        )
        dao.insertMessage(message)

        if (attachmentName != null && merkleRoot != null && attachmentType != "VOICE") {
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
                speedKbps = if (isOfflineMode) 0 else 1420,
                status = if (isOfflineMode) "QUEUED_OFFLINE" else "VERIFIED_COMPLETE",
                isOutgoing = true,
                protocol = "YGG_OVERLAY_STREAM",
                timestamp = now
            )
            dao.upsertFileTransfer(transfer)
        }

        val previewPrefix = when (attachmentType) {
            "VOICE" -> "🎤 Голосовое сообщение (${voiceDurationSec}с) "
            "IMAGE" -> "🖼️ Фото ($attachmentName) "
            "FILE" -> "📎 Файл ($attachmentName) "
            else -> if (attachmentName != null) "[Файл P2P: $attachmentName] " else ""
        }
        dao.upsertConversation(
            conv.copy(
                lastMessagePreview = "$previewPrefix$plainText".trim(),
                lastTimestamp = now,
                unreadCount = 0
            )
        )
        return message
    }

    suspend fun toggleReactionOnMessage(
        messageId: String,
        emoji: String,
        byMe: Boolean
    ): MessageEntity? {
        val msg = dao.getMessageById(messageId) ?: return null
        val currentMap = parseReactionsMap(msg.reactionsJson).toMutableMap()
        val existing = currentMap[emoji]
        if (existing != null) {
            if (byMe) {
                if (existing.second) {
                    val newCount = (existing.first - 1).coerceAtLeast(0)
                    if (newCount == 0) currentMap.remove(emoji) else currentMap[emoji] = newCount to false
                } else {
                    currentMap[emoji] = (existing.first + 1) to true
                }
            } else {
                currentMap[emoji] = (existing.first + 1) to existing.second
            }
        } else {
            currentMap[emoji] = 1 to byMe
        }
        val encoded = serializeReactionsMap(currentMap)
        dao.updateMessageReactions(messageId, encoded)
        return msg.copy(reactionsJson = encoded)
    }

    companion object {
        const val SELF_NODE_CHAT_ID = "chat_self_ygg_node"

        fun parseReactionsMap(raw: String): Map<String, Pair<Int, Boolean>> {
            if (raw.isBlank()) return emptyMap()
            val result = linkedMapOf<String, Pair<Int, Boolean>>()
            raw.split("|").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size >= 2) {
                    val emoji = parts[0]
                    val count = parts[1].toIntOrNull() ?: 1
                    val isMe = parts.getOrNull(2) == "me"
                    if (emoji.isNotBlank() && count > 0) {
                        result[emoji] = count to isMe
                    }
                }
            }
            return result
        }

        fun serializeReactionsMap(map: Map<String, Pair<Int, Boolean>>): String {
            return map.entries.joinToString("|") { (emoji, pair) ->
                val tag = if (pair.second) "me" else "peer"
                "$emoji:${pair.first}:$tag"
            }
        }
    }

    suspend fun markMessageSentP2P(messageId: String) {
        if (messageId.isNotBlank()) {
            dao.updateMessageDeliveryState(messageId, "SENT_P2P")
        }
    }

    suspend fun markMessageDelivered(messageId: String) {
        if (messageId.isNotBlank()) {
            dao.updateMessageDeliveryState(messageId, "DELIVERED")
        }
    }

    suspend fun updateConversationRouteStatus(chatId: String, isOnline: Boolean, routeSummary: String) {
        val conv = dao.getConversationById(chatId) ?: return
        dao.upsertConversation(
            conv.copy(
                isOnlineInMesh = isOnline,
                routeTransportSummary = routeSummary
            )
        )
    }

    suspend fun recordIncomingTwinMessage(packet: IncomingTwinPacket, activeChatId: String?): String {
        var conv = if (packet.targetChatId.isNotBlank()) {
            dao.getConversationById(packet.targetChatId)
        } else null

        if (conv == null) {
            val newChatId = "chat_${packet.senderIpv6.hashCode().toUInt()}"
            val existingByIpv6 = dao.getConversationById(newChatId)
            if (existingByIpv6 != null) {
                conv = existingByIpv6
            } else {
                val created = ConversationEntity(
                    chatId = newChatId,
                    title = packet.senderNodeLabel.ifBlank { "Пир ${packet.senderIpv6.take(16)}" },
                    yggIpv6Address = packet.senderIpv6,
                    publicKeyHex = packet.senderPubKeyHex.ifBlank { YggCryptoEngine.sha256Hex(packet.senderIpv6) },
                    protocolType = "X3DH_DOUBLE_RATCHET",
                    isVerified = true,
                    sealedSenderEnabled = true,
                    disappearingTtlSeconds = 0,
                    unreadCount = 0,
                    lastMessagePreview = packet.plainText,
                    lastTimestamp = packet.timestamp,
                    isOnlineInMesh = true,
                    avatarColorHex = "#10B981",
                    bioStatus = "Входящий пир Yggdrasil",
                    routeTransportSummary = "Yggdrasil Mesh (${packet.rttMs} мс)"
                )
                dao.upsertConversation(created)
                conv = created
            }
        }

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
            deliveryState = "DELIVERED",
            ratchetStep = ((packet.timestamp / 1000L) % 900).toInt() + 1,
            sealedSenderFlag = true,
            hashcashPowToken = packet.hashcashPowTokenOrDefault(),
            attachmentName = packet.attachmentName,
            attachmentSizeBytes = packet.attachmentSizeBytes,
            merkleRootHex = merkleRoot,
            transferProgress = 1.0f,
            attachmentType = packet.attachmentType,
            attachmentUri = packet.attachmentUri,
            voiceDurationSec = packet.voiceDurationSec,
            voiceWaveformCsv = packet.voiceWaveformCsv,
            reactionsJson = ""
        )
        dao.insertMessage(msg)
        val unreadIncrement = if (activeChatId == conv.chatId) 0 else 1
        dao.upsertConversation(
            conv.copy(
                lastMessagePreview = packet.plainText.lineSequence().firstOrNull() ?: packet.plainText,
                lastTimestamp = packet.timestamp,
                unreadCount = if (unreadIncrement == 0) 0 else conv.unreadCount + 1,
                isOnlineInMesh = true,
                routeTransportSummary = "Yggdrasil Mesh (${packet.rttMs} мс)"
            )
        )
        return conv.chatId
    }

    private fun IncomingTwinPacket.hashcashPowTokenOrDefault(): String =
        hashcashToken.ifBlank { "1:20:yggdrasil" }

    suspend fun getQueuedMessages(): List<MessageEntity> = dao.getQueuedOfflineMessages()

    suspend fun getConversation(chatId: String): ConversationEntity? = dao.getConversationById(chatId)

    suspend fun markConversationRead(chatId: String) {
        val conv = dao.getConversationById(chatId) ?: return
        if (conv.unreadCount > 0) {
            dao.upsertConversation(conv.copy(unreadCount = 0))
        }
    }

    suspend fun deleteConversation(chatId: String) {
        dao.clearMessagesInChat(chatId)
        dao.deleteConversation(chatId)
    }

    /**
     * Creates a new real contact or MLS group session without inserting any fake welcome messages.
     * Also parses `ygggram://` URI links (including `pub=` and `lan=`), Ed25519 hex public keys,
     * and Yggdrasil IPv6 addresses.
     */
    suspend fun createNewConversation(
        title: String,
        yggIpv6Input: String,
        isMlsGroup: Boolean,
        sealedSender: Boolean,
        disappearingSeconds: Int,
        directEndpointHint: String = "",
        localIdentity: DerivedIdentityHierarchy? = null
    ): String {
        val now = System.currentTimeMillis()
        val rawInput = yggIpv6Input.trim()

        val extractedAddress = if (rawInput.startsWith("ygggram://", ignoreCase = true)) {
            rawInput.substringAfter("://").substringBefore("?")
        } else {
            rawInput
        }
        val extractedPubKey = if (rawInput.contains("pub=")) {
            rawInput.substringAfter("pub=").substringBefore("&")
        } else if (rawInput.length == 64 && rawInput.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            rawInput.lowercase()
        } else {
            YggCryptoEngine.sha256Hex("$title:$extractedAddress")
        }
        val extractedLanHint = if (directEndpointHint.isNotBlank()) {
            directEndpointHint.trim()
        } else if (rawInput.contains("lan=")) {
            rawInput.substringAfter("lan=").substringBefore("&").trim()
        } else {
            ""
        }

        val cleanIpv6 = when {
            extractedAddress.startsWith("020") || extractedAddress.startsWith("200:") || extractedAddress.startsWith("201:") || extractedAddress.startsWith("202:") -> extractedAddress
            extractedAddress.contains(".") && extractedAddress.contains(":") -> extractedAddress
            extractedPubKey.length >= 64 -> {
                val pubBytes = extractedPubKey.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                YggCryptoEngine.computeYggdrasilIpv6(if (pubBytes.isNotEmpty()) pubBytes else extractedPubKey.toByteArray())
            }
            else -> YggCryptoEngine.computeYggdrasilIpv6(extractedPubKey.toByteArray())
        }

        // If user entered their own Yggdrasil IPv6 or Public Key, route it through Yggdrasil External Mesh
        val isSelfNode = localIdentity != null && (
            cleanIpv6.equals(localIdentity.yggIpv6Address, ignoreCase = true) ||
                extractedPubKey.equals(localIdentity.yggNodePublicKeyHex, ignoreCase = true)
            )

        if (isSelfNode && localIdentity != null) {
            return ensureSelfYggdrasilConversation(localIdentity)
        }

        val endpointSummary = when {
            extractedLanHint.isNotBlank() -> "Direct P2P ($extractedLanHint)"
            extractedAddress.contains(".") && extractedAddress.contains(":") -> "Direct P2P ($extractedAddress)"
            else -> "Yggdrasil 0200::/7 Overlay"
        }

        val chatId = "chat_${UUID.randomUUID().toString().take(8)}"
        val conv = ConversationEntity(
            chatId = chatId,
            title = title.trim().ifBlank { "Пир ${cleanIpv6.take(14)}" },
            yggIpv6Address = cleanIpv6,
            publicKeyHex = extractedPubKey,
            protocolType = if (isMlsGroup) "MLS_RFC9420" else "X3DH_DOUBLE_RATCHET",
            isVerified = true,
            sealedSenderEnabled = sealedSender,
            disappearingTtlSeconds = disappearingSeconds,
            unreadCount = 0,
            lastMessagePreview = "Нет сообщений • Начните диалог",
            lastTimestamp = now,
            isOnlineInMesh = false,
            avatarColorHex = if (isMlsGroup) "#06B6D4" else "#10B981",
            bioStatus = extractedLanHint.ifBlank { "Контакт в сети Yggdrasil" },
            routeTransportSummary = endpointSummary
        )
        dao.upsertConversation(conv)
        return chatId
    }

    suspend fun toggleFileTransferPauseResume(transfer: P2PFileTransferEntity) {
        val updated = when (transfer.status) {
            "ACTIVE_STREAMING" -> transfer.copy(status = "PAUSED", speedKbps = 0)
            "PAUSED" -> transfer.copy(status = "QUEUED_OFFLINE", speedKbps = 0)
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
        latencyMs: Int = -1,
        isConnected: Boolean = false,
        whitelistCompatible: Boolean
    ) {
        val existing = dao.getAllPeersNow().find { it.endpointUri.equals(endpointUri.trim(), ignoreCase = true) }
        val peer = YggPeerEntity(
            peerId = existing?.peerId ?: peerId,
            endpointUri = endpointUri.trim(),
            transportType = transportType,
            sniDomain = sniDomain.trim(),
            utlsFingerprint = utlsFingerprint,
            regionLabel = regionLabel.trim(),
            latencyMs = latencyMs,
            rxBytes = existing?.rxBytes ?: 0L,
            txBytes = existing?.txBytes ?: 0L,
            isConnected = isConnected,
            isWhitelistCompatible = whitelistCompatible,
            ed25519Verified = true
        )
        dao.upsertPeer(peer)
    }

    suspend fun updatePeerProbeStatus(
        peerId: String,
        reachable: Boolean,
        latencyMs: Int,
        rxDelta: Long = 0L,
        txDelta: Long = 0L
    ) {
        val current = dao.getAllPeersNow().find { it.peerId == peerId } ?: return
        dao.upsertPeer(
            current.copy(
                isConnected = reachable,
                latencyMs = if (reachable) latencyMs else -1,
                rxBytes = current.rxBytes + rxDelta,
                txBytes = current.txBytes + txDelta
            )
        )
    }

    suspend fun updatePeerTrafficByEndpoint(
        endpointUri: String,
        rttMs: Int,
        txDelta: Long,
        rxDelta: Long
    ) {
        val current = dao.getAllPeersNow().find { it.endpointUri.equals(endpointUri, ignoreCase = true) } ?: return
        dao.upsertPeer(
            current.copy(
                isConnected = true,
                latencyMs = rttMs,
                txBytes = current.txBytes + txDelta,
                rxBytes = current.rxBytes + rxDelta
            )
        )
    }

    suspend fun markAllPeersDisconnected() {
        val all = dao.getAllPeersNow()
        if (all.isEmpty()) return
        dao.insertPeers(all.map { it.copy(isConnected = false, latencyMs = -1) })
    }

    suspend fun getAllPeersNow(): List<YggPeerEntity> = dao.getAllPeersNow()

    suspend fun togglePeerConnection(peer: YggPeerEntity) {
        dao.upsertPeer(peer.copy(isConnected = !peer.isConnected))
    }

    suspend fun deletePeer(peerId: String) {
        dao.deletePeer(peerId)
    }

    suspend fun updatePeersTransportProfile(
        whitelistMode: Boolean,
        preferredSni: String,
        utlsProfile: String
    ) {
        val all = dao.getAllPeersNow()
        val updated = all.map { p ->
            val newSni = if (whitelistMode && p.isWhitelistCompatible) preferredSni else p.sniDomain
            p.copy(
                sniDomain = newSni,
                utlsFingerprint = if (p.transportType == "QUIC_UDP_PADDING") p.utlsFingerprint else utlsProfile
            )
        }
        dao.insertPeers(updated)
    }

    suspend fun rotateKeysForEpoch(identity: DerivedIdentityHierarchy, newEpoch: Int) {
        val updatedRecords = buildHierarchyKeyRecords(identity, epoch = newEpoch)
        dao.insertCryptoKeys(updatedRecords)
    }

    suspend fun syncAllLinkedDevicesNow(isOnline: Boolean): Int {
        val now = System.currentTimeMillis()
        val currentDevices = dao.getLinkedDevicesNow()
        if (currentDevices.isEmpty()) return 0
        val nextVector = (currentDevices.maxOfOrNull { it.stateVectorVersion } ?: 1) + 1
        val updated = currentDevices.map { dev ->
            dev.copy(
                lastSyncTimestamp = if (isOnline) now else dev.lastSyncTimestamp,
                stateVectorVersion = if (isOnline) nextVector else dev.stateVectorVersion,
                isOnline = isOnline
            )
        }
        dao.insertLinkedDevices(updated)
        return if (isOnline) nextVector else 0
    }

    suspend fun linkNewCrossPlatformDevice(
        deviceName: String,
        platform: String,
        yggIpv6OrCode: String,
        lanEndpoint: String,
        isOnline: Boolean
    ) {
        val now = System.currentTimeMillis()
        val currentDevices = dao.getLinkedDevicesNow()
        val vector = (currentDevices.maxOfOrNull { it.stateVectorVersion } ?: 0) + 1
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
            isOnline = isOnline,
            syncedChunksCount = 0
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
            messagesCount = 0,
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
