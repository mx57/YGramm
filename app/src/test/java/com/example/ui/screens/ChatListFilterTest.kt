package com.example.ui.screens

import com.example.data.ConversationEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Archiving hides a chat, which is exactly the thing a user must never lose by accident: these
 * tests pin that an archived row still appears under its own chip, and that no other chip quietly
 * shows it.
 */
class ChatListFilterTest {

    private fun conversation(
        chatId: String = "chat_peer",
        title: String = "Марина",
        handle: String = "",
        address: String = "204:2e83:18af:1ff2::1",
        routeConfirmed: Boolean = true,
        archived: Boolean = false
    ) = ConversationEntity(
        chatId = chatId,
        title = title,
        yggIpv6Address = address,
        publicKeyHex = "aa",
        protocolType = "YGG_X25519_AESGCM",
        isVerified = false,
        sealedSenderEnabled = true,
        disappearingTtlSeconds = 0,
        unreadCount = 0,
        lastMessagePreview = "",
        lastTimestamp = 1L,
        isOnlineInMesh = routeConfirmed,
        avatarColorHex = "#00AA88",
        bioStatus = "",
        routeTransportSummary = "",
        peerHandle = handle,
        isArchived = archived
    )

    @Test
    fun `an archived chat is reachable only through the archive chip`() {
        val archived = conversation(archived = true)
        assertFalse(conversationMatchesChatFilter(archived, "ALL", false))
        assertFalse(conversationMatchesChatFilter(archived, "DIRECT", false))
        assertFalse(conversationMatchesChatFilter(archived, "OFFLINE_SF", true))
        assertTrue(conversationMatchesChatFilter(archived, "ARCHIVE", false))
    }

    @Test
    fun `a live chat is not hidden by the archive chip`() {
        assertFalse(conversationMatchesChatFilter(conversation(), "ARCHIVE", false))
        assertTrue(conversationMatchesChatFilter(conversation(), "ALL", false))
    }

    @Test
    fun `the self chat stays out of the personal chip`() {
        val self = conversation(chatId = "chat_self_ygg_node_primary")
        assertTrue(conversationMatchesChatFilter(self, "ALL", false))
        assertFalse(conversationMatchesChatFilter(self, "DIRECT", false))
    }

    @Test
    fun `the queue chip follows the route and the offline switch`() {
        val reachable = conversation(routeConfirmed = true)
        val unreachable = conversation(routeConfirmed = false)
        assertTrue(conversationMatchesChatFilter(unreachable, "OFFLINE_SF", false))
        assertFalse(conversationMatchesChatFilter(reachable, "OFFLINE_SF", false))
        // Store-and-forward mode on: everything waits in the queue, including a reachable peer.
        assertTrue(conversationMatchesChatFilter(reachable, "OFFLINE_SF", true))
    }

    @Test
    fun `search matches the names this device holds and the keys it knows`() {
        val conv = conversation(handle = "@marina")
        assertTrue(conversationMatchesSearchQuery(conv, ""))
        assertTrue(conversationMatchesSearchQuery(conv, "марин"))
        assertTrue(conversationMatchesSearchQuery(conv, "@MARINA"))
        assertTrue(conversationMatchesSearchQuery(conv, "204:2E83"))
        assertTrue(conversationMatchesSearchQuery(conv, "AA"))
        assertFalse(conversationMatchesSearchQuery(conv, "нет такого"))
    }
}
