package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggChatPinTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.yggDao()

    @After
    fun cleanUp() {
        db.close()
    }

    private fun conversation(chatId: String, timestamp: Long) = ConversationEntity(
        chatId = chatId,
        title = chatId,
        yggIpv6Address = "200::1",
        publicKeyHex = "00",
        protocolType = "YGG_X25519_AESGCM",
        isVerified = false,
        sealedSenderEnabled = false,
        disappearingTtlSeconds = 0,
        unreadCount = 0,
        lastMessagePreview = "",
        lastTimestamp = timestamp,
        isOnlineInMesh = false,
        avatarColorHex = "#00AA88",
        bioStatus = "",
        routeTransportSummary = ""
    )

    @Test
    fun pinnedChatStaysAboveNewerUnpinnedChats() = runBlocking {
        dao.upsertConversation(conversation("older", 100L))
        dao.upsertConversation(conversation("newer", 200L))
        dao.setConversationPinned("older", true)
        val order = dao.observeConversations().first().map { it.chatId }
        assertEquals(listOf("older", "newer"), order)
        dao.setConversationPinned("older", false)
        val unpinned = dao.observeConversations().first().map { it.chatId }
        assertEquals(listOf("newer", "older"), unpinned)
    }

    @Test
    fun pinSurvivesFullRowResavesFromOtherWriters() = runBlocking {
        dao.upsertConversation(conversation("chat", 100L))
        dao.setConversationPinned("chat", true)
        // The unread-reset path re-saves the whole row from a copy it just read; the pin must
        // still be there afterwards, same guarantee the avatar and profile fields rely on.
        val read = dao.getConversationById("chat")!!
        dao.upsertConversation(read.copy(unreadCount = 3, lastMessagePreview = "привет"))
        val reloaded = dao.getConversationById("chat")!!
        assertTrue(reloaded.isPinned)
        assertEquals(3, reloaded.unreadCount)
        dao.setConversationPinned("chat", false)
        assertFalse(dao.getConversationById("chat")!!.isPinned)
    }
}
