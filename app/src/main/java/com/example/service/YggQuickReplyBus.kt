package com.example.service

import android.content.Context
import org.json.JSONObject

/**
 * Carries a reply typed in a notification from the broadcast receiver to the live
 * ViewModel. If the process has no ViewModel yet (cold start from the notification),
 * the reply is persisted and delivered on the next initialization.
 */
object YggQuickReplyBus {

    data class PendingReply(
        val profileId: String,
        val chatId: String,
        val text: String
    )

    private const val PREFS_NAME = "ygggram_quick_replies"
    private const val KEY_PENDING = "pending"
    private val lock = Any()

    @Volatile
    private var handler: ((PendingReply) -> Unit)? = null

    fun submit(context: Context, reply: PendingReply) {
        if (reply.chatId.isBlank() || reply.text.isBlank()) return
        synchronized(lock) {
            val active = handler
            if (active != null) {
                active(reply)
                return
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_PENDING, "").orEmpty()
            val line = JSONObject().apply {
                put("profileId", reply.profileId)
                put("chatId", reply.chatId)
                put("text", reply.text)
                put("timestamp", System.currentTimeMillis())
            }.toString()
            prefs.edit()
                .putString(KEY_PENDING, if (existing.isBlank()) line else "$existing\n$line")
                .commit()
        }
    }

    fun registerHandler(handler: (PendingReply) -> Unit) {
        synchronized(lock) { this.handler = handler }
    }

    fun unregisterHandler() {
        synchronized(lock) { handler = null }
    }

    fun drain(context: Context): List<PendingReply> = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PENDING, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        prefs.edit().remove(KEY_PENDING).commit()
        raw.lineSequence().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                PendingReply(
                    profileId = json.optString("profileId"),
                    chatId = json.optString("chatId"),
                    text = json.optString("text")
                )
            }.getOrNull()
        }.filter { it.chatId.isNotBlank() && it.text.isNotBlank() }.toList()
    }
}
