// File: app/src/main/java/com/example/viewmodel/controllers/ChatConversationController.kt
package com.example.viewmodel.controllers

import android.app.Application
import com.example.data.ConversationEntity
import com.example.data.MessageEntity
import com.example.data.P2PFileTransferEntity
import com.example.data.YggRepository
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.YggTwinP2PBridge
import com.example.viewmodel.DecentralizedUserProfile
import com.example.viewmodel.PrivacyMatrixState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Controller responsible for chat messaging, typing state, reactions,
 * Store-and-Forward offline queue, and file transfer coordination.
 */
class ChatConversationController(
    private val application: Application,
    private val repository: YggRepository,
    private val networkEngine: YggTwinP2PBridge,
    private val scope: CoroutineScope,
    private val onBannerMessage: (String) -> Unit,
    private val onIncrementTx: () -> Unit,
    private val onIncrementRx: () -> Unit
) {

    private val _typingPeers = MutableStateFlow<Map<String, String>>(emptyMap())
    val typingPeers: StateFlow<Map<String, String>> = _typingPeers.asStateFlow()

    private val typingExpiryJobs = ConcurrentHashMap<String, Job>()
    private val lastTypingSentAt = ConcurrentHashMap<String, Long>()

    private companion object {
        const val TYPING_THROTTLE_MS = 4_000L
        const val TYPING_EXPIRY_MS = 6_000L
    }

    fun onPeerTypingReceived(chatId: String, senderName: String) {
        if (chatId.isBlank()) return
        val label = senderName.ifBlank { "Собеседник" }
        _typingPeers.update { it + (chatId to label) }
        typingExpiryJobs[chatId]?.cancel()
        typingExpiryJobs[chatId] = scope.launch {
            delay(TYPING_EXPIRY_MS)
            _typingPeers.update { it - chatId }
            typingExpiryJobs.remove(chatId)
        }
    }

    fun clearTypingForChat(chatId: String) {
        typingExpiryJobs[chatId]?.cancel()
        typingExpiryJobs.remove(chatId)
        _typingPeers.update { it - chatId }
    }

    fun notifyTyping(
        chatId: String,
        targetConv: ConversationEntity?,
        userProfile: DecentralizedUserProfile,
        identity: DerivedIdentityHierarchy,
        allowLan: Boolean
    ) {
        val now = System.currentTimeMillis()
        val lastSent = lastTypingSentAt[chatId] ?: 0L
        if (now - lastSent < TYPING_THROTTLE_MS) return
        lastTypingSentAt[chatId] = now

        if (targetConv == null || YggRepository.isSelfChatId(chatId)) return
        scope.launch {
            val peerIdentity = repository.resolvePeerNodeIdentity(targetConv)
            val peerIpv6 = peerIdentity.yggIpv6Address.ifBlank { targetConv.yggIpv6Address }
            if (peerIpv6.isBlank()) return@launch
            val hint = extractDirectEndpointHint(targetConv)
            networkEngine.dispatchTypingIndicator(
                chatId = chatId,
                peerIpv6 = peerIpv6,
                directEndpointHint = hint,
                senderIpv6 = identity.yggIpv6Address,
                senderPubKeyHex = identity.yggNodePublicKeyHex,
                senderNodeLabel = userProfile.displayName,
                recipientNodePubKeyHex = peerIdentity.publicKeyHex,
                allowLanFallback = allowLan || hint.isNotBlank()
            )
        }
    }

    private fun extractDirectEndpointHint(conv: ConversationEntity?): String {
        if (conv == null) return ""
        val bio = conv.bioStatus.trim()
        if (bio.contains(":") && !bio.contains(" ") && !bio.startsWith("127.0.0.1:")) return bio
        return ""
    }

    fun toggleFileTransfer(transfer: P2PFileTransferEntity) {
        scope.launch {
            repository.toggleFileTransferPauseResume(transfer)
        }
    }

    fun cleanup() {
        typingExpiryJobs.values.forEach { it.cancel() }
        typingExpiryJobs.clear()
        lastTypingSentAt.clear()
    }
}
