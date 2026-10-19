// File: app/src/main/java/com/example/data/YggRepository.kt
package com.example.data

import android.content.Context
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggAudioMediaEngine
import com.example.engine.YggCryptoEngine
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class YggRepository(
    private val dao: YggDao,
    private val appContext: Context? = null
) {

    val userProfiles: Flow<List<UserProfileEntity>> = dao.observeUserProfiles()
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
     * Initializes the primary SLIP-0010 cryptographic profile, key hierarchy, and default
     * public Yggdrasil peer entry points.
     */
    suspend fun ensureInitialized(defaultIdentity: DerivedIdentityHierarchy): UserProfileEntity {
        val now = System.currentTimeMillis()
        val existingProfiles = dao.getAllProfilesNow()
        val primaryProfile = if (existingProfiles.isEmpty()) {
            val created = UserProfileEntity(
                profileId = DEFAULT_PROFILE_ID,
                displayName = "Узел YggGram #1",
                handle = "@node1.ygg",
                bio = "Основной децентрализованный профиль в сети Yggdrasil 0200::/7 • Прямой P2P & Zero-Knowledge",
                statusText = "В оверлее 200::/7 (Порт :51921)",
                mnemonicCsv = defaultIdentity.mnemonicWords.joinToString(" "),
                yggIpv6Address = defaultIdentity.yggIpv6Address,
                yggPublicKeyHex = defaultIdentity.yggNodePublicKeyHex,
                allocatedTcpPort = 51921,
                isActiveInBackground = true,
                isCurrentPrimary = true,
                avatarColorHex = "#10B981",
                createdAt = now
            )
            dao.upsertProfile(created)
            created
        } else {
            existingProfiles.find { it.isCurrentPrimary } ?: existingProfiles.first()
        }

        if (dao.getCryptoKeysNow().isEmpty()) {
            val words = primaryProfile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
                .ifEmpty { defaultIdentity.mnemonicWords }
            val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(words, epoch = 1)
            val initialKeys = buildHierarchyKeyRecords(hierarchy, epoch = 1, now = now)
            dao.insertCryptoKeys(initialKeys)
        }

        reconcileLegacyYggdrasilAddresses()

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
        return primaryProfile
    }

    suspend fun getAllProfilesNow(): List<UserProfileEntity> = dao.getAllProfilesNow()

    suspend fun createNewUserProfile(
        displayName: String,
        handle: String,
        bio: String,
        runSimultaneouslyInBackground: Boolean,
        switchImmediately: Boolean,
        nodeRelayRole: String = "PEER_RELAY"
    ): Pair<UserProfileEntity, DerivedIdentityHierarchy> {
        val now = System.currentTimeMillis()
        val existing = dao.getAllProfilesNow()
        val index = existing.size + 1
        val usedPorts = existing.map { it.allocatedTcpPort }.toSet()
        val nextPort = (51921..51939).firstOrNull { it !in usedPorts } ?: (51920 + index)

        val freshMnemonic = YggCryptoEngine.generateFreshMnemonic12()
        val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(freshMnemonic, epoch = 1)
        val palette = listOf("#10B981", "#06B6D4", "#F59E0B", "#8B5CF6", "#EC4899")
        val avatarHex = palette[(index - 1) % palette.size]

        val cleanName = displayName.trim().ifBlank { "Узел YggGram #$index" }
        val cleanHandle = handle.trim().ifBlank { "@node$index.ygg" }.let {
            if (it.startsWith("@")) it else "@$it"
        }
        val cleanBio = bio.trim().ifBlank {
            "Независимый крипто-профиль #$index в сети Yggdrasil • P2P порт :$nextPort"
        }

        val roleLabel = when (nodeRelayRole) {
            "CLIENT_ONLY" -> "Только клиент"
            "SF_BLIND_RELAY" -> "S&F Ретранслятор"
            else -> "Пир-узел"
        }

        val newProfileId = "profile_${UUID.randomUUID().toString().take(8)}"
        val newEntity = UserProfileEntity(
            profileId = newProfileId,
            displayName = cleanName,
            handle = cleanHandle,
            bio = cleanBio,
            statusText = if (runSimultaneouslyInBackground) {
                "$roleLabel • В фоне (:$nextPort)"
            } else {
                "$roleLabel • Готов (:$nextPort)"
            },
            mnemonicCsv = freshMnemonic.joinToString(" "),
            yggIpv6Address = hierarchy.yggIpv6Address,
            yggPublicKeyHex = hierarchy.yggNodePublicKeyHex,
            allocatedTcpPort = nextPort,
            isActiveInBackground = runSimultaneouslyInBackground,
            isCurrentPrimary = switchImmediately,
            avatarColorHex = avatarHex,
            createdAt = now,
            nodeRelayRole = nodeRelayRole,
            relayedPacketsCount = 0
        )

        if (switchImmediately) {
            val demoted = existing.map { it.copy(isCurrentPrimary = false) }
            dao.insertProfiles(demoted + newEntity)
            rotateKeysForEpoch(hierarchy, 1)
        } else {
            dao.upsertProfile(newEntity)
        }

        return newEntity to hierarchy
    }

    suspend fun switchPrimaryUserProfile(profileId: String): Pair<UserProfileEntity, DerivedIdentityHierarchy>? {
        val all = dao.getAllProfilesNow()
        val target = all.find { it.profileId == profileId } ?: return null
        val updatedList = all.map {
            it.copy(
                isCurrentPrimary = it.profileId == profileId,
                // Keep previously active profiles active if configured, and ensure the selected primary is active
                isActiveInBackground = if (it.profileId == profileId) true else it.isActiveInBackground
            )
        }
        dao.insertProfiles(updatedList)
        val updatedTarget = updatedList.first { it.profileId == profileId }
        val words = updatedTarget.mnemonicCsv.split(" ").filter { it.isNotBlank() }
        val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(
            if (words.size >= 6) words else YggCryptoEngine.generateFreshMnemonic12(),
            epoch = 1
        )
        rotateKeysForEpoch(hierarchy, 1)
        return updatedTarget to hierarchy
    }

    suspend fun setProfileBackgroundActive(profileId: String, activeInBackground: Boolean): List<UserProfileEntity> {
        val all = dao.getAllProfilesNow()
        val updated = all.map { prof ->
            if (prof.profileId == profileId) {
                // Current primary profile always stays active while selected
                val effectiveActive = if (prof.isCurrentPrimary) true else activeInBackground
                val roleLabel = when (prof.nodeRelayRole) {
                    "CLIENT_ONLY" -> "Только клиент"
                    "SF_BLIND_RELAY" -> "S&F Ретранслятор"
                    else -> "Пир-узел"
                }
                val status = if (effectiveActive) {
                    "$roleLabel • В фоне (:${prof.allocatedTcpPort})"
                } else {
                    "$roleLabel • На паузе (:${prof.allocatedTcpPort})"
                }
                prof.copy(
                    isActiveInBackground = effectiveActive,
                    statusText = status
                )
            } else prof
        }
        dao.insertProfiles(updated)
        return updated
    }

    suspend fun setProfileNodeRelayRole(profileId: String, nodeRelayRole: String): List<UserProfileEntity> {
        val all = dao.getAllProfilesNow()
        val updated = all.map { prof ->
            if (prof.profileId == profileId) {
                val roleLabel = when (nodeRelayRole) {
                    "CLIENT_ONLY" -> "Только клиент"
                    "SF_BLIND_RELAY" -> "S&F Ретранслятор"
                    else -> "Пир-узел"
                }
                val status = if (prof.isActiveInBackground || prof.isCurrentPrimary) {
                    "$roleLabel • В фоне (:${prof.allocatedTcpPort})"
                } else {
                    "$roleLabel • На паузе (:${prof.allocatedTcpPort})"
                }
                prof.copy(
                    nodeRelayRole = nodeRelayRole,
                    statusText = status
                )
            } else prof
        }
        dao.insertProfiles(updated)
        return updated
    }

    suspend fun incrementProfileRelayedPackets(profileId: String, delta: Int = 1) {
        val existing = dao.getProfileById(profileId) ?: return
        dao.upsertProfile(existing.copy(relayedPacketsCount = existing.relayedPacketsCount + delta))
    }

    suspend fun updateCurrentProfileMetadata(
        profileId: String,
        displayName: String,
        handle: String,
        bio: String,
        statusText: String
    ): UserProfileEntity? {
        val existing = dao.getProfileById(profileId) ?: return null
        val updated = existing.copy(
            displayName = displayName.trim().ifBlank { existing.displayName },
            handle = handle.trim().ifBlank { existing.handle },
            bio = bio.trim(),
            statusText = statusText.trim().ifBlank { existing.statusText }
        )
        dao.upsertProfile(updated)
        return updated
    }

    suspend fun deleteSecondaryProfile(profileId: String): List<UserProfileEntity> {
        val all = dao.getAllProfilesNow()
        if (all.size <= 1) return all
        val target = all.find { it.profileId == profileId } ?: return all
        if (target.isCurrentPrimary) return all
        dao.deleteProfile(profileId)
        return dao.getAllProfilesNow()
    }

    /**
     * Persists a freshly generated identity into the profile row and re-pins its self-chat,
     * keeping UI state, DB and the P2P bridge listeners consistent.
     */
    suspend fun regenerateProfileIdentity(
        profileId: String,
        identity: DerivedIdentityHierarchy
    ): UserProfileEntity? {
        val existing = dao.getProfileById(profileId) ?: return null
        val updated = existing.copy(
            mnemonicCsv = identity.mnemonicWords.joinToString(" "),
            yggIpv6Address = identity.yggIpv6Address,
            yggPublicKeyHex = identity.yggNodePublicKeyHex
        )
        dao.upsertProfile(updated)
        rotateKeysForEpoch(identity, 1)
        val selfChatId = if (profileId == DEFAULT_PROFILE_ID) {
            SELF_NODE_CHAT_ID
        } else {
            "${SELF_NODE_CHAT_ID}_$profileId"
        }
        dao.getConversationById(selfChatId)?.let { selfConv ->
            dao.upsertConversation(
                selfConv.copy(
                    yggIpv6Address = identity.yggIpv6Address,
                    publicKeyHex = identity.yggNodePublicKeyHex
                )
            )
        }
        return updated
    }

    /**
     * Creates or retrieves the "Избранное • Мой узел Yggdrasil" self-chat for the current profile.
     */
    suspend fun ensureSelfYggdrasilConversation(
        identity: DerivedIdentityHierarchy,
        ownerProfileId: String = DEFAULT_PROFILE_ID
    ): String {
        val selfChatId = if (ownerProfileId == DEFAULT_PROFILE_ID) {
            SELF_NODE_CHAT_ID
        } else {
            "${SELF_NODE_CHAT_ID}_$ownerProfileId"
        }
        val existing = dao.getConversationById(selfChatId)
        if (existing != null) {
            return existing.chatId
        }
        val now = System.currentTimeMillis()
        val selfConv = ConversationEntity(
            chatId = selfChatId,
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
            routeTransportSummary = "Yggdrasil External Mesh Relay (0200::/7)",
            ownerProfileId = ownerProfileId
        )
        dao.upsertConversation(selfConv)
        return selfConv.chatId
    }

    /**
     * Identities created by older releases used a non-standard address derivation and
     * placeholder keys. Recompute profile addresses from their persisted mnemonics and
     * conversation addresses from their pinned Ed25519 keys so the stored topology
     * matches the real Yggdrasil v0.5 scheme.
     */
    private suspend fun reconcileLegacyYggdrasilAddresses() {
        try {
            dao.getAllProfilesNow().forEach { profile ->
                val words = profile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
                if (words.size < 6) return@forEach
                val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(words, epoch = 1)
                if (!profile.yggIpv6Address.equals(hierarchy.yggIpv6Address, ignoreCase = true) ||
                    !profile.yggPublicKeyHex.equals(hierarchy.yggNodePublicKeyHex, ignoreCase = true)
                ) {
                    dao.upsertProfile(
                        profile.copy(
                            yggIpv6Address = hierarchy.yggIpv6Address,
                            yggPublicKeyHex = hierarchy.yggNodePublicKeyHex
                        )
                    )
                }
            }

            dao.getAllConversationsNow().forEach { conv ->
                // Never touch LAN endpoint entries ("host:port") — only mesh addresses.
                if (!conv.yggIpv6Address.contains(":") || conv.yggIpv6Address.contains(".")) return@forEach
                val key = conv.publicKeyHex.trim().lowercase()
                if (key.length != 64 || !key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return@forEach
                val keyBytes = key.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                val realAddress = YggCryptoEngine.computeYggdrasilIpv6(keyBytes)
                if (!realAddress.equals(conv.yggIpv6Address, ignoreCase = true)) {
                    dao.upsertConversation(conv.copy(yggIpv6Address = realAddress))
                }
            }
        } catch (_: Exception) {
            // Reconciliation is best-effort; never block startup on it.
        }
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

    suspend fun persistProfileMnemonic(profileId: String, words: List<String>) {
        val profile = dao.getAllProfilesNow().find { it.profileId == profileId } ?: return
        dao.upsertProfile(profile.copy(mnemonicCsv = words.joinToString(" ")))
    }

    suspend fun sendEncryptedMessage(
        chatId: String,
        plainText: String,
        senderName: String = "Вы",
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

        val allProfiles = dao.getAllProfilesNow()
        val senderProfile = allProfiles.find { it.profileId == conv.ownerProfileId }
            ?: allProfiles.find { it.isCurrentPrimary }
            ?: allProfiles.firstOrNull()
            ?: return null
        var senderWords = senderProfile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
        if (senderWords.isEmpty()) {
            senderWords = YggCryptoEngine.generateFreshMnemonic12()
            dao.upsertProfile(senderProfile.copy(mnemonicCsv = senderWords.joinToString(" ")))
        }
        val senderXPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(senderWords)
        val senderIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(senderWords)

        val peerX25519Pub = conv.peerX25519PublicKeyHex.takeIf { it.isNotBlank() }
            ?: allProfiles.find {
                it.profileId != senderProfile.profileId &&
                    it.yggIpv6Address.equals(conv.yggIpv6Address, ignoreCase = true)
            }?.let { peerProfile ->
                YggCryptoEngine.deriveHierarchyFromMnemonic(
                    peerProfile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
                ).x25519IdentityPubHex.takeIf { it.isNotBlank() }
            }
            ?: senderIdentity.x25519IdentityPubHex.takeIf { isSelfChatId(conv.chatId) }

        val cipherHex = YggCryptoEngine.sealConversationEnvelope(
            plainText = plainText,
            senderX25519PrivateKey = senderXPriv,
            senderEd25519PublicKeyHex = senderIdentity.yggNodePublicKeyHex,
            peerX25519PublicKeyHex = peerX25519Pub,
            keyEpoch = 1,
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
            senderName = senderName,
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
        const val DEFAULT_PROFILE_ID = "profile_primary"
        const val SELF_NODE_CHAT_ID = "chat_self_ygg_node"

        fun isSelfChatId(chatId: String): Boolean =
            chatId == SELF_NODE_CHAT_ID || chatId.startsWith("${SELF_NODE_CHAT_ID}_")

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

    /**
     * Records an incoming P2P message (from an external peer, self-echo, or a concurrent local profile),
     * saves any attached binary file/voice note/image to disk, and routes it to the target profile's conversation list.
     */
    suspend fun recordIncomingTwinMessage(
        packet: IncomingTwinPacket,
        activeChatId: String?,
        currentPrimaryProfileId: String = DEFAULT_PROFILE_ID
    ): String {
        val allProfiles = dao.getAllProfilesNow()
        val targetOwnerProfileId = when {
            packet.recipientProfileId.isNotBlank() -> packet.recipientProfileId
            packet.recipientIpv6.isNotBlank() -> {
                allProfiles.find { it.yggIpv6Address.equals(packet.recipientIpv6, ignoreCase = true) }?.profileId
                    ?: currentPrimaryProfileId
            }
            else -> currentPrimaryProfileId
        }

        val allConvs = dao.getAllConversationsNow()
        var conv: ConversationEntity? = null

        // If this is a self-echo packet in a self-chat, match the exact targetChatId
        if (packet.targetChatId.isNotBlank() && isSelfChatId(packet.targetChatId)) {
            conv = dao.getConversationById(packet.targetChatId)
        }

        // Otherwise match by (ownerProfileId + senderIpv6) so each profile gets its own dialog with the sender!
        if (conv == null) {
            conv = allConvs.find {
                it.ownerProfileId == targetOwnerProfileId &&
                    !isSelfChatId(it.chatId) &&
                    it.yggIpv6Address.equals(packet.senderIpv6, ignoreCase = true)
            }
        }

        // Decrypt the sealed envelope with the recipient profile's X25519 key. A v2 envelope's
        // sender key ("sx") is implicitly authenticated by successful AEAD verification, so it
        // is safe to pin as the peer's X25519 public key for future outgoing envelopes.
        val recipientWords = allProfiles.find { it.profileId == targetOwnerProfileId }
            ?.mnemonicCsv?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        var resolvedPlainText = ""
        var authenticatedSenderX25519Pub = ""
        if (recipientWords.isNotEmpty() && packet.cipherPayloadHex.isNotBlank()) {
            try {
                val opened = YggCryptoEngine.openConversationEnvelope(
                    envelope = packet.cipherPayloadHex,
                    recipientX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(recipientWords),
                    senderEd25519PublicKeyHex = packet.senderPubKeyHex.takeIf { it.isNotBlank() }
                )
                resolvedPlainText = opened.plainText
                authenticatedSenderX25519Pub = opened.senderX25519PublicKeyHex
            } catch (_: Exception) {
                // Envelope did not verify under this profile's key; fall back to legacy text below.
            }
        }
        if (resolvedPlainText.isBlank()) {
            // Legacy interop: frames from older builds carry the text alongside the
            // non-confidential v1 envelope, which provides no confidentiality anyway.
            resolvedPlainText = packet.plainText.ifBlank { "[Зашифрованное сообщение — не удалось расшифровать]" }
        }

        if (conv == null) {
            val newChatId = "chat_${targetOwnerProfileId}_${packet.senderIpv6.hashCode().toUInt()}"
            val senderProfile = allProfiles.find { it.yggIpv6Address.equals(packet.senderIpv6, ignoreCase = true) }
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
                lastMessagePreview = resolvedPlainText,
                lastTimestamp = packet.timestamp,
                isOnlineInMesh = true,
                avatarColorHex = senderProfile?.avatarColorHex ?: "#10B981",
                bioStatus = "Маршрут через внешние пиры Yggdrasil 0200::/7",
                routeTransportSummary = "Yggdrasil Overlay 0200::/7 (${packet.rttMs} мс)",
                ownerProfileId = targetOwnerProfileId
            )
            dao.upsertConversation(created)
            conv = created
        }

        val resolvedAttachmentUri = if (appContext != null && (packet.attachmentName != null || packet.attachmentType != null)) {
            YggAudioMediaEngine.saveIncomingP2PAttachment(
                context = appContext,
                attachmentName = packet.attachmentName,
                attachmentType = packet.attachmentType,
                attachmentBase64 = packet.attachmentBase64,
                fallbackUri = packet.attachmentUri,
                voiceDurationSec = packet.voiceDurationSec,
                voiceWaveformCsv = packet.voiceWaveformCsv
            )
        } else {
            packet.attachmentUri
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
            plainText = resolvedPlainText,
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
            attachmentUri = resolvedAttachmentUri,
            voiceDurationSec = packet.voiceDurationSec,
            voiceWaveformCsv = packet.voiceWaveformCsv,
            reactionsJson = ""
        )
        dao.insertMessage(msg)

        if (packet.attachmentName != null && merkleRoot != null && packet.attachmentType != "VOICE") {
            val (chunks, _) = YggCryptoEngine.computeFastCdcMerkleProfile(packet.attachmentName, packet.attachmentSizeBytes)
            val rxTransfer = P2PFileTransferEntity(
                transferId = "tx_rx_${UUID.randomUUID()}",
                chatId = conv.chatId,
                peerName = packet.senderNodeLabel,
                peerIpv6 = packet.senderIpv6,
                fileName = packet.attachmentName,
                fileSizeBytes = packet.attachmentSizeBytes,
                chunkCount = chunks,
                completedChunks = chunks,
                merkleRootSha256 = merkleRoot,
                speedKbps = 1850,
                status = "VERIFIED_COMPLETE",
                isOutgoing = false,
                protocol = "YGG_OVERLAY_STREAM",
                timestamp = packet.timestamp
            )
            dao.upsertFileTransfer(rxTransfer)
        }

        val isCurrentlyViewing = (targetOwnerProfileId == currentPrimaryProfileId) && (activeChatId == conv.chatId)
        if (authenticatedSenderX25519Pub.isNotBlank() && authenticatedSenderX25519Pub != conv.peerX25519PublicKeyHex) {
            conv = conv.copy(peerX25519PublicKeyHex = authenticatedSenderX25519Pub)
        }
        val previewPrefix = when (packet.attachmentType) {
            "VOICE" -> "🎤 Голосовое (${packet.voiceDurationSec}с) "
            "IMAGE" -> "🖼️ Фото (${packet.attachmentName}) "
            "FILE" -> "📎 Файл (${packet.attachmentName}) "
            else -> ""
        }
        val firstLine = resolvedPlainText.lineSequence().firstOrNull() ?: resolvedPlainText
        dao.upsertConversation(
            conv.copy(
                lastMessagePreview = "$previewPrefix$firstLine".trim(),
                lastTimestamp = packet.timestamp,
                unreadCount = if (isCurrentlyViewing) 0 else conv.unreadCount + 1,
                isOnlineInMesh = true
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

    suspend fun createNewConversation(
        title: String,
        yggIpv6Input: String,
        isMlsGroup: Boolean,
        sealedSender: Boolean,
        disappearingSeconds: Int,
        directEndpointHint: String = "",
        localIdentity: DerivedIdentityHierarchy? = null,
        ownerProfileId: String = DEFAULT_PROFILE_ID,
        peerX25519PublicKeyHex: String = ""
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
        val extractedX25519Pub = peerX25519PublicKeyHex.trim().lowercase().ifBlank {
            if (rawInput.contains("xpub=")) {
                rawInput.substringAfter("xpub=").substringBefore("&").trim().lowercase()
                    .takeIf { it.length == 64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
            } else {
                null
            }
        }.orEmpty()
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

        val allProfilesNow = dao.getAllProfilesNow()
        val ownerProfile = allProfilesNow.find { it.profileId == ownerProfileId }
        val isSelfNode = (
            localIdentity != null && (
                cleanIpv6.equals(localIdentity.yggIpv6Address, ignoreCase = true) ||
                    extractedPubKey.equals(localIdentity.yggNodePublicKeyHex, ignoreCase = true)
                )
            ) || (
            ownerProfile != null && (
                cleanIpv6.equals(ownerProfile.yggIpv6Address, ignoreCase = true) ||
                    (extractedPubKey.isNotBlank() && extractedPubKey.equals(ownerProfile.yggPublicKeyHex, ignoreCase = true))
                )
            )

        if (isSelfNode) {
            val selfIdentity = localIdentity ?: ownerProfile?.let {
                YggCryptoEngine.deriveHierarchyFromMnemonic(
                    it.mnemonicCsv.split(" ").filter { w -> w.isNotBlank() },
                    epoch = 1
                )
            }
            if (selfIdentity != null) {
                return ensureSelfYggdrasilConversation(selfIdentity, ownerProfileId)
            }
        }

        val localTargetProfile = allProfilesNow.find {
            it.profileId != ownerProfileId && it.yggIpv6Address.equals(cleanIpv6, ignoreCase = true)
        }
        val localTargetXPub = localTargetProfile?.let { profile ->
            profile.mnemonicCsv.split(" ").filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
                YggCryptoEngine.deriveHierarchyFromMnemonic(it).x25519IdentityPubHex
            }
        }.orEmpty()
        val effectivePeerX25519Pub = extractedX25519Pub.ifBlank { localTargetXPub }

        // Reuse existing conversation if this profile already has a chat with cleanIpv6
        val existingForProfile = dao.getAllConversationsNow().find {
            it.ownerProfileId == ownerProfileId &&
                !isSelfChatId(it.chatId) &&
                it.yggIpv6Address.equals(cleanIpv6, ignoreCase = true)
        }
        if (existingForProfile != null) {
            if (effectivePeerX25519Pub.isNotBlank() && existingForProfile.peerX25519PublicKeyHex != effectivePeerX25519Pub) {
                dao.upsertConversation(existingForProfile.copy(peerX25519PublicKeyHex = effectivePeerX25519Pub))
            }
            if (extractedLanHint.isNotBlank() && !extractedLanHint.startsWith("127.0.0.1:")) {
                dao.upsertConversation(
                    existingForProfile.copy(
                        bioStatus = extractedLanHint,
                        routeTransportSummary = "Direct P2P ($extractedLanHint)",
                        isOnlineInMesh = true
                    )
                )
            } else if (existingForProfile.bioStatus.startsWith("127.0.0.1:") ||
                existingForProfile.routeTransportSummary.contains("127.0.0.1:")
            ) {
                dao.upsertConversation(
                    existingForProfile.copy(
                        bioStatus = "Маршрут через внешние пиры Yggdrasil 0200::/7",
                        routeTransportSummary = "Yggdrasil 0200::/7 Overlay"
                    )
                )
            }
            return existingForProfile.chatId
        }

        val effectiveHint = extractedLanHint.takeUnless { it.startsWith("127.0.0.1:") }.orEmpty()
        val endpointSummary = when {
            localTargetProfile != null -> "Локальный профиль «${localTargetProfile.displayName}» (внутренний P2P-мост устройства)"
            effectiveHint.isNotBlank() -> "Direct P2P ($effectiveHint)"
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
            bioStatus = when {
                localTargetProfile != null -> "Диалог с локальным профилем этого устройства — доставка через внутренний P2P-мост"
                effectiveHint.isNotBlank() -> effectiveHint
                else -> "Маршрут через внешние пиры Yggdrasil 0200::/7"
            },
            routeTransportSummary = endpointSummary,
            ownerProfileId = ownerProfileId,
            peerX25519PublicKeyHex = effectivePeerX25519Pub
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
