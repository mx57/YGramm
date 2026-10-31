package com.example.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The draft feature exists because a half-written message used to die when the chat was left, so
 * the case worth pinning is the one that looks like a race: the owner types, then moves away
 * before the debounce expires.
 */
class ChatDraftRulesTest {

    @Test
    fun typedTextIsScheduledForItsOwnChatEvenAfterTheOwnerLeaves() {
        // There is no "still selected" condition on purpose. The note belongs to the chat it was
        // typed in; refusing to store it because another chat is on screen is how the draft gets
        // eaten, which is the bug this whole feature is here to stop.
        assertEquals(DraftWrite.SAVE, planDraftWrite(text = "привет", chatId = "chat_a"))
        assertEquals(DraftWrite.SAVE, planDraftWrite(text = "   ", chatId = "chat_a"))
    }

    @Test
    fun emptyingTheComposerClearsAtOnce() {
        // Waiting here would leave the old text in the row until the delay expires, and a chat
        // re-opened in that window shows a note that was already deleted.
        assertEquals(DraftWrite.CLEAR, planDraftWrite(text = "", chatId = "chat_a"))
    }

    @Test
    fun nothingIsWrittenWithoutAChatToWriteTo() {
        assertEquals(DraftWrite.NONE, planDraftWrite(text = "привет", chatId = ""))
        assertEquals(DraftWrite.NONE, planDraftWrite(text = "", chatId = "  "))
    }

    @Test
    fun storedNoteWinsTheRowLineOverTheLastSentMessage() {
        assertEquals("Черновик: не забыл", rowPreviewText(lastPreview = "Принято", draftText = "не забыл"))
        // A draft is usually half-typed across lines; the row shows the first one and keeps the
        // rest for the composer.
        assertEquals("Черновик: строка одна",
            rowPreviewText(lastPreview = "Принято", draftText = "  строка одна\nстрока две"))
    }

    @Test
    fun blankNoteLeavesTheRowOnTheRealLastMessage() {
        // Whitespace left in the composer is not a message the owner still owes anyone, and a row
        // reading "Черновик:" with nothing after it would claim a note that is not there.
        assertEquals("Принято", rowPreviewText(lastPreview = "Принято", draftText = ""))
        assertEquals("Принято", rowPreviewText(lastPreview = "Принято", draftText = "   \n  "))
    }
}
