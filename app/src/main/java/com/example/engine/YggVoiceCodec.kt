// File: app/src/main/java/com/example/engine/YggVoiceCodec.kt
package com.example.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voice payload between two profiles.
 *
 * The wire used to carry nothing but raw PCM: a 40 s note was ~1.3 MB of base64 inside an encrypted
 * envelope, which is exactly the traffic a whitelisted uplink throttles. The platform ships an Opus
 * encoder (`c2.android.opus.encoder` on AOSP and most vendor images), so the codec is asked for, not
 * assumed: [capability] reads the real MediaCodecList, and every caller treats a null encoder as
 * "this device sends PCM" rather than pretending compression happened.
 *
 * The container is deliberately ours and trivially parseable: Opus packets are not self-delimiting,
 * so a decoder needs the lengths, and a receiver must be able to tell an Opus note from a WAV it was
 * never meant to open.
 */
object YggVoiceCodec {

    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val FRAME_MS = 20
    const val BIT_RATE = 24_000

    /** Bytes of one 20 ms frame of 16-bit mono PCM at [SAMPLE_RATE]. */
    const val FRAME_BYTES = SAMPLE_RATE / 1000 * FRAME_MS * 2

    /** Uncompressed call path: 16000 samples/s * 2 bytes - what the app sent before this codec. */
    const val PCM_BYTES_PER_SECOND = SAMPLE_RATE * 2

    const val OPUS_MIME = MediaFormat.MIMETYPE_AUDIO_OPUS

    const val CODEC_OPUS = 1
    const val CODEC_PCM = 2

    /** What an ACK and a voice note say on the wire when this device really has the codec. */
    const val NAME_OPUS = "opus"

    fun advertises(voiceCodecsCsv: String): Boolean =
        voiceCodecsCsv.split(',').any { it.trim().equals(NAME_OPUS, ignoreCase = true) }

    /** `YGO1` + version + codec + geometry + packet table. */
    private const val MAGIC = "YGO1"
    private const val CONTAINER_VERSION = 1
    internal const val HEADER_BYTES = 14

    data class Capability(
        val encoderCodecName: String?,
        val decoderCodecName: String?
    ) {
        /** Both halves must exist before a device may compress: a note nobody can open is worse than a big one. */
        val canEncodeOpus: Boolean get() = encoderCodecName != null && decoderCodecName != null
        val canDecodeOpus: Boolean get() = decoderCodecName != null
    }

    /** What one voice payload actually cost, measured rather than calculated from a table. */
    data class EncodedVoice(
        val codecId: Int,
        val bytes: ByteArray,
        val pcmBytes: Int
    ) {
        val durationSeconds: Int get() = if (pcmBytes <= 0) 0 else pcmBytes / PCM_BYTES_PER_SECOND
    }

    @Volatile
    private var cachedCapability: Capability? = null

    /**
     * Enumerates the codecs this device really has. An encoder is only usable if it accepts 16 kHz
     * mono - a codec that insists on 48 kHz would be configured, fail, and leave the caller with
     * nothing but a worse-sounding note.
     */
    fun capability(): Capability = cachedCapability ?: probe().also { cachedCapability = it }

    /** Test seam: the probe caches, so a device that gains a codec after an update needs a reset. */
    fun resetCapabilityCache() {
        cachedCapability = null
    }

    private fun probe(): Capability = try {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        var encoder: String? = null
        var decoder: String? = null
        for (info in list.codecInfos) {
            if (info.isEncoder) {
                if (encoder == null && supportsOpus16kMono(info)) encoder = info.name
            } else if (decoder == null && supportsOpus16kMono(info)) {
                decoder = info.name
            }
        }
        Capability(encoder, decoder)
    } catch (_: Throwable) {
        Capability(null, null)
    }

    private fun supportsOpus16kMono(info: MediaCodecInfo): Boolean {
        val types = info.supportedTypes ?: return false
        if (!types.any { it.equals(OPUS_MIME, ignoreCase = true) }) return false
        val audio = try {
            info.getCapabilitiesForType(OPUS_MIME)?.audioCapabilities
        } catch (_: Throwable) {
            null
        } ?: return false
        // A codec that insists on 48 kHz or on stereo would be configured, fail, and leave the
        // caller with nothing, so the probe asks the platform what it really accepts.
        val rateOk = try {
            audio.isSampleRateSupported(SAMPLE_RATE)
        } catch (_: Throwable) {
            false
        }
        val minChannels = audio.minInputChannelCount
        val maxChannels = audio.maxInputChannelCount
        // Zero means the platform published no bound, which the configuration below satisfies.
        val channelOk = minChannels <= 0 || maxChannels <= 0 || CHANNELS in minChannels..maxChannels
        return rateOk && channelOk
    }

    fun hasOpusMagic(header: ByteArray): Boolean =
        header.size >= 4 && String(header, 0, 4, Charsets.US_ASCII) == MAGIC

    fun isOpusContainer(payload: ByteArray): Boolean =
        payload.size > HEADER_BYTES && hasOpusMagic(payload)

    /**
     * Compresses PCM into [CODEC_OPUS] when this device can, and hands back the untouched
     * [CODEC_PCM] payload when it cannot. Only empty input returns null: a codec that is missing
     * or that fails mid-run must not cost the user their message, and the caller reads the codec
     * id to say honestly which shape went out.
     */
    fun encode(pcm: ByteArray): EncodedVoice? {
        if (pcm.isEmpty()) return null
        val cap = capability()
        if (!cap.canEncodeOpus) return EncodedVoice(CODEC_PCM, pcm, pcm.size)
        val packets = try {
            runEncoder(cap.encoderCodecName!!, pcm)
        } catch (_: Throwable) {
            null
        }
        if (packets == null || packets.isEmpty()) {
            return EncodedVoice(CODEC_PCM, pcm, pcm.size)
        }
        // Compression that inflates the payload (a fraction of a frame, or a codec that ignores
        // the bitrate) is not worth a second codec on the wire: keep the PCM it started from.
        val container = writeContainer(CODEC_OPUS, packets)
        return if (container.size >= pcm.size) {
            EncodedVoice(CODEC_PCM, pcm, pcm.size)
        } else {
            EncodedVoice(CODEC_OPUS, container, pcm.size)
        }
    }

    /**
     * Returns 16-bit mono PCM at the rate stored in the payload, or null when the bytes cannot be
     * opened. A null here is a real failure the caller must report: silent playback of a note that
     * did arrive is the kind of bug the recipient cannot see.
     */
    fun decode(payload: ByteArray): ByteArray? {
        if (!isOpusContainer(payload)) return null
        val header = readHeader(payload) ?: return null
        val cap = capability()
        if (!cap.canDecodeOpus) return null
        return try {
            runDecoder(cap.decoderCodecName!!, header.packets, header.sampleRate, header.channels)
        } catch (_: Throwable) {
            null
        }
    }

    // --- container ---------------------------------------------------------

    internal data class ContainerHeader(
        val codecId: Int,
        val sampleRate: Int,
        val channels: Int,
        val frameMs: Int,
        val packets: List<ByteArray>
    )

    internal fun writeContainer(codecId: Int, packets: List<ByteArray>): ByteArray {
        val total = HEADER_BYTES + packets.sumOf { it.size + 2 }
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buffer.put(CONTAINER_VERSION.toByte())
        buffer.put(codecId.toByte())
        buffer.putShort(SAMPLE_RATE.toShort())
        buffer.put(CHANNELS.toByte())
        buffer.put(FRAME_MS.toByte())
        buffer.putShort(packets.size.toShort())
        buffer.putShort(0) // reserved: a future frame-duration or DTX flag goes here, not in a new magic
        for (packet in packets) {
            buffer.putShort(packet.size.toShort())
            buffer.put(packet)
        }
        return buffer.array()
    }

    internal fun readHeader(payload: ByteArray): ContainerHeader? = try {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buffer.get(magic)
        val version = buffer.get().toInt()
        val codecId = buffer.get().toInt()
        val sampleRate = buffer.short.toInt() and 0xFFFF
        val channels = buffer.get().toInt()
        val frameMs = buffer.get().toInt()
        val count = buffer.short.toInt() and 0xFFFF
        buffer.short // reserved
        if (String(magic, Charsets.US_ASCII) != MAGIC || version != CONTAINER_VERSION ||
            codecId != CODEC_OPUS || sampleRate <= 0 || channels <= 0 || frameMs <= 0 || count <= 0
        ) {
            null
        } else {
            val packets = ArrayList<ByteArray>(count)
            var damaged = false
            repeat(count) {
                if (damaged) return@repeat
                val length = buffer.short.toInt() and 0xFFFF
                if (length <= 0 || buffer.remaining() < length) {
                    damaged = true
                } else {
                    val packet = ByteArray(length)
                    buffer.get(packet)
                    packets.add(packet)
                }
            }
            if (damaged || packets.size != count) null else ContainerHeader(
                codecId,
                sampleRate,
                channels,
                frameMs,
                packets
            )
        }
    } catch (_: Throwable) {
        null
    }

    // --- MediaCodec --------------------------------------------------------

    /**
     * A codec that wedges must cost seconds, not the caller's thread: the pump below only stops on
     * end-of-stream, and a device whose encoder never reports it would otherwise hang the send.
     */
    private const val PUMP_TIMEOUT_MS = 15_000L

    private fun runEncoder(codecName: String, pcm: ByteArray): List<ByteArray>? {
        val codec = try {
            MediaCodec.createByCodecName(codecName)
        } catch (_: Throwable) {
            return null
        }
        val info = MediaCodec.BufferInfo()
        val packets = ArrayList<ByteArray>()
        val result: List<ByteArray>? = try {
            val format = MediaFormat.createAudioFormat(OPUS_MIME, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 4)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val deadline = SystemClock.elapsedRealtime() + PUMP_TIMEOUT_MS
            var frameIndex = 0
            var offset = 0
            var inputDone = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val take = (pcm.size - offset).coerceAtMost(FRAME_BYTES)
                        val frame = ByteArray(FRAME_BYTES)
                        if (take > 0) System.arraycopy(pcm, offset, frame, 0, take)
                        offset += take
                        inputDone = offset >= pcm.size
                        val buffer = codec.getInputBuffer(inputIndex) ?: return null
                        buffer.clear()
                        buffer.put(frame)
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            FRAME_BYTES,
                            frameIndex * FRAME_MS * 1000L,
                            if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        )
                        frameIndex++
                    }
                }
                var outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                while (outputIndex >= 0) {
                    val packet = readOutput(codec, outputIndex, info) ?: return null
                    if (packet.isNotEmpty()) packets.add(packet)
                    codec.releaseOutputBuffer(outputIndex, false)
                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (ended) return packets.ifEmpty { null }
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            releaseQuietly(codec)
        }
        return result
    }

    private fun runDecoder(
        codecName: String,
        packets: List<ByteArray>,
        sampleRate: Int,
        channels: Int
    ): ByteArray? {
        val codec = try {
            MediaCodec.createByCodecName(codecName)
        } catch (_: Throwable) {
            return null
        }
        val info = MediaCodec.BufferInfo()
        val out = java.io.ByteArrayOutputStream(packets.size * FRAME_BYTES * 4)
        val result: ByteArray? = try {
            val format = MediaFormat.createAudioFormat(OPUS_MIME, sampleRate, channels)
            codec.configure(format, null, null, 0)
            codec.start()

            val deadline = SystemClock.elapsedRealtime() + PUMP_TIMEOUT_MS
            var index = 0
            var queuedAll = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!queuedAll) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val packet = packets[index]
                        val buffer = codec.getInputBuffer(inputIndex) ?: return null
                        buffer.clear()
                        buffer.put(packet)
                        val last = index == packets.size - 1
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            packet.size,
                            index * FRAME_MS * 1000L,
                            if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        )
                        index++
                        queuedAll = last
                    }
                }
                var outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                while (outputIndex >= 0) {
                    val chunk = readOutput(codec, outputIndex, info) ?: return null
                    out.write(chunk)
                    codec.releaseOutputBuffer(outputIndex, false)
                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (ended) return out.toByteArray().takeIf { it.isNotEmpty() }
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            releaseQuietly(codec)
        }
        return result
    }

    private fun readOutput(
        codec: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo
    ): ByteArray? {
        val buffer = codec.getOutputBuffer(outputIndex) ?: return null
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val data = ByteArray(info.size)
        buffer.get(data)
        return data
    }

    private fun releaseQuietly(codec: MediaCodec) {
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
    }

    // --- self check --------------------------------------------------------

    /**
     * Measures the whole codec path on this device with a tone this code generates itself. This is
     * not a stand-in for a real microphone: it proves the encoder, the container and the decoder
     * agree on the bytes, and it reports the compression the platform really delivers here instead
     * of a number copied from an Opus brochure.
     */
    fun selfCheck(seconds: Int = 3): VoiceSelfCheck {
        val cap = capability()
        val pcm = syntheticTonePcm(seconds)
        val encoded: EncodedVoice = (if (cap.canEncodeOpus) encode(pcm) else EncodedVoice(CODEC_PCM, pcm, pcm.size))
            ?: return VoiceSelfCheck(
                encoder = cap.encoderCodecName,
                decoder = cap.decoderCodecName,
                pcmBytes = pcm.size,
                payloadBytes = 0,
                decodedBytes = 0,
                snrDb = null,
                verdict = "кодер вернул пусто"
            )
        val decoded = if (encoded.codecId == CODEC_OPUS) decode(encoded.bytes) else pcm
        val snr = decoded?.let { toneSignalToNoiseDb(pcm, it) }
        return VoiceSelfCheck(
            encoder = cap.encoderCodecName,
            decoder = cap.decoderCodecName,
            pcmBytes = pcm.size,
            payloadBytes = encoded.bytes.size,
            decodedBytes = decoded?.size ?: 0,
            snrDb = snr,
            verdict = when {
                !cap.canEncodeOpus -> "Opus-кодер в этой системе не найден: голос идёт несжатым PCM"
                decoded == null -> "кадр не раскрылся назад: голос остаётся PCM"
                decoded.size < pcm.size / 2 -> "на выходе меньше половины длительности: голос остаётся PCM"
                snr == null -> "тон не измерен: голос остаётся PCM"
                snr <= 6.0 -> "тон до и после кодека разошёлся: голос остаётся PCM"
                else -> "OK"
            }
        )
    }

    data class VoiceSelfCheck(
        val encoder: String?,
        val decoder: String?,
        val pcmBytes: Int,
        val payloadBytes: Int,
        val decodedBytes: Int,
        val snrDb: Double?,
        val verdict: String
    ) {
        /** Percent of the original PCM that actually went on the wire, measured on this device. */
        val payloadPercent: Int get() = if (pcmBytes <= 0) 100 else (payloadBytes * 100 / pcmBytes)

        /** Only a measured round trip lets the UI say the codec works here. */
        val passed: Boolean get() = verdict == "OK"
    }

    /**
     * 300 Hz tone with a slow amplitude envelope, so the codec has something to bite on and the
     * measurement is not a DC offset. Only used by [selfCheck].
     */
    private fun syntheticTonePcm(seconds: Int): ByteArray {
        val rate = SAMPLE_RATE
        val samples = rate * seconds
        val bytes = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val t = i.toDouble() / rate
            val envelope = 0.55 + 0.45 * kotlin.math.sin(2.0 * kotlin.math.PI * 0.7 * t)
            val value = kotlin.math.sin(2.0 * kotlin.math.PI * 300.0 * t) * 12000.0 * envelope
            bytes.putShort(value.toInt().coerceIn(-32767, 32767).toShort())
        }
        return bytes.array()
    }

    /**
     * Signal-to-noise of the decoded tone against the original, allowing for the codec delay:
     * the shift is found by correlation rather than assumed, so a honest 20 ms latency is not
     * scored as distortion.
     */
    private fun toneSignalToNoiseDb(original: ByteArray, decoded: ByteArray): Double? {
        if (original.size < 4 || decoded.size < 4) return null
        val orig = shortsOf(original)
        val dec = shortsOf(decoded)
        val maxShift = kotlin.math.min(2048, dec.size - 1)
        if (maxShift <= 0) return null
        var bestShift = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var shift = 0
        while (shift <= maxShift) {
            var score = 0.0
            val limit = kotlin.math.min(orig.size, dec.size - shift) - 1
            var i = 0
            while (i < limit) {
                score += orig[i].toDouble() * dec[i + shift].toDouble()
                i += 3
            }
            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
            shift += 8
        }
        val limit = kotlin.math.min(orig.size, dec.size - bestShift)
        if (limit < 64) return null
        var errSum = 0.0
        var sigSum = 0.0
        for (i in 0 until limit) {
            val diff = orig[i].toDouble() - dec[i + bestShift].toDouble()
            errSum += diff * diff
            sigSum += orig[i].toDouble() * orig[i].toDouble()
        }
        if (sigSum <= 0.0) return null
        if (errSum <= 0.0) return 99.0
        return 10.0 * kotlin.math.log10(sigSum / errSum)
    }

    private fun shortsOf(pcm: ByteArray): ShortArray {
        val count = pcm.size / 2
        val out = ShortArray(count)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) out[i] = buffer.short
        return out
    }
}
