package com.example.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class YggDatabaseExportSnapshotTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "export_test_${UUID.randomUUID()}.db"
    private val snapshotDirectory = File(System.getProperty("java.io.tmpdir"), "export_test_${UUID.randomUUID()}").apply { mkdir() }
    private var database: YggDatabase? = null

    @After
    fun cleanUp() {
        database?.close()
        app.deleteDatabase(databaseName)
        snapshotDirectory.deleteRecursively()
    }

    @Test
    fun archiveKeepsCommittedSnapshotWhenProviderWritesToLiveDatabase() = runBlocking {
        val db = openDatabase()
        val snapshot = db.createExportSnapshot(snapshotDirectory)
        val written = ByteArrayOutputStream()
        try {
            assertEquals("before", readSnapshot(snapshot))
            val profile = YggEncryptedExport.copyAndProfile(
                sourceFile = snapshot,
                archiveFileName = "snapshot.db",
                openSink = {
                    db.openHelper.writableDatabase.execSQL("UPDATE export_probe SET value = 'after'")
                    written
                },
                readBackArchive = { ByteArrayInputStream(written.toByteArray()) },
                cipherSpec = "SQLite test fixture",
                keySpec = "No encryption in the transaction test fixture"
            )
            assertTrue(profile.verified)
            assertEquals("before", readSnapshot(snapshot))
            assertEquals("after", liveValue(db))
            assertTrue(written.toByteArray().contentEquals(snapshot.readBytes()))
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun snapshotWaitsForAnActiveWriterAndIncludesItsCommit() = runBlocking {
        withTimeout(10_000) {
            val db = openDatabase()
            val writerStarted = CompletableDeferred<Unit>()
            val releaseWriter = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                db.withTransaction {
                    db.openHelper.writableDatabase.execSQL("UPDATE export_probe SET value = 'committed'")
                    writerStarted.complete(Unit)
                    releaseWriter.await()
                }
            }
            writerStarted.await()
            val export = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                db.createExportSnapshot(snapshotDirectory)
            }
            try {
                assertFalse(export.isCompleted)
            } finally {
                releaseWriter.complete(Unit)
            }
            writer.await()
            val snapshot = export.await()
            try {
                assertEquals("committed", readSnapshot(snapshot))
            } finally {
                snapshot.delete()
            }
        }
    }

    @Test
    fun cancelledExportRemovesItsSnapshotWithoutCancellingTheWriter() = runBlocking {
        withTimeout(10_000) {
            val db = openDatabase()
            val writerStarted = CompletableDeferred<Unit>()
            val releaseWriter = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                db.withTransaction {
                    writerStarted.complete(Unit)
                    releaseWriter.await()
                }
            }
            writerStarted.await()
            val export = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                db.createExportSnapshot(snapshotDirectory)
            }
            try {
                export.cancelAndJoin()
                assertEquals(0, snapshotDirectory.listFiles()!!.size)
                assertFalse(writer.isCancelled)
            } finally {
                releaseWriter.complete(Unit)
            }
            writer.await()
            assertEquals("before", liveValue(db))
        }
    }

    @Test
    fun deleteJournalProducesAConsistentSnapshot() = runBlocking {
        val db = openDatabase("delete")
        val snapshot = db.createExportSnapshot(snapshotDirectory)
        try {
            assertEquals("before", readSnapshot(snapshot))
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun persistJournalProducesAConsistentSnapshot() = runBlocking {
        val db = openDatabase("persist")
        val snapshot = db.createExportSnapshot(snapshotDirectory)
        try {
            assertEquals("before", readSnapshot(snapshot))
        } finally {
            snapshot.delete()
        }
    }

    @Test
    fun walModeIsRefusedAndTheTemporarySnapshotIsRemoved() {
        val db = openDatabase("wal")
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { db.createExportSnapshot(snapshotDirectory) }
        }
        assertTrue(error.message.orEmpty().contains("rollback"))
        assertEquals(0, snapshotDirectory.listFiles()!!.size)
        assertEquals("before", liveValue(db))
    }

    private fun openDatabase(journalMode: String = "truncate"): YggDatabase {
        val mode = if (journalMode == "wal") RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING else RoomDatabase.JournalMode.TRUNCATE
        val db = Room.databaseBuilder(app, YggDatabase::class.java, databaseName)
            .setJournalMode(mode)
            .allowMainThreadQueries()
            .build()
        database = db
        db.openHelper.writableDatabase.query("PRAGMA journal_mode=$journalMode").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(journalMode, cursor.getString(0))
        }
        db.openHelper.writableDatabase.execSQL("CREATE TABLE export_probe (value TEXT NOT NULL)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO export_probe VALUES ('before')")
        return db
    }

    private fun liveValue(db: YggDatabase): String = db.openHelper.writableDatabase
        .query("SELECT value FROM export_probe").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun readSnapshot(snapshot: File): String = SQLiteDatabase.openDatabase(
        snapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY
    ).use { db ->
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
        db.rawQuery("SELECT value FROM export_probe", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
    }
}
