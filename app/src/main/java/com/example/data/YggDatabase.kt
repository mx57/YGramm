// File: app/src/main/java/com/example/data/YggDatabase.kt
package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserProfileEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        P2PFileTransferEntity::class,
        YggPeerEntity::class,
        LinkedDeviceEntity::class,
        BackupSnapshotEntity::class,
        CryptoKeyRecordEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class YggDatabase : RoomDatabase() {
    abstract fun yggDao(): YggDao

    companion object {
        @Volatile
        private var INSTANCE: YggDatabase? = null

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN peerX25519PublicKeyHex TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getInstance(context: Context): YggDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    YggDatabase::class.java,
                    "ygggram_encrypted_store.db"
                )
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
