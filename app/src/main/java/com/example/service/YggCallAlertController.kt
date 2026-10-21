// File: app/src/main/java/com/example/service/YggCallAlertController.kt
package com.example.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Real ringing for an incoming call: loops the system ringtone and repeats the
 * vibration pattern until the call is answered, declined or times out. The
 * heads-up notification alone is silent on most devices, which made an incoming
 * call easy to miss with the screen off.
 */
object YggCallAlertController {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    @Synchronized
    fun start(context: Context) {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                val mediaPlayer = MediaPlayer()
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mediaPlayer.setDataSource(context, uri)
                mediaPlayer.isLooping = true
                mediaPlayer.prepare()
                mediaPlayer.start()
                player = mediaPlayer
            }
        } catch (_: Throwable) {
            player = null
        }
        try {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            }
            if (device != null && device.hasVibrator()) {
                val pattern = longArrayOf(0L, 900L, 650L, 900L, 650L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    device.vibrate(pattern, 0)
                }
                vibrator = device
            }
        } catch (_: Throwable) {
            vibrator = null
        }
    }

    @Synchronized
    fun stop() {
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null
    }
}
