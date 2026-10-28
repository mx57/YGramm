// File: app/src/main/java/com/example/data/YggEncryptedExport.kt
package com.example.data

import com.example.engine.YggCryptoEngine
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A byte-for-byte copy of the live SQLCipher file, plus what the copy actually contains.
 * Nothing here derives a number from row counts: size, piece count and Merkle root are read
 * back from the bytes that were written.
 */
object YggEncryptedExport {

    data class ArchiveProfile(
        val archiveFileName: String,
        val sizeBytes: Long,
        val pieceCount: Int,
        val merkleRootHex: String,
        val sourceRootHex: String,
        val sourceSizeBytes: Long,
        /** The read-back archive hashes to the same root as the database file it came from. */
        val verified: Boolean,
        val cipherSpec: String,
        val keySpec: String
    )

    /**
     * Copies [sourceFile] into the stream [openSink] hands out, closes it, and only then re-reads
     * the destination through [readBackArchive]: a document provider commits on close, so hashing
     * before the close could measure bytes the archive never got. A provider that hands back a
     * truncated or unreadable stream yields verified=false instead of a false success.
     */
    fun copyAndProfile(
        sourceFile: File,
        archiveFileName: String,
        openSink: () -> OutputStream,
        readBackArchive: () -> InputStream?,
        cipherSpec: String,
        keySpec: String
    ): ArchiveProfile {
        val source = YggCryptoEngine.pieceProfileOf(sourceFile)
            ?: throw IllegalArgumentException("База не читается: ${sourceFile.absolutePath}")
        openSink().use { output ->
            sourceFile.inputStream().use { it.copyTo(output) }
            output.flush()
        }
        val archive = readBackArchive()?.use { YggCryptoEngine.pieceProfileOfStream(it) }
        return ArchiveProfile(
            archiveFileName = archiveFileName,
            sizeBytes = archive?.totalBytes ?: 0L,
            pieceCount = archive?.pieceCount ?: 0,
            merkleRootHex = archive?.merkleRootHex.orEmpty(),
            sourceRootHex = source.merkleRootHex,
            sourceSizeBytes = source.totalBytes,
            verified = archive != null &&
                archive.merkleRootHex == source.merkleRootHex &&
                archive.totalBytes == source.totalBytes,
            cipherSpec = cipherSpec,
            keySpec = keySpec
        )
    }

    /**
     * Hashes an archive the user points at again and compares it with what the export recorded.
     * Returns the reason the archive does not match, or null when it is intact.
     */
    fun checkAgainstSnapshot(archive: InputStream?, snapshot: BackupSnapshotEntity): String? {
        if (archive == null) return "Файл архива не открылся для чтения"
        val profile = archive.use { YggCryptoEngine.pieceProfileOfStream(it) }
        if (profile.totalBytes != snapshot.sizeBytes) {
            return "Размер архива ${profile.totalBytes} байт, а в снимке записано ${snapshot.sizeBytes}"
        }
        if (profile.merkleRootHex != snapshot.merkleRootHex) {
            return "Merkle root архива не совпал с записанным при экспорте: файл изменён после выгрузки"
        }
        return null
    }

    /**
     * There is no restore path, and the only honest thing the button can do is say so: the live
     * database is open in this process, and the archive's key is wrapped by this device's
     * Keystore, so no in-app action can swap the file and keep working.
     */
    fun restoreRefusalReason(archiveFileName: String): String =
        "Восстановление из $archiveFileName не выполнено: база открыта этим приложением, а ключ " +
            "архива обёрнут Keystore этого устройства — обратного пути в приложении нет"
}
