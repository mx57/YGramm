// File: app/src/main/java/com/example/service/YggNotificationCenter.kt
package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import java.util.concurrent.atomic.AtomicInteger

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
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Dispatches high-priority Android System Heads-Up notifications for all enabled Yggdrasil profiles
 * (Incoming Calls, Missed Calls, Voice Notes, and Encrypted P2P Messages) in addition to the
 * In-App animated popup banner.
 */
object YggNotificationCenter {

    private const val CALLS_CHANNEL_ID = "ygggram_calls_heads_up_channel"
    private const val MESSAGES_CHANNEL_ID = "ygggram_messages_heads_up_channel"
    private val nextSystemNotificationId = AtomicInteger(8100)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val callsChannel = NotificationChannel(
                CALLS_CHANNEL_ID,
                "YggGram • Звонки и пропущенные вызовы",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Всплывающие уведомления о входящих и пропущенных звонках по всем активным профилям"
                enableVibration(true)
            }
            val msgChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "YggGram • Сообщения и голосовые (Мульти-профиль)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых P2P сообщениях и голосовых записях для всех включенных профилей"
                enableVibration(true)
            }
            nm.createNotificationChannel(callsChannel)
            nm.createNotificationChannel(msgChannel)
        } catch (_: Throwable) {
        }
    }

    fun postSystemHeadsUpNotification(
        context: Context,
        item: YggPopupNotificationItem
    ) {
        try {
            ensureChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val channelId = when (item.category) {
                YggAlertCategory.INCOMING_CALL, YggAlertCategory.MISSED_CALL -> CALLS_CHANNEL_ID
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

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NOTIFICATION_PROFILE_ID, item.targetProfileId)
                putExtra(EXTRA_NOTIFICATION_CHAT_ID, item.chatId)
                putExtra(EXTRA_NOTIFICATION_CATEGORY, item.category.name)
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                item.notificationId.hashCode(),
                launchIntent,
                pendingFlags
            )

            val categoryFlag = when (item.category) {
                YggAlertCategory.INCOMING_CALL -> NotificationCompat.CATEGORY_CALL
                YggAlertCategory.MISSED_CALL -> NotificationCompat.CATEGORY_MISSED_CALL
                else -> NotificationCompat.CATEGORY_MESSAGE
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(titlePrefix)
                .setContentText(item.messagePreview)
                .setSubText(subHeader)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("${item.messagePreview}\nУзел отправителя: ${item.senderIpv6}\nПолучатель: профиль «${item.targetProfileName}»")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(categoryFlag)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()

            nm.notify(nextSystemNotificationId.getAndIncrement(), notification)
        } catch (_: Throwable) {
        }
    }

    const val EXTRA_NOTIFICATION_PROFILE_ID = "extra_notif_profile_id"
    const val EXTRA_NOTIFICATION_CHAT_ID = "extra_notif_chat_id"
    const val EXTRA_NOTIFICATION_CATEGORY = "extra_notif_category"
}
