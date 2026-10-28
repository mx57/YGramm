// File: app/src/test/java/com/example/data/YggEncryptedExportTest.kt
package com.example.data

import com.example.engine.YggCryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The export used to be a summary built from row counts: the UI showed Argon2id and
 * XChaCha20-Poly1305 for bytes that were never written. These tests pin that every number a
 * snapshot carries comes from the archive that actually landed on the chosen document, and that a
 * mismatch there is reported instead of glossed over.
 */
class YggEncryptedExportTest {

    private val cipherSpec = "SQLCipher 4.19.1 community; cipher=aes-256-cbc, page_size=4096"
    private val keySpec = "Пароль базы: 32 байта из SecureRandom, обёрнут AES-GCM ключом Android Keystore"

    private fun databaseFile(body: ByteArray): File = File.createTempFile("ygg_store_", ".db").apply {
        writeBytes(body)
        deleteOnExit()
    }

    @Test
    fun `an export records the real byte count and piece root of the file it wrote`() {
        val body = ByteArray(37_000) { (it % 251).toByte() }
        val source = databaseFile(body)
        val written = ByteArrayOutputStream()

        val profile = YggEncryptedExport.copyAndProfile(
            sourceFile = source,
            archiveFileName = "ygggram_export.db",
            openSink = { written },
            readBackArchive = { ByteArrayInputStream(written.toByteArray()) },
            cipherSpec = cipherSpec,
            keySpec = keySpec
        )

        val expected = YggCryptoEngine.pieceProfileOf(source)
        requireNotNull(expected)
        assertEquals(body.size.toLong(), profile.sizeBytes)
        assertEquals(expected.pieceCount, profile.pieceCount)
        assertEquals(expected.merkleRootHex, profile.merkleRootHex)
        assertTrue(profile.verified)
        // The pieces are the fixed 12 KiB slices the file transport already uses, not a
        // content-defined FastCDC boundary the app cannot compute.
        assertEquals((body.size + YggCryptoEngine.FILE_PIECE_BYTES - 1) / YggCryptoEngine.FILE_PIECE_BYTES, profile.pieceCount)
    }

    @Test
    fun `an archive that reads back short is not recorded as verified`() {
        val source = databaseFile(ByteArray(30_000) { 3 })
        val written = ByteArrayOutputStream()

        val profile = YggEncryptedExport.copyAndProfile(
            sourceFile = source,
            archiveFileName = "truncated.db",
            openSink = { written },
            // A provider that accepted the write but kept only part of it.
            readBackArchive = { ByteArrayInputStream(written.toByteArray().copyOfRange(0, 12_000)) },
            cipherSpec = cipherSpec,
            keySpec = keySpec
        )

        assertEquals(12_000L, profile.sizeBytes)
        assertEquals(source.length(), profile.sourceSizeBytes)
        assertNotEquals(profile.sourceRootHex, profile.merkleRootHex)
        assertTrue(!profile.verified)
    }

    @Test
    fun `an archive that cannot be read back has no root at all`() {
        val source = databaseFile(ByteArray(4_096) { 9 })
        val profile = YggEncryptedExport.copyAndProfile(
            sourceFile = source,
            archiveFileName = "unreadable.db",
            openSink = { ByteArrayOutputStream() },
            readBackArchive = { null },
            cipherSpec = cipherSpec,
            keySpec = keySpec
        )

        assertEquals(0L, profile.sizeBytes)
        assertEquals(0, profile.pieceCount)
        assertEquals("", profile.merkleRootHex)
        assertTrue(!profile.verified)
    }

    @Test
    fun `a byte changed in the archive flips the recorded root`() {
        val body = ByteArray(20_000) { (it % 97).toByte() }
        val source = databaseFile(body)
        val written = ByteArrayOutputStream()
        YggEncryptedExport.copyAndProfile(
            sourceFile = source,
            archiveFileName = "intact.db",
            openSink = { written },
            readBackArchive = { ByteArrayInputStream(written.toByteArray()) },
            cipherSpec = cipherSpec,
            keySpec = keySpec
        )
        val snapshot = BackupSnapshotEntity(
            snapshotId = "bak_intact",
            archiveFileName = "intact.db",
            createdAt = 1L,
            sizeBytes = body.size.toLong(),
            chunkCountFastCdc = 2,
            kdfSpec = keySpec,
            cipherSpec = cipherSpec,
            merkleRootHex = YggCryptoEngine.pieceProfileOf(source)!!.merkleRootHex,
            destination = "SQLCIPHER_DB_FILE_COPY",
            isVerified = true
        )
        assertNull(YggEncryptedExport.checkAgainstSnapshot(stream(written.toByteArray()), snapshot))

        val tampered = written.toByteArray().also { it[15_000] = (it[15_000] + 1).toByte() }
        val reason = YggEncryptedExport.checkAgainstSnapshot(stream(tampered), snapshot)
        assertTrue(reason != null && reason.contains("Merkle"))
    }

    @Test
    fun `a short archive is reported by size before its root is even compared`() {
        val source = databaseFile(ByteArray(20_000) { 5 })
        val profile = YggEncryptedExport.copyAndProfile(
            sourceFile = source,
            archiveFileName = "half.db",
            openSink = { ByteArrayOutputStream() },
            readBackArchive = { ByteArrayInputStream(ByteArray(100)) },
            cipherSpec = cipherSpec,
            keySpec = keySpec
        )
        val snapshot = BackupSnapshotEntity(
            snapshotId = "bak_half",
            archiveFileName = "half.db",
            createdAt = 1L,
            sizeBytes = profile.sizeBytes,
            chunkCountFastCdc = profile.pieceCount,
            kdfSpec = profile.keySpec,
            cipherSpec = profile.cipherSpec,
            merkleRootHex = profile.merkleRootHex,
            destination = "SQLCIPHER_DB_FILE_COPY",
            isVerified = false
        )
        val reason = YggEncryptedExport.checkAgainstSnapshot(ByteArrayInputStream(ByteArray(50)), snapshot)
        assertTrue(reason != null && reason.contains("Размер архива"))
    }

    @Test
    fun `restoring an archive says nothing was restored`() {
        val reason = YggEncryptedExport.restoreRefusalReason("ygggram_export_1700000000.db")

        assertTrue(reason.contains("не выполнено"))
        assertTrue(reason.contains("ygggram_export_1700000000.db"))
        val lower = reason.lowercase()
        for (claimed in listOf("восстановлено", "готово", "успешно")) {
            assertTrue("текст обещает «$claimed»", !lower.contains(claimed))
        }
    }

    @Test
    fun `the export path names no cipher it did not measure`() {
        val source = databaseFile(ByteArray(12 * 1024) { 1 })
        val written = ByteArrayOutputStream()
        val profile = YggEncryptedExport.copyAndProfile(
            sourceFile = source,
            archiveFileName = "labels.db",
            openSink = { written },
            readBackArchive = { ByteArrayInputStream(written.toByteArray()) },
            cipherSpec = cipherSpec,
            keySpec = keySpec
        )

        // Nothing is invented downstream of the copy: the two spec lines are exactly what the
        // caller measured, and the algorithm names the old placeholder promised are absent.
        assertEquals(cipherSpec, profile.cipherSpec)
        assertEquals(keySpec, profile.keySpec)
        val whole = "${profile.cipherSpec} ${profile.keySpec}".lowercase()
        for (fabricated in listOf("argon2", "xchacha20", "poly1305", "zstd", "fastcdc")) {
            assertTrue("экспорт приписывает алгоритм $fabricated", !whole.contains(fabricated))
        }
    }

    private fun stream(bytes: ByteArray): InputStream = ByteArrayInputStream(bytes)
}
