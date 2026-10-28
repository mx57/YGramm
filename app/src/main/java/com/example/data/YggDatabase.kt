// File: app/src/main/java/com/example/data/YggDatabase.kt
package com.example.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileInputStream

@Database(
    entities = [
        UserProfileEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        P2PFileTransferEntity::class,
        YggPeerEntity::class,
        LinkedDeviceEntity::class,
        BackupSnapshotEntity::class,
        CryptoKeyRecordEntity::class,
        CallLogEntity::class,
        GroupMemberEntity::class,
        MessageDeletionEntity::class
    ],
    version = 15,
    exportSchema = false
)
abstract class YggDatabase : RoomDatabase() {
    abstract fun yggDao(): YggDao

    companion object {
        const val DATABASE_NAME = "ygggram_encrypted_store.db"
        private const val TAG = "YggGramDb"
        private const val SQLITE_HEADER = "SQLite format 3"

        @Volatile
        private var INSTANCE: YggDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN peerX25519PublicKeyHex TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN peerMessageId TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN replyToMessageId TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN forwardedFrom TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN envelopeVersion INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `call_log` (
                        `callId` TEXT NOT NULL,
                        `chatId` TEXT NOT NULL,
                        `peerName` TEXT NOT NULL,
                        `peerIpv6` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `transport` TEXT NOT NULL,
                        `ownerProfileId` TEXT NOT NULL,
                        PRIMARY KEY(`callId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ygg_profiles ADD COLUMN avatarUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ygg_profiles ADD COLUMN avatarHashHex TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN avatarUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN avatarHashHex TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_members` (" +
                        "`chatId` TEXT NOT NULL, `memberIpv6` TEXT NOT NULL, " +
                        "`memberPubKeyHex` TEXT NOT NULL, `memberName` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY (`chatId`, `memberIpv6`))"
                )
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN chatType TEXT NOT NULL DEFAULT 'DIRECT'"
                )
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN groupId TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN deliveredMemberCsv TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // A member the local owner removed has to stay removed: the roster another node
                // advertises arrives again with the next message, and without this column that
                // frame would silently re-add the address.
                db.execSQL("ALTER TABLE group_members ADD COLUMN removedAt INTEGER NOT NULL DEFAULT 0")
                // Matches the index Room builds from the entity for a fresh database; an
                // upgraded one has to end up with the same schema or validation fails.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_chatId_peerMessageId` " +
                        "ON `messages` (`chatId`, `peerMessageId`)"
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Deletion has to outlive the row it removed: a duplicate copy of a deleted
                // message arrives with the same wire id after a lost ACK, and the tombstone is
                // the only thing that can refuse it. An unconfirmed request is kept too, so a
                // peer that was offline when the delete was sent still gets it later.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_deletions` (" +
                        "`chatId` TEXT NOT NULL, `wireMessageId` TEXT NOT NULL, " +
                        "`deleteId` TEXT NOT NULL, `peerIpv6` TEXT NOT NULL, " +
                        "`requestedAt` INTEGER NOT NULL, `confirmedAt` INTEGER NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `localOnly` INTEGER NOT NULL, " +
                        "PRIMARY KEY (`chatId`, `wireMessageId`))"
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // A remote delete is only honoured for the node that actually authored the
                // message. That authorship is what the envelope's X25519 key proved on receipt,
                // so the key has to survive next to the row instead of only in RAM.
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN authorX25519PubHex TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // What a contact says about themselves used to reach this device only as a
                // name in a message frame, applied when the dialog row was created and never
                // again — a renamed peer left the chat titled with the old name forever. The
                // PROFILE frame carries the fields, and the version is what lets a receiver
                // refuse an older copy of somebody's identity.
                db.execSQL("ALTER TABLE conversations ADD COLUMN peerHandle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN peerStatusText TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN peerProfileVersion INTEGER NOT NULL DEFAULT 0"
                )
                // The last moment a frame from this peer opened here. Presence is a timestamp,
                // not a dot: a background socket dies, and nothing in the mesh says whether a
                // person is at the phone right now.
                db.execSQL("ALTER TABLE conversations ADD COLUMN peerLastSeenAt INTEGER NOT NULL DEFAULT 0")
                // Set only by an envelope this device actually opened as v3, so an outgoing
                // choice of key agreement rests on evidence rather than on a guess about the peer.
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN peerEnvelopeV3Proven INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE ygg_profiles ADD COLUMN profileVersion INTEGER NOT NULL DEFAULT 0")
                // The status line the person typed, kept apart from ygg_profiles.statusText: that
                // column holds node-role bookkeeping written by the app itself, and it is what a
                // peer would otherwise receive as somebody's "status".
                db.execSQL("ALTER TABLE ygg_profiles ADD COLUMN socialStatus TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Compressed voice only goes to a peer that proved it can open it, so the choice
                // of codec is a measurement of the other side, not a guess about it.
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN peerVoiceOpusProven INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): YggDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildEncrypted(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * SQLCipher receives the key both as a SQL literal (ATTACH) and as raw factory
         * bytes. Random bytes are not valid UTF-8, so the byte-identical hex form keeps
         * both paths on the same key.
         */
        private fun ByteArray.toSqlPassphrase(): ByteArray =
            joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)

        private fun buildEncrypted(context: Context): YggDatabase {
            System.loadLibrary("sqlcipher")
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val passphrase = DatabaseKeyStore.getOrCreatePassphrase(context).toSqlPassphrase()
            recoverInterruptedEncryption(dbFile)
            if (isPlaintextDatabase(dbFile)) {
                encryptPlaintextDatabase(dbFile, passphrase)
            }
            return Room.databaseBuilder(context, YggDatabase::class.java, DATABASE_NAME)
                .addMigrations(
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                    MIGRATION_14_15
                )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
        }

        private fun tempEncryptedFile(dbFile: File): File =
            File(dbFile.parentFile, "${dbFile.name}.sqlcipher-tmp")

        private fun isPlaintextDatabase(file: File): Boolean {
            if (!file.exists() || file.length() < 16L) return false
            val header = ByteArray(16)
            FileInputStream(file).use { stream ->
                if (stream.read(header) != header.size) return false
            }
            return String(header, Charsets.US_ASCII).startsWith(SQLITE_HEADER)
        }

        private fun recoverInterruptedEncryption(dbFile: File) {
            val pending = tempEncryptedFile(dbFile)
            if (!pending.exists()) return
            if (!dbFile.exists()) {
                if (pending.renameTo(dbFile)) {
                    Log.i(TAG, "Recovered encrypted store left by an interrupted migration")
                } else {
                    Log.e(TAG, "Encrypted store recovery failed, pending file kept")
                }
            } else {
                pending.delete()
            }
        }

        /**
         * SQLCipher cannot read a plaintext SQLite file with a key, so the old store is
         * opened without a key and copied into a fresh encrypted database via
         * sqlcipher_export. The plaintext file is removed only after the encrypted copy
         * passes integrity_check.
         */
        private fun encryptPlaintextDatabase(dbFile: File, passphrase: ByteArray) {
            val target = tempEncryptedFile(dbFile)
            if (target.exists()) target.delete()
            val password = String(passphrase, Charsets.US_ASCII)
            Log.i(TAG, "Migrating plaintext store to SQLCipher (${dbFile.length()} bytes)")
            val plaintext = SQLiteDatabase.openOrCreateDatabase(dbFile, "", null, null)
            val schemaVersion = try {
                plaintext.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            } catch (e: Exception) {
                plaintext.close()
                throw e
            }
            try {
                plaintext.rawExecSQL(
                    "ATTACH DATABASE '${escapeSqlLiteral(target.absolutePath)}' AS encrypted " +
                        "KEY '${escapeSqlLiteral(password)}'"
                )
                plaintext.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                plaintext.rawExecSQL("DETACH DATABASE encrypted")
            } finally {
                plaintext.close()
            }

            val encrypted = SQLiteDatabase.openDatabase(
                target.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )
            try {
                // sqlcipher_export copies tables but not user_version; without it Room
                // treats the existing schema as a brand-new database and skips migrations.
                encrypted.rawExecSQL("PRAGMA user_version = $schemaVersion")
                encrypted.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    val ok = cursor.moveToFirst() && cursor.getString(0) == "ok"
                    check(ok) { "Encrypted store failed PRAGMA integrity_check" }
                }
            } finally {
                encrypted.close()
            }

            check(!isPlaintextDatabase(target)) { "Encrypted store has a plaintext header" }
            check(dbFile.delete()) { "Cannot remove plaintext store after export" }
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            check(target.renameTo(dbFile)) { "Cannot move encrypted store into place" }
            Log.i(TAG, "At-rest encryption enabled (${dbFile.length()} bytes)")
        }

        private fun escapeSqlLiteral(value: String): String = value.replace("'", "''")
    }
}
