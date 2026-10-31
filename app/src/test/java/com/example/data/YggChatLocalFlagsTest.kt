package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Four things a person does to a chat on this device: silence it, put it out of the list, name it
 * themselves, and leave half a message in the composer. None of them travels on the wire, and each
 * one is stored in a column the other writers of the same row have never heard of - so every one of
 * these tests is about the flag still being there after something else touched the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggChatLocalFlagsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.yggDao()
    private val repo = YggRepository(dao)

    @After
    fun cleanUp() {
        db.close()
    }

    private fun conversation(chatId: String) = ConversationEntity(
        chatId = chatId,
        title = "Пир 2001:db8:cafe:0:0:0:0:1",
        yggIpv6Address = "2001:db8:cafe::1",
        publicKeyHex = "00",
        protocolType = "YGG_X25519_AESGCM",
        isVerified = false,
        sealedSenderEnabled = true,
        disappearingTtlSeconds = 0,
        unreadCount = 0,
        lastMessagePreview = "",
        lastTimestamp = 100L,
        isOnlineInMesh = true,
        avatarColorHex = "#00AA88",
        bioStatus = "",
        routeTransportSummary = ""
    )

    private suspend fun seed(chatId: String = "chat_a") {
        dao.upsertConversation(conversation(chatId))
    }

    @Test
    fun localFlagsSurviveAWholeRowWriter() = runBlocking {
        seed()
        dao.setConversationMuted("chat_a", true)
        dao.setConversationArchived("chat_a", true)
        dao.setConversationDraft("chat_a", "не отправлено")

        // The unread-reset path re-saves the row it just read. Anything it did not read would be
        // lost with a full REPLACE, so this is the one writer that can quietly erase a flag.
        val read = dao.getConversationById("chat_a")!!
        dao.upsertConversation(read.copy(unreadCount = 4, lastMessagePreview = "привет"))

        val reloaded = dao.getConversationById("chat_a")!!
        assertTrue(reloaded.isMuted)
        assertTrue(reloaded.isArchived)
        assertEquals("не отправлено", reloaded.draftText)
        assertEquals(4, reloaded.unreadCount)
    }

    @Test
    fun muteAndArchiveAreIndependentChoices() = runBlocking {
        seed()
        dao.setConversationArchived("chat_a", true)
        val archived = dao.getConversationById("chat_a")!!
        assertFalse("archiving must not silence anything", archived.isMuted)

        dao.setConversationMuted("chat_a", true)
        dao.setConversationArchived("chat_a", false)
        val both = dao.getConversationById("chat_a")!!
        assertTrue("undoing the archive must not undo the silence", both.isMuted)
        assertFalse(both.isArchived)
    }

    @Test
    fun aDraftIsWrittenPerChatAndAnEmptyWriteClearsIt() = runBlocking {
        seed("chat_a")
        seed("chat_b")
        dao.setConversationDraft("chat_a", "пишу в первый")
        dao.setConversationDraft("chat_b", "пишу во второй")

        assertEquals("пишу в первый", dao.getConversationById("chat_a")!!.draftText)
        assertEquals("пишу во второй", dao.getConversationById("chat_b")!!.draftText)

        dao.setConversationDraft("chat_a", "")
        assertEquals("", dao.getConversationById("chat_a")!!.draftText)
        assertEquals("пишу во второй", dao.getConversationById("chat_b")!!.draftText)
    }

    @Test
    fun repositoryRefusesToWriteADraftForNoChat() = runBlocking {
        seed()
        repo.setConversationDraft("", "мусор")
        assertEquals("", dao.getConversationById("chat_a")!!.draftText)
    }

    @Test
    fun aRenamedContactKeepsItsNameThroughTheNextProfileFrame() = runBlocking {
        seed()
        assertTrue(repo.renameConversation("chat_a", "Марина"))
        val locked = dao.getConversationById("chat_a")!!
        assertTrue("the lock is what makes the rename last", locked.titleLockedByUser)

        // What a PROFILE frame does to the row: the peer still calls themselves something else.
        dao.updatePeerProfile(
            chatId = "chat_a",
            title = "Marina V.",
            peerHandle = "@marina",
            peerStatusText = "на связи",
            peerProfileVersion = 7L
        )
        val after = dao.getConversationById("chat_a")!!
        assertEquals("Марина", after.title)
        assertEquals("@marina", after.peerHandle)
        assertEquals("на связи", after.peerStatusText)
        assertEquals(7L, after.peerProfileVersion)
    }

    @Test
    fun aChatNobodyRenamedStillFollowsWhatThePeerClaims() = runBlocking {
        seed()
        dao.updatePeerProfile("chat_a", "Marina V.", "@marina", "", 3L)
        val after = dao.getConversationById("chat_a")!!
        assertEquals("Marina V.", after.title)
        assertFalse(after.titleLockedByUser)
    }

    @Test
    fun renameTrimsAndRefusesANameThatWouldChangeNothing() = runBlocking {
        seed()
        assertFalse("a blank name never reaches the row", repo.renameConversation("chat_a", "   "))
        assertEquals("Пир 2001:db8:cafe:0:0:0:0:1", dao.getConversationById("chat_a")!!.title)

        assertTrue(repo.renameConversation("chat_a", "  Марина  "))
        assertEquals("Марина", dao.getConversationById("chat_a")!!.title)
        assertFalse("the same name twice is not a write", repo.renameConversation("chat_a", "Марина"))

        assertFalse(repo.renameConversation("chat_missing", "Кто-то"))
        val trimmed = "И".repeat(90)
        assertTrue(repo.renameConversation("chat_a", trimmed))
        assertEquals(80, dao.getConversationById("chat_a")!!.title.length)
    }
}
