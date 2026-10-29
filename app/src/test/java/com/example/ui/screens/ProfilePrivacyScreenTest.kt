package com.example.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.data.UserProfileEntity
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
class ProfilePrivacyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeKeepsTechnicalControlsOutOfEverydaySettings() {
        composeRule.setContent { TestProfile() }
        composeRule.onNodeWithTag("edit_profile_button").assertExists()
        composeRule.onNodeWithTag("notif_hide_content_switch").assertDoesNotExist()
        composeRule.onNodeWithTag("pow_bits_chip_10").assertDoesNotExist()
        composeRule.onNodeWithTag("privacy_strip_metadata_switch").assertDoesNotExist()
        composeRule.onNodeWithTag("panic_wipe_button").assertDoesNotExist()
        openSection("EXPERT")
        composeRule.onNodeWithTag("profile_settings_list")
            .performScrollToNode(hasTestTag("pow_bits_chip_10"))
        composeRule.onNodeWithTag("pow_bits_chip_10").assertExists()
        composeRule.onNodeWithTag("theme_chip_cyber_emerald").assertDoesNotExist()
        composeRule.onNodeWithTag("profile_settings_back").performScrollTo().performClick()
        composeRule.onNodeWithTag("edit_profile_button").assertExists()
    }

    @Test
    fun eachNotificationSwitchChangesOnlyItsOwnCallbackField() {
        val changes = mutableListOf<List<Boolean?>>()
        composeRule.setContent {
            TestProfile(onNotifications = { changes.add(it) })
        }
        openSection("NOTIFICATIONS")
        listOf(
            "notif_hide_content_switch" to 6,
            "notif_messages_switch" to 3,
            "notif_voice_switch" to 4,
            "notif_calls_switch" to 5
        ).forEach { (tag, index) ->
            composeRule.onNodeWithTag("profile_settings_list").performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.runOnIdle {
                assertEquals(List<Boolean?>(7) { if (it == index) false else null }, changes.last())
            }
        }
    }

    @Test
    fun privacyKeepsScreenProtectionAndWipeButNotNetworkTuning() {
        composeRule.setContent { TestProfile() }
        openSection("PRIVACY")
        composeRule.onNodeWithTag("privacy_block_screenshots_switch").assertExists()
        composeRule.onNodeWithTag("ttl_chip_86400").assertExists()
        composeRule.onNodeWithTag("privacy_lan_discovery_switch").assertDoesNotExist()
        composeRule.onNodeWithTag("profile_settings_list").performScrollToNode(hasTestTag("panic_wipe_button"))
        composeRule.onNodeWithTag("panic_wipe_button").assertExists()
    }

    @Test
    fun keysRemainReachableAndSectionSurvivesRecreation() {
        var openedKeys = 0
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { TestProfile(onOpenKeys = { openedKeys++ }) }
        composeRule.onNodeWithTag("profile_settings_list").performScrollToNode(hasTestTag("profile_open_keys"))
        composeRule.onNodeWithTag("profile_open_keys").performClick()
        composeRule.runOnIdle { assertEquals(1, openedKeys) }
        openSection("NOTIFICATIONS")
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("notif_in_app_switch").assertExists()
        composeRule.onNodeWithTag("edit_profile_button").assertDoesNotExist()
    }

    @Test
    fun secondaryProfileDeletionRequiresConfirmationAndCancelDoesNothing() {
        val deleted = mutableListOf<String>()
        composeRule.setContent {
            TestProfile(
                profiles = listOf(profile("primary", true), profile("secondary", false)),
                onDeleteProfile = { deleted.add(it) }
            )
        }
        openSection("PROFILES")
        composeRule.onNodeWithTag("profile_settings_list")
            .performScrollToNode(hasTestTag("delete_profile_secondary"))
        composeRule.onNodeWithTag("delete_profile_primary").assertDoesNotExist()
        composeRule.onNodeWithTag("delete_profile_secondary").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), deleted) }
        composeRule.onNodeWithText("Отмена").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), deleted) }
        composeRule.onNodeWithTag("delete_profile_secondary").performClick()
        composeRule.onNodeWithTag("confirm_delete_profile_button").performClick()
        composeRule.runOnIdle { assertEquals(listOf("secondary"), deleted) }
    }

    // Robolectric #8460: custom screen dimensions keep dialog text fields from settling.
    @Test
    @Config(qualifiers = "mdpi")
    fun normalProfileCreationKeepsTheDefaultRoleWithoutShowingTechnicalOptions() {
        val roles = mutableListOf<String>()
        composeRule.setContent { TestProfile(onCreateRole = { roles.add(it) }) }
        openSection("PROFILES")
        composeRule.onNodeWithTag("profile_settings_list")
            .performScrollToNode(hasTestTag("create_new_profile_button"))
        composeRule.onNodeWithTag("create_new_profile_button").performClick()
        composeRule.onNodeWithTag("new_profile_name_input").assertExists()
        composeRule.onNodeWithTag("new_profile_role_PEER_RELAY").assertDoesNotExist()
        composeRule.onNodeWithTag("confirm_create_profile_button").performClick()
        composeRule.runOnIdle { assertEquals(listOf("PEER_RELAY"), roles) }
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun expertProfileCreationKeepsRoleSelectionAvailable() {
        val roles = mutableListOf<String>()
        composeRule.setContent { TestProfile(onCreateRole = { roles.add(it) }) }
        openSection("EXPERT")
        composeRule.onNodeWithTag("profile_settings_list")
            .performScrollToNode(hasTestTag("create_new_profile_button"))
        composeRule.onNodeWithTag("create_new_profile_button").performClick()
        composeRule.onNodeWithTag("new_profile_role_CLIENT_ONLY").onParent().performScrollTo()
        composeRule.onNodeWithTag("new_profile_role_CLIENT_ONLY")
            .performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        composeRule.onNodeWithTag("confirm_create_profile_button").performClick()
        composeRule.runOnIdle { assertEquals(listOf("CLIENT_ONLY"), roles) }
    }

    @Test
    fun relayUpdateControlsAreHiddenUntilExpandedAndCanBeCollapsed() {
        composeRule.setContent { TestProfile() }
        openSection("UPDATES")
        composeRule.onNodeWithTag("relay_manifest_field").assertDoesNotExist()
        composeRule.onNodeWithTag("profile_settings_list")
            .performScrollToNode(hasTestTag("update_relay_details_button"))
        composeRule.onNodeWithTag("update_relay_details_button").performClick()
        composeRule.onNodeWithTag("relay_manifest_field").assertExists()
        composeRule.onNodeWithTag("update_relay_details_button").performClick()
        composeRule.onNodeWithTag("relay_manifest_field").assertDoesNotExist()
    }

    private fun openSection(name: String) {
        val tag = "profile_section_$name"
        composeRule.onNodeWithTag("profile_settings_list").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun profile(id: String, primary: Boolean) = UserProfileEntity(
        profileId = id,
        displayName = id,
        handle = "@$id",
        bio = "",
        statusText = "",
        mnemonicCsv = "test-only",
        yggIpv6Address = "200::1",
        yggPublicKeyHex = "00",
        allocatedTcpPort = 51921,
        isActiveInBackground = true,
        isCurrentPrimary = primary,
        avatarColorHex = "#00AA88",
        createdAt = 1L
    )

    @Composable
    private fun TestProfile(
        profiles: List<UserProfileEntity> = emptyList(),
        onNotifications: (List<Boolean?>) -> Unit = {},
        onOpenKeys: () -> Unit = {},
        onDeleteProfile: (String) -> Unit = {},
        onCreateRole: (String) -> Unit = {}
    ) {
        MaterialTheme {
            ProfilePrivacyScreen(
                uiState = YggGramUiState(
                    currentProfileId = profiles.firstOrNull { it.isCurrentPrimary }?.profileId ?: "test-profile",
                    identity = DerivedIdentityHierarchy(
                        mnemonicWords = List(12) { "word${it + 1}" },
                        masterFingerprintHex = "00",
                        yggNodePublicKeyHex = "00",
                        yggIpv6Address = "200::1",
                        yggSubnetPrefix = "300::",
                        x25519IdentityPubHex = "00",
                        x25519SignedPreKeyHex = "00",
                        mlsLeafKeyHex = "00",
                        backupMasterKeyFingerprint = "00"
                    )
                ),
                userProfiles = profiles,
                onUpdateProfile = { _, _, _, _ -> },
                onUpdatePrivacy = { _, _, _, _, _, _, _, _ -> },
                onUpdateNotifications = { inApp, system, suppress, messages, voice, calls, hide ->
                    onNotifications(listOf(inApp, system, suppress, messages, voice, calls, hide))
                },
                onOpenKeys = onOpenKeys,
                onDeleteSecondaryProfile = onDeleteProfile,
                onCreateNewProfile = { _, _, _, _, _, role -> onCreateRole(role) }
            )
        }
    }
}
