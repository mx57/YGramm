// File: app/src/main/java/com/example/viewmodel/controllers/VaultIdentityController.kt
package com.example.viewmodel.controllers

import android.app.Application
import com.example.data.UserProfileEntity
import com.example.data.YggRepository
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.YggCryptoEngine
import com.example.engine.YggTwinP2PBridge
import com.example.viewmodel.DecentralizedUserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Controller responsible for BIP-39 / SLIP-0010 cryptographic keys,
 * decentralized identities, profile switching, and cross-platform backup sync.
 */
class VaultIdentityController(
    private val application: Application,
    private val repository: YggRepository,
    private val networkEngine: YggTwinP2PBridge,
    private val scope: CoroutineScope,
    private val onBannerMessage: (String) -> Unit,
    private val onProfileUpdated: (UserProfileEntity) -> Unit,
    private val onSyncForegroundNotification: () -> Unit
) {

    private val _isSeedPhraseRevealed = MutableStateFlow(false)
    val isSeedPhraseRevealed: StateFlow<Boolean> = _isSeedPhraseRevealed.asStateFlow()

    fun toggleRevealSeedPhrase() {
        _isSeedPhraseRevealed.value = !_isSeedPhraseRevealed.value
    }

    fun regenerateNewSeedIdentity(currentProfileId: String) {
        val freshWords = YggCryptoEngine.generateFreshMnemonic12()
        val freshIdentity = YggCryptoEngine.deriveHierarchyFromMnemonic(freshWords, epoch = 1)
        scope.launch {
            val updatedProfile = repository.regenerateProfileIdentity(
                profileId = currentProfileId,
                identity = freshIdentity
            )
            if (updatedProfile == null) {
                onBannerMessage("Не удалось перегенерировать идентичность: профиль не найден")
                return@launch
            }
            onProfileUpdated(updatedProfile)
            networkEngine.syncConcurrentProfileListeners(repository.getAllProfilesNow())
            onBannerMessage("Сгенерирована новая BIP-39 мнемоника и IPv6-адрес узла ${freshIdentity.yggIpv6Address.take(19)}...")
            onSyncForegroundNotification()
        }
    }

    fun triggerCrossPlatformSyncNow(userProfile: DecentralizedUserProfile, identity: DerivedIdentityHierarchy) {
        scope.launch {
            if (!networkEngine.isRealNetworkAvailable() || networkEngine.isAirplaneModeActive()) {
                onBannerMessage("Синхронизация невозможна: устройство офлайн или в Авиарежиме")
                return@launch
            }
            networkEngine.broadcastLanDiscoveryBeacon(
                displayName = userProfile.displayName,
                yggIpv6 = identity.yggIpv6Address,
                pubKeyHex = identity.yggNodePublicKeyHex
            )
            val newVector = repository.syncAllLinkedDevicesNow(isOnline = true)
            onBannerMessage(
                if (newVector > 0) "Запрос синхронизации отправлен в LAN и оверлей Yggdrasil (StateVector #$newVector)"
                else "Отправлен LAN-маяк синхронизации (нет привязанных устройств)"
            )
        }
    }

    fun pairNewDevice(
        deviceName: String,
        platform: String,
        yggIpv6OrCode: String,
        lanEndpoint: String
    ) {
        if (deviceName.isBlank()) return
        scope.launch {
            repository.linkNewCrossPlatformDevice(
                deviceName = deviceName,
                platform = platform,
                yggIpv6OrCode = yggIpv6OrCode,
                lanEndpoint = lanEndpoint,
                isOnline = networkEngine.isRealNetworkAvailable()
            )
            onBannerMessage("Устройство «$deviceName» привязано к ZK-хранилищу")
        }
    }
}
