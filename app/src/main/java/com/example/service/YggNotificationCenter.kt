// File: app/src/main/java/com/example/service/YggNotificationCenter.kt
package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

enum class YggAlertCategory {
    INCOMING_CALL,
    MISSED_CALL,
    NEW_VOICE,
    NEW_MESSAGE
}

data class YggPopupNotificationItem(
    val notificationId: String,
    val category: YggAlertCategory,
    val targetProfileId: String,
    val targetProfileName: String,
    val targetProfileIpv6: String,
    val targetProfileColorHex: String = "#10B981",
    val targetProfileRelayRole: String = "PEER_RELAY",
    val chatId: String,
    val senderName: String,
    val senderIpv6: String,
    val messagePreview: String,
    val voiceDurationSec: Int = 0,
    val callId: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Dispatches high-priority Android notifications for all enabled Yggdrasil
 * profiles. Incoming calls get a call-style notification with full-screen
 * intent and Answer/Decline actions; the ringtone itself is played by
 * [YggCallAlertController] so it keeps ringing until the call is resolved.
 */
object YggNotificationCenter {

    private const val CALLS_CHANNEL_ID = "ygggram_calls_heads_up_channel"
    private const val INCOMING_CALL_CHANNEL_ID = "ygggram_incoming_call_channel"
    private const val MESSAGES_CHANNEL_ID = "ygggram_messages_heads_up_channel"
    private const val GROUP_KEY = "ygggram_alerts_group"
    private val ACCENT_COLOR = 0xFF10B981.toInt()

    const val EXTRA_NOTIFICATION_PROFILE_ID = "extra_notif_profile_id"
    const val EXTRA_NOTIFICATION_CHAT_ID = "extra_notif_chat_id"
    const val EXTRA_NOTIFICATION_CATEGORY = "extra_notif_category"
    const val EXTRA_NOTIFICATION_CALL_ID = "extra_notif_call_id"
    const val EXTRA_NOTIFICATION_ACTION = "extra_notif_action"
    const val ACTION_ANSWER_CALL = "ANSWER_CALL"
    const val ACTION_DECLINE_CALL = "DECLINE_CALL"

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val incomingCallChannel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                "YGramm • Входящие звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Полноэкранный входящий вызов с кнопками ответа и отклонения"
                // Звонок звучит и вибрирует сам (YggCallAlertController), поэтому
                // канал не должен дублировать сигнал уведомления.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val callsChannel = NotificationChannel(
                CALLS_CHANNEL_ID,
                "YGramm • Пропущенные вызовы",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о пропущенных звонках по всем активным профилям"
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 250L, 180L, 250L)
            }
            val msgChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "YGramm • Сообщения и голосовые (Мульти-профиль)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых P2P сообщениях и голосовых записях для всех включенных профилей"
                enableVibration(true)
            }
            nm.createNotificationChannel(incomingCallChannel)
            nm.createNotificationChannel(callsChannel)
            nm.createNotificationChannel(msgChannel)
        } catch (_: Throwable) {
        }
    }

    private fun stableNotificationId(item: YggPopupNotificationItem): Int {
        // Messages and voice notes update one notification per chat instead of
        // flooding the tray; incoming calls are keyed by callId so a repeated
        // RINGING frame updates the same notification and the call can cancel it.
        return if (item.category == YggAlertCategory.INCOMING_CALL) {
            "ygggram_call_${item.callId.ifBlank { item.notificationId }}".hashCode()
        } else {
            "ygggram_${item.targetProfileId}_${item.chatId}".hashCode()
        }
    }

    private fun notificationIntent(
        context: Context,
        item: YggPopupNotificationItem,
        action: String? = null
    ): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_NOTIFICATION_PROFILE_ID, item.targetProfileId)
        putExtra(EXTRA_NOTIFICATION_CHAT_ID, item.chatId)
        putExtra(EXTRA_NOTIFICATION_CATEGORY, item.category.name)
        putExtra(EXTRA_NOTIFICATION_CALL_ID, item.callId)
        if (action != null) putExtra(EXTRA_NOTIFICATION_ACTION, action)
    }

    private fun pendingIntent(
        context: Context,
        item: YggPopupNotificationItem,
        action: String?,
        requestCodeSalt: Int
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(
            context,
            ("${item.callId}_${action.orEmpty()}".hashCode() * 31) + requestCodeSalt,
            notificationIntent(context, item, action),
            flags
        )
    }

    fun postSystemHeadsUpNotification(
        context: Context,
        item: YggPopupNotificationItem
    ) {
        try {
            if (!canPostNotifications(context)) return
            ensureChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val channelId = when (item.category) {
                YggAlertCategory.INCOMING_CALL -> INCOMING_CALL_CHANNEL_ID
                YggAlertCategory.MISSED_CALL -> CALLS_CHANNEL_ID
                YggAlertCategory.NEW_VOICE, YggAlertCategory.NEW_MESSAGE -> MESSAGES_CHANNEL_ID
            }

            val titlePrefix = when (item.category) {
                YggAlertCategory.INCOMING_CALL -> "📞 Входящий вызов • ${item.senderName}"
                YggAlertCategory.MISSED_CALL -> "📲 Пропущенный звонок • ${item.senderName}"
                YggAlertCategory.NEW_VOICE -> "🎙️ Голосовое (${item.voiceDurationSec.coerceAtLeast(1)} с) • ${item.senderName}"
                YggAlertCategory.NEW_MESSAGE -> "💬 ${item.senderName}"
            }

            val roleShort = when (item.targetProfileRelayRole) {
                "CLIENT_ONLY" -> "Клиент"
                "SF_BLIND_RELAY" -> "S&F Реле"
                else -> "Пир-узел"
            }

            val subHeader = "Профиль: ${item.targetProfileName} ($roleShort • ${item.targetProfileIpv6.take(14)}..)"

            val contentIntent = pendingIntent(context, item, null, 0)
            val categoryFlag = when (item.category) {
                YggAlertCategory.INCOMING_CALL -> NotificationCompat.CATEGORY_CALL
                YggAlertCategory.MISSED_CALL -> NotificationCompat.CATEGORY_MISSED_CALL
                else -> NotificationCompat.CATEGORY_MESSAGE
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ACCENT_COLOR)
                .setContentTitle(titlePrefix)
                .setContentText(item.messagePreview)
                .setSubText(subHeader)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("${item.messagePreview}\nУзел отправителя: ${item.senderIpv6}\nПолучатель: профиль «${item.targetProfileName}»")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(categoryFlag)
                .setGroup(GROUP_KEY)
                .setWhen(item.timestamp)
                .setShowWhen(true)
                .setContentIntent(contentIntent)

            if (item.category == YggAlertCategory.INCOMING_CALL) {
                val answerIntent = pendingIntent(context, item, ACTION_ANSWER_CALL, 1)
                val declineIntent = pendingIntent(context, item, ACTION_DECLINE_CALL, 2)
                builder
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setTimeoutAfter(60_000L)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setFullScreenIntent(contentIntent, true)
                    .setDeleteIntent(declineIntent)
                    .addAction(0, "Ответить", answerIntent)
                    .addAction(0, "Отклонить", declineIntent)
            } else {
                // Message content is masked before it reaches this class when the owner asked
                // for it; PRIVATE keeps even the masked line off a locked screen's preview.
                builder.setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                if (item.category == YggAlertCategory.NEW_MESSAGE ||
                    item.category == YggAlertCategory.NEW_VOICE
                ) {
                    val remoteInput = RemoteInput.Builder(YggQuickReplyReceiver.EXTRA_QUICK_REPLY_TEXT)
                        .setLabel("Быстрый ответ")
                        .build()
                    val replyIntent = Intent(context, YggQuickReplyReceiver::class.java).apply {
                        putExtra(EXTRA_NOTIFICATION_PROFILE_ID, item.targetProfileId)
                        putExtra(EXTRA_NOTIFICATION_CHAT_ID, item.chatId)
                        putExtra(EXTRA_NOTIFICATION_CATEGORY, item.category.name)
                    }
                    val replyPendingIntent = PendingIntent.getBroadcast(
                        context,
                        "reply_${item.targetProfileId}_${item.chatId}".hashCode(),
                        replyIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    builder.addAction(
                        NotificationCompat.Action.Builder(
                            0,
                            "Ответить",
                            replyPendingIntent
                        ).addRemoteInput(remoteInput).build()
                    )
                }
            }

            nm.notify(stableNotificationId(item), builder.build())
        } catch (_: Throwable) {
        }
    }

    /** Removes the ringing notification once the call is answered or ended. */
    fun cancelIncomingCall(context: Context, callId: String) {
        if (callId.isBlank()) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel("ygggram_call_$callId".hashCode())
        } catch (_: Throwable) {
        }
    }
}
