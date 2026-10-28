// File: app/src/test/java/com/example/service/YggNotificationAvatarTest.kt
package com.example.service

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.engine.YggAvatarCodec
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A notification sits on a lock screen where anyone holding the phone reads it, and its large icon
 * is the one visual claim the app makes about who wrote. The rule these tests pin is that the icon
 * is the picture this device actually received and filed for that chat — and that when no such
 * file exists the notification simply goes out with no icon. A plausible face taken from a cache
 * would be a claim about a person the network never confirmed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggNotificationAvatarTest {

    private fun avatarDir(): File =
        YggAvatarCodec.avatarDir(ApplicationProvider.getApplicationContext())

    /** Filed under a made-up name: what matters to the icon is that these bytes are a picture on this device. */
    private fun storedAvatar(name: String): File {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.rgb(200, 40, 90))
        val bytes = java.io.ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this)
        }.toByteArray()
        return File(avatarDir(), name).apply { writeBytes(bytes) }
    }

    @Test
    fun `a notification posts the picture this device received as its large icon`() {
        val file = storedAvatar("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg")
        assertNotNull("Файл на этом устройстве даёт иконку", YggNotificationCenter.notificationLargeIcon("file://${file.absolutePath}"))
    }

    @Test
    fun `a notification with a missing avatar file posts without a large icon`() {
        val gone = File(avatarDir(), "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff.jpg")
        assertNull(
            "Файла нет — иконки нет",
            YggNotificationCenter.notificationLargeIcon("file://${gone.absolutePath}")
        )
        assertNull(
            "Пустая строка означает «аватар не приходил», а не «возьми любой»",
            YggNotificationCenter.notificationLargeIcon("")
        )
    }

    /**
     * A content:// uri is a picture in the user's gallery, not one a peer sent. Only this device's
     * own profile row ever holds one, so a chat must never resolve such a uri for an icon: the
     * owner can revoke it at any moment, and a notification that outlives the grant is a broken
     * promise rather than a face.
     */
    @Test
    fun `a uri that is not a stored file gives no icon`() {
        assertNull(
            YggNotificationCenter.notificationLargeIcon("content://media/external/images/media/42")
        )
        assertNull(
            "Каталог вместо файла иконкой не становится",
            YggNotificationCenter.notificationLargeIcon("file://${avatarDir().absolutePath}")
        )
    }
}
