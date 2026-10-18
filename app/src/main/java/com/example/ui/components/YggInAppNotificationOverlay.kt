// File: app/src/main/java/com/example/ui/components/YggInAppNotificationOverlay.kt
package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.service.YggAlertCategory
import com.example.service.YggPopupNotificationItem
import com.example.ui.theme.AmberShieldTertiary
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyberTealSecondary
import com.example.ui.theme.EmeraldPrimary

data class PendingProfileSwitchRequest(
    val notification: YggPopupNotificationItem,
    val actionType: String // "OPEN_CHAT", "ANSWER_CALL", "CALLBACK_MISSED"
)

/**
 * Animated In-App Heads-Up Popup Notification Banner for all enabled Yggdrasil profiles:
 * - Incoming Encrypted Calls (with Accept / Decline actions)
 * - Missed Calls (with Call Back / Open Chat actions)
 * - New Voice Messages (with duration & waveform indicator)
 * - New P2P Text/File Messages
 */
@Composable
fun YggInAppNotificationBannerOverlay(
    notification: YggPopupNotificationItem?,
    currentActiveProfileId: String,
    onPrimaryAction: (YggPopupNotificationItem, String) -> Unit,
    onDeclineIncomingCall: (YggPopupNotificationItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = notification != null,
        enter = slideInVertically(
            initialOffsetY = { -it - 40 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(220)),
        exit = slideOutVertically(
            targetOffsetY = { -it - 40 },
            animationSpec = tween(200)
        ) + fadeOut(animationSpec = tween(180)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val item = notification ?: return@AnimatedVisibility
        val isBackgroundProfile = item.targetProfileId != currentActiveProfileId

        val accentColor = when (item.category) {
            YggAlertCategory.INCOMING_CALL -> EmeraldPrimary
            YggAlertCategory.MISSED_CALL -> CoralError
            YggAlertCategory.NEW_VOICE -> CyberTealSecondary
            YggAlertCategory.NEW_MESSAGE -> EmeraldPrimary
        }

        val categoryLabel = when (item.category) {
            YggAlertCategory.INCOMING_CALL -> "ВХОДЯЩИЙ ЗВОНОК YGGDRASIL"
            YggAlertCategory.MISSED_CALL -> "ПРОПУЩЕННЫЙ ЗВОНОК"
            YggAlertCategory.NEW_VOICE -> "НОВОЕ ГОЛОСОВОЕ СООБЩЕНИЕ"
            YggAlertCategory.NEW_MESSAGE -> "НОВОЕ P2P СООБЩЕНИЕ"
        }

        val roleBadgeText = when (item.targetProfileRelayRole) {
            "CLIENT_ONLY" -> "Только клиент"
            "SF_BLIND_RELAY" -> "S&F Реле"
            else -> "Пир-узел"
        }

        val pulseTransition = rememberInfiniteTransition(label = "popup_pulse")
        val iconScale by pulseTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = if (item.category == YggAlertCategory.INCOMING_CALL) 1.16f else 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(550),
                repeatMode = RepeatMode.Reverse
            ),
            label = "popup_icon_scale"
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0A1D17).copy(alpha = 0.97f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    brush = Brush.horizontalGradient(
                        listOf(accentColor, CyberTealSecondary, accentColor.copy(alpha = 0.5f))
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .clickable {
                    val defaultAct = when (item.category) {
                        YggAlertCategory.INCOMING_CALL -> "ANSWER_CALL"
                        YggAlertCategory.MISSED_CALL -> "CALLBACK_MISSED"
                        else -> "OPEN_CHAT"
                    }
                    onPrimaryAction(item, defaultAct)
                }
                .testTag("in_app_popup_notification_banner")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accentColor.copy(alpha = 0.14f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top Row: Category Badge + Target Profile Pill + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            color = accentColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = categoryLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = accentColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }

                        Surface(
                            color = if (isBackgroundProfile) {
                                AmberShieldTertiary.copy(alpha = 0.22f)
                            } else {
                                CyberTealSecondary.copy(alpha = 0.18f)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (isBackgroundProfile) AmberShieldTertiary else CyberTealSecondary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${item.targetProfileName} ($roleBadgeText)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isBackgroundProfile) AmberShieldTertiary else CyberTealSecondary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("dismiss_popup_notification_button")
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть уведомление",
                            tint = Color(0xFF9AB8AE),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Middle Row: Icon + Sender + Message/Call Preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .scale(iconScale)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.22f))
                            .border(1.dp, accentColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (item.category) {
                                YggAlertCategory.INCOMING_CALL -> Icons.Default.Call
                                YggAlertCategory.MISSED_CALL -> Icons.Default.CallEnd
                                YggAlertCategory.NEW_VOICE -> Icons.Default.Mic
                                YggAlertCategory.NEW_MESSAGE -> Icons.AutoMirrored.Filled.Chat
                            },
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = item.senderName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.senderIpv6.take(16) + "..",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = CyberTealSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.messagePreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD5F5EC),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isBackgroundProfile) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Пришло на фоновый профиль «${item.targetProfileName}» • Нажмите для перехода",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberShieldTertiary
                            )
                        }
                    }
                }

                // Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (item.category) {
                        YggAlertCategory.INCOMING_CALL -> {
                            OutlinedButton(
                                onClick = { onDeclineIncomingCall(item) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralError),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("popup_decline_call_button")
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Отклонить", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { onPrimaryAction(item, "ANSWER_CALL") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldPrimary,
                                    contentColor = Color(0xFF042217)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("popup_accept_call_button")
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (isBackgroundProfile) "Сменить и принять" else "Принять звонок",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        YggAlertCategory.MISSED_CALL -> {
                            OutlinedButton(
                                onClick = { onPrimaryAction(item, "OPEN_CHAT") },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("popup_missed_open_chat_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Открыть чат", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { onPrimaryAction(item, "CALLBACK_MISSED") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AmberShieldTertiary,
                                    contentColor = Color(0xFF1F1200)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("popup_callback_missed_button")
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Перезвонить", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }

                        YggAlertCategory.NEW_VOICE, YggAlertCategory.NEW_MESSAGE -> {
                            TextButton(
                                onClick = onDismiss,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Скрыть", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9AB8AE))
                            }
                            Button(
                                onClick = { onPrimaryAction(item, "OPEN_CHAT") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color(0xFF042217)
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("popup_open_message_chat_button")
                            ) {
                                Icon(
                                    if (item.category == YggAlertCategory.NEW_VOICE) Icons.Default.Mic else Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        isBackgroundProfile -> "Переключить на «${item.targetProfileName}»"
                                        item.category == YggAlertCategory.NEW_VOICE -> "Слушать голосовое (${item.voiceDurationSec} с)"
                                        else -> "Открыть диалог"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun roleShortLabel(label: String): String = label

/**
 * Confirmation Modal shown when the user taps a notification belonging to a secondary/background profile.
 * Ensures the user explicitly confirms switching their active profile context.
 */
@Composable
fun ProfileSwitchConfirmationDialog(
    pendingRequest: PendingProfileSwitchRequest?,
    currentProfileName: String,
    onConfirmSwitchAndExecute: (PendingProfileSwitchRequest) -> Unit,
    onDismiss: () -> Unit
) {
    val req = pendingRequest ?: return
    val item = req.notification

    val actionDescription = when (req.actionType) {
        "ANSWER_CALL" -> "принять входящий зашифрованный звонок от «${item.senderName}»"
        "CALLBACK_MISSED" -> "перезвонить узлу «${item.senderName}» по пропущенному вызову"
        else -> "открыть чат с «${item.senderName}»"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = null,
                tint = AmberShieldTertiary,
                modifier = Modifier.size(30.dp)
            )
        },
        title = {
            Text(
                text = "Переключить активный профиль?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Это событие пришло на ваш параллельный фоновый профиль «${item.targetProfileName}» (${item.targetProfileIpv6.take(18)}...).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Текущий профиль: $currentProfileName",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Целевой профиль: ${item.targetProfileName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Действие: $actionDescription",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTealSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSwitchAndExecute(req) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = Color(0xFF042217)
                ),
                modifier = Modifier.testTag("confirm_profile_switch_from_notification_button")
            ) {
                Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Переключить профиль", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_profile_switch_from_notification_button")
            ) {
                Text("Остаться в текущем")
            }
        }
    )
}
