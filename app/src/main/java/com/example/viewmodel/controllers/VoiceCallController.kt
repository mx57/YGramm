// File: app/src/main/java/com/example/viewmodel/controllers/VoiceCallController.kt
package com.example.viewmodel.controllers

import android.app.Application
import com.example.data.YggRepository
import com.example.engine.YggAudioMediaEngine
import com.example.engine.YggTwinP2PBridge
import com.example.viewmodel.ActiveVoiceCallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Controller responsible for managing voice call states, WebRTC/P2P audio sessions,
 * SAS emoji verification and in-call audio toggles.
 */
class VoiceCallController(
    private val application: Application,
    private val repository: YggRepository,
    private val networkEngine: YggTwinP2PBridge,
    private val scope: CoroutineScope,
    private val onBannerMessage: (String) -> Unit
) {

    private val _activeVoiceCall = MutableStateFlow<ActiveVoiceCallState?>(null)
    val activeVoiceCall: StateFlow<ActiveVoiceCallState?> = _activeVoiceCall.asStateFlow()

    fun updateActiveVoiceCall(state: ActiveVoiceCallState?) {
        _activeVoiceCall.value = state
    }

    fun mutateActiveVoiceCall(transform: (ActiveVoiceCallState) -> ActiveVoiceCallState) {
        _activeVoiceCall.update { current ->
            if (current != null) transform(current) else null
        }
    }

    fun toggleCallMute() {
        val current = _activeVoiceCall.value ?: return
        val nextMuted = !current.isMuted
        mutateActiveVoiceCall { it.copy(isMuted = nextMuted) }
        YggAudioMediaEngine.updateVoipCallAudioState(
            context = application,
            isDialing = current.callStatus != "ACTIVE_ENCRYPTED",
            isMuted = nextMuted,
            isSpeakerphoneOn = current.isSpeakerphoneOn
        )
    }

    fun toggleCallSpeakerphone() {
        val current = _activeVoiceCall.value ?: return
        val nextSpeaker = !current.isSpeakerphoneOn
        mutateActiveVoiceCall { it.copy(isSpeakerphoneOn = nextSpeaker) }
        YggAudioMediaEngine.updateVoipCallAudioState(
            context = application,
            isDialing = current.callStatus != "ACTIVE_ENCRYPTED",
            isMuted = current.isMuted,
            isSpeakerphoneOn = nextSpeaker
        )
    }

    fun toggleCallLoopbackEcho() {
        val current = _activeVoiceCall.value ?: return
        val nextEcho = !current.isLoopbackEchoEnabled
        mutateActiveVoiceCall { it.copy(isLoopbackEchoEnabled = nextEcho) }
    }

    fun stopCallAudio() {
        YggAudioMediaEngine.stopVoipCallAudioSession(application)
        _activeVoiceCall.value = null
    }
}
