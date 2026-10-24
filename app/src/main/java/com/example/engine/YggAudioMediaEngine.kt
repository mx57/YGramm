// File: app/src/main/java/com/example/engine/YggAudioMediaEngine.kt
package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordedVoiceNoteResult(
    val wavFile: File,
    val fileUriString: String,
    val fileName: String,
    val sizeBytes: Long,
    val durationSeconds: Int,
    val waveformCsv: String,
    val usedHardwareMic: Boolean
)

/**
 * Full-featured audio and binary media engine for YggGram:
 * 1. Captures real 16kHz 16-bit mono PCM audio from the device microphone via AudioRecord and writes
 *    standard RIFF/WAVE (.wav) files. If the microphone returns digital silence (e.g. cloud emulator
 *    without a physical mic), automatically synthesizes an audible speech-cadence harmonic signal
 *    modulated by the waveform so playback always produces clear sound.
 * 2. Plays voice messages with real sound via MediaPlayer and AudioTrack PCM streaming.
 * 3. Drives real VoIP call audio (audible ringback tone during dialing + AudioRecord/AudioTrack loop
 *    with AudioManager speakerphone and microphone mute control).
 * 4. Encodes and decodes binary P2P attachments (voice notes, images, documents) via Base64 chunks
 *    and materializes received files into internal storage so the recipient can play/view/open them.
 */
object YggAudioMediaEngine {

    private const val SAMPLE_RATE = 16000
    // The overlay transport splits large attachments into ACKed chunks, but the whole base64
    // payload is still assembled in memory on both ends, so the ceiling stays. Over-cap files
    // are refused with this number in the message rather than sent truncated.
    const val MAX_P2P_INLINE_BYTES = 4 * 1024 * 1024

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Active Voice Note Recording state
    private val isRecordingMic = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val recordedPcmStream = ByteArrayOutputStream()
    private val recordedAmplitudes = mutableListOf<Float>()
    private var recordingStartMs: Long = 0L
    private var hardwareMicCapturedNonZero = false

    // Active Voice Note Playback state
    private var activeMediaPlayer: MediaPlayer? = null
    private var activeAudioTrack: AudioTrack? = null
    private var activePlaybackJob: Job? = null
    private var currentPlayingMessageId: String? = null

    // Active VoIP Call Audio state
    private val isVoipCallActive = AtomicBoolean(false)
    private var voipCallJob: Job? = null
    @Volatile private var voipIsDialing = true
    @Volatile private var voipIsMuted = false
    private var voipIsSpeakerOn = true
    private var voipAudioFocusRequest: AudioFocusRequest? = null
    private val voipAudioFocusListener = AudioManager.OnAudioFocusChangeListener { }
    // Mic PCM is handed to the call layer for AEAD encryption and transmission.
    @Volatile private var voipMicFrameListener: ((ByteArray) -> Unit)? = null
    @Volatile private var voipLevelListener: ((Float) -> Unit)? = null
    @Volatile private var voipMicCapturing = false
    private val voipRemotePcmQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /** False when the call is transmitting filler because no AudioRecord came up. */
    fun isVoipMicCapturing(): Boolean = voipMicCapturing

    /**
     * Starts real microphone capture using AudioRecord (16kHz, 16-bit PCM Mono).
     */
    @SuppressLint("MissingPermission")
    fun startVoiceNoteRecording(
        onLiveAmplitude: (Float, Int) -> Unit = { _, _ -> }
    ) {
        stopVoiceNoteRecordingInternal()
        synchronized(recordedPcmStream) {
            recordedPcmStream.reset()
        }
        synchronized(recordedAmplitudes) {
            recordedAmplitudes.clear()
        }
        recordingStartMs = System.currentTimeMillis()
        hardwareMicCapturedNonZero = false
        isRecordingMic.set(true)

        recordingJob = engineScope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            var audioRecord: AudioRecord? = null
            try {
                val candidate = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    candidate.startRecording()
                    audioRecord = candidate
                } else {
                    candidate.release()
                }
            } catch (_: Throwable) {
                audioRecord = null
            }

            val shortBuf = ShortArray(1024) // ~64ms per read at 16kHz
            var tick = 0
            try {
                while (isActive && isRecordingMic.get()) {
                    val rec = audioRecord
                    if (rec != null) {
                        val readShorts = rec.read(shortBuf, 0, shortBuf.size)
                        if (readShorts > 0) {
                            var sumSq = 0.0
                            var maxPeak = 0
                            val byteBuf = ByteBuffer.allocate(readShorts * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until readShorts) {
                                val s = shortBuf[i].toInt()
                                val absVal = abs(s)
                                if (absVal > maxPeak) maxPeak = absVal
                                sumSq += (s * s).toDouble()
                                byteBuf.putShort(shortBuf[i])
                            }
                            if (maxPeak > 45) {
                                hardwareMicCapturedNonZero = true
                            }
                            synchronized(recordedPcmStream) {
                                recordedPcmStream.write(byteBuf.array())
                            }
                            val rms = sqrt(sumSq / readShorts)
                            val normalized = if (maxPeak > 45) {
                                ((rms / 6500.0).toFloat() + 0.18f).coerceIn(0.18f, 0.98f)
                            } else {
                                // A silent mic must draw a flat bar: this CSV travels inside the
                                // voice note, so a sine here would fake a waveform the peer never heard.
                                0f
                            }
                            if (tick % 2 == 0) {
                                synchronized(recordedAmplitudes) {
                                    recordedAmplitudes.add(normalized)
                                }
                                val elapsedSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt()
                                onLiveAmplitude(normalized, elapsedSec)
                            }
                            tick++
                        } else {
                            delay(60L)
                            tick++
                        }
                    } else {
                        delay(80L)
                        // No AudioRecord handle: nothing was captured, so the meter stays flat.
                        synchronized(recordedAmplitudes) {
                            recordedAmplitudes.add(0f)
                        }
                        val elapsedSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt()
                        onLiveAmplitude(0f, elapsedSec)
                        tick++
                    }
                }
            } finally {
                try {
                    audioRecord?.stop()
                } catch (_: Throwable) {
                }
                try {
                    audioRecord?.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun stopVoiceNoteRecordingInternal() {
        isRecordingMic.set(false)
        recordingJob?.cancel()
        recordingJob = null
    }

    fun cancelVoiceNoteRecording() {
        stopVoiceNoteRecordingInternal()
        synchronized(recordedPcmStream) {
            recordedPcmStream.reset()
        }
    }

    /**
     * Finalizes the voice recording, writes a valid RIFF/WAVE (.wav) file to `filesDir/voice_notes/`,
     * and returns its metadata and URI. Returns null when the microphone never produced samples:
     * a note must never be filled with generated audio.
     */
    suspend fun finishAndSaveVoiceNote(context: Context): RecordedVoiceNoteResult? = withContext(Dispatchers.IO) {
        stopVoiceNoteRecordingInternal()
        delay(40L) // allow last read cycle to settle

        val elapsedSec = (((System.currentTimeMillis() - recordingStartMs) / 1000L).toInt()).coerceIn(1, 180)
        val rawPcmBytes = synchronized(recordedPcmStream) { recordedPcmStream.toByteArray() }
        val amps = synchronized(recordedAmplitudes) { recordedAmplitudes.toList() }

        if (!hardwareMicCapturedNonZero || rawPcmBytes.size < SAMPLE_RATE) {
            return@withContext null
        }

        val effectiveAmps = if (amps.size >= 8) downsampleAmplitudes(amps, 24) else List(24) { 0f }
        val waveformCsv = effectiveAmps.joinToString(",") { String.format(Locale.US, "%.2f", it) }

        val finalPcmBytes = normalizeOrBoostMicPcm(rawPcmBytes)

        val voiceDir = File(context.filesDir, "voice_notes").apply { mkdirs() }
        val fileName = "voice_${System.currentTimeMillis() % 1000000}.wav"
        val wavFile = File(voiceDir, fileName)
        writeWavFile(wavFile, finalPcmBytes, SAMPLE_RATE, channels = 1, bitsPerSample = 16)

        RecordedVoiceNoteResult(
            wavFile = wavFile,
            fileUriString = Uri.fromFile(wavFile).toString(),
            fileName = fileName,
            sizeBytes = wavFile.length(),
            durationSeconds = elapsedSec,
            waveformCsv = waveformCsv,
            usedHardwareMic = hardwareMicCapturedNonZero
        )
    }

    /**
     * Returns the local .wav for a voice message, or null when the audio was never received or
     * has been deleted. Missing audio is reported to the caller instead of being synthesized from
     * the duration and waveform bars.
     */
    fun ensurePlayableVoiceFile(
        context: Context,
        messageId: String,
        attachmentUri: String?,
        durationSec: Int,
        waveformCsv: String?
    ): File? {
        if (!attachmentUri.isNullOrBlank()) {
            try {
                val parsed = Uri.parse(attachmentUri)
                if (parsed.scheme == "file" && parsed.path != null) {
                    val f = File(parsed.path!!)
                    if (f.exists() && f.length() > 44L) {
                        return f
                    }
                }
            } catch (_: Throwable) {
            }
        }
        val voiceDir = File(context.filesDir, "voice_notes").apply { mkdirs() }
        val safeId = messageId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val fallbackFile = File(voiceDir, "voice_msg_$safeId.wav")
        if (fallbackFile.exists() && fallbackFile.length() > 44L) {
            return fallbackFile
        }
        return null
    }

    /**
     * Plays a voice note file with real audible output using AudioTrack (direct PCM streaming from WAV)
     * with MediaPlayer fallback, reporting live playback progress (0f..1f).
     */
    fun startPlayingVoiceNote(
        context: Context,
        messageId: String,
        attachmentUri: String?,
        durationSec: Int,
        waveformCsv: String?,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit
    ) {
        stopVoiceNotePlayback()
        currentPlayingMessageId = messageId

        activePlaybackJob = engineScope.launch {
            val wavFile = ensurePlayableVoiceFile(
                context = context,
                messageId = messageId,
                attachmentUri = attachmentUri,
                durationSec = durationSec,
                waveformCsv = waveformCsv
            )

            // Ensure speaker volume is audible and AudioManager is in normal media mode
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.mode = AudioManager.MODE_NORMAL
            } catch (_: Throwable) {
            }

            val rawBytes = try {
                wavFile?.readBytes() ?: ByteArray(0)
            } catch (_: Throwable) {
                ByteArray(0)
            }

            if (rawBytes.size > 44) {
                val pcmOffset = 44
                val pcmLength = rawBytes.size - pcmOffset
                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                var track: AudioTrack? = null
                try {
                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuffer * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    activeAudioTrack = track
                    track.play()

                    val chunkSize = 1280 // 40ms of 16kHz 16-bit mono audio
                    var written = 0
                    while (isActive && currentPlayingMessageId == messageId && written < pcmLength) {
                        val toWrite = (pcmLength - written).coerceAtMost(chunkSize)
                        val res = track.write(rawBytes, pcmOffset + written, toWrite)
                        if (res > 0) {
                            written += res
                        } else {
                            written += toWrite
                            delay(40L)
                        }
                        val progress = (written.toFloat() / pcmLength.toFloat()).coerceIn(0f, 1f)
                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                    }
                } catch (_: Throwable) {
                    // Fallback timer if AudioTrack fails on a custom host
                    val totalMs = (durationSec.coerceAtLeast(1) * 1000L)
                    var elapsed = 0L
                    while (isActive && currentPlayingMessageId == messageId && elapsed < totalMs) {
                        delay(80L)
                        elapsed += 80L
                        withContext(Dispatchers.Main) {
                            onProgress((elapsed.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                } finally {
                    try {
                        track?.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        track?.release()
                    } catch (_: Throwable) {
                    }
                    if (activeAudioTrack === track) {
                        activeAudioTrack = null
                    }
                }
            }

            if (currentPlayingMessageId == messageId) {
                currentPlayingMessageId = null
                withContext(Dispatchers.Main) {
                    onProgress(0f)
                    onComplete()
                }
            }
        }
    }

    fun stopVoiceNotePlayback() {
        currentPlayingMessageId = null
        activePlaybackJob?.cancel()
        activePlaybackJob = null
        try {
            activeMediaPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            activeMediaPlayer?.release()
        } catch (_: Throwable) {
        }
        activeMediaPlayer = null
        try {
            activeAudioTrack?.stop()
        } catch (_: Throwable) {
        }
        try {
            activeAudioTrack?.release()
        } catch (_: Throwable) {
        }
        activeAudioTrack = null
    }

    /**
     * Starts real VoIP call audio (audible ringback tone during dialing + full-duplex audio stream
     * during active encrypted conversation).
     */
    @SuppressLint("MissingPermission")
    fun startVoipCallAudioSession(
        context: Context,
        isDialing: Boolean,
        isMuted: Boolean,
        isSpeakerphoneOn: Boolean
    ) {
        stopVoipCallAudioSession(context)
        voipIsDialing = isDialing
        voipIsMuted = isMuted
        voipIsSpeakerOn = isSpeakerphoneOn
        isVoipCallActive.set(true)

        requestVoipAudioFocus(context)
        applyAudioManagerVoipRouting(context, isSpeakerphoneOn, isMuted)

        voipCallJob = engineScope.launch {
            val minOutBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            var outTrack: AudioTrack? = null
            var inRecord: AudioRecord? = null
            try {
                outTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minOutBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                outTrack.play()

                val minInBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)
                val candidateRec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minInBuf * 2
                )
                if (candidateRec.state == AudioRecord.STATE_INITIALIZED) {
                    candidateRec.startRecording()
                    inRecord = candidateRec
                    voipMicCapturing = true
                } else {
                    candidateRec.release()
                    voipMicCapturing = false
                }

                val frameSamples = 640 // 40ms at 16kHz
                val micShorts = ShortArray(frameSamples)
                val outBytes = ByteArray(frameSamples * 2)
                var sampleCursor = 0L

                while (isActive && isVoipCallActive.get()) {
                    if (voipIsDialing) {
                        // Standard 425 Hz ringback tone (1s on, 2s off)
                        val cyclePos = (sampleCursor % (SAMPLE_RATE * 3)).toInt()
                        val toneOn = cyclePos < SAMPLE_RATE
                        val bb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until frameSamples) {
                            val t = (sampleCursor + i).toDouble() / SAMPLE_RATE.toDouble()
                            val sampleVal = if (toneOn) {
                                (sin(2.0 * PI * 425.0 * t) * 4200.0).toInt().toShort()
                            } else 0.toShort()
                            bb.putShort(sampleVal)
                        }
                        outTrack.write(outBytes, 0, outBytes.size)
                        sampleCursor += frameSamples
                    } else {
                        // Active encrypted call: real mic -> listener -> network, and
                        // network -> decrypted remote PCM -> AudioTrack. No synthesized
                        // sidetone or fake comfort carrier is injected.
                        if (!voipIsMuted && inRecord != null) {
                            val read = inRecord.read(micShorts, 0, frameSamples)
                            if (read > 0) {
                                val micBytes = ByteArray(read * 2)
                                val micBb = ByteBuffer.wrap(micBytes).order(ByteOrder.LITTLE_ENDIAN)
                                var sumSq = 0.0
                                for (i in 0 until read) {
                                    val s = micShorts[i]
                                    micBb.putShort(s)
                                    sumSq += (s.toInt() * s.toInt()).toDouble()
                                }
                                voipMicFrameListener?.invoke(micBytes)
                                val rms = sqrt(sumSq / read)
                                val level = (rms / 5200.0).toFloat().coerceIn(0.02f, 0.98f)
                                voipLevelListener?.invoke(level)
                            }
                        } else {
                            // Muted calls still emit an encrypted silent frame: it keeps the
                            // persistent media socket alive instead of letting the peer's
                            // read timeout tear the channel down mid-call.
                            voipMicFrameListener?.invoke(ByteArray(frameSamples * 2))
                            voipLevelListener?.invoke(0.02f)
                            delay(40L)
                        }
                        val remoteFrame = voipRemotePcmQueue.poll()
                        if (remoteFrame != null && remoteFrame.isNotEmpty()) {
                            outTrack.write(remoteFrame, 0, remoteFrame.size)
                        } else {
                            java.util.Arrays.fill(outBytes, 0)
                            outTrack.write(outBytes, 0, outBytes.size)
                        }
                        sampleCursor += frameSamples
                    }
                }
            } catch (_: Throwable) {
            } finally {
                voipMicCapturing = false
                try {
                    inRecord?.stop()
                } catch (_: Throwable) {
                }
                try {
                    inRecord?.release()
                } catch (_: Throwable) {
                }
                try {
                    outTrack?.stop()
                } catch (_: Throwable) {
                }
                try {
                    outTrack?.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Registers the callback that receives raw 40 ms mic PCM frames during a call. */
    fun setVoipMicFrameListener(listener: ((ByteArray) -> Unit)?) {
        voipMicFrameListener = listener
    }

    /** Registers a throttled microphone level callback for the in-call UI. */
    fun setVoipLevelListener(listener: ((Float) -> Unit)?) {
        voipLevelListener = listener
    }

    /** Queues a decrypted remote PCM frame for playback through the call AudioTrack. */
    fun enqueueVoipRemotePcm(pcm: ByteArray) {
        if (pcm.isNotEmpty()) voipRemotePcmQueue.add(pcm)
    }

    fun clearVoipRemotePcm() {
        voipRemotePcmQueue.clear()
    }

    fun updateVoipCallAudioState(
        context: Context,
        isDialing: Boolean,
        isMuted: Boolean,
        isSpeakerphoneOn: Boolean
    ) {
        voipIsDialing = isDialing
        voipIsMuted = isMuted
        voipIsSpeakerOn = isSpeakerphoneOn
        applyAudioManagerVoipRouting(context, isSpeakerphoneOn, isMuted)
    }

    fun stopVoipCallAudioSession(context: Context) {
        isVoipCallActive.set(false)
        voipCallJob?.cancel()
        voipCallJob = null
        voipRemotePcmQueue.clear()
        abandonVoipAudioFocus(context)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMicrophoneMute = false
            @Suppress("DEPRECATION")
            am?.stopBluetoothSco()
            @Suppress("DEPRECATION")
            am?.isBluetoothScoOn = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am?.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            am?.isSpeakerphoneOn = false
            am?.mode = AudioManager.MODE_NORMAL
        } catch (_: Throwable) {
        }
    }

    private fun requestVoipAudioFocus(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (voipAudioFocusRequest != null) return
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(voipAudioFocusListener)
                    .build()
                voipAudioFocusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    voipAudioFocusListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun abandonVoipAudioFocus(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                voipAudioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                voipAudioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(voipAudioFocusListener)
            }
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun applyAudioManagerVoipRouting(
        context: Context,
        isSpeakerphoneOn: Boolean,
        isMuted: Boolean
    ) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isMicrophoneMute = isMuted
            val bluetoothScoAvailable = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val desiredType = when {
                    isSpeakerphoneOn -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    bluetoothScoAvailable -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    else -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                am.availableCommunicationDevices
                    .firstOrNull { it.type == desiredType }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                if (bluetoothScoAvailable && !isSpeakerphoneOn) {
                    if (!am.isBluetoothScoOn) am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                } else {
                    if (am.isBluetoothScoOn) am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                am.isSpeakerphoneOn = isSpeakerphoneOn
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Plays an immediate 450ms dual-tone acoustic verification chime through the active speaker
     * so the user can verify audio output during a call.
     */
    fun playVoipTestTonePulse() {
        engineScope.launch {
            try {
                val samplesCount = (SAMPLE_RATE * 0.45).toInt()
                val pcmBytes = ByteArray(samplesCount * 2)
                val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until samplesCount) {
                    val t = i.toDouble() / SAMPLE_RATE.toDouble()
                    val freq = if (i < samplesCount / 2) 523.25 else 659.25 // C5 -> E5 chime
                    val env = sin(PI * (i.toDouble() / samplesCount.toDouble()))
                    val s = (sin(2.0 * PI * freq * t) * 14000.0 * env).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    bb.putShort(s)
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcmBytes.size.coerceAtLeast(2048))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcmBytes, 0, pcmBytes.size)
                track.play()
                delay(520L)
                track.stop()
                track.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Reads binary bytes from a local `file://` or `content://` Uri and returns a Pair of
     * (localSavedUriString, urlSafeBase64Payload). An empty payload means the source could not
     * be read and the caller must not send anything.
     */
    fun prepareAttachmentForP2P(
        context: Context,
        attachmentName: String?,
        attachmentType: String?,
        attachmentUri: String?,
        voiceDurationSec: Int = 0,
        voiceWaveformCsv: String? = null
    ): Pair<String?, String> {
        if (attachmentName == null && attachmentType == null) return null to ""

        val attachDir = File(context.filesDir, "ygg_attachments").apply { mkdirs() }
        val safeName = (attachmentName ?: "attachment_${System.currentTimeMillis()}").replace(Regex("[^a-zA-Z0-9._-]"), "_")

        // 1. Try reading bytes from existing file:// or content:// URI
        if (!attachmentUri.isNullOrBlank()) {
            try {
                val uri = Uri.parse(attachmentUri)
                val rawBytes: ByteArray? = when (uri.scheme) {
                    "file" -> uri.path?.let { File(it).takeIf { f -> f.exists() }?.readBytes() }
                    "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    else -> null
                }
                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    // Copy content:// into app's persistent ygg_attachments folder so URI never expires
                    val localCopy = if (uri.scheme == "content") {
                        val dest = File(attachDir, "${System.currentTimeMillis()}_$safeName")
                        dest.writeBytes(rawBytes)
                        Uri.fromFile(dest).toString()
                    } else {
                        attachmentUri
                    }
                    if (rawBytes.size > MAX_P2P_INLINE_BYTES) {
                        // Truncating used to hand the peer a corrupt file while the sender saw
                        // "sent": over-cap payloads are refused outright, the local copy stays.
                        return localCopy to ""
                    }
                    val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
                    return localCopy to b64
                }
            } catch (_: Throwable) {
            }
        }

        // Nothing was readable. Generating a PNG, a WAV or a document stub here used to make the
        // sender see "sent" while the peer received media that never existed, so the caller gets
        // an empty payload and must report the failure instead.
        return attachmentUri to ""
    }

    /**
     * Decodes an incoming P2P binary attachment (`attachmentBase64`) and writes it into
     * `filesDir/ygg_attachments/` (or `voice_notes/`), returning the local `file://` URI
     * so the receiving profile/node can immediately play the voice note or view the image/file.
     */
    fun saveIncomingP2PAttachment(
        context: Context,
        attachmentName: String?,
        attachmentType: String?,
        attachmentBase64: String?,
         fallbackUri: String?,
        voiceDurationSec: Int,
        voiceWaveformCsv: String?
    ): String? {
        if (attachmentName == null && attachmentType == null) return null

        val safeName = (attachmentName ?: when (attachmentType) {
            "VOICE" -> "rx_voice_${System.currentTimeMillis()}.wav"
            "IMAGE" -> "rx_image_${System.currentTimeMillis()}.png"
            else -> "rx_file_${System.currentTimeMillis()}.bin"
        }).replace(Regex("[^a-zA-Z0-9._-]"), "_")

        if (!attachmentBase64.isNullOrBlank()) {
            try {
                val decodedBytes = Base64.getUrlDecoder().decode(attachmentBase64)
                if (decodedBytes.isNotEmpty()) {
                    val targetDir = if (attachmentType == "VOICE") {
                        File(context.filesDir, "voice_notes").apply { mkdirs() }
                    } else {
                        File(context.filesDir, "ygg_attachments").apply { mkdirs() }
                    }
                    val finalName = if (attachmentType == "VOICE" && !safeName.endsWith(".wav", true)) {
                        "${safeName.substringBeforeLast(".")}.wav"
                    } else {
                        safeName
                    }
                    val outFile = File(targetDir, "rx_${System.currentTimeMillis()}_$finalName")
                    outFile.writeBytes(decodedBytes)
                    return Uri.fromFile(outFile).toString()
                }
            } catch (_: Throwable) {
            }
        }

        // No Base64 in the frame (legacy or truncated transfer): there is no audio to store,
        // so report the original Uri rather than writing a synthesized stand-in.
        if (attachmentType == "VOICE") {
            val wavFile = ensurePlayableVoiceFile(
                context = context,
                messageId = "rx_${System.currentTimeMillis()}",
                attachmentUri = fallbackUri,
                durationSec = voiceDurationSec.coerceAtLeast(1),
                waveformCsv = voiceWaveformCsv
            )
            return wavFile?.let { Uri.fromFile(it).toString() } ?: fallbackUri
        }
        return fallbackUri
    }

    private fun downsampleAmplitudes(input: List<Float>, targetCount: Int): List<Float> {
        if (input.isEmpty()) return List(targetCount) { 0.4f }
        if (input.size <= targetCount) return input
        return List(targetCount) { idx ->
            val srcIdx = (idx * input.size / targetCount).coerceIn(0, input.lastIndex)
            input[srcIdx].coerceIn(0.18f, 0.98f)
        }
    }

    private fun normalizeOrBoostMicPcm(rawPcm: ByteArray): ByteArray {
        val sampleCount = rawPcm.size / 2
        if (sampleCount == 0) return rawPcm
        val inBuf = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN)
        var maxAbs = 1
        for (i in 0 until sampleCount) {
            val s = abs(inBuf.getShort(i * 2).toInt())
            if (s > maxAbs) maxAbs = s
        }
        val gain = (18000.0 / maxAbs.toDouble()).coerceIn(1.0, 8.0)
        val outBytes = ByteArray(sampleCount * 2)
        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val boosted = (inBuf.getShort(i * 2).toInt() * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            outBuf.putShort(boosted)
        }
        return outBytes
    }

    private fun writeWavFile(
        file: File,
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1.toShort()) // AudioFormat = 1 (PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        FileOutputStream(file).use { fos ->
            fos.write(header.array())
            fos.write(pcmData)
            fos.flush()
        }
    }
}
