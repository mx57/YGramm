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
    val installPermissionMissing: Boolean = false
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
                    statusLine = describe(outcome)
                )
            }
            if (outcome is YGrammCheckOutcome.Available) {
                YGrammUpdateEngine.clearDownloadedApk(context)
            }
        }
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
     * Hands the verified APK to the system installer. Returns false and explains why instead of
     * launching an intent the user cannot act on.
     */
    fun installUpdate(): Boolean {
        val context = appContext ?: return false
        val path = _state.value.downloadedApkPath
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            _state.update { it.copy(statusLine = "Сначала скачайте файл: он ещё не готов") }
            return false
        }
        if (!YGrammUpdateEngine.canRequestInstall(context)) {
            _state.update {
                it.copy(
                    installPermissionMissing = true,
                    statusLine = "Приложению не разрешена установка из неизвестных источников — открываю системные настройки"
                )
            }
            context.startActivity(YGrammUpdateEngine.unknownSourcesSettingsIntent())
            return false
        }
        val intent = YGrammUpdateEngine.installIntent(context, file)
        val launchable = context.packageManager.resolveActivity(
            intent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        ) != null
        if (!launchable) {
            _state.update { it.copy(statusLine = "На устройстве нет установщика пакетов — установка невозможна") }
            return false
        }
        // The installer is a separate process: our job ends at handing it a granted URI. Anything
        // beyond that (the user tapping through it, a rejected signature) is reported by Android.
        context.startActivity(intent)
        _state.update {
            it.copy(statusLine = "Системный установщик открыт: подтвердите установку в нём")
        }
        return true
    }

    fun refreshInstallPermission() {
        val context = appContext ?: return
        _state.update { it.copy(installPermissionMissing = !YGrammUpdateEngine.canRequestInstall(context)) }
    }
}
