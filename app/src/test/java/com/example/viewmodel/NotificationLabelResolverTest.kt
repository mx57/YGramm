package com.example.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A notification has one line to say who is writing, and until now that line carried the address
 * the wire happened to use. These are the rules for what may replace it - and the address has to
 * stay whenever this device genuinely knows nothing else, because a name invented for an anonymous
 * node is worse than an ugly one.
 */
class NotificationLabelResolverTest {

    private val address = "204:2e83:18af:1ff2:9c1e:eb01:b708:8dd0"

    private fun label(
        chatType: String = "DIRECT",
        title: String = "",
        handle: String = "",
        senderName: String = ""
    ) = notificationSenderLabel(
        chatType = chatType,
        chatTitle = title,
        peerHandle = handle,
        senderName = senderName,
        senderIpv6 = address
    )

    @Test
    fun `a renamed contact is named and the address is gone`() {
        assertEquals(
            "Марина",
            label(title = "Марина", senderName = "Пир $address")
        )
    }

    @Test
    fun `a placeholder title is no name at all`() {
        // Chat rows created from a bare frame are titled with the address. Repeating that in the
        // notification would change nothing, so the peer's own label still gets its turn.
        assertEquals("Marina V.", label(title = "Пир $address", senderName = "Marina V."))
        assertEquals("Marina V.", label(title = address, senderName = "Marina V."))
        assertEquals("Узел 204:2e83…", label(title = "Пир $address", senderName = "Узел Yggdrasil"))
    }

    @Test
    fun `a proved handle is shown next to the name`() {
        // Two peers can claim the same display name; the handle is what tells them apart at a
        // glance on a lock screen.
        assertEquals("Марина • @marina", label(title = "Марина", handle = "@marina"))
        assertEquals("@marina", label(handle = "@marina"))
    }

    @Test
    fun `a group names the member who spoke and the group`() {
        assertEquals("Пётр • Рабочая группа", label(chatType = "GROUP", title = "Рабочая группа", senderName = "Пётр"))
        assertEquals("Рабочая группа", label(chatType = "GROUP", title = "Рабочая группа", senderName = "Пир $address"))
        assertEquals("Пётр", label(chatType = "GROUP", senderName = "Пётр"))
    }

    @Test
    fun `nothing known about anyone leaves the shortened address`() {
        // The honest last resort: the body of the notification still carries the full address.
        assertEquals("Узел 204:2e83…", label(senderName = "Пир $address"))
        assertEquals("Собеседник", notificationSenderLabel("DIRECT", "", "", "", ""))
    }
}
