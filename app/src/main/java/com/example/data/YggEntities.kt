// File: app/src/main/java/com/example/data/YggEntities.kt
package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ygg_profiles")
data class UserProfileEntity(
    @PrimaryKey val profileId: String,
    val displayName: String,
    val handle: String,
    val bio: String,
    val statusText: String,
    val mnemonicCsv: String,
    val yggIpv6Address: String,
    val yggPublicKeyHex: String,
    val allocatedTcpPort: Int,
    val isActiveInBackground: Boolean,
    val isCurrentPrimary: Boolean,
    val avatarColorHex: String,
    val createdAt: Long,
    val nodeRelayRole: String = "PEER_RELAY", // "CLIENT_ONLY", "PEER_RELAY", "SF_BLIND_RELAY"
    val relayedPacketsCount: Int = 0,
    // Local file:// path of this profile's own picture, and the hash the other nodes
    // last saw from us. The hash is what decides whether a message has to carry the
    // bytes again.
    val avatarUri: String = "",
    val avatarHashHex: String = "",
    // The user's own status line — the text that travels to peers in a PROFILE frame.
    // Separate from [statusText] on purpose: that column is written by the node-role and
    // background-state bookkeeping ("Пир-узел • В фоне (:51921)"), so shipping it as a person's
    // status would broadcast machine bookkeeping and overwrite whatever they typed.
    val socialStatus: String = "",
    // When this profile was last edited. Every PROFILE frame carries it, and the receiver
    // applies a frame only when it is newer than what it already stored: without a version on
    // the wire, a delayed or replayed frame could rewrite a contact's name with an older one.
    val profileVersion: Long = 0L
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val yggIpv6Address: String,
    val publicKeyHex: String,
    val protocolType: String, // "YGG_X25519_AESGCM"
    val isVerified: Boolean,
    val sealedSenderEnabled: Boolean,
    val disappearingTtlSeconds: Int,
    val unreadCount: Int,
    val lastMessagePreview: String,
    val lastTimestamp: Long,
    val isOnlineInMesh: Boolean,
    val avatarColorHex: String,
    val bioStatus: String,
    val routeTransportSummary: String,
    val ownerProfileId: String = "profile_primary",
    val peerX25519PublicKeyHex: String = "",
    // The contact's picture as it reached us over the overlay, plus the hash it arrived
    // with. Empty avatarUri means "never received one", which the UI must show as
    // initials — not as a placeholder image someone could mistake for the real thing.
    val avatarUri: String = "",
    val avatarHashHex: String = "",
    // "DIRECT" or "GROUP".
    val chatType: String = "DIRECT",
    // The identifier every member of a group sees on the wire. chatId stays local — one
    // per owning profile — so two profiles on this device can each hold their own row for
    // the same group instead of fighting over one primary key.
    val groupId: String = "",
    // What the contact told us about themselves, through a frame this device actually
    // opened. A name on the wire is a claim; these fields only ever hold what the sealed
    // profile envelope proved, so an unauthenticated node cannot rename a contact.
    val peerHandle: String = "",
    val peerStatusText: String = "",
    // The sender's own edit timestamp, kept so an older frame never overwrites a newer one.
    val peerProfileVersion: Long = 0,
    // The last time a frame from this peer opened successfully on this device. This is the
    // only presence fact the app has: it is not "online now", it is "was reachable then".
    val peerLastSeenAt: Long = 0,
    // True only after this peer sent an envelope this device opened as v3. Outgoing traffic
    // uses v3 toward a peer that proved it, so the flag is evidence, not a preference.
    val peerEnvelopeV3Proven: Boolean = false
)

@Entity(
    tableName = "messages",
    // Every incoming frame is looked up by (chat, wire id) before it is stored, and the
    // chat list reads one chat at a time; without this index both scanned the whole table.
    indices = [Index("chatId", "peerMessageId")]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val senderName: String,
    val senderIpv6: String,
    val cipherPayloadHex: String,
    val plainText: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val deliveryState: String, // "QUEUED_OFFLINE_SF", "SENT_P2P", "LOCAL_NOTE", "DELIVERED", "READ"
    val ratchetStep: Int,
    val sealedSenderFlag: Boolean,
    val hashcashPowToken: String,
    val attachmentName: String? = null,
    val attachmentSizeBytes: Long = 0L,
    val merkleRootHex: String? = null,
    val transferProgress: Float = 1.0f,
    val attachmentType: String? = null, // "FILE", "IMAGE", "VOICE"
    val attachmentUri: String? = null,
    val voiceDurationSec: Int = 0,
    val voiceWaveformCsv: String? = null,
    val reactionsJson: String = "", // e.g. "👍:2:me|🔥:1:peer"
    // Wire id the sender assigned to this message. Reactions must reference the
    // sender's id, otherwise the receiving device cannot find the message.
    val peerMessageId: String = "",
    // Wire id of the quoted message (empty when this message is not a reply).
    val replyToMessageId: String = "",
    // Display name of the original author for forwarded messages.
    val forwardedFrom: String = "",
    // 2 = X25519 + HKDF + AES-256-GCM; 1 = legacy envelope without confidentiality.
    val envelopeVersion: Int = 1,
    // Which group members have ACKed this exact message. A group message is only
    // DELIVERED when that set covers the roster, so the count shown to the user is a
    // count of received acknowledgements and never an assumption.
    val deliveredMemberCsv: String = "",
    // The X25519 key that authenticated this received copy when its envelope opened. A
    // delete-for-everyone request has to come back sealed from the same key, which is what
    // makes "only the author may erase it on another device" enforceable with no trust list.
    val authorX25519PubHex: String = ""
)

@Entity(tableName = "p2p_file_transfers")
data class P2PFileTransferEntity(
    @PrimaryKey val transferId: String,
    val chatId: String,
    val peerName: String,
    val peerIpv6: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val chunkCount: Int,
    val completedChunks: Int,
    val merkleRootSha256: String,
    val speedKbps: Int,
    val status: String, // "ACTIVE_STREAMING", "PAUSED", "QUEUED_OFFLINE", "VERIFIED_COMPLETE"
    val isOutgoing: Boolean,
    val protocol: String, // "QUIC_P2P_DIRECT", "YGG_OVERLAY_STREAM", "LAN_DIRECT_ZERO_CONF"
    val timestamp: Long
)

@Entity(tableName = "ygg_peers")
data class YggPeerEntity(
    @PrimaryKey val peerId: String,
    val endpointUri: String,
    val transportType: String, // "WSS_CDN_WHITELIST", "VLESS_REALITY_UTLS", "SHADOWTLS_V3", "QUIC_UDP_PADDING"
    val sniDomain: String,
    val utlsFingerprint: String,
    val regionLabel: String,
    val latencyMs: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val isConnected: Boolean,
    val isWhitelistCompatible: Boolean,
    val ed25519Verified: Boolean
)

@Entity(tableName = "linked_devices")
data class LinkedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val platform: String, // "WINDOWS_X64", "ANDROID_ARM64", "LINUX_NODE"
    val yggIpv6: String,
    val lanEndpoint: String,
    val lastSyncTimestamp: Long,
    val stateVectorVersion: Int,
    val syncMode: String, // "LAN_AND_YGG_P2P", "WEBDAV_CHUNKS"
    val isOnline: Boolean,
    val syncedChunksCount: Int
)

@Entity(tableName = "backup_snapshots")
data class BackupSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val archiveFileName: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val chunkCountFastCdc: Int,
    val kdfSpec: String,
    val cipherSpec: String,
    val merkleRootHex: String,
    val destination: String, // "LOCAL_ENCRYPTED_CONTAINER", "WEBDAV_CLOUD_ZERO_KNOWLEDGE"
    val isVerified: Boolean
)

@Entity(tableName = "crypto_keys")
data class CryptoKeyRecordEntity(
    @PrimaryKey val keyId: String,
    val keyName: String,
    val derivationPath: String,
    val algorithm: String, // "Ed25519", "X25519", "SHA-256 fingerprint"
    val publicKeyHex: String,
    val fingerprintHex: String,
    val createdAt: Long,
    val rotationEpoch: Int,
    val isActive: Boolean,
    val usageScope: String
)

@Entity(tableName = "call_log")
data class CallLogEntity(
    @PrimaryKey val callId: String,
    val chatId: String,
    val peerName: String,
    val peerIpv6: String,
    val direction: String, // "INCOMING", "OUTGOING"
    val outcome: String, // "MISSED", "DECLINED", "COMPLETED", "FAILED", "CANCELED"
    val startedAt: Long,
    val durationSeconds: Int,
    val transport: String,
    val ownerProfileId: String
)

/**
 * One remote member of a group, keyed by the mesh address the overlay can actually dial.
 *
 * Members are stored by identity rather than by a local contact id because that is the
 * only form both sides of the wire agree on: the roster a peer advertises references
 * addresses, and each device resolves them into its own contact rows.
 *
 * `removedAt` is non-zero for a member this device took out of the group. The row is kept
 * instead of deleted because every incoming group frame re-advertises the roster, and a
 * deleted row would simply come back with the next message.
 */
@Entity(tableName = "group_members", primaryKeys = ["chatId", "memberIpv6"])
data class GroupMemberEntity(
    val chatId: String,
    val memberIpv6: String,
    val memberPubKeyHex: String,
    val memberName: String,
    val addedAt: Long,
    val removedAt: Long = 0
)

/**
 * A message this device deleted, and (for its own outgoing rows) asked the author-side copy
 * to delete too. One row covers both directions because the same id is what a retransmitted
 * frame carries: without the tombstone a duplicate MSG datagram would resurrect a message
 * the user already removed, and without `confirmedAt` an offline peer's deletion request
 * would be forgotten as soon as the process died.
 */
@Entity(tableName = "message_deletions", primaryKeys = ["chatId", "wireMessageId"])
data class MessageDeletionEntity(
    val chatId: String,
    val wireMessageId: String,
    // The delete request this device sent for that message: the peer echoes it in its ACK,
    // so «удалено у собеседника» is only ever claimed for a confirmed request.
    val deleteId: String,
    val peerIpv6: String,
    val requestedAt: Long,
    // 0 while the peer has not confirmed the deletion.
    val confirmedAt: Long = 0,
    val attempts: Int = 0,
    // True when only this device removed the row; the peer was never asked.
    val localOnly: Boolean
)

