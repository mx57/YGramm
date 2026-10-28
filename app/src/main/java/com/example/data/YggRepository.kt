// File: app/src/main/java/com/example/data/YggRepository.kt
package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.FileTransferProgress
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggAudioMediaEngine
import com.example.engine.YggAvatarCodec
import com.example.engine.YggCryptoEngine
import com.example.engine.YggProfilePayload
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    fun observeCallLog(ownerProfileId: String, limit: Int = 40): Flow<List<CallLogEntity>> =
        dao.observeCallLog(ownerProfileId, limit)

    fun searchMessages(
        ownerProfileId: String,
        query: String,
        limit: Int = 60
    ): Flow<List<MessageEntity>> =
        if (query.isBlank()) flowOf(emptyList()) else dao.searchMessages(ownerProfileId, query.trim(), limit)

    suspend fun getConversationTitle(chatId: String): String? =
        dao.getConversationById(chatId)?.title

    suspend fun setConversationKeyVerified(chatId: String, verified: Boolean) {
        val conv = dao.getConversationById(chatId) ?: return
        dao.upsertConversation(conv.copy(isVerified = verified))
    }

    suspend fun recordCallLog(entry: CallLogEntity) = dao.upsertCallLog(entry)

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
                displayName = "Узел YGramm #1",
                handle = "@node1.ygg",
                bio = "Основной децентрализованный профиль в сети Yggdrasil 0200::/7 • Прямой P2P & Zero-Knowledge",
                statusText = "В оверлее 200::/7 (Порт 51921)",
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
            val active = existingProfiles.find { it.isCurrentPrimary } ?: existingProfiles.first()
            if (active.statusText.contains("Порт :")) {
                val normalized = active.copy(statusText = active.statusText.replace("Порт :", "Порт "))
                dao.upsertProfile(normalized)
                normalized
            } else {
                active
            }
        }

        if (dao.getCryptoKeysNow().isEmpty()) {
            val words = primaryProfile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
                .ifEmpty { defaultIdentity.mnemonicWords }
            val hierarchy = YggCryptoEngine.deriveHierarchyFromMnemonic(words, epoch = 1)
            val initialKeys = buildHierarchyKeyRecords(hierarchy, now = now)
            dao.deleteLegacyCryptoClaims()
            dao.insertCryptoKeys(initialKeys)
        }

        reconcileLegacyYggdrasilAddresses()

        if (dao.getAllPeersNow().isEmpty()) {
            val defaultPublicPeers = listOf(
                YggPeerEntity(
                    peerId = "peer_fr_par_scaleway",
                    endpointUri = "tls://51.15.204.214:54321",
                    transportType = "TLS_YGGDRASIL",
                    sniDomain = "51.15.204.214",
                    utlsFingerprint = "HelloChrome_128_Auto",
                    regionLabel = "🇫🇷 FR • Париж (Scaleway FR #1)",
                    latencyMs = -1,
                    rxBytes = 0L,
                    txBytes = 0L,
                    isConnected = false,
                    isWhitelistCompatible = false,
                    ed25519Verified = true
                ),
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

    suspend fun panicWipeAllData() = dao.wipeAllUserData()

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

        val cleanName = displayName.trim().ifBlank { "Узел YGramm #$index" }
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
            syncKeyRecords(hierarchy)
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
        syncKeyRecords(hierarchy)
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

    /**
     * Stamp for the next outgoing profile.
     *
     * The receiver only applies a profile newer than the one it stored, so a stamp that does not
     * strictly increase turns the second edit of a burst into a silent no-op. Wall-clock time ties
     * inside a millisecond and rolls back when the network corrects the clock; the +1 floor covers
     * both.
     */
    private fun nextProfileVersion(previous: Long): Long =
        maxOf(System.currentTimeMillis(), previous + 1L)

    suspend fun updateCurrentProfileMetadata(
        profileId: String,
        displayName: String,
        handle: String,
        bio: String,
        socialStatus: String
    ): UserProfileEntity? {
        val existing = dao.getProfileById(profileId) ?: return null
        val updated = existing.copy(
            displayName = displayName.trim().ifBlank { existing.displayName },
            handle = handle.trim().ifBlank { existing.handle },
            bio = bio.trim(),
            socialStatus = socialStatus.trim().ifBlank { existing.socialStatus },
            profileVersion = nextProfileVersion(existing.profileVersion)
        )
        dao.upsertProfile(updated)
        return updated
    }

    /**
     * Installing a picture the user picked as this profile's avatar.
     *
     * @return the updated row, or null when the source is not a readable image or will not
     *         compress into a message frame; the previous avatar is kept in that case,
     *         because showing the user a face they did not choose is worse than telling
     *         them this one did not fit.
     */
    suspend fun setProfileAvatar(profileId: String, source: Uri): UserProfileEntity? {
        val context = appContext ?: return null
        val existing = dao.getProfileById(profileId) ?: return null
        val stored = YggAvatarCodec.storeFromUri(context, source) ?: return null
        if (stored.hashHex != existing.avatarHashHex) {
            YggAvatarCodec.forget(context, existing.avatarUri)
            dao.updateProfileAvatar(
                profileId,
                stored.localUri,
                stored.hashHex,
                nextProfileVersion(existing.profileVersion)
            )
        }
        return dao.getProfileById(profileId)
    }

    suspend fun clearProfileAvatar(profileId: String) {
        val context = appContext ?: return
        val existing = dao.getProfileById(profileId) ?: return
        if (existing.avatarUri.isBlank() && existing.avatarHashHex.isBlank()) return
        YggAvatarCodec.forget(context, existing.avatarUri)
        // The new version is what tells peers the picture is gone rather than merely unreached:
        // a body with an empty hash is a deletion the receiver can order against the last one.
        dao.updateProfileAvatar(profileId, "", "", nextProfileVersion(existing.profileVersion))
    }

    suspend fun profileAvatarBase64(profileId: String): String =
        dao.getProfileById(profileId)?.avatarUri
            ?.let(YggAvatarCodec::base64OfStoredFile)
            .orEmpty()

    /**
     * What this device did with an incoming PROFILE frame, named apart because each of these
     * outcomes has to be sayable differently: "не доставлено", "отклонено" and "старая версия"
     * are not the same event, and only the first is the sender's fault.
     */
    enum class ProfileApplyOutcome {
        APPLIED,
        NO_DIALOG,
        KEY_MISMATCH,
        UNSEALED,
        CORRUPT,
        OUT_OF_DATE
    }

    /**
     * A contact's own profile applied to the dialog it belongs to.
     *
     * Every field arrives inside a conversation envelope sealed to this device, and the frame is
     * refused unless the sender's node key reproduces the mesh address it claims. That is the whole
     * security argument: without it, any node able to put one frame on the path could rename
     * itself as a saved contact or hand over a picture to plant under their face.
     *
     * The dialog must already exist. A profile is something peers exchange because they write to
     * each other; accepting one from a stranger would turn the chat list into an inbox of names
     * chosen by whoever messages first.
     */
    suspend fun applyIncomingProfile(
        senderIpv6: String,
        senderPubKeyHex: String,
        profileEnvelope: String,
        avatarHashHex: String,
        avatarBase64: String,
        recipientProfileId: String = "",
        currentPrimaryProfileId: String = DEFAULT_PROFILE_ID
    ): ProfileApplyOutcome {
        val verifiedSenderKey = senderPubKeyHex.trim().lowercase().takeIf {
            isEd25519Hex(it) && YggCryptoEngine.addressMatchesEd25519Key(senderIpv6, it)
        } ?: return ProfileApplyOutcome.KEY_MISMATCH

        val allProfiles = dao.getAllProfilesNow()
        val ownerProfileId = recipientProfileId.ifBlank { currentPrimaryProfileId }
        val chatId = findConversationIdByPeerIpv6(senderIpv6, ownerProfileId)
            ?: return ProfileApplyOutcome.NO_DIALOG
        val conv = dao.getConversationById(chatId) ?: return ProfileApplyOutcome.NO_DIALOG

        val recipientWords = allProfiles.find { it.profileId == ownerProfileId }
            ?.mnemonicCsv?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        val recipientXPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(recipientWords)
            .takeIf { recipientWords.isNotEmpty() }
            ?: return ProfileApplyOutcome.UNSEALED
        val opened = try {
            YggCryptoEngine.openConversationEnvelope(
                envelope = profileEnvelope,
                recipientX25519PrivateKey = recipientXPriv,
                senderEd25519PublicKeyHex = verifiedSenderKey
            )
        } catch (e: Exception) {
            Log.w("YggGramProfile", "Профиль от $senderIpv6 не распечатан: ${e.javaClass.simpleName}")
            return ProfileApplyOutcome.UNSEALED
        }
        val body = YggProfilePayload.decode(opened.plainText)
            ?: return ProfileApplyOutcome.CORRUPT
        if (body.version <= conv.peerProfileVersion) return ProfileApplyOutcome.OUT_OF_DATE

        dao.updatePeerProfile(
            chatId = chatId,
            title = body.displayName.ifBlank { conv.title },
            peerHandle = body.handle,
            peerStatusText = body.statusText,
            peerProfileVersion = body.version
        )
        // A body that says "no picture" is a removal the receiver can act on: the version already
        // proved which side of the two states this is, and leaving the old face up would show the
        // user a picture their contact deleted.
        if (body.avatarHashHex.isBlank()) {
            clearIncomingAvatar(chatId)
        } else if (body.avatarHashHex == avatarHashHex.lowercase()) {
            storeIncomingAvatar(chatId, avatarHashHex, avatarBase64)
        }
        dao.updatePeerLastSeen(chatId, System.currentTimeMillis())
        return ProfileApplyOutcome.APPLIED
    }

    private suspend fun clearIncomingAvatar(chatId: String) {
        val context = appContext ?: return
        val conv = dao.getConversationById(chatId) ?: return
        if (conv.avatarUri.isBlank() && conv.avatarHashHex.isBlank()) return
        YggAvatarCodec.forget(context, conv.avatarUri)
        dao.updateConversationAvatar(chatId, "", "")
    }

    /**
     * Saving a contact's avatar received over the overlay.
     *
     * Runs after the message itself is persisted and as a partial UPDATE: the conversation
     * row is re-saved from a copy elsewhere, and a full upsert there would drop the picture
     * again the moment the contact sent their next message.
     */
    suspend fun storeIncomingAvatar(chatId: String, avatarHashHex: String, avatarBase64: String) {
        val context = appContext ?: return
        if (chatId.isBlank() || avatarHashHex.isBlank() || avatarBase64.isBlank()) return
        val conv = dao.getConversationById(chatId) ?: return
        if (conv.avatarHashHex.equals(avatarHashHex, ignoreCase = true)) return
        val stored = YggAvatarCodec.storeIncoming(
            avatarBase64,
            avatarHashHex,
            YggAvatarCodec.avatarDir(context)
        ) ?: return
        dao.updateConversationAvatar(chatId, stored.localUri, stored.hashHex)
    }

    // ── Groups ──────────────────────────────────────────────────────────────────────

    fun observeGroupMembers(chatId: String): Flow<List<GroupMemberEntity>> =
        dao.observeGroupMembers(chatId)

    fun observeGroupMemberCounts(): Flow<List<GroupMemberCount>> = dao.observeGroupMemberCounts()

    suspend fun getGroupMembers(chatId: String): List<GroupMemberEntity> = dao.getGroupMembers(chatId)

    /**
     * Opening a group from contacts this profile already has a chat with.
     *
     * A group is only ever a roster of mesh addresses: there is no server to host it, so
     "creating" one is local bookkeeping and the first message is what tells the other
     nodes the group exists and who is in it.
     *
     * @return the local chat id, or "" when no member resolved to a diallable address.
     */
    suspend fun createNewGroup(
        title: String,
        memberContactChatIds: List<String>,
        ownerProfileId: String,
        localIdentity: DerivedIdentityHierarchy?
    ): String {
        val contacts = memberContactChatIds.mapNotNull { dao.getConversationById(it) }
            .filter { !isSelfChatId(it.chatId) && it.chatType != "GROUP" && it.yggIpv6Address.isNotBlank() }
            .distinctBy { it.yggIpv6Address.lowercase() }
        if (contacts.isEmpty()) return ""

        val groupId = "grp_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val now = System.currentTimeMillis()
        val chatId = "chat_${ownerProfileId}_$groupId"
        dao.upsertConversation(
            ConversationEntity(
                chatId = chatId,
                title = title.trim().ifBlank { "Группа на ${contacts.size + 1}" },
                yggIpv6Address = localIdentity?.yggIpv6Address.orEmpty(),
                publicKeyHex = localIdentity?.yggNodePublicKeyHex.orEmpty(),
                protocolType = "YGG_X25519_AESGCM",
                isVerified = false,
                sealedSenderEnabled = true,
                disappearingTtlSeconds = 0,
                unreadCount = 0,
                lastMessagePreview = "",
                lastTimestamp = now,
                isOnlineInMesh = true,
                avatarColorHex = "#10B981",
                bioStatus = "Группа в оверлее Yggdrasil",
                routeTransportSummary = "Yggdrasil Overlay 0200::/7",
                ownerProfileId = ownerProfileId,
                chatType = "GROUP",
                groupId = groupId
            )
        )
        dao.upsertGroupMembers(
            contacts.map {
                GroupMemberEntity(
                    chatId = chatId,
                    memberIpv6 = it.yggIpv6Address,
                    memberPubKeyHex = it.publicKeyHex,
                    memberName = it.title,
                    addedAt = now
                )
            }
        )
        return chatId
    }

    /** @return how many contacts were newly added to the group. */
    suspend fun addContactsToGroup(chatId: String, memberContactChatIds: List<String>): Int {
        val group = dao.getConversationById(chatId)?.takeIf { it.chatType == "GROUP" } ?: return 0
        val existing = dao.getGroupMembers(chatId).map { it.memberIpv6.lowercase() }.toSet()
        val now = System.currentTimeMillis()
        val fresh = memberContactChatIds.mapNotNull { dao.getConversationById(it) }
            .filter {
                !isSelfChatId(it.chatId) && it.chatType != "GROUP" &&
                    it.yggIpv6Address.isNotBlank() && it.yggIpv6Address.lowercase() !in existing
            }
            .distinctBy { it.yggIpv6Address.lowercase() }
        if (fresh.isEmpty()) return 0
        dao.upsertGroupMembers(
            fresh.map {
                GroupMemberEntity(
                    chatId = group.chatId,
                    memberIpv6 = it.yggIpv6Address,
                    memberPubKeyHex = it.publicKeyHex,
                    memberName = it.title,
                    addedAt = now
                )
            }
        )
        return fresh.size
    }

    /**
     * Folding the roster a frame advertises into the local member table.
     *
     * The sender of a group message is itself a member whether or not it listed itself —
     * it just proved it can deliver to this group. Own profiles are skipped so a device
     * running several profiles does not list one of its own nodes as an external member.
     */
    private suspend fun mergeGroupRoster(chatId: String, packet: IncomingTwinPacket) {
        val ownAddresses = dao.getAllProfilesNow().map { it.yggIpv6Address.lowercase() }.toSet()
        val now = System.currentTimeMillis()
        // A frame from another node only carries the speaker's name; everyone else arrives
        // as a bare address. REPLACE would then wipe a name learned earlier — from a contact
        // row or from that member speaking itself — so the known values are carried over.
        val known = dao.getAllGroupMembersIncludingRemoved(chatId)
            .associateBy { it.memberIpv6.lowercase() }
        val rows = mutableListOf<GroupMemberEntity>()
        // The speaker is listed first: it is the one member whose name the frame really
        // carries, and distinctBy below keeps the first row for an address.
        val senderAddress = packet.senderIpv6.trim().lowercase()
        if (senderAddress.contains(':') && senderAddress !in ownAddresses) {
            val previously = known[senderAddress]
            rows += GroupMemberEntity(
                chatId = chatId,
                memberIpv6 = senderAddress,
                memberPubKeyHex = packet.senderPubKeyHex.trim().lowercase()
                    .takeIf { isEd25519Hex(it) } ?: previously?.memberPubKeyHex.orEmpty(),
                memberName = packet.senderNodeLabel.trim().ifBlank { previously?.memberName.orEmpty() },
                addedAt = previously?.addedAt ?: now,
                removedAt = previously?.removedAt ?: 0
            )
        }
        packet.groupMembersCsv.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { entry ->
            val address = entry.substringBefore('|').trim().lowercase()
            val key = entry.substringAfter('|', "").trim().lowercase()
            if (address.contains(':') && address !in ownAddresses) {
                val previously = known[address]
                rows += GroupMemberEntity(
                    chatId = chatId,
                    memberIpv6 = address,
                    memberPubKeyHex = if (isEd25519Hex(key)) key else previously?.memberPubKeyHex.orEmpty(),
                    memberName = previously?.memberName.orEmpty(),
                    addedAt = previously?.addedAt ?: now,
                    removedAt = previously?.removedAt ?: 0
                )
            }
        }
        if (rows.isNotEmpty()) dao.upsertGroupMembers(rows.distinctBy { it.memberIpv6 })
    }

    /**
     * Recording one member's acknowledgement for a group message.
     *
     * «DELIVERED» is written only when the delivered set covers the whole roster; until
     * then the message reports exactly how many acknowledgements have arrived, because a
     * group of five where two are offline has not been delivered to.
     */
    suspend fun recordGroupMemberDelivery(messageId: String, memberIpv6: String): Pair<Int, Int> {
        val msg = dao.getMessageById(messageId) ?: return 0 to 0
        val delivered = msg.deliveredMemberCsv.split(',')
            .map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableSet()
        delivered += memberIpv6.trim().lowercase()
        val total = dao.getGroupMembers(msg.chatId).size
        val state = if (total in 1..delivered.size) "DELIVERED" else msg.deliveryState
        dao.updateMessageGroupDelivery(messageId, delivered.joinToString(","), state)
        return delivered.size to total
    }

    /**
     * Attributing an acknowledgement that arrives after the fan-out to the member that sent
     * it. The overlay names the responder by its node key and a direct socket by its address,
     * so both forms are accepted and matched against the roster — by stored key, by the
     * address derived from that key, then by the address itself.
     *
     * @return the member's address and the running delivered/total counts, or "" when the
     * responder cannot be placed in this group.
     */
    suspend fun recordGroupMemberAckBySender(
        messageId: String,
        ackSenderKey: String
    ): Pair<String, Pair<Int, Int>> {
        val msg = dao.getMessageById(messageId) ?: return "" to (0 to 0)
        val members = dao.getGroupMembers(msg.chatId)
        val alreadyDelivered = msg.deliveredMemberCsv.split(',').filter { it.isNotBlank() }
        val sender = ackSenderKey.trim().lowercase()
        val senderAddress = if (sender.contains(':')) YggCryptoEngine.normalizeIpv6Literal(sender) else null
        val member = members.firstOrNull { stored ->
            val byKey = isEd25519Hex(sender) &&
                (stored.memberPubKeyHex.trim().lowercase() == sender ||
                    YggCryptoEngine.addressMatchesEd25519Key(stored.memberIpv6, sender))
            byKey || (senderAddress != null &&
                YggCryptoEngine.normalizeIpv6Literal(stored.memberIpv6) == senderAddress)
        } ?: return "" to (alreadyDelivered.size to members.size)
        return member.memberIpv6 to recordGroupMemberDelivery(messageId, member.memberIpv6)
    }

    /**
     * Renaming a group. The title travels with every later frame, so the peers learn the
     * new name from the next message rather than from a dedicated packet.
     */
    suspend fun renameGroup(chatId: String, newTitle: String): Boolean {
        val title = newTitle.trim().take(80)
        if (title.isEmpty()) return false
        val group = dao.getConversationById(chatId)?.takeIf { it.chatType == "GROUP" } ?: return false
        if (group.title == title) return false
        dao.upsertConversation(group.copy(title = title))
        return true
    }

    /**
     * Removing one member. The stored address is matched case- and format-insensitively
     * because rows created from a contact keep the literal the contact was added with, while
     * roster merges store the lowercase form.
     *
     * @return the roster size after the removal.
     */
    suspend fun removeMemberFromGroup(chatId: String, memberIpv6: String): Int {
        val group = dao.getConversationById(chatId)?.takeIf { it.chatType == "GROUP" } ?: return 0
        val wanted = YggCryptoEngine.normalizeIpv6Literal(memberIpv6)
        val target = dao.getAllGroupMembersIncludingRemoved(group.chatId).firstOrNull { stored ->
            stored.memberIpv6.lowercase() == memberIpv6.trim().lowercase() ||
                (wanted != null && YggCryptoEngine.normalizeIpv6Literal(stored.memberIpv6) == wanted)
        } ?: return dao.getGroupMembers(group.chatId).size
        dao.markGroupMemberRemoved(group.chatId, target.memberIpv6, System.currentTimeMillis())
        return dao.getGroupMembers(group.chatId).size
    }

    /**
     * Leaving a group: the roster and the chat go, together with the messages that were
     * fanned out into it. Nothing is sent to the remaining members — the group simply stops
     * addressing this node, and the next roster they advertise no longer lists us.
     */
    suspend fun leaveGroup(chatId: String): Boolean {
        val group = dao.getConversationById(chatId)?.takeIf { it.chatType == "GROUP" } ?: return false
        dao.clearGroupMembers(group.chatId)
        deleteConversation(group.chatId)
        return true
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
        syncKeyRecords(identity)
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
            protocolType = "YGG_X25519_AESGCM",
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
     * Authentic Yggdrasil node identity of a conversation peer.
     *
     * The overlay addresses packets by the recipient's Ed25519 node key, so a placeholder
     * key routes traffic to a node that does not exist and the mesh drops the frame
     * without any ACK.
     */
    data class PeerNodeIdentity(
        val yggIpv6Address: String,
        val publicKeyHex: String,
        val localProfileId: String?
    )

    private fun isEd25519Hex(value: String): Boolean {
        val clean = value.trim()
        return clean.length == 64 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /** IPv6 literals may arrive compressed, expanded or in mixed case from old builds. */
    private fun sameMeshAddress(first: String, second: String): Boolean {
        val left = first.trim()
        val right = second.trim()
        if (left.equals(right, ignoreCase = true)) return true
        val normalizedLeft = YggCryptoEngine.normalizeIpv6Literal(left) ?: return false
        val normalizedRight = YggCryptoEngine.normalizeIpv6Literal(right) ?: return false
        return normalizedLeft == normalizedRight
    }

    private fun profileX25519IdentityPub(profile: UserProfileEntity): String {
        val words = profile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        return YggCryptoEngine.deriveHierarchyFromMnemonic(words).x25519IdentityPubHex.lowercase()
    }

    private fun isX25519Hex(value: String): Boolean {
        val clean = value.trim()
        return clean.length == 64 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /**
     * X25519 identity key of a call peer. Local profiles are resolved from their mnemonic;
     * remote peers only have the key that was pinned when the contact was added.
     */
    suspend fun resolvePeerX25519PublicKeyHex(conv: ConversationEntity): String {
        val pinned = conv.peerX25519PublicKeyHex.trim().lowercase()
        if (isX25519Hex(pinned)) return pinned
        val profiles = dao.getAllProfilesNow()
        val storedAddress = conv.yggIpv6Address.trim()
        val storedKey = conv.publicKeyHex.trim()
        profiles.firstOrNull { sameMeshAddress(it.yggIpv6Address, storedAddress) }
            ?.let { return profileX25519IdentityPub(it) }
        profiles.firstOrNull { it.yggPublicKeyHex.trim().equals(storedKey, ignoreCase = true) }
            ?.let { return profileX25519IdentityPub(it) }
        return ""
    }

    /** Pins the X25519 key learned from a verified call signal so later calls reuse it. */
    suspend fun pinConversationPeerX25519PublicKey(
        conv: ConversationEntity,
        x25519PublicKeyHex: String
    ): ConversationEntity {
        val clean = x25519PublicKeyHex.trim().lowercase()
        if (!isX25519Hex(clean)) return conv
        if (conv.peerX25519PublicKeyHex.equals(clean, ignoreCase = true)) return conv
        val updated = conv.copy(peerX25519PublicKeyHex = clean)
        dao.upsertConversation(updated)
        return updated
    }

    /**
     * Resolves the real Yggdrasil node identity behind a conversation.
     *
     * Local profiles are authoritative and matched by mesh address, by node key or by the
     * X25519 chat key; only their mnemonic-derived Ed25519 key can actually be addressed
     * on the overlay. Remote peers keep the key they were added with.
     */
    suspend fun resolvePeerNodeIdentity(conv: ConversationEntity): PeerNodeIdentity {
        val profiles = dao.getAllProfilesNow()
        val storedAddress = conv.yggIpv6Address.trim()
        val storedKey = conv.publicKeyHex.trim().lowercase()

        profiles.firstOrNull { sameMeshAddress(it.yggIpv6Address, storedAddress) }
            ?.let { return PeerNodeIdentity(it.yggIpv6Address, it.yggPublicKeyHex, it.profileId) }

        profiles.firstOrNull { it.yggPublicKeyHex.trim().equals(storedKey, ignoreCase = true) }
            ?.let { return PeerNodeIdentity(it.yggIpv6Address, it.yggPublicKeyHex, it.profileId) }

        val peerXPub = conv.peerX25519PublicKeyHex.trim().lowercase()
        if (peerXPub.isNotBlank()) {
            profiles.firstOrNull { profileX25519IdentityPub(it) == peerXPub }
                ?.let { return PeerNodeIdentity(it.yggIpv6Address, it.yggPublicKeyHex, it.profileId) }
        }

        // A remote overlay address is derived from its node key. A key that does not
        // reproduce the address is a legacy placeholder and would be silently dropped
        // by the mesh, so it must not be used for addressing at all.
        val validRemoteKey = when {
            !isEd25519Hex(storedKey) -> ""
            YggCryptoEngine.normalizeIpv6Literal(storedAddress) != null ->
                storedKey.takeIf { YggCryptoEngine.addressMatchesEd25519Key(storedAddress, it) }.orEmpty()
            else -> storedKey
        }
        return PeerNodeIdentity(storedAddress, validRemoteKey, null)
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
                val identity = resolvePeerNodeIdentity(conv)
                if (identity.localProfileId == null) {
                    // Remote peer: the key was supplied by the user (QR or ?pub=), so the
                    // stored address is kept as-is instead of being re-derived from a key
                    // that may be a legacy placeholder.
                    return@forEach
                }
                val pinnedAddress = identity.yggIpv6Address.trim()
                val pinnedKey = identity.publicKeyHex.trim()
                val pinnedXPub = profileX25519IdentityPub(
                    dao.getAllProfilesNow().first { it.profileId == identity.localProfileId }
                )
                if (pinnedAddress.equals(conv.yggIpv6Address, ignoreCase = true) &&
                    pinnedKey.equals(conv.publicKeyHex, ignoreCase = true) &&
                    pinnedXPub.equals(conv.peerX25519PublicKeyHex, ignoreCase = true)
                ) {
                    return@forEach
                }
                dao.upsertConversation(
                    conv.copy(
                        yggIpv6Address = pinnedAddress,
                        publicKeyHex = pinnedKey,
                        peerX25519PublicKeyHex = pinnedXPub
                    )
                )
            }

            dao.getAllConversationsNow().forEach { conv ->
                val legacyBridge = conv.bioStatus.contains("внутренний P2P-мост") ||
                    conv.routeTransportSummary.contains("внутренний P2P-мост")
                if (legacyBridge) {
                    dao.upsertConversation(
                        conv.copy(
                            bioStatus = "Диалог между профилями устройства — доставка через интернет-оверлей Yggdrasil 0200::/7",
                            routeTransportSummary = "Yggdrasil 0200::/7 Overlay (интернет)"
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Reconciliation is best-effort; never block startup on it.
        }
    }

    /**
     * Records the keys this app actually derives and uses. Message envelopes use static
     * X25519 ECDH + HKDF-SHA256 + AES-256-GCM; no Double Ratchet, Signed PreKey or MLS
     * state exists behind these records.
     */
    fun buildHierarchyKeyRecords(
        identity: DerivedIdentityHierarchy,
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
            keyName = "Долгосрочный ключ чатов и звонков (X25519)",
            derivationPath = "m/44'/999'/1'/0'",
            algorithm = "X25519",
            publicKeyHex = identity.x25519IdentityPubHex,
            fingerprintHex = identity.x25519IdentityPubHex.take(24).uppercase(),
            createdAt = now,
            rotationEpoch = 1,
            isActive = true,
            usageScope = "Статический ECDH для конвертов v2 и медиаключей звонков; без прямой секретности"
        ),
        CryptoKeyRecordEntity(
            keyId = "key_backup_master",
            keyName = "Фингерпринт мастер-ключа бэкапов",
            derivationPath = "m/44'/999'/3'/0'",
            algorithm = "SHA-256 fingerprint",
            publicKeyHex = YggCryptoEngine.sha256Hex(identity.backupMasterKeyFingerprint),
            fingerprintHex = identity.backupMasterKeyFingerprint,
            createdAt = now,
            rotationEpoch = 1,
            isActive = true,
            usageScope = "Парольная фраза по умолчанию для локального экспорта"
        )
    )

    suspend fun syncKeyRecords(identity: DerivedIdentityHierarchy) {
        dao.deleteLegacyCryptoClaims()
        dao.insertCryptoKeys(buildHierarchyKeyRecords(identity))
    }

    suspend fun persistProfileMnemonic(profileId: String, words: List<String>) {
        val profile = dao.getAllProfilesNow().find { it.profileId == profileId } ?: return
        dao.upsertProfile(profile.copy(mnemonicCsv = words.joinToString(" ")))
    }

    private data class SealingKeys(
        val senderX25519PrivateKey: ByteArray,
        val senderEd25519PublicKeyHex: String,
        val peerX25519PublicKeyHex: String
    )

    /**
     * Heals a 1:1 chat whose pinned key disappeared (contact re-added, storage repaired) while
     * its history still holds copies whose sender key was authenticated by the envelope itself.
     * That stored key is the very value the receive path writes onto the conversation, so reusing
     * it adds no trust the chat did not already have. Groups are excluded: a group row has no
     * single peer key, and picking one member would seal everyone's copy to the wrong secret.
     */
    private suspend fun recoverPeerKeyFromHistory(conv: ConversationEntity): String? {
        if (conv.chatType == "GROUP" || isSelfChatId(conv.chatId)) return null
        val recovered = dao.getNewestAuthenticatedIncoming(conv.chatId)
            ?.authorX25519PubHex
            ?.trim()
            ?.lowercase()
            ?.takeIf { YggCryptoEngine.isUsablePeerX25519PublicKey(it) }
            ?: return null
        dao.upsertConversation(conv.copy(peerX25519PublicKeyHex = recovered))
        return recovered
    }

    /**
     * Keys an outgoing envelope for this conversation would be built from, or null when the
     * peer X25519 key is unknown. Without that key there is no shared secret, and the only
     * alternative is a key anyone can recompute from the public values on the wire, so the
     * caller must refuse to send instead.
     */
    private suspend fun resolveSealingKeys(conv: ConversationEntity): SealingKeys? {
        val profiles = dao.getAllProfilesNow()
        val senderProfile = profiles.find { it.profileId == conv.ownerProfileId }
            ?: profiles.find { it.isCurrentPrimary }
            ?: profiles.firstOrNull()
            ?: return null
        var senderWords = senderProfile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
        if (senderWords.isEmpty()) {
            senderWords = YggCryptoEngine.generateFreshMnemonic12()
            dao.upsertProfile(senderProfile.copy(mnemonicCsv = senderWords.joinToString(" ")))
        }
        val senderIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(senderWords)
        val peerKey = conv.peerX25519PublicKeyHex.takeIf { YggCryptoEngine.isUsablePeerX25519PublicKey(it) }
            ?: profiles.find {
                it.profileId != senderProfile.profileId &&
                    sameMeshAddress(it.yggIpv6Address, conv.yggIpv6Address)
            }?.let { peerProfile ->
                YggCryptoEngine.deriveHierarchyFromMnemonic(
                    peerProfile.mnemonicCsv.split(" ").filter { it.isNotBlank() }
                ).x25519IdentityPubHex.takeIf { YggCryptoEngine.isUsablePeerX25519PublicKey(it) }
            }
            ?: recoverPeerKeyFromHistory(conv)
            ?: senderIdentity.x25519IdentityPubHex.takeIf {
                // A group row has no single peer key: its stored envelope is only the local
                // copy, addressed to ourselves. Every member gets a real envelope sealed to
                // that member's own key when the message is dispatched.
                (isSelfChatId(conv.chatId) || conv.chatType == "GROUP") &&
                    YggCryptoEngine.isUsablePeerX25519PublicKey(senderIdentity.x25519IdentityPubHex)
            }
            ?: return null
        return SealingKeys(
            senderX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(senderWords),
            senderEd25519PublicKeyHex = senderIdentity.yggNodePublicKeyHex,
            peerX25519PublicKeyHex = peerKey
        )
    }

    /** Null means the conversation has no usable peer key, so nothing is sealed. */
    suspend fun sealForConversation(
        conv: ConversationEntity,
        plainText: String,
        ratchetStep: Int = 0,
        sealedSender: Boolean = true
    ): String? {
        val keys = resolveSealingKeys(conv) ?: return null
        return YggCryptoEngine.sealConversationEnvelope(
            plainText = plainText,
            senderX25519PrivateKey = keys.senderX25519PrivateKey,
            senderEd25519PublicKeyHex = keys.senderEd25519PublicKeyHex,
            peerX25519PublicKeyHex = keys.peerX25519PublicKeyHex,
            keyEpoch = 1,
            ratchetStep = ratchetStep,
            sealedSender = sealedSender
        )
    }

    suspend fun conversationHasEndToEndKey(chatId: String): Boolean =
        dao.getConversationById(chatId)?.let { resolveSealingKeys(it) != null } ?: false

    /**
     * Everything one PROFILE frame needs for one dialog: the body sealed to that peer's key,
     * the version stamped on the last edit, and the picture the hash refers to.
     *
     * Returns null when the chat has no key to seal under. There is no unsealed form of this
     * frame — a profile that anybody could write is not a profile, it is a way for a stranger to
     * pick the name shown in the user's chat list.
     */
    suspend fun profileOfferFor(
        chatId: String,
        profileId: String
    ): ProfileOffer? {
        val conv = dao.getConversationById(chatId) ?: return null
        val profile = dao.getProfileById(profileId) ?: return null
        if (profile.profileVersion <= 0L) return null
        val plain = YggProfilePayload.encode(
            YggProfilePayload.ProfileBody(
                displayName = profile.displayName,
                handle = profile.handle,
                statusText = profile.socialStatus,
                avatarHashHex = profile.avatarHashHex,
                version = profile.profileVersion
            )
        )
        val envelope = sealForConversation(conv, plain) ?: return null
        return ProfileOffer(
            envelope = envelope,
            version = profile.profileVersion,
            avatarHashHex = profile.avatarHashHex,
            avatarBase64 = YggAvatarCodec.base64OfStoredFile(profile.avatarUri).orEmpty()
        )
    }

    suspend fun directDialogsWithSealedKey(ownerProfileId: String): List<ConversationEntity> =
        dao.getAllConversationsNow().filter {
            it.ownerProfileId == ownerProfileId &&
                !isSelfChatId(it.chatId) &&
                it.chatType != "GROUP" &&
                it.yggIpv6Address.isNotBlank() &&
                resolveSealingKeys(it) != null
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
        voiceWaveformCsv: String? = null,
        replyToMessageId: String = "",
        forwardedFrom: String = ""
    ): MessageEntity? {
        val conv = dao.getConversationById(chatId) ?: return null
        val now = System.currentTimeMillis()
        val step = ((now / 1000L) % 900).toInt() + 1
        val useSealed = sealedSenderGlobal || conv.sealedSenderEnabled

        val cipherHex = sealForConversation(
            conv = conv,
            plainText = plainText,
            ratchetStep = step,
            sealedSender = useSealed
        ) ?: return null
        val powToken = YggCryptoEngine.computeHashcashStamp(
            recipientIpv6 = conv.yggIpv6Address,
            payloadHashHex = cipherHex,
            difficultyBits = powDifficultyBits
        )
        val initialDelivery = "QUEUED_OFFLINE_SF"
        // The manifest is built from the bytes actually on disk, so the ledger and the bubble
        // quote a real SHA-256 Merkle root over real 12 KiB pieces of this file.
        val outgoingProfile = attachmentLocalFile(attachmentUri)?.let {
            YggCryptoEngine.pieceProfileOf(it)
        }
        val merkleRoot = if (attachmentName != null) outgoingProfile?.merkleRootHex else null

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
            reactionsJson = "",
            replyToMessageId = replyToMessageId,
            forwardedFrom = forwardedFrom,
            envelopeVersion = 2
        )
        dao.insertMessage(message)

        if (attachmentName != null && outgoingProfile != null && attachmentType != "VOICE") {
            val transfer = P2PFileTransferEntity(
                transferId = "tx_out_${message.messageId}",
                chatId = chatId,
                peerName = conv.title,
                peerIpv6 = conv.yggIpv6Address,
                fileName = attachmentName,
                fileSizeBytes = outgoingProfile.totalBytes,
                chunkCount = outgoingProfile.pieceCount,
                completedChunks = if (isOfflineMode) 0 else outgoingProfile.pieceCount,
                merkleRootSha256 = outgoingProfile.merkleRootHex,
                // Measured by the piece transport when the file really moves; nothing is
                // reported before it happened.
                speedKbps = 0,
                status = if (isOfflineMode) "QUEUED_OFFLINE" else "VERIFIED_LOCAL",
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

    suspend fun getMessage(messageId: String): MessageEntity? = dao.getMessageById(messageId)

    /**
     * Enforces the per-chat disappearing timer: every message older than the chat's TTL is
     * dropped from the database together with its local attachment file. Callers must run this
     * on an IO dispatcher. Returns how many messages were removed.
     */
    suspend fun purgeExpiredDisappearingMessages(): Int {
        val cutoffBase = System.currentTimeMillis()
        var removed = 0
        for (conv in dao.getAllConversationsNow()) {
            val ttlSeconds = conv.disappearingTtlSeconds
            if (ttlSeconds <= 0) continue
            val expired = dao.getExpiredMessages(conv.chatId, cutoffBase - ttlSeconds * 1000L)
            if (expired.isEmpty()) continue
            expired.forEach { msg ->
                val uri = msg.attachmentUri ?: return@forEach
                // Only files this app wrote itself may be deleted; content:// rows belong to the provider.
                if (uri.startsWith("file://")) {
                    Uri.parse(uri).path?.let { File(it).delete() }
                }
            }
            dao.deleteMessagesByIds(expired.map { it.messageId })
            removed += expired.size
            // The chat list keeps its own preview copy: without this the text of a
            // disappeared message would stay visible in the conversation list forever.
            refreshConversationPreview(conv)
        }
        return removed
    }

    data class LocalDeletion(
        val chatId: String,
        val wireMessageId: String,
        val wasOutgoing: Boolean,
        val freedBytes: Long
    )

    data class PeerDeletion(
        val chatId: String,
        val freedBytes: Long
    )

    data class ChatClearOutcome(
        val messagesRemoved: Int,
        val freedBytes: Long
    )

    /**
     * Removes a message from this device only: the row, the file this app wrote for it, and a
     * tombstone so a retransmitted copy cannot bring it back. When [peerWasAsked] the tombstone
     * stays unconfirmed until the peer's ACK arrives, which is what lets the UI say
     * «удалено у собеседника» instead of guessing. Returns the bytes actually freed on disk.
     */
    suspend fun deleteMessageForMe(
        messageId: String,
        wireMessageId: String,
        deleteId: String,
        peerIpv6: String,
        peerWasAsked: Boolean
    ): LocalDeletion? {
        val msg = dao.getMessageById(messageId) ?: return null
        val targetWireId = wireMessageId.ifBlank {
            if (msg.isOutgoing) msg.messageId else msg.peerMessageId
        }
        if (targetWireId.isBlank()) return null
        val freed = removeMessageRow(msg)
        dao.upsertMessageDeletion(
            MessageDeletionEntity(
                chatId = msg.chatId,
                wireMessageId = targetWireId,
                deleteId = deleteId,
                peerIpv6 = peerIpv6,
                requestedAt = System.currentTimeMillis(),
                localOnly = !peerWasAsked
            )
        )
        dao.getConversationById(msg.chatId)?.let { refreshConversationPreview(it) }
        return LocalDeletion(msg.chatId, targetWireId, msg.isOutgoing, freed)
    }

    /**
     * An outgoing delete request travels as a proof envelope, so it needs the same keys an
     * ordinary message needs. Null means this device cannot prove authorship to that peer at
     * all, and the caller must keep the deletion local rather than claim it was sent.
     */
    suspend fun sealDeleteProof(
        conv: ConversationEntity,
        deleteId: String,
        wireMessageId: String
    ): String? = sealForConversation(
        conv = conv,
        plainText = YggCryptoEngine.buildDeleteProofText(deleteId, wireMessageId)
    )

    /**
     * Applies a delete-for-everyone request that came over the wire. Two checks decide it:
     * the message has to be a received copy authored by the claimed address, and [proofEnvelope]
     * has to open under this device's key with the same X25519 key that authenticated that
     * message. Without the second check the address in a frame is only a claim, and any node
     * that guessed a message id could rewrite somebody else's history. A repeat request is
     * answered as already-applied rather than as a fresh delete.
     */
    suspend fun applyPeerMessageDeletion(
        wireMessageId: String,
        senderIpv6: String,
        deleteId: String,
        proofEnvelope: String
    ): PeerDeletion? {
        if (wireMessageId.isBlank() || senderIpv6.isBlank() || proofEnvelope.isBlank()) return null
        val msg = dao.getMessageByWireId(wireMessageId) ?: return null
        if (msg.isOutgoing) return null
        if (!sameMeshAddress(msg.senderIpv6, senderIpv6)) return null
        val conv = dao.getConversationById(msg.chatId) ?: return null
        val authorKey = msg.authorX25519PubHex.ifBlank { conv.peerX25519PublicKeyHex }
            .trim().lowercase()
        if (!YggCryptoEngine.isUsablePeerX25519PublicKey(authorKey)) return null
        val recipientWords = dao.getAllProfilesNow()
            .find { it.profileId == conv.ownerProfileId }
            ?.mnemonicCsv?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        if (recipientWords.isEmpty()) return null
        val proof = try {
            val opened = YggCryptoEngine.openConversationEnvelope(
                envelope = proofEnvelope,
                recipientX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(recipientWords),
                senderEd25519PublicKeyHex = null
            )
            val parsed = YggCryptoEngine.parseDeleteProofText(opened.plainText)
            if (parsed == null || parsed.wireMessageId != wireMessageId || parsed.deleteId != deleteId) {
                null
            } else if (opened.senderX25519PublicKeyHex.lowercase() != authorKey) {
                null
            } else parsed
        } catch (_: Exception) {
            null
        } ?: return null
        val freed = removeMessageRow(msg)
        dao.upsertMessageDeletion(
            MessageDeletionEntity(
                chatId = msg.chatId,
                wireMessageId = wireMessageId,
                deleteId = proof.deleteId,
                peerIpv6 = senderIpv6,
                requestedAt = System.currentTimeMillis(),
                // Nothing to retry here: the author is the one who asked.
                localOnly = true
            )
        )
        dao.getConversationById(msg.chatId)?.let { refreshConversationPreview(it) }
        return PeerDeletion(msg.chatId, freed)
    }

    /**
     * A deletion tombstone is the durable half of the duplicate guard. The bridge's in-RAM
     * frame-id set is lost on restart and expires after ten minutes, so a sender retry
     * arriving later would otherwise re-insert a message the user already removed.
     */
    suspend fun isMessageDeleted(chatId: String, wireMessageId: String): Boolean =
        wireMessageId.isNotBlank() && dao.getMessageDeletion(chatId, wireMessageId) != null

    suspend fun getPendingDeletions(): List<MessageDeletionEntity> = dao.getPendingDeletions()

    suspend fun markDeletionConfirmed(chatId: String, wireMessageId: String) {
        dao.markDeletionConfirmed(chatId, wireMessageId, System.currentTimeMillis())
    }

    suspend fun bumpDeletionAttempts(chatId: String, wireMessageId: String) {
        dao.bumpDeletionAttempts(chatId, wireMessageId)
    }

    /**
     * Clears a chat on this device: every message row, every file this app wrote for them,
     * the transfer ledger and the deletion tombstones, plus the preview the chat list shows.
     * Returns the message count and the bytes really removed from disk.
     */
    suspend fun clearChat(chatId: String): ChatClearOutcome {
        val conv = dao.getConversationById(chatId) ?: return ChatClearOutcome(0, 0L)
        val withFiles = dao.getChatMessagesWithFiles(chatId)
        var freed = 0L
        withFiles.forEach { msg ->
            val uri = msg.attachmentUri ?: return@forEach
            // Only files this app wrote itself may be deleted; content:// rows belong to the provider.
            if (uri.startsWith("file://")) {
                Uri.parse(uri).path?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        val size = file.length()
                        if (file.delete()) freed += size
                    }
                }
            }
        }
        val cleared = dao.clearMessagesAndGetCount(chatId)
        dao.clearFileTransfersInChat(chatId)
        dao.clearDeletionsForChat(chatId)
        dao.upsertConversation(
            conv.copy(
                lastMessagePreview = "Нет сообщений • Начните диалог",
                unreadCount = 0
            )
        )
        return ChatClearOutcome(cleared, freed)
    }

    /**
     * What this app actually holds on disk, measured from the files rather than from the
     * database counters, so the numbers in Профиль cannot drift away from reality.
     */
    data class StorageBreakdown(
        val attachmentsBytes: Long,
        val voiceBytes: Long,
        val avatarBytes: Long,
        val databaseBytes: Long,
        val cacheBytes: Long,
        val updateBytes: Long
    ) {
        val totalBytes: Long
            get() = attachmentsBytes + voiceBytes + avatarBytes + databaseBytes + cacheBytes + updateBytes
    }

    suspend fun storageBreakdown(): StorageBreakdown {
        val context = appContext ?: return StorageBreakdown(0L, 0L, 0L, 0L, 0L, 0L)
        val dbFile = context.getDatabasePath(YggDatabase.DATABASE_NAME)
        return StorageBreakdown(
            attachmentsBytes = directoryBytes(File(context.filesDir, "ygg_attachments")),
            voiceBytes = directoryBytes(File(context.filesDir, "voice_notes")),
            avatarBytes = directoryBytes(File(context.filesDir, "ygg_avatars")),
            databaseBytes = dbFile.length(),
            cacheBytes = directoryBytes(context.cacheDir),
            // The updater keeps its downloaded APK under files/update, not in the cache dir,
            // so leaving it out of the card would understate what the app really holds.
            updateBytes = directoryBytes(File(context.filesDir, "update"))
        )
    }

    /**
     * Deletes orphaned attachment files — every file this app wrote that no remaining message
     * row points at. Rows are authoritative, so a file whose row was cleared first is garbage.
     */
    suspend fun purgeOrphanedAttachmentFiles(): Long {
        val context = appContext ?: return 0L
        val referenced = dao.getAllAttachmentUris().toSet()
        var freed = 0L
        listOf(File(context.filesDir, "ygg_attachments"), File(context.filesDir, "voice_notes"))
            .forEach { dir ->
                val children = dir.listFiles() ?: return@forEach
                children.forEach { file ->
                    // A «.part» is scratch space of an inbound stream that may still be running;
                    // no message row can vouch for it. The bridge deletes every leftover when it
                    // starts, because a stream never outlives the process that owned it.
                    if (file.name.startsWith("stream_") && file.name.endsWith(".part")) return@forEach
                    val uri = Uri.fromFile(file).toString()
                    if (!referenced.contains(uri)) {
                        val size = file.length()
                        if (file.delete()) freed += size
                    }
                }
            }
        return freed
    }

    /** The app's own readable copy of an attachment Uri, or null when it is not a local file. */
    private fun attachmentLocalFile(uri: String?): File? {
        val raw = uri?.takeIf { it.startsWith("file://") } ?: return null
        val path = try {
            Uri.parse(raw).path
        } catch (_: Throwable) {
            null
        } ?: return null
        val file = File(path)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /**
     * AEAD key for one streamed file: X25519 with this profile's own secret and the peer key
     * pinned in the chat, stretched with the transfer id. Null when the chat has no pinned peer
     * key — without it the pieces could not be authenticated, so the file is neither sent nor
     * accepted. Every piece is a separate AEAD message, so a piece from any other node fails to
     * open and the stream is dropped instead of written.
     */
    suspend fun fileTransferKey(profileId: String, chatId: String, transferId: String): ByteArray? {
        val conv = dao.getConversationById(chatId) ?: return null
        val ownerProfileId = conv.ownerProfileId.ifBlank { profileId }
        val words = dao.getAllProfilesNow()
            .find { it.profileId == ownerProfileId }
            ?.mnemonicCsv?.split(" ")?.filter { it.isNotBlank() }
            .orEmpty()
        if (words.isEmpty()) return null
        val peer = conv.peerX25519PublicKeyHex.trim().lowercase()
        if (!YggCryptoEngine.isUsablePeerX25519PublicKey(peer)) return null
        return try {
            YggCryptoEngine.deriveFileTransferKey(
                localX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(words),
                peerX25519PublicKeyHex = peer,
                transferId = transferId
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Local dialog an inbound file stream belongs to. The chatId inside the frame is a row on the
     * sender's device and means nothing here, while the per-transfer AEAD key can only be derived
     * from the pinned peer key of a real local conversation — so the same (owner + mesh address)
     * rule that routes incoming messages is reused, with the authenticated node key as fallback.
     */
    suspend fun incomingDialogChatId(
        profileId: String,
        senderPubKeyHex: String,
        senderIpv6: String
    ): String? {
        val allProfiles = dao.getAllProfilesNow()
        val ownerProfileId = profileId.takeIf { id -> allProfiles.any { it.profileId == id } }
            ?: DEFAULT_PROFILE_ID
        val cleanIpv6 = senderIpv6.trim()
        val nodeKey = senderPubKeyHex.trim().lowercase()
        return dao.getAllConversationsNow().find {
            it.ownerProfileId == ownerProfileId &&
                !isSelfChatId(it.chatId) &&
                it.chatType != "GROUP" &&
                ((cleanIpv6.isNotBlank() && sameMeshAddress(it.yggIpv6Address, cleanIpv6)) ||
                    (nodeKey.isNotBlank() && it.publicKeyHex.trim().lowercase() == nodeKey))
        }?.chatId
    }

    /**
     * Writes the measured state of an outbound streamed transfer into the ledger and the message
     * row. Only pieces the peer has acknowledged are counted, and the speed is the wall clock of
     * that run — nothing is reported that did not happen.
     */
    suspend fun recordFileTransferProgress(progress: FileTransferProgress) {
        if (!progress.isOutgoing) return
        val pieceBytes = YggCryptoEngine.FILE_PIECE_BYTES.toLong()
        val chunkCount = ((progress.totalBytes + pieceBytes - 1) / pieceBytes).toInt().coerceAtLeast(1)
        val doneChunks = (progress.doneBytes / pieceBytes).toInt().coerceAtMost(chunkCount)
        val speedKbps = if (progress.elapsedMs > 300L && progress.doneBytes > 0L) {
            (progress.doneBytes * 8.0 / 1024.0 / (progress.elapsedMs / 1000.0))
                .toLong()
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            0
        }
        val existing = dao.getFileTransferById("tx_out_${progress.transferId}")
        val conv = dao.getConversationById(progress.chatId)
        val transfer = existing ?: P2PFileTransferEntity(
            transferId = "tx_out_${progress.transferId}",
            chatId = progress.chatId,
            peerName = conv?.title ?: "Диалог",
            peerIpv6 = conv?.yggIpv6Address ?: "",
            fileName = progress.fileName,
            fileSizeBytes = progress.totalBytes,
            chunkCount = chunkCount,
            completedChunks = doneChunks,
            merkleRootSha256 = progress.merkleRootHex,
            speedKbps = speedKbps,
            status = progress.state,
            isOutgoing = true,
            protocol = "YGG_OVERLAY_STREAM",
            timestamp = System.currentTimeMillis()
        )
        dao.upsertFileTransfer(
            transfer.copy(
                chunkCount = chunkCount,
                completedChunks = doneChunks,
                fileSizeBytes = progress.totalBytes,
                merkleRootSha256 = progress.merkleRootHex.ifBlank { transfer.merkleRootSha256 },
                speedKbps = speedKbps,
                status = progress.state
            )
        )
        val fraction = if (chunkCount > 0) doneChunks.toFloat() / chunkCount else 0f
        dao.updateMessageTransferProgress(progress.transferId, fraction)
    }

    private suspend fun removeMessageRow(msg: MessageEntity): Long {
        var freed = 0L
        val uri = msg.attachmentUri
        if (uri != null && uri.startsWith("file://")) {
            Uri.parse(uri).path?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val size = file.length()
                    if (file.delete()) freed += size
                }
            }
        }
        dao.deleteMessagesByIds(listOf(msg.messageId))
        return freed
    }

    private suspend fun refreshConversationPreview(conv: ConversationEntity) {
        val newest = dao.getNewestMessage(conv.chatId)
        dao.upsertConversation(
            conv.copy(
                lastMessagePreview = when {
                    newest == null -> "Нет сообщений • Начните диалог"
                    newest.plainText.isNotBlank() -> newest.plainText
                    else -> "📎 ${newest.attachmentName ?: "Вложение"}"
                },
                lastTimestamp = newest?.timestamp ?: conv.lastTimestamp
            )
        )
    }

    private fun directoryBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            children.forEach { child ->
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    /**
     * Applies a reaction event received from a peer. The wire id may be this device's
     * local id (peer reacted to our outgoing message) or the peer's original id stored
     * in [MessageEntity.peerMessageId] (peer reacted to their own outgoing message).
     */
    suspend fun applyPeerReaction(
        wireMessageId: String,
        emoji: String,
        added: Boolean
    ): MessageEntity? {
        val reactionEmoji = normalizeReactionEmoji(emoji)
        if (wireMessageId.isBlank() || reactionEmoji.isBlank()) return null
        val msg = dao.getMessageByWireId(wireMessageId) ?: return null
        val currentMap = parseReactionsMap(msg.reactionsJson).toMutableMap()
        val existing = currentMap[reactionEmoji]
        if (added) {
            currentMap[reactionEmoji] = if (existing == null) 1 to false else (existing.first + 1) to existing.second
        } else if (existing != null) {
            val newCount = (existing.first - 1).coerceAtLeast(0)
            if (newCount == 0) currentMap.remove(reactionEmoji) else currentMap[reactionEmoji] = newCount to existing.second
        }
        val encoded = serializeReactionsMap(currentMap)
        dao.updateMessageReactions(msg.messageId, encoded)
        return msg.copy(reactionsJson = encoded)
    }

    suspend fun toggleReactionOnMessage(
        messageId: String,
        emoji: String,
        byMe: Boolean
    ): MessageEntity? {
        val msg = dao.getMessageById(messageId) ?: return null
        val reactionEmoji = normalizeReactionEmoji(emoji)
        if (reactionEmoji.isBlank()) return null
        val currentMap = parseReactionsMap(msg.reactionsJson).toMutableMap()
        val existing = currentMap[reactionEmoji]
        if (existing != null) {
            if (byMe) {
                if (existing.second) {
                    val newCount = (existing.first - 1).coerceAtLeast(0)
                    if (newCount == 0) currentMap.remove(reactionEmoji) else currentMap[reactionEmoji] = newCount to false
                } else {
                    currentMap[reactionEmoji] = (existing.first + 1) to true
                }
            } else {
                currentMap[reactionEmoji] = (existing.first + 1) to existing.second
            }
        } else {
            currentMap[reactionEmoji] = 1 to byMe
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

        /**
         * The stored form is `emoji:count:tag`, so a separator inside an emoji would split one
         * reaction into two rows. A peer sends the emoji over the wire unfiltered, so the token
         * is cleaned here rather than trusted: separators and control characters are dropped and
         * a runaway label cannot grow into the stored string. The limit counts UTF-16 units, so
         * a cut can land inside an emoji pair; the trailing half is dropped rather than stored,
         * because a lone surrogate would render as a box and no later tap could match it back.
         */
        fun normalizeReactionEmoji(raw: String): String {
            val cleaned = raw.filter { !":|\u0000".contains(it) && !Character.isISOControl(it) }.trim()
            return cleaned.take(REACTION_EMOJI_MAX_CODEPOINTS * 4).trimEnd { it.isHighSurrogate() }
        }

        private const val REACTION_EMOJI_MAX_CODEPOINTS = 8
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

    /**
     * A frame handed to the overlay is not delivery. Without an ACK the message goes back
     * to the store-and-forward queue instead of freezing at «Ожидает доставки» until the
     * user taps «Повторить»; the receiver dedupes by wire message id, so the resend is safe.
     * False means the message was never awaiting an ACK, so its own reason stays on screen.
     */
    suspend fun requeueUnacknowledged(messageId: String): Boolean =
        messageId.isNotBlank() && dao.requeueIfAwaitingAck(messageId) > 0

    suspend fun getUnreadIncomingWireIds(chatId: String): List<String> =
        if (chatId.isBlank()) emptyList() else dao.getUnreadIncomingPeerIds(chatId)

    suspend fun markIncomingMessagesRead(chatId: String) {
        if (chatId.isNotBlank()) dao.markIncomingMessagesRead(chatId)
    }

    suspend fun markOutgoingMessageRead(wireMessageId: String) {
        if (wireMessageId.isNotBlank()) dao.markOutgoingMessageRead(wireMessageId)
    }

    /**
     * A message addressed to the own node stays on the device: a Yggdrasil node
     * cannot address itself inside the 0200::/7 overlay, so the note is stored
     * locally instead of pretending to be an acknowledged delivery.
     */
    suspend fun markMessageLocalNote(messageId: String) {
        if (messageId.isNotBlank()) {
            dao.updateMessageDeliveryState(messageId, "LOCAL_NOTE")
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
                allProfiles.find { sameMeshAddress(it.yggIpv6Address, packet.recipientIpv6) }?.profileId
                    ?: currentPrimaryProfileId
            }
            else -> currentPrimaryProfileId
        }

        val allConvs = dao.getAllConversationsNow()
        var conv: ConversationEntity? = null

        // A group frame belongs to the group, never to whoever happened to relay it. This
        // has to be resolved before the sender-address match below, otherwise every member
        // that speaks in a group would silently open a separate 1:1 chat with us.
        if (packet.groupId.isNotBlank()) {
            conv = dao.getGroupConversation(packet.groupId, targetOwnerProfileId)
                ?: run {
                    val ownerAddr = allProfiles.find { it.profileId == targetOwnerProfileId }
                        ?.yggIpv6Address.orEmpty()
                    val created = ConversationEntity(
                        chatId = "chat_${targetOwnerProfileId}_${packet.groupId}",
                        title = packet.groupTitle.trim().ifBlank { "Группа ${packet.groupId.take(8)}" },
                        yggIpv6Address = ownerAddr,
                        publicKeyHex = "",
                        protocolType = "YGG_X25519_AESGCM",
                        isVerified = false,
                        sealedSenderEnabled = true,
                        disappearingTtlSeconds = 0,
                        unreadCount = 0,
                        lastMessagePreview = "",
                        lastTimestamp = packet.timestamp,
                        isOnlineInMesh = true,
                        avatarColorHex = "#10B981",
                        bioStatus = "Группа в оверлее Yggdrasil",
                        routeTransportSummary = "Yggdrasil Overlay 0200::/7 (${packet.rttMs} мс)",
                        ownerProfileId = targetOwnerProfileId,
                        chatType = "GROUP",
                        groupId = packet.groupId
                    )
                    dao.upsertConversation(created)
                    created
                }
        }

        // If this is a self-echo packet in a self-chat, match the exact targetChatId
        if (conv == null && packet.targetChatId.isNotBlank() && isSelfChatId(packet.targetChatId)) {
            conv = dao.getConversationById(packet.targetChatId)
        }

        // Otherwise match by (ownerProfileId + senderIpv6) so each profile gets its own dialog with the sender!
        if (conv == null) {
            conv = allConvs.find {
                it.ownerProfileId == targetOwnerProfileId &&
                    !isSelfChatId(it.chatId) &&
                    it.chatType != "GROUP" &&
                    sameMeshAddress(it.yggIpv6Address, packet.senderIpv6)
            }
        }

        // Decrypt the sealed envelope with the recipient profile's X25519 key. A v2 envelope's
        // sender key ("sx") is implicitly authenticated by successful AEAD verification, so it
        // is safe to pin as the peer's X25519 public key for future outgoing envelopes.
        val recipientWords = allProfiles.find { it.profileId == targetOwnerProfileId }
            ?.mnemonicCsv?.split(" ")?.filter { it.isNotBlank() }.orEmpty()
        val recipientXPriv = YggCryptoEngine.x25519PrivateKeyFromMnemonic(recipientWords)
            .takeIf { recipientWords.isNotEmpty() }
        var resolvedPlainText = ""
        var authenticatedSenderX25519Pub = ""
        var resolvedEnvelopeVersion = 1
        if (recipientXPriv != null && packet.cipherPayloadHex.isNotBlank()) {
            try {
                val opened = YggCryptoEngine.openConversationEnvelope(
                    envelope = packet.cipherPayloadHex,
                    recipientX25519PrivateKey = recipientXPriv,
                    senderEd25519PublicKeyHex = packet.senderPubKeyHex.takeIf { it.isNotBlank() }
                )
                resolvedPlainText = opened.plainText
                authenticatedSenderX25519Pub = opened.senderX25519PublicKeyHex
                resolvedEnvelopeVersion = opened.version
            } catch (e: Exception) {
                // Envelope did not verify under this profile's key; fall back to legacy text below.
                Log.w(
                    "YggGramCrypto",
                    "Envelope ${packet.messageId.take(16)} rejected: " +
                        "${e.javaClass.simpleName}: ${e.message} • wireSender=${packet.senderPubKeyHex.take(16)}"
                )
            }
        }
        if (resolvedPlainText.isBlank()) {
            // An envelope that did not verify under this profile's key is never replaced by
            // the plaintext a frame might carry: accepting it would let anyone write words
            // into this chat under someone else's name.
            resolvedPlainText = "[Сообщение не расшифровано: нет ключа собеседника]"
        }

        // Attachment bytes (photos, files, voice notes) are sealed exactly like text. An
        // envelope that does not verify under this profile's key is discarded instead of
        // being written to disk, so nothing unauthenticated becomes playable.
        var attachmentPayload = packet.attachmentBase64.orEmpty()
        if (attachmentPayload.isNotBlank()) {
            attachmentPayload = recipientXPriv?.let { key ->
                try {
                    YggCryptoEngine.openConversationEnvelope(
                        envelope = attachmentPayload,
                        recipientX25519PrivateKey = key,
                        senderEd25519PublicKeyHex = packet.senderPubKeyHex.takeIf { it.isNotBlank() }
                    ).plainText
                } catch (e: Exception) {
                    Log.w(
                        "YggGramCrypto",
                        "Attachment ${packet.messageId.take(16)} rejected: " +
                            "${e.javaClass.simpleName}: ${e.message} • wireSender=${packet.senderPubKeyHex.take(16)}"
                    )
                    ""
                }
            }.orEmpty()
        }

        if (conv == null) {
            val newChatId = "chat_${targetOwnerProfileId}_${packet.senderIpv6.hashCode().toUInt()}"
            val senderProfile = allProfiles.find { sameMeshAddress(it.yggIpv6Address, packet.senderIpv6) }
            val created = ConversationEntity(
                chatId = newChatId,
                title = packet.senderNodeLabel.ifBlank { "Пир ${packet.senderIpv6.take(16)}" },
                yggIpv6Address = senderProfile?.yggIpv6Address ?: packet.senderIpv6,
                publicKeyHex = senderProfile?.yggPublicKeyHex
                    ?: packet.senderPubKeyHex.trim().lowercase().takeIf {
                        isEd25519Hex(it) && YggCryptoEngine.addressMatchesEd25519Key(packet.senderIpv6, it)
                    }
                    ?: "",
                protocolType = "YGG_X25519_AESGCM",
                isVerified = false,
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

        // The bridge's in-RAM frame-id set forgets everything on process restart and stops
        // remembering after ten minutes, so a sender retry arriving then would be inserted
        // a second time. The message store is the durable list of what this device already
        // accepted, and the frame's wire id is the only identity both sides agree on.
        if (packet.messageId.isNotBlank()) {
            val alreadyStored = dao.getIncomingCopyByWireId(conv.chatId, packet.messageId)
            if (alreadyStored != null) return alreadyStored.chatId
            // A retransmitted copy of a message that was already deleted — locally or by the
            // author's delete-for-everyone request — must not resurrect it. The tombstone is
            // the only durable record of that refusal once the in-RAM frame set expires.
            if (dao.getMessageDeletion(conv.chatId, packet.messageId) != null) return conv.chatId
        }

        val resolvedAttachmentUri = if (appContext != null && (packet.attachmentName != null || packet.attachmentType != null)) {
            YggAudioMediaEngine.saveIncomingP2PAttachment(
                context = appContext,
                attachmentName = packet.attachmentName,
                attachmentType = packet.attachmentType,
                attachmentBase64 = attachmentPayload.ifBlank { null },
                fallbackUri = packet.attachmentUri,
                voiceDurationSec = packet.voiceDurationSec,
                voiceWaveformCsv = packet.voiceWaveformCsv
            )
        } else {
            packet.attachmentUri
        }

        // For an inline payload this hashes the bytes this device just saved; for a streamed one
        // the piece transport already verified the same tree piece by piece before the message
        // was acknowledged, so the root it reports is this device's own measurement.
        val receivedProfile = attachmentLocalFile(resolvedAttachmentUri)?.let {
            YggCryptoEngine.pieceProfileOf(it)
        }
        val merkleRoot = if (packet.attachmentName != null) {
            receivedProfile?.merkleRootHex ?: packet.streamedAttachmentRoot.takeIf { it.isNotBlank() }
        } else {
            null
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
            sealedSenderFlag = resolvedEnvelopeVersion >= 2,
            hashcashPowToken = packet.hashcashPowTokenOrDefault(),
            attachmentName = packet.attachmentName,
            attachmentSizeBytes = packet.attachmentSizeBytes,
            merkleRootHex = merkleRoot,
            transferProgress = 1.0f,
            attachmentType = packet.attachmentType,
            attachmentUri = resolvedAttachmentUri,
            voiceDurationSec = packet.voiceDurationSec,
            voiceWaveformCsv = packet.voiceWaveformCsv,
            reactionsJson = "",
            peerMessageId = packet.messageId,
            replyToMessageId = packet.replyToMessageId,
            forwardedFrom = packet.forwardedFrom,
            envelopeVersion = resolvedEnvelopeVersion,
            authorX25519PubHex = authenticatedSenderX25519Pub
        )
        dao.insertMessage(msg)

        if (packet.attachmentName != null && merkleRoot != null && packet.attachmentType != "VOICE") {
            val rxTransfer = P2PFileTransferEntity(
                transferId = "tx_in_${msg.messageId}",
                chatId = conv.chatId,
                peerName = packet.senderNodeLabel,
                peerIpv6 = packet.senderIpv6,
                fileName = packet.attachmentName,
                fileSizeBytes = receivedProfile?.totalBytes ?: msg.attachmentSizeBytes,
                chunkCount = receivedProfile?.pieceCount ?: 0,
                completedChunks = receivedProfile?.pieceCount ?: 0,
                merkleRootSha256 = merkleRoot,
                speedKbps = 0,
                status = if (receivedProfile != null) "VERIFIED_COMPLETE" else "RECEIVED_UNVERIFIED",
                isOutgoing = false,
                protocol = "YGG_OVERLAY_STREAM",
                timestamp = packet.timestamp
            )
            dao.upsertFileTransfer(rxTransfer)
        }

        val isCurrentlyViewing = (targetOwnerProfileId == currentPrimaryProfileId) && (activeChatId == conv.chatId)
        if (conv.chatType == "GROUP") {
            mergeGroupRoster(conv.chatId, packet)
        } else {
            if (authenticatedSenderX25519Pub.isNotBlank() && authenticatedSenderX25519Pub != conv.peerX25519PublicKeyHex) {
                conv = conv.copy(peerX25519PublicKeyHex = authenticatedSenderX25519Pub)
            }
            // An overlay-delivered frame carries the sender's node key authenticated by the
            // Yggdrasil session. A LAN frame only claims it, but pinning is still safe: a key
            // is accepted only when it reproduces the sender's mesh address, and finding a
            // different key for the same 128-bit address is infeasible. This also heals
            // contacts that were added by a bare IPv6 without a key.
            val verifiedSenderKey = packet.senderPubKeyHex.trim().lowercase().takeIf {
                isEd25519Hex(it) && YggCryptoEngine.addressMatchesEd25519Key(packet.senderIpv6, it)
            }
            if (verifiedSenderKey != null && !verifiedSenderKey.equals(conv.publicKeyHex, ignoreCase = true)) {
                conv = conv.copy(publicKeyHex = verifiedSenderKey)
            }
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

    /**
     * Control frames (TYPING/READ) carry the sender's local chatId, which never matches
     * the recipient's conversation id. Resolve the local chat from the peer address instead.
     */
    suspend fun findConversationIdByPeerIpv6(senderIpv6: String, ownerProfileId: String): String? {
        if (senderIpv6.isBlank() || ownerProfileId.isBlank()) return null
        return dao.getAllConversationsNow().firstOrNull {
            it.ownerProfileId == ownerProfileId &&
                !isSelfChatId(it.chatId) &&
                it.chatType != "GROUP" &&
                sameMeshAddress(it.yggIpv6Address, senderIpv6)
        }?.chatId
    }

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
        disappearingSeconds: Int,
        directEndpointHint: String = "",
        localIdentity: DerivedIdentityHierarchy? = null,
        ownerProfileId: String = DEFAULT_PROFILE_ID,
        peerX25519PublicKeyHex: String = "",
        peerEd25519PublicKeyHex: String = ""
    ): String {
        val now = System.currentTimeMillis()
        val rawInput = yggIpv6Input.trim()

        val isContactUri = rawInput.startsWith("ygggram://", ignoreCase = true)
        val extractedAddress = if (isContactUri) {
            rawInput.substringAfter("://").substringBefore("?").trim()
        } else {
            rawInput
        }
        val queryParams = if (isContactUri) {
            rawInput.substringAfter("?", "").split("&").mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx <= 0) null else kv.substring(0, idx).lowercase() to kv.substring(idx + 1).trim()
            }.toMap()
        } else {
            emptyMap()
        }
        // Only a genuine 64-hex Ed25519 key can address a node on the overlay. Older
        // builds hashed the title/address into a placeholder key, which the mesh
        // silently dropped, so no key is ever fabricated here anymore.
        val requestedPubKey = when {
            isEd25519Hex(peerEd25519PublicKeyHex) -> peerEd25519PublicKeyHex.trim().lowercase()
            isContactUri -> queryParams["pub"]?.lowercase()?.takeIf { isEd25519Hex(it) }.orEmpty()
            else -> rawInput.takeIf { isEd25519Hex(it) }?.lowercase().orEmpty()
        }
        val extractedX25519Pub = peerX25519PublicKeyHex.trim().lowercase().ifBlank {
            queryParams["xpub"]?.lowercase()?.takeIf { isEd25519Hex(it) }.orEmpty()
        }
        val extractedLanHint = if (directEndpointHint.isNotBlank()) {
            directEndpointHint.trim()
        } else {
            queryParams["lan"]?.takeIf { it.contains(":") }.orEmpty()
        }

        val allProfilesNow = dao.getAllProfilesNow()
        val localTargetProfile = allProfilesNow.firstOrNull { profile ->
            profile.profileId != ownerProfileId && (
                sameMeshAddress(profile.yggIpv6Address, extractedAddress) ||
                    (requestedPubKey.isNotBlank() && profile.yggPublicKeyHex.trim().equals(requestedPubKey, ignoreCase = true))
                )
        }
        // The overlay addresses a peer by its real Ed25519 node key, so a local profile
        // always wins; for remote peers the key supplied in the URI is authoritative.
        val effectivePubKey = localTargetProfile
            ?.yggPublicKeyHex?.trim()?.lowercase()
            ?.takeIf { isEd25519Hex(it) }
            ?: requestedPubKey

        val extractedIsIpv6 = YggCryptoEngine.normalizeIpv6Literal(extractedAddress) != null
        val derivedAddress = effectivePubKey.takeIf { it.isNotBlank() }?.let { key ->
            val pubBytes = key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            YggCryptoEngine.computeYggdrasilIpv6(pubBytes)
        }
        val cleanIpv6 = when {
            localTargetProfile != null -> localTargetProfile.yggIpv6Address
            effectivePubKey.isNotBlank() -> {
                // The key is authoritative: store the address derived from it in the
                // canonical form the overlay frames carry, even when the user typed an
                // equivalent but differently formatted IPv6 literal.
                when {
                    extractedIsIpv6 -> derivedAddress.orEmpty()
                    extractedAddress.contains(".") && extractedAddress.contains(":") -> extractedAddress
                    else -> derivedAddress.orEmpty()
                }
            }
            extractedAddress.contains(".") && extractedAddress.contains(":") -> extractedAddress
            else -> extractedAddress.ifBlank { "unknown" }
        }
        val keyUnknownForOverlay = localTargetProfile == null && effectivePubKey.isBlank() && extractedIsIpv6

        val ownerProfile = allProfilesNow.find { it.profileId == ownerProfileId }
        val isSelfNode = (
            localIdentity != null && (
                sameMeshAddress(cleanIpv6, localIdentity.yggIpv6Address) ||
                    (requestedPubKey.isNotBlank() && requestedPubKey.equals(localIdentity.yggNodePublicKeyHex, ignoreCase = true))
                )
            ) || (
            ownerProfile != null && (
                sameMeshAddress(cleanIpv6, ownerProfile.yggIpv6Address) ||
                    (requestedPubKey.isNotBlank() && requestedPubKey.equals(ownerProfile.yggPublicKeyHex, ignoreCase = true))
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

        val localTargetXPub = localTargetProfile?.let { profile ->
            profile.mnemonicCsv.split(" ").filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
                YggCryptoEngine.deriveHierarchyFromMnemonic(it).x25519IdentityPubHex
            }
        }.orEmpty()
        val effectivePeerX25519Pub = extractedX25519Pub.ifBlank { localTargetXPub }

        // Reuse existing conversation if this profile already has a chat with cleanIpv6
        // or with the same real node key (the stored address may be stale).
        val existingForProfile = dao.getAllConversationsNow().find {
            it.ownerProfileId == ownerProfileId &&
                !isSelfChatId(it.chatId) &&
                (sameMeshAddress(it.yggIpv6Address, cleanIpv6) ||
                    (effectivePubKey.isNotBlank() && it.publicKeyHex.equals(effectivePubKey, ignoreCase = true)))
        }
        if (existingForProfile != null) {
            var repaired = existingForProfile
            // The card is authored by the node owner, so a re-import is their rename.
            // Without this the chat keeps the name from the first card and the second
            // import looks like it silently did nothing.
            if (title.isNotBlank() && existingForProfile.title != title.trim()) {
                repaired = repaired.copy(title = title.trim())
            }
            if (!sameMeshAddress(existingForProfile.yggIpv6Address, cleanIpv6)) {
                repaired = repaired.copy(yggIpv6Address = cleanIpv6)
            }
            if (effectivePeerX25519Pub.isNotBlank() && existingForProfile.peerX25519PublicKeyHex != effectivePeerX25519Pub) {
                repaired = repaired.copy(peerX25519PublicKeyHex = effectivePeerX25519Pub)
            }
            // Never overwrite a pinned real key with an address-only re-add; only a
            // supplied 64-hex key may replace it.
            if (effectivePubKey.isNotBlank() && !existingForProfile.publicKeyHex.equals(effectivePubKey, ignoreCase = true)) {
                repaired = repaired.copy(publicKeyHex = effectivePubKey)
            }
            if (repaired != existingForProfile) {
                dao.upsertConversation(repaired)
            }
            if (extractedLanHint.isNotBlank() && !extractedLanHint.startsWith("127.0.0.1:")) {
                dao.upsertConversation(
                    repaired.copy(
                        bioStatus = extractedLanHint,
                        routeTransportSummary = "Direct P2P ($extractedLanHint)",
                        isOnlineInMesh = true
                    )
                )
            } else if (existingForProfile.bioStatus.startsWith("127.0.0.1:") ||
                existingForProfile.routeTransportSummary.contains("127.0.0.1:") ||
                existingForProfile.bioStatus.contains("внутренний P2P-мост") ||
                existingForProfile.routeTransportSummary.contains("внутренний P2P-мост")
            ) {
                dao.upsertConversation(
                    repaired.copy(
                        bioStatus = "Диалог между профилями устройства — доставка через интернет-оверлей Yggdrasil 0200::/7",
                        routeTransportSummary = "Yggdrasil 0200::/7 Overlay (интернет)"
                    )
                )
            }
            return existingForProfile.chatId
        }

        val effectiveHint = extractedLanHint.takeUnless { it.startsWith("127.0.0.1:") }.orEmpty()
        val endpointSummary = when {
            localTargetProfile != null -> "Yggdrasil 0200::/7 Overlay (интернет)"
            effectiveHint.isNotBlank() -> "Direct P2P ($effectiveHint)"
            extractedAddress.contains(".") && extractedAddress.contains(":") -> "Direct P2P ($extractedAddress)"
            keyUnknownForOverlay -> "Yggdrasil 0200::/7 • ключ ноды неизвестен"
            else -> "Yggdrasil 0200::/7 Overlay"
        }

        val chatId = "chat_${UUID.randomUUID().toString().take(8)}"
        val conv = ConversationEntity(
            chatId = chatId,
            title = title.trim().ifBlank { "Пир ${cleanIpv6.take(14)}" },
            yggIpv6Address = cleanIpv6,
            publicKeyHex = effectivePubKey,
            protocolType = "YGG_X25519_AESGCM",
            // Only a second profile of this device is verified by construction; a remote
            // contact's key needs the out-of-band safety-number check in the chat inspector.
            isVerified = localTargetProfile != null,
            sealedSenderEnabled = true,
            disappearingTtlSeconds = disappearingSeconds,
            unreadCount = 0,
            lastMessagePreview = "Нет сообщений • Начните диалог",
            lastTimestamp = now,
            isOnlineInMesh = false,
            avatarColorHex = "#10B981",
            bioStatus = when {
                localTargetProfile != null -> "Диалог между профилями устройства — доставка через интернет-оверлей Yggdrasil 0200::/7"
                effectiveHint.isNotBlank() -> effectiveHint
                keyUnknownForOverlay -> "Нужен полный идентификатор ygggram://…?pub=… — без Ed25519-ключа оверлей не доставит сообщения"
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
        preferredSni: String
    ) {
        val all = dao.getAllPeersNow()
        val updated = all.map { p ->
            val newSni = if (whitelistMode && p.isWhitelistCompatible) preferredSni else p.sniDomain
            p.copy(sniDomain = newSni)
        }
        dao.insertPeers(updated)
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

    /**
     * Records an export that already happened on disk. The numbers come from the archive bytes,
     * never from row counts, and isVerified is only true when the read-back archive hashed the
     * same as the database file.
     */
    suspend fun recordDatabaseExport(profile: YggEncryptedExport.ArchiveProfile): BackupSnapshotEntity {
        val entity = BackupSnapshotEntity(
            snapshotId = "bak_${UUID.randomUUID().toString().take(8)}",
            archiveFileName = profile.archiveFileName,
            createdAt = System.currentTimeMillis(),
            sizeBytes = profile.sizeBytes,
            chunkCountFastCdc = profile.pieceCount,
            kdfSpec = profile.keySpec,
            cipherSpec = profile.cipherSpec,
            merkleRootHex = profile.merkleRootHex,
            destination = "SQLCIPHER_DB_FILE_COPY",
            isVerified = profile.verified
        )
        dao.upsertBackupSnapshot(entity)
        return entity
    }

    suspend fun recordSnapshotCheck(snapshot: BackupSnapshotEntity, verified: Boolean) {
        dao.upsertBackupSnapshot(snapshot.copy(isVerified = verified))
    }
}

/** One peer's copy of this device's profile, ready to go on the wire as a PROFILE frame. */
data class ProfileOffer(
    val envelope: String,
    val version: Long,
    val avatarHashHex: String,
    val avatarBase64: String
)
