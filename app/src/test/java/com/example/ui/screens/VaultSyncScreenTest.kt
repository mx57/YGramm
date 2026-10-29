package com.example.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.CryptoKeyRecordEntity
import com.example.engine.DerivedIdentityHierarchy
import com.example.viewmodel.YggGramUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h891dp")
class VaultSyncScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun regenerationRequiresConfirmationAndCancelDoesNothing() {
        var regenerations = 0
        composeRule.setContent {
            TestVault(onRegenerateIdentity = { regenerations++ })
        }

        val regenerate = composeRule.onNodeWithTag("regenerate_seed_button")
        regenerate.performScrollTo().performClick()
        composeRule.onNodeWithTag("regenerate_identity_dialog").assertExists()
        composeRule.runOnIdle { assertEquals(0, regenerations) }

        composeRule.onNodeWithTag("cancel_regenerate_identity_button").performClick()
        composeRule.onNodeWithTag("regenerate_identity_dialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, regenerations) }

        regenerate.performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_regenerate_identity_button").performClick()
        composeRule.onNodeWithTag("regenerate_identity_dialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, regenerations) }
    }

    @Test
    fun keyTreeIsHiddenUntilExpandedAndNeverRevealsSeed() {
        var seedRevealRequests = 0
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            TestVault(onToggleSeedReveal = { seedRevealRequests++ })
        }

        val tree = composeRule.onNodeWithText("Дерево криптографических ключей (1)")
        val keyCard = composeRule.onNodeWithTag("crypto_key_card_test-key")
        val details = composeRule.onNodeWithTag("toggle_technical_key_details_button")
        tree.assertDoesNotExist()
        keyCard.assertDoesNotExist()

        details.performScrollTo().performClick()
        tree.assertExists()
        keyCard.performScrollTo().assertExists()
        restorationTester.emulateSavedInstanceStateRestore()
        keyCard.performScrollTo().assertExists()

        details.performScrollTo().performClick()
        tree.assertDoesNotExist()
        keyCard.assertDoesNotExist()
        composeRule.onNodeWithTag("toggle_seed_reveal_button")
            .performScrollTo().assertTextContains("Показать")
        composeRule.runOnIdle { assertEquals(0, seedRevealRequests) }
    }

    @Composable
    private fun TestVault(
        onRegenerateIdentity: () -> Unit = {},
        onToggleSeedReveal: () -> Unit = {}
    ) {
        MaterialTheme {
            VaultSyncScreen(
                uiState = YggGramUiState(
                    identity = DerivedIdentityHierarchy(
                        mnemonicWords = List(12) { "word${it + 1}" },
                        masterFingerprintHex = "00",
                        yggNodePublicKeyHex = "00",
                        yggIpv6Address = "",
                        yggSubnetPrefix = "",
                        x25519IdentityPubHex = "00",
                        x25519SignedPreKeyHex = "00",
                        mlsLeafKeyHex = "00",
                        backupMasterKeyFingerprint = "00"
                    )
                ),
                cryptoKeys = listOf(
                    CryptoKeyRecordEntity(
                        keyId = "test-key",
                        keyName = "Test key",
                        derivationPath = "m/0",
                        algorithm = "Ed25519",
                        publicKeyHex = "00",
                        fingerprintHex = "00",
                        createdAt = 0L,
                        rotationEpoch = 1,
                        isActive = true,
                        usageScope = "Test"
                    )
                ),
                linkedDevices = emptyList(),
                backupSnapshots = emptyList(),
                onToggleSeedReveal = onToggleSeedReveal,
                onRegenerateIdentity = onRegenerateIdentity,
                onSyncDevicesNow = {},
                onPairDevice = { _, _, _, _ -> },
                onExportDatabase = {},
                onVerifyExportedArchive = { _, _ -> },
                onRestoreFromArchive = {}
            )
        }
    }
}
