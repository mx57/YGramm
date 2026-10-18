// File: app/src/main/java/com/example/data/YggEntities.kt
package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val yggIpv6Address: String,
    val publicKeyHex: String,
    val protocolType: String, // "X3DH_DOUBLE_RATCHET" or "MLS_RFC9420"
    val isVerified: Boolean,
    val sealedSenderEnabled: Boolean,
    val disappearingTtlSeconds: Int,
    val unreadCount: Int,
    val lastMessagePreview: String,
    val lastTimestamp: Long,
    val isOnlineInMesh: Boolean,
    val avatarColorHex: String,
    val bioStatus: String,
    val routeTransportSummary: String
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val senderName: String,
    val senderIpv6: String,
    val cipherPayloadHex: String,
    val plainText: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val deliveryState: String, // "QUEUED_OFFLINE_SF", "SENT_P2P", "DELIVERED", "READ"
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
    val reactionsJson: String = "" // e.g. "👍:2:me|🔥:1:peer"
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
    val algorithm: String, // "Ed25519", "X25519", "MLS-HPKE", "XChaCha20-256"
    val publicKeyHex: String,
    val fingerprintHex: String,
    val createdAt: Long,
    val rotationEpoch: Int,
    val isActive: Boolean,
    val usageScope: String
)
