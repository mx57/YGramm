// File: app/src/main/java/com/example/engine/YggNativeCoreBridge.kt
package com.example.engine

/**
 * JNI bridge to the real Yggdrasil Go core (`libygggram.so` on arm64-v8a).
 * Every native call returns a JSON string {"ok":bool,...} and never throws
 * across the boundary; this object degrades gracefully to "unavailable" when
 * the prebuilt `.so` is not packaged, letting callers fall back to the
 * Kotlin/C-socket transport.
 */
object YggNativeCoreBridge {
    private const val OK_MARKER = "\"ok\":true"

    @Volatile
    var isNativeLibraryLoaded: Boolean = false
        private set

    init {
        isNativeLibraryLoaded = try {
            System.loadLibrary("ygggram")
            true
        } catch (_: Throwable) {
            false
        }
    }

    data class NativeCoreResult(
        val ok: Boolean,
        val rawJson: String,
        val error: String
    )

    private external fun nativeInitCore(mnemonic: String, filesDir: String): String?
    private external fun nativeStopCore(): String?
    private external fun nativeAddPeer(peerUri: String, sinInterface: String): String?
    private external fun nativeRemovePeer(peerUri: String, sinInterface: String): String?
    private external fun nativeGetPeersJson(): String?

    private val unavailableResult: NativeCoreResult
        get() = NativeCoreResult(
            ok = false,
            rawJson = "",
            error = "Нативное ядро Yggdrasil недоступно (libygggram.so не загружена)"
        )

    private fun invoke(nativeCall: () -> String?): NativeCoreResult {
        if (!isNativeLibraryLoaded) return unavailableResult
        val raw = try {
            nativeCall() ?: return NativeCoreResult(false, "", "Нативное ядро вернуло пустой ответ")
        } catch (t: Throwable) {
            return NativeCoreResult(false, "", "Нативное ядро упало: ${t.javaClass.simpleName}: ${t.message}")
        }
        return if (raw.contains(OK_MARKER)) {
            NativeCoreResult(ok = true, rawJson = raw, error = "")
        } else {
            NativeCoreResult(ok = false, rawJson = raw, error = extractJsonError(raw))
        }
    }

    private fun extractJsonError(raw: String): String {
        val marker = "\"error\":\""
        val valueStart = raw.indexOf(marker)
        if (valueStart < 0) return "Нативное ядро вернуло ошибку без описания"
        val start = valueStart + marker.length
        var i = start
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                i += 2
                continue
            }
            if (c == '"') return raw.substring(start, i)
            i++
        }
        return raw.substring(start).take(200)
    }

    fun initCore(mnemonic: String, filesDir: String): NativeCoreResult =
        invoke { nativeInitCore(mnemonic, filesDir) }

    fun stopCore(): NativeCoreResult =
        invoke { nativeStopCore() }

    fun addPeer(peerUri: String, sinInterface: String = ""): NativeCoreResult =
        invoke { nativeAddPeer(peerUri, sinInterface) }

    fun removePeer(peerUri: String, sinInterface: String = ""): NativeCoreResult =
        invoke { nativeRemovePeer(peerUri, sinInterface) }

    fun getPeersJson(): NativeCoreResult =
        invoke { nativeGetPeersJson() }
}
