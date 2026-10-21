// File: app/src/main/java/com/example/data/YggDao.kt
package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("UPDATE messages SET deliveryState = :newState WHERE messageId = :messageId")
    suspend fun updateMessageDeliveryState(messageId: String, newState: String)

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

    @Query("DELETE FROM conversations WHERE chatId = :chatId")
    suspend fun deleteConversation(chatId: String)

    @Query("SELECT * FROM p2p_file_transfers ORDER BY timestamp DESC")
    fun observeFileTransfers(): Flow<List<P2PFileTransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileTransfers(transfers: List<P2PFileTransferEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFileTransfer(transfer: P2PFileTransferEntity)

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
