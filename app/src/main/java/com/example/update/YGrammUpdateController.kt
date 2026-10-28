// State holder for the updater: the only place that touches the network off the main thread.
package com.example.update

import android.content.Context

import com.example.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class YGrammUpdateUiState(
    val autoCheckEnabled: Boolean = true,
    val runningVersionLabel: String = "",
    val repoSlug: String = "",
    val lastCheckedLabel: String = "ещё не проверялось",
    val statusLine: String = "",
    val busy: Boolean = false,
    val available: YGrammAvailableUpdate? = null,
    val downloadPercent: Int = -1,
    val downloadedApkPath: String? = null,
    val installPermissionMissing: Boolean = false,
    // Peer relay. Off until the user says so on both sides, and it only ever moves bytes:
    // whether they are the release is decided by the local manifest and the pinned key below.
    val relayOfferEnabled: Boolean = false,
    val relayBusy: Boolean = false,
    val relayTransferId: String = "",
    val relayPercent: Int = -1,
    val relayStatusLine: String = "",
    val localManifestLabel: String = ""
)

object YGrammUpdateController {
    private const val PREF_AUTO = "auto_update_checks"
    private const val PREF_LAST_CHECK = "update_last_check_epoch"
    private const val PREF_LAST_STATUS = "update_last_status"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(YGrammUpdateUiState())
    val state: StateFlow<YGrammUpdateUiState> = _state

    private var appContext: Context? = null
    private var started = false

    // What this device asked for, remembered locally. The peer's copy of the same facts is never
    // read: bytes arriving without a matching order here are deleted without further ceremony.
    private data class PendingRelay(
        val transferId: String,
        val apkAssetName: String,
        val expectedSha256Hex: String
    )

    @Volatile
    private var pendingRelay: PendingRelay? = null

    // Hand-rolled because DateUtils formats in the system locale, which put "0 minutes ago"
    // inside an otherwise Russian UI.
    private fun friendlyTime(epochMillis: Long): String {
        val minutes = ((System.currentTimeMillis() - epochMillis) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 1440 -> "${minutes / 60} ч назад"
            else -> "${minutes / 1440} дн назад"
        }
    }

    /** Called from the ViewModel: restores the last result and schedules the automatic check. */
    fun attach(context: Context) {
        val prefs = context.getSharedPreferences("ygggram_ui_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_AUTO, true)
        val lastEpoch = prefs.getLong(PREF_LAST_CHECK, 0L)
        val lastStatus = prefs.getString(PREF_LAST_STATUS, "").orEmpty()
        appContext = context.applicationContext
        _state.update {
            it.copy(
                autoCheckEnabled = enabled,
                runningVersionLabel = "${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})",
                repoSlug = YGrammUpdateEngine.updateRepo(),
                relayOfferEnabled = prefs.getBoolean(YGrammPeerRelay.PREF_OFFER_ENABLED, false),
                localManifestLabel = describeLocalManifest(YGrammUpdateEngine.updateDir(context)),
                lastCheckedLabel = if (lastEpoch == 0L) "ещё не проверялось"
                else friendlyTime(lastEpoch),
                statusLine = if (lastStatus.isEmpty() && enabled) {
                    "Автопроверка включена: релиз на GitHub будет проверен при запуске"
                } else {
                    lastStatus
                }
            )
        }
        if (started) return
        started = true
        if (enabled) {
            // The core, the mesh and the DB all boot at once; a network probe has to wait for
            // connectivity rather than add a fourth thing failing in the first seconds.
            scope.launch {
                delay(12000)
                checkNow()
            }
        }
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("ygggram_ui_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_AUTO, enabled).apply()
        _state.update {
            it.copy(
                autoCheckEnabled = enabled,
                statusLine = if (enabled) "Автопроверка включена"
                else "Автопроверка выключена: обновления только по кнопке"
            )
        }
        if (enabled) {
            scope.launch { checkNow() }
        }
    }

    fun checkNow() {
        val context = appContext ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, statusLine = "Проверяю релиз на GitHub…", downloadPercent = -1) }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { YGrammUpdateEngine.checkForUpdate() }
            val epoch = System.currentTimeMillis()
            // Whatever release this device saw with its own request becomes the local anchor. A
            // later peer transfer is checked against it, so the anchor never comes from the peer.
            val seenManifest = (outcome as? YGrammCheckOutcome.Available)?.update?.manifest
            val dir = YGrammUpdateEngine.updateDir(context)
            withContext(Dispatchers.IO) {
                if (seenManifest != null) YGrammPeerRelay.saveLocalManifest(dir, seenManifest, epoch)
            }
            context.getSharedPreferences("ygggram_ui_prefs", Context.MODE_PRIVATE).edit()
                .putLong(PREF_LAST_CHECK, epoch)
                .putString(PREF_LAST_STATUS, describe(outcome)).apply()
            _state.update {
                it.copy(
                    busy = false,
                    available = (outcome as? YGrammCheckOutcome.Available)?.update,
                    // A new release invalidates any APK downloaded for the previous one.
                    downloadedApkPath = null,
                    downloadPercent = -1,
                    lastCheckedLabel = friendlyTime(epoch),
                    localManifestLabel = describeLocalManifest(dir),
                    statusLine = describe(outcome)
                )
            }
            if (outcome is YGrammCheckOutcome.Available) {
                YGrammUpdateEngine.clearDownloadedApk(context)
            }
        }
    }

    private fun describeLocalManifest(dir: java.io.File): String {
        val stored = YGrammPeerRelay.readLocalManifest(dir)
            ?: return "Своего манифеста релиза на устройстве нет: спрашивать APK у пира не с чем. " +
                "Нужна одна успешная проверка на GitHub либо манифест, вставленный вручную"
        val saved = if (stored.savedAtMs <= 0L) "дата сохранения не записана"
        else "сохранён ${friendlyTime(stored.savedAtMs)}"
        return "Манифест ${stored.manifest.versionName} (код ${stored.manifest.versionCode}) " +
            "получен этим устройством напрямую, $saved • sha256 ${stored.manifest.sha256Hex.take(16)}…"
    }

    private fun describe(outcome: YGrammCheckOutcome): String = when (outcome) {
        is YGrammCheckOutcome.UpToDate ->
            "Обновлений нет: на GitHub ${outcome.latestVersionName} (код ${outcome.latestVersionCode}), " +
                "здесь код ${outcome.runningVersionCode}"

        is YGrammCheckOutcome.Available ->
            "Доступно обновление: ${outcome.update.manifest.versionName} (код " +
                "${outcome.update.manifest.versionCode}), ${outcome.update.assetSizeBytes / 1024 / 1024} МБ"

        is YGrammCheckOutcome.Failed -> "Проверка не удалась: ${outcome.reason}"
    }

    fun downloadUpdate() {
        val context = appContext ?: return
        val update = _state.value.available ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, downloadPercent = 0, statusLine = "Скачиваю APK с GitHub…") }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                var lastPercent = -1
                YGrammUpdateEngine.downloadAndVerify(context, update) { done, total ->
                    val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                    // Progress comes at 64 KB per callback; only whole percents are worth a recomposition.
                    if (percent != lastPercent) {
                        lastPercent = percent
                        val snapshot = percent
                        _state.update { it.copy(downloadPercent = snapshot) }
                    }
                }
            }
            _state.update {
                when (outcome) {
                    is YGrammDownloadOutcome.Verified -> it.copy(
                        busy = false,
                        downloadPercent = 100,
                        downloadedApkPath = outcome.file.absolutePath,
                        statusLine = "Файл ${outcome.file.name} скачан: sha256 ${outcome.sha256Hex.take(16)}… " +
                            "совпал с релизом, подпись ${outcome.certDigestHex.take(16)}… совпала с релизным ключом. " +
                            "Можно устанавливать"
                    )

                    is YGrammDownloadOutcome.Failed -> it.copy(
                        busy = false,
                        downloadPercent = -1,
                        downloadedApkPath = null,
                        statusLine = "Скачивание отменено: ${outcome.reason}"
                    )
                }
            }
        }
    }

    /**
     * Hands the verified APK to the system installer. The pinned release certificate is re-read
     * here rather than trusted from an earlier session: the only file this will launch is one this
     * device already watched being downloaded, but "earlier" may mean before a bad storage layer,
     * a restored directory or a version-code rollback.
     */
    fun installUpdate() {
        val context = appContext ?: return
        if (_state.value.busy) return
        val file = _state.value.downloadedApkPath?.let { File(it) }
        if (file == null || !file.exists()) {
            _state.update { it.copy(statusLine = "Сначала скачайте файл: он ещё не готов") }
            return
        }
        _state.update { it.copy(busy = true, statusLine = "Проверяю подпись готового файла…") }
        scope.launch {
            // Reading the signature means hashing the whole archive: off the main thread, or a
            // fifteen megabyte APK stalls the settings screen before the installer ever opens.
            val cert = withContext(Dispatchers.IO) {
                YGrammUpdateEngine.apkCertificateDigestHex(context, file)
            }
            if (cert != YGrammUpdateEngine.PINNED_RELEASE_CERT_SHA256_HEX) {
                _state.update {
                    it.copy(
                        busy = false,
                        downloadedApkPath = null,
                        statusLine = if (cert == null) "Файл ${file.name} больше не читается — установка отменена"
                        else "Файл ${file.name} подписан не релизным ключом ($cert…) — установка отменена, " +
                            "файл нужно скачать заново"
                    )
                }
                return@launch
            }
            if (!YGrammUpdateEngine.canRequestInstall(context)) {
                _state.update {
                    it.copy(
                        busy = false,
                        installPermissionMissing = true,
                        statusLine = "Приложению не разрешена установка из неизвестных источников — открываю системные настройки"
                    )
                }
                context.startActivity(YGrammUpdateEngine.unknownSourcesSettingsIntent())
                return@launch
            }
            val intent = YGrammUpdateEngine.installIntent(context, file)
            val launchable = context.packageManager.resolveActivity(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            ) != null
            if (!launchable) {
                _state.update {
                    it.copy(
                        busy = false,
                        statusLine = "На устройстве нет установщика пакетов — установка невозможна"
                    )
                }
                return@launch
            }
            // The installer is a separate process: our job ends at handing it a granted URI. Anything
            // beyond that (the user tapping through it, a rejected signature) is reported by Android.
            context.startActivity(intent)
            _state.update {
                it.copy(
                    busy = false,
                    statusLine = "Системный установщик открыт: подтвердите установку в нём"
                )
            }
        }
    }

    fun refreshInstallPermission() {
        val context = appContext ?: return
        _state.update { it.copy(installPermissionMissing = !YGrammUpdateEngine.canRequestInstall(context)) }
    }

    // --- APK through a peer, for the case where GitHub itself is not reachable -------------

    fun setRelayOfferEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("ygggram_ui_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(YGrammPeerRelay.PREF_OFFER_ENABLED, enabled).apply()
        _state.update {
            it.copy(
                relayOfferEnabled = enabled,
                relayStatusLine = if (enabled) {
                    "Раздача включена: собеседник из личного диалога может попросить у этого устройства " +
                        "тот APK, который здесь уже сошёл с контрольной суммой GitHub"
                } else {
                    "Раздача выключена: входящие запросы на APK отклоняются, ничего не передаётся"
                }
            )
        }
    }

    /** The anchor this device holds, read from disk so a peer can never have written it. */
    fun localManifest(): YGrammUpdateManifest? = appContext?.let {
        YGrammPeerRelay.readLocalManifest(YGrammUpdateEngine.updateDir(it))?.manifest
    }

    fun importManifestFromText(raw: String) {
        val context = appContext ?: return
        val manifest = YGrammUpdateCodec.extractManifest(raw)
        if (manifest == null) {
            _state.update {
                it.copy(
                    statusLine = "Манифест не распознан: нужен блок ${YGrammUpdateCodec.MANIFEST_FENCE} " +
                        "с полями versionCode, versionName, apk и sha256 из описания релиза"
                )
            }
            return
        }
        val dir = YGrammUpdateEngine.updateDir(context)
        val saved = YGrammPeerRelay.saveLocalManifest(dir, manifest, System.currentTimeMillis())
        _state.update {
            it.copy(
                localManifestLabel = describeLocalManifest(dir),
                statusLine = if (saved) {
                    "Якорь принят: ${manifest.versionName} (код ${manifest.versionCode}) — " +
                        "теперь пир может передать этот APK"
                } else {
                    "Манифест не сохранён: его не удалось записать в папку приложения, " +
                        "обновление через пира недоступно"
                }
            )
        }
    }

    /**
     * Called by the ViewModel before the frame goes out. Only one relay runs at a time: two
     * simultaneous streams for one dialog would fight over the same resume positions.
     */
    fun beginRelayRequest(transferId: String, manifest: YGrammUpdateManifest): Boolean {
        if (_state.value.relayBusy) return false
        pendingRelay = PendingRelay(transferId, manifest.apkAssetName, manifest.sha256Hex)
        _state.update {
            it.copy(
                relayBusy = true,
                relayTransferId = transferId,
                relayPercent = -1,
                relayStatusLine = "Запрос на ${manifest.versionName} отправлен: ждём ответа собеседника"
            )
        }
        return true
    }

    fun onRelayServing(peerLabel: String) {
        _state.update {
            it.copy(relayStatusLine = "$peerLabel начал передачу файла — куски идут по оверлею")
        }
    }

    fun onRelayHandshakeFailed(reason: String) {
        pendingRelay = null
        _state.update {
            it.copy(relayBusy = false, relayTransferId = "", relayPercent = -1, relayStatusLine = reason)
        }
    }

    fun relayProgress(transferId: String, doneBytes: Long, totalBytes: Long) {
        val pending = pendingRelay ?: return
        if (pending.transferId != transferId) return
        val percent = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
        _state.update {
            it.copy(
                relayPercent = percent,
                relayStatusLine = "Пир передаёт файл: ${doneBytes / 1024} КБ из ${totalBytes / 1024} КБ"
            )
        }
    }

    fun cancelRelay() {
        pendingRelay = null
        _state.update {
            it.copy(
                relayBusy = false,
                relayTransferId = "",
                relayPercent = -1,
                relayStatusLine = "Передача отменена: если куски ещё придут, они будут удалены, " +
                    "потому что заказа на них больше нет"
            )
        }
    }

    /**
     * A peer finished carrying the bytes. Transport integrity (the Merkle root) was already
     * checked inside the stream; this is the provenance half, and it is checked against this
     * device's own records rather than against anything the peer claimed.
     */
    fun onRelayApkDelivered(transferId: String, localPath: String) {
        val context = appContext ?: return
        val pending = pendingRelay
        val source = File(localPath)
        if (pending == null || pending.transferId != transferId) {
            // Unsolicited or expired: the bytes came without an order, so they are thrown away
            // before anything reads them.
            scope.launch { withContext(Dispatchers.IO) { source.delete() } }
            _state.update {
                it.copy(relayStatusLine = "Принят файл, который это устройство не запрашивал — он удалён")
            }
            return
        }
        pendingRelay = null
        _state.update { it.copy(relayStatusLine = "Проверяю принятый от пира файл по своему манифесту…") }
        scope.launch {
            val verdict = withContext(Dispatchers.IO) { verifyRelayedApk(context, pending, source) }
            _state.update { current ->
                when (verdict) {
                    is RelayVerdict.Accepted -> current.copy(
                        relayBusy = false,
                        relayPercent = 100,
                        downloadedApkPath = verdict.file.absolutePath,
                        relayStatusLine = "APK от собеседника совпал с локальным манифестом " +
                            "(sha256 ${verdict.sha256Hex.take(16)}…) и с релизным ключом " +
                            "(${verdict.certDigestHex.take(16)}…). Можно устанавливать"
                    )

                    is RelayVerdict.Rejected -> current.copy(
                        relayBusy = false,
                        relayPercent = -1,
                        relayStatusLine = verdict.reason
                    )
                }
            }
        }
    }

    private sealed class RelayVerdict {
        data class Accepted(val file: File, val sha256Hex: String, val certDigestHex: String) : RelayVerdict()
        data class Rejected(val reason: String) : RelayVerdict()
    }

    private fun verifyRelayedApk(context: Context, pending: PendingRelay, source: File): RelayVerdict {
        fun discard() {
            source.delete()
            YGrammUpdateEngine.updateDir(context).listFiles()?.forEach {
                if (it.name.startsWith("YGramm-")) it.delete()
            }
        }
        if (!source.isFile || source.length() <= 0L) {
            return RelayVerdict.Rejected("Пиру нечем было поделиться: принят пустой файл")
        }
        val digest = try {
            YGrammUpdateCodec.sha256HexOfFile(source)
        } catch (_: Throwable) {
            return RelayVerdict.Rejected("Принятый файл не читается до конца — контрольную сумму построить нечем")
        }
        if (digest != pending.expectedSha256Hex) {
            discard()
            return RelayVerdict.Rejected(
                "Файл от собеседника не совпал с манифестом этого устройства: ожидалась " +
                    "${pending.expectedSha256Hex.take(16)}…, получена ${digest.take(16)}… Файл удален"
            )
        }
        val cert = YGrammUpdateEngine.apkCertificateDigestHex(context, source)
        if (cert == null) {
            discard()
            return RelayVerdict.Rejected("Подпись принятого APK не читается — установка не предлагается")
        }
        if (cert != YGrammUpdateEngine.PINNED_RELEASE_CERT_SHA256_HEX) {
            discard()
            return RelayVerdict.Rejected(
                "APK подписан другим ключом ($cert…), а не релизным " +
                    "(${YGrammUpdateEngine.PINNED_RELEASE_CERT_SHA256_HEX.take(16)}…). Файл удален"
            )
        }
        val dir = YGrammUpdateEngine.updateDir(context)
        val target = File(dir, pending.apkAssetName)
        val carriedBytes = source.length()
        val moved = runCatching {
            target.delete()
            source.copyTo(target, overwrite = true)
            val intact = target.isFile && target.length() == carriedBytes
            source.delete()
            intact
        }.getOrDefault(false)
        if (!moved || !target.isFile) {
            discard()
            return RelayVerdict.Rejected("Проверенный файл не удалось сохранить рядом с приложением")
        }
        return RelayVerdict.Accepted(target, digest, cert)
    }
}
