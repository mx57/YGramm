// File: app/src/main/java/com/example/data/YggDatabase.kt
package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        P2PFileTransferEntity::class,
        YggPeerEntity::class,
        LinkedDeviceEntity::class,
        BackupSnapshotEntity::class,
        CryptoKeyRecordEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class YggDatabase : RoomDatabase() {
    abstract fun yggDao(): YggDao

    companion object {
        @Volatile
        private var INSTANCE: YggDatabase? = null

        fun getInstance(context: Context): YggDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    YggDatabase::class.java,
                    "ygggram_encrypted_store.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
