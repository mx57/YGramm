package com.example.ui.screens

import com.example.data.ConversationEntity

/**
 * Which chats a chip in the chat list shows.
 *
 * Kept out of the composable because both flags decide where a conversation is reachable from,
 * and an archived chat that silently vanished would look like a deleted one.
 */
internal fun conversationMatchesChatFilter(
    conversation: ConversationEntity,
    filterKey: String,
    isOfflineStoreAndForwardMode: Boolean
): Boolean = when (filterKey) {
    "DIRECT" -> !conversation.isArchived && !conversation.chatId.startsWith("chat_self_ygg_node")
    "OFFLINE_SF" -> !conversation.isArchived &&
        (!conversation.isOnlineInMesh || isOfflineStoreAndForwardMode)
    // An archived chat is hidden, not removed: its own chip is the only place it comes back.
    "ARCHIVE" -> conversation.isArchived
    else -> !conversation.isArchived
}

/** The search box matches the chat list on the names this device holds, plus the keys it knows. */
internal fun conversationMatchesSearchQuery(
    conversation: ConversationEntity,
    query: String
): Boolean {
    if (query.isBlank()) return true
    return conversation.title.contains(query, ignoreCase = true) ||
        conversation.yggIpv6Address.contains(query, ignoreCase = true) ||
        conversation.publicKeyHex.contains(query, ignoreCase = true) ||
        conversation.peerHandle.contains(query, ignoreCase = true)
}
