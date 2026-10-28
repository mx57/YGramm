// File: app/src/main/java/com/example/data/YggDao.kt
package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface YggDao {
    @Query("SELECT * FROM ygg_profiles ORDER BY isCurrentPrimary DESC, createdAt ASC")
    fun observeUserProfiles(): Flow<List<UserProfileEntity>>

    @Query("SELECT * FROM ygg_profiles ORDER BY isCurrentPrimary DESC, createdAt ASC")
    suspend fun getAllProfilesNow(): List<UserProfileEntity>

    @Query("SELECT * FROM ygg_profiles WHERE profileId = :profileId LIMIT 1")
    suspend fun getProfileById(profileId: String): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(profiles: List<UserProfileEntity>)

    @Query("DELETE FROM ygg_profiles WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)

    // Panic-wipe: removes every trace of user data (profiles with mnemonics,
    // chats, messages, keys, transfers, backups) in one transaction.
    @Query("DELETE FROM ygg_profiles")
    suspend fun wipeProfilesTable()

    @Query("DELETE FROM conversations")
    suspend fun wipeConversationsTable()

    @Query("DELETE FROM messages")
    suspend fun wipeMessagesTable()

    @Query("DELETE FROM p2p_file_transfers")
    suspend fun wipeFileTransfersTable()

    @Query("DELETE FROM ygg_peers")
    suspend fun wipePeersTable()

    @Query("DELETE FROM linked_devices")
    suspend fun wipeLinkedDevicesTable()

    @Query("DELETE FROM backup_snapshots")
    suspend fun wipeBackupSnapshotsTable()

    @Query("DELETE FROM crypto_keys")
    suspend fun wipeCryptoKeysTable()

    @Query("DELETE FROM call_log")
    suspend fun wipeCallLogTable()

    @Transaction
    suspend fun wipeAllUserData() {
        wipeMessagesTable()
        wipeConversationsTable()
        wipeFileTransfersTable()
        wipeCallLogTable()
        wipeBackupSnapshotsTable()
        wipeLinkedDevicesTable()
        wipeCryptoKeysTable()
        wipePeersTable()
        wipeProfilesTable()
    }

    @Query("SELECT * FROM conversations ORDER BY lastTimestamp DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE chatId = :chatId LIMIT 1")
    suspend fun getConversationById(chatId: String): ConversationEntity?

    @Query("SELECT * FROM conversations")
    suspend fun getAllConversationsNow(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    // An avatar is written on its own because an incoming frame must not have to re-save
    // the whole conversation to carry one: the re-save path rebuilds the row from a copy
    // and used to drop fields the writer never looked at.
    @Query(
        "UPDATE conversations SET avatarUri = :avatarUri, avatarHashHex = :avatarHashHex " +
            "WHERE chatId = :chatId"
    )
    suspend fun updateConversationAvatar(chatId: String, avatarUri: String, avatarHashHex: String)

    // Same reason as the avatar update above: an incoming profile frame has to rename the
    // dialog without re-saving the row, because the re-save path rebuilds the conversation from
    // a copy and drops whatever the writer never looked at.
    @Query(
        "UPDATE conversations SET title = :title, peerHandle = :peerHandle, " +
            "peerStatusText = :peerStatusText, peerProfileVersion = :peerProfileVersion " +
            "WHERE chatId = :chatId"
    )
    suspend fun updatePeerProfile(
        chatId: String,
        title: String,
        peerHandle: String,
        peerStatusText: String,
        peerProfileVersion: Long
    )

    // A timestamp of the last frame this device actually opened. Deliberately its own partial
    // UPDATE: it moves on every incoming message, and rewriting the whole row for that would
    // wipe fields another writer owns.
    @Query("UPDATE conversations SET peerLastSeenAt = :timestamp WHERE chatId = :chatId")
    suspend fun updatePeerLastSeen(chatId: String, timestamp: Long)

    @Query(
        "UPDATE ygg_profiles SET avatarUri = :avatarUri, avatarHashHex = :avatarHashHex, " +
            "profileVersion = :profileVersion WHERE profileId = :profileId"
    )
    suspend fun updateProfileAvatar(
        profileId: String,
        avatarUri: String,
        avatarHashHex: String,
        profileVersion: Long
    )

    // Group membership is keyed by the member's mesh address, which is the only form of
    // identity both sides of the wire agree on. REPLACE makes roster merging idempotent:
    // the same member advertised twice only refreshes its name.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupMembers(members: List<GroupMemberEntity>)

    @Query(
        "SELECT * FROM conversations WHERE groupId = :groupId AND ownerProfileId = :ownerProfileId " +
            "AND chatType = 'GROUP' LIMIT 1"
    )
    suspend fun getGroupConversation(groupId: String, ownerProfileId: String): ConversationEntity?

    @Query(
        "SELECT * FROM group_members WHERE chatId = :chatId AND removedAt = 0 ORDER BY addedAt ASC"
    )
    suspend fun getGroupMembers(chatId: String): List<GroupMemberEntity>

    @Query(
        "SELECT * FROM group_members WHERE chatId = :chatId AND removedAt = 0 ORDER BY addedAt ASC"
    )
    fun observeGroupMembers(chatId: String): Flow<List<GroupMemberEntity>>

    @Query(
        "SELECT chatId AS chatId, COUNT(*) AS memberCount FROM group_members " +
            "WHERE removedAt = 0 GROUP BY chatId"
    )
    fun observeGroupMemberCounts(): Flow<List<GroupMemberCount>>

    @Query(
        "UPDATE group_members SET removedAt = :removedAt " +
            "WHERE chatId = :chatId AND memberIpv6 = :memberIpv6"
    )
    suspend fun markGroupMemberRemoved(chatId: String, memberIpv6: String, removedAt: Long)

    /**
     * The roster as stored, removals included. Roster merging needs it: a peer keeps
     * advertising an address this device took out of the group, and the merge has to see the
     * flag to keep it down instead of re-adding the member.
     */
    @Query("SELECT * FROM group_members WHERE chatId = :chatId ORDER BY addedAt ASC")
    suspend fun getAllGroupMembersIncludingRemoved(chatId: String): List<GroupMemberEntity>

    @Query("DELETE FROM group_members WHERE chatId = :chatId")
    suspend fun clearGroupMembers(chatId: String)

    /**
     * Recording which members acknowledged a group message. Written as a whole because the
     * caller holds the roster and decides the state: «DELIVERED» is only ever passed here
     * when the set covers every member.
     */
    @Query("UPDATE messages SET deliveredMemberCsv = :deliveredCsv, deliveryState = :deliveryState WHERE messageId = :messageId")
    suspend fun updateMessageGroupDelivery(messageId: String, deliveredCsv: String, deliveryState: String)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE deliveryState = 'QUEUED_OFFLINE_SF'")
    suspend fun getQueuedOfflineMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE messageId = :wireMessageId " +
            "OR (peerMessageId != '' AND peerMessageId = :wireMessageId) " +
            "ORDER BY CASE WHEN messageId = :wireMessageId THEN 0 ELSE 1 END LIMIT 1"
    )
    suspend fun getMessageByWireId(wireMessageId: String): MessageEntity?

    /**
     * Received copies of one wire frame, scoped to a single chat. The bridge keeps a
     * in-RAM set of frame ids, but that set is lost when the process restarts, so the
     * store is the only durable record of what this device already accepted.
     */
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND peerMessageId = :wireMessageId " +
            "AND isOutgoing = 0 LIMIT 1"
    )
    suspend fun getIncomingCopyByWireId(chatId: String, wireMessageId: String): MessageEntity?

    @Query("UPDATE messages SET deliveryState = :newState WHERE messageId = :messageId")
    suspend fun updateMessageDeliveryState(messageId: String, newState: String)

    // Rows touched is what tells the caller whether this message was actually claiming
    // «Ожидает доставки»: a send rejected for having no route must keep that reason.
    @Query("UPDATE messages SET deliveryState = 'QUEUED_OFFLINE_SF' WHERE messageId = :messageId AND deliveryState = 'SENT_P2P'")
    suspend fun requeueIfAwaitingAck(messageId: String): Int

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND timestamp < :beforeTimestamp")
    suspend fun getExpiredMessages(chatId: String, beforeTimestamp: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getNewestMessage(chatId: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND isOutgoing = 0 AND authorX25519PubHex <> '' " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getNewestAuthenticatedIncoming(chatId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE messageId IN (:messageIds)")
    suspend fun deleteMessagesByIds(messageIds: List<String>)

    @Query(
        "UPDATE messages SET deliveryState = 'READ' " +
            "WHERE isOutgoing = 1 AND (messageId = :wireMessageId OR peerMessageId = :wireMessageId)"
    )
    suspend fun markOutgoingMessageRead(wireMessageId: String)

    @Query(
        "SELECT peerMessageId FROM messages " +
            "WHERE chatId = :chatId AND isOutgoing = 0 AND deliveryState != 'READ' AND peerMessageId != ''"
    )
    suspend fun getUnreadIncomingPeerIds(chatId: String): List<String>

    @Query(
        "UPDATE messages SET deliveryState = 'READ' " +
            "WHERE chatId = :chatId AND isOutgoing = 0 AND deliveryState != 'READ'"
    )
    suspend fun markIncomingMessagesRead(chatId: String)

    @Query(
        "SELECT messages.* FROM messages INNER JOIN conversations ON messages.chatId = conversations.chatId " +
            "WHERE conversations.ownerProfileId = :ownerProfileId " +
            "AND messages.plainText LIKE '%' || :query || '%' " +
            "ORDER BY messages.timestamp DESC LIMIT :limit"
    )
    fun searchMessages(
        ownerProfileId: String,
        query: String,
        limit: Int
    ): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM call_log WHERE ownerProfileId = :ownerProfileId " +
            "ORDER BY startedAt DESC LIMIT :limit"
    )
    fun observeCallLog(ownerProfileId: String, limit: Int): Flow<List<CallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCallLog(entry: CallLogEntity)

    @Query("UPDATE messages SET reactionsJson = :reactionsJson WHERE messageId = :messageId")
    suspend fun updateMessageReactions(messageId: String, reactionsJson: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearMessagesInChat(chatId: String)

    /**
     * Message deletion and chat clearing. A tombstone row is what keeps a retransmitted
     * copy of a deleted message from coming back: the overlay can hand the same frame over
     * again after a lost ACK, and only this table remembers that we already refused it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessageDeletion(entry: MessageDeletionEntity)

    @Query(
        "SELECT * FROM message_deletions WHERE chatId = :chatId AND wireMessageId = :wireMessageId LIMIT 1"
    )
    suspend fun getMessageDeletion(chatId: String, wireMessageId: String): MessageDeletionEntity?

    @Query("SELECT * FROM message_deletions WHERE confirmedAt = 0 AND localOnly = 0 ORDER BY requestedAt ASC")
    suspend fun getPendingDeletions(): List<MessageDeletionEntity>

    @Query(
        "UPDATE message_deletions SET confirmedAt = :confirmedAt, attempts = attempts + 1 " +
            "WHERE chatId = :chatId AND wireMessageId = :wireMessageId"
    )
    suspend fun markDeletionConfirmed(chatId: String, wireMessageId: String, confirmedAt: Long)

    @Query(
        "UPDATE message_deletions SET attempts = attempts + 1 " +
            "WHERE chatId = :chatId AND wireMessageId = :wireMessageId"
    )
    suspend fun bumpDeletionAttempts(chatId: String, wireMessageId: String)

    @Query("DELETE FROM message_deletions WHERE chatId = :chatId")
    suspend fun clearDeletionsForChat(chatId: String)

    // The row count is what the UI reports as «очистено N сообщений», so the delete has to
    // answer how many rows it actually took rather than assume the list was unchanged.
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearMessagesAndGetCount(chatId: String): Int

    @Query("DELETE FROM p2p_file_transfers WHERE chatId = :chatId")
    suspend fun clearFileTransfersInChat(chatId: String)

    @Query("SELECT attachmentUri FROM messages WHERE attachmentUri IS NOT NULL AND attachmentUri <> ''")
    suspend fun getAllAttachmentUris(): List<String>

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND attachmentUri IS NOT NULL AND attachmentUri <> ''"
    )
    suspend fun getChatMessagesWithFiles(chatId: String): List<MessageEntity>

    @Query("DELETE FROM conversations WHERE chatId = :chatId")
    suspend fun deleteConversation(chatId: String)

    @Query("SELECT * FROM p2p_file_transfers ORDER BY timestamp DESC")
    fun observeFileTransfers(): Flow<List<P2PFileTransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileTransfers(transfers: List<P2PFileTransferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFileTransfer(transfer: P2PFileTransferEntity)

    @Query("SELECT * FROM p2p_file_transfers WHERE transferId = :transferId")
    suspend fun getFileTransferById(transferId: String): P2PFileTransferEntity?

    @Query("UPDATE messages SET transferProgress = :fraction WHERE messageId = :messageId")
    suspend fun updateMessageTransferProgress(messageId: String, fraction: Float)

    @Query("SELECT * FROM ygg_peers ORDER BY isConnected DESC, latencyMs ASC")
    fun observePeers(): Flow<List<YggPeerEntity>>

    @Query("SELECT * FROM ygg_peers")
    suspend fun getAllPeersNow(): List<YggPeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeers(peers: List<YggPeerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: YggPeerEntity)

    @Query("DELETE FROM ygg_peers WHERE peerId = :peerId")
    suspend fun deletePeer(peerId: String)

    @Query("SELECT * FROM linked_devices ORDER BY lastSyncTimestamp DESC")
    fun observeLinkedDevices(): Flow<List<LinkedDeviceEntity>>

    @Query("SELECT * FROM linked_devices")
    suspend fun getLinkedDevicesNow(): List<LinkedDeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinkedDevices(devices: List<LinkedDeviceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLinkedDevice(device: LinkedDeviceEntity)

    @Query("SELECT * FROM backup_snapshots ORDER BY createdAt DESC")
    fun observeBackupSnapshots(): Flow<List<BackupSnapshotEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackupSnapshots(snapshots: List<BackupSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBackupSnapshot(snapshot: BackupSnapshotEntity)

    @Query("SELECT * FROM crypto_keys ORDER BY rotationEpoch DESC, createdAt DESC")
    fun observeCryptoKeys(): Flow<List<CryptoKeyRecordEntity>>

    @Query("SELECT * FROM crypto_keys")
    suspend fun getCryptoKeysNow(): List<CryptoKeyRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCryptoKeys(keys: List<CryptoKeyRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCryptoKey(key: CryptoKeyRecordEntity)

    @Query("DELETE FROM crypto_keys WHERE keyId IN ('key_x25519_spk', 'key_mls_hpke')")
    suspend fun deleteLegacyCryptoClaims()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun countConversations(): Int
}

/** Room's row shape for the per-chat member count the chat list needs. */
data class GroupMemberCount(val chatId: String, val memberCount: Int)
