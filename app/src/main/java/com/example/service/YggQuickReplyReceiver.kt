package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

/** Receives the text typed into the notification's inline reply field. */
class YggQuickReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(EXTRA_QUICK_REPLY_TEXT)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val chatId = intent.getStringExtra(YggNotificationCenter.EXTRA_NOTIFICATION_CHAT_ID).orEmpty()
        if (chatId.isBlank()) return
        val profileId = intent.getStringExtra(
            YggNotificationCenter.EXTRA_NOTIFICATION_PROFILE_ID
        ).orEmpty()
        YggQuickReplyBus.submit(
            context,
            YggQuickReplyBus.PendingReply(profileId = profileId, chatId = chatId, text = text)
        )
        // The node service keeps the process and the P2P engine alive while the reply
        // is dispatched; notification actions are allowed to start a foreground service.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, YggNodeForegroundService::class.java)
            )
        }
    }

    companion object {
        const val EXTRA_QUICK_REPLY_TEXT = "extra_quick_reply_text"
    }
}
