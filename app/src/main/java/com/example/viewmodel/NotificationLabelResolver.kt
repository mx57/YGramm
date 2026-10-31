package com.example.viewmodel

/**
 * Turns what a device knows about a sender into the one line a notification gets to use.
 *
 * The wire only ever names a node by its address unless the peer sent a profile envelope, and
 * the raw address is unreadable at a glance on a lock screen. Every local name this device holds
 * - the owner's own rename, the handle the peer proved, the group's title - outranks it. When
 * none of those exist the address stays, shortened: inventing a name for an anonymous node would
 * be a lie, and the full value remains in the expanded body.
 */

/** Four colon-separated hex groups: any real or compressed IPv6 form, and nothing a person is called. */
private val IPV6_SHAPED = Regex("(?i)([0-9a-f]{1,4}:){3}[0-9a-f]{1,4}")

/** A label that actually names someone, or "" when the string is a placeholder or an address. */
internal fun realLabel(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return ""
    if (value.startsWith("Пир ") || value.startsWith("Узел ")) return ""
    if (IPV6_SHAPED.containsMatchIn(value)) return ""
    return value
}

/**
 * The first line of a heads-up for one chat.
 *
 * DIRECT: the chat's own title wins because that is what this device calls the contact - the
 * owner's rename, or the name the peer proved through a sealed profile. The peer's claimed sender
 * label is only the fallback when the chat has no title yet. A proved @handle is appended so the
 * same display name cannot hide two different people.
 *
 * GROUP: the member who spoke comes first, then the group, because a group frame is addressed to
 * the group and read by its members.
 */
internal fun notificationSenderLabel(
    chatType: String,
    chatTitle: String,
    peerHandle: String,
    senderName: String,
    senderIpv6: String
): String {
    val title = realLabel(chatTitle)
    val member = realLabel(senderName)
    val handle = realLabel(peerHandle).removePrefix("@")
    val isGroup = chatType.equals("GROUP", ignoreCase = true)

    val address = senderIpv6.trim()
    val lastResort = if (address.isEmpty()) {
        "Собеседник"
    } else {
        "Узел ${address.take(8)}…"
    }

    return when {
        isGroup && title.isNotEmpty() && member.isNotEmpty() -> "$member • $title"
        isGroup && title.isNotEmpty() -> title
        isGroup && member.isNotEmpty() -> member
        title.isNotEmpty() && handle.isNotEmpty() -> "$title • @$handle"
        title.isNotEmpty() -> title
        member.isNotEmpty() && handle.isNotEmpty() -> "$member • @$handle"
        member.isNotEmpty() -> member
        handle.isNotEmpty() -> "@$handle"
        else -> lastResort
    }
}
