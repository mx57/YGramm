// File: app/src/main/java/com/example/engine/YggNativeCoreBridge.kt
package com.example.engine

/**
 * JNI Bridge to the compiled Go Yggdrasil library (`libygggram.so` on arm64-v8a)
 * with graceful fallback to the JVM/Kotlin Yggdrasil TLS/TCP/WSS & Mimir P2P socket engine
 * when running in environments without the prebuilt `.so` binary.
 */
object YggNativeCoreBridge {
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
}
