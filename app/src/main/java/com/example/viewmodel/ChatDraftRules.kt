package com.example.viewmodel

/** What the composer's latest text asks the stored draft of this chat to do. */
internal enum class DraftWrite { NONE, CLEAR, SAVE }

/**
 * A draft is written on a short delay after typing stops, and the delay is deliberately the only
 * waiting involved: the owner may leave the chat mid-word, and a note kept in the chat they were
 * writing belongs to that chat row, not to whatever is on screen when the timer expires. Dropping
 * the write as soon as the selection moved is the bug this rule exists to avoid - it eats exactly
 * the draft the feature was added to keep.
 *
 * An emptied composer is the one write that must not wait: it is a note already deleted, and a
 * chat re-opened before the delay would show it again.
 */
internal fun planDraftWrite(text: String, chatId: String): DraftWrite = when {
    chatId.isBlank() -> DraftWrite.NONE
    text.isEmpty() -> DraftWrite.CLEAR
    else -> DraftWrite.SAVE
}

/**
 * The row line a chat shows. A stored note wins over the last sent message because it is the
 * thing the owner still owes this chat, and an unread note hidden behind an old message reads as
 * if it were lost.
 */
internal fun rowPreviewText(lastPreview: String, draftText: String): String {
    val draft = draftText.trim()
    if (draft.isEmpty()) return lastPreview
    return "$DRAFT_PREFIX${draft.lineSequence().first().take(80)}"
}

private const val DRAFT_PREFIX = "Черновик: "
