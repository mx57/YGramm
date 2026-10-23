// Network, download and install handoff for the GitHub Releases updater.
package com.example.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class YGrammDownloadOutcome {
    data class Verified(val file: File, val sha256Hex: String, val certDigestHex: String) : YGrammDownloadOutcome()
    data class Failed(val reason: String) : YGrammDownloadOutcome()
}

object YGrammUpdateEngine {
    // Release signing certificate (SHA-256 of the DER), printed by
    // `apksigner verify --print-certs`. The digest published next to an APK comes from the same
    // GitHub release that served the file, so it cannot be the root of trust: this pin can.
    // An APK signed with any other key is refused here and would also be refused by the
    // installer, because it cannot replace a package signed by the pinned key.
    const val PINNED_RELEASE_CERT_SHA256_HEX =
        "2a058c38de884fa8df2751226100a5d780fb20c28465b52d9d7bd238ff9d0f9c"

    private const val USER_AGENT = "YGramm-Android update-check"

    fun updateRepo(): String = BuildConfig.UPDATE_REPO

    fun releaseLatestUrl(repo: String = updateRepo()): String =
        "https://api.github.com/repos/${repo.trim().trim('/')}/releases/latest"

    /** Blocking HTTP call; every caller must be off the main thread. */
    fun fetchText(url: String, timeoutMs: Int = 10000): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            connection.disconnect()
        }
    }

    fun checkForUpdate(runningVersionCode: Int = BuildConfig.VERSION_CODE): YGrammCheckOutcome {
        val (code, body) = try {
            fetchText(releaseLatestUrl())
        } catch (e: Exception) {
            return YGrammCheckOutcome.Failed(
                "Сеть недоступна: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}"
            )
        }
        if (code == 404) {
            return YGrammCheckOutcome.Failed(
                "Репозиторий ${updateRepo()} не публикует релизов (GitHub ответил 404)"
            )
        }
        if (code == 403 || code == 429) {
            return YGrammCheckOutcome.Failed(
                "GitHub ограничил частоту запросов (HTTP $code): ${body.take(100)}"
            )
        }
        if (code !in 200..299) {
            return YGrammCheckOutcome.Failed("GitHub ответил HTTP $code")
        }
        return YGrammUpdateCodec.parseLatestRelease(body, runningVersionCode)
    }

    fun updateDir(context: Context): File =
        File(context.filesDir, "update").apply { if (!exists()) mkdirs() }

    /** Removes a previously downloaded APK so a stale file can never be offered for install. */
    fun clearDownloadedApk(context: Context) {
        updateDir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Streams the release asset to app-private storage, then verifies the published sha256 and the
     * signing certificate. Nothing partial is ever reported as ready: the file is renamed only
     * after both checks pass.
     */
    fun downloadAndVerify(
        context: Context,
        update: YGrammAvailableUpdate,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit
    ): YGrammDownloadOutcome {
        val dir = updateDir(context)
        dir.listFiles()?.forEach { if (it.name.startsWith("YGramm-")) it.delete() }
        val temp = File(dir, "${update.manifest.apkAssetName}.part")
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                return YGrammDownloadOutcome.Failed("Скачивание не началось: HTTP $code")
            }
            val total = connection.contentLengthLong.coerceAtLeast(update.assetSizeBytes)
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: Exception) {
            temp.delete()
            return YGrammDownloadOutcome.Failed(
                "Скачивание оборвалось: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}"
            )
        } finally {
            connection.disconnect()
        }

        if (!temp.exists() || temp.length() == 0L) {
            temp.delete()
            return YGrammDownloadOutcome.Failed("Загружен пустой файл")
        }

        val digest = YGrammUpdateCodec.sha256HexOfFile(temp)
        if (digest != update.manifest.sha256Hex) {
            temp.delete()
            return YGrammDownloadOutcome.Failed(
                "Контрольная сумма не совпала: ожидалась ${update.manifest.sha256Hex.take(16)}…, " +
                    "получена ${digest.take(16)}… Файл удален"
            )
        }

        val certDigest = apkCertificateDigestHex(context, temp)
        if (certDigest == null) {
            temp.delete()
            return YGrammDownloadOutcome.Failed("Не удалось прочитать подпись APK — установка отменена")
        }
        if (certDigest != PINNED_RELEASE_CERT_SHA256_HEX) {
            temp.delete()
            return YGrammDownloadOutcome.Failed(
                "APK подписан другим ключом ($certDigest), а не релизным " +
                    "($PINNED_RELEASE_CERT_SHA256_HEX). Файл удален"
            )
        }

        val finalFile = File(dir, update.manifest.apkAssetName)
        if (!temp.renameTo(finalFile)) {
            return YGrammDownloadOutcome.Failed("Не удалось сохранить файл рядом с приложением")
        }
        return YGrammDownloadOutcome.Verified(finalFile, digest, certDigest)
    }

    fun apkCertificateDigestHex(context: Context, apk: File): String? {
        val signatureBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            info?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNATURES
            )
            @Suppress("DEPRECATION")
            info?.signatures?.firstOrNull()?.toByteArray()
        }
        val der = signatureBytes ?: return null
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString("") { "%02x".format(it) }
    }

    fun canRequestInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${BuildConfig.APPLICATION_ID}")
    )

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
    }
}
