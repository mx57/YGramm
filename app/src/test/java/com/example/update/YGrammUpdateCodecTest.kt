package com.example.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class YGrammUpdateCodecTest {

    private val manifestBlock = """
        ```ygramm-update
        {"versionCode":14,"versionName":"1.6.7-arm64-v8a","apk":"YGramm-arm64-v8a.apk","sha256":"${SHA}"}
        ```
    """.trimIndent()

    private fun releaseJson(body: String, assetName: String = "YGramm-arm64-v8a.apk"): String {
        val assets = JSONObject()
            .put("name", assetName)
            .put("size", 15831093)
            .put("browser_download_url", "https://github.com/mx57/YGramm/releases/download/v1.6.7/$assetName")
        return JSONObject()
            .put("tag_name", "v1.6.7")
            .put("html_url", "https://github.com/mx57/YGramm/releases/tag/v1.6.7")
            .put("body", body)
            .put("assets", org.json.JSONArray().put(assets))
            .toString()
    }

    @Test
    fun `manifest is parsed out of a release body`() {
        val manifest = YGrammUpdateCodec.extractManifest("## YGramm 1.6.7\n\n$manifestBlock")
        assertNotNull(manifest)
        assertEquals(14, manifest!!.versionCode)
        assertEquals("1.6.7-arm64-v8a", manifest.versionName)
        assertEquals("YGramm-arm64-v8a.apk", manifest.apkAssetName)
        assertEquals(SHA, manifest.sha256Hex)
    }

    @Test
    fun `a release without a manifest is not guessed from the tag`() {
        assertNull(YGrammUpdateCodec.extractManifest("Просто текст релиза без блока"))
        assertNull(YGrammUpdateCodec.extractManifest(null))
        assertNull(
            YGrammUpdateCodec.extractManifest(
                "```ygramm-update\n{\"versionCode\":14,\"versionName\":\"1.6.7\",\"apk\":\"a.apk\",\"sha256\":\"zz\"}\n```"
            )
        )
        assertNull(
            YGrammUpdateCodec.extractManifest(
                "```ygramm-update\n{\"versionCode\":0,\"versionName\":\"1.6.7\",\"apk\":\"a.apk\",\"sha256\":\"$SHA\"}\n```"
            )
        )
    }

    @Test
    fun `newer release with a matching asset becomes an offer`() {
        val outcome = YGrammUpdateCodec.parseLatestRelease(releaseJson(manifestBlock), 13)
        assertTrue(outcome is YGrammCheckOutcome.Available)
        val available = outcome as YGrammCheckOutcome.Available
        assertEquals(14, available.update.manifest.versionCode)
        assertEquals(SHA, available.update.manifest.sha256Hex)
        assertTrue(available.update.downloadUrl.endsWith("YGramm-arm64-v8a.apk"))
        assertEquals(15831093L, available.update.assetSizeBytes)
    }

    @Test
    fun `equal or newer installed version reports no update`() {
        val latest = YGrammUpdateCodec.parseLatestRelease(releaseJson(manifestBlock), 14)
        assertTrue(latest is YGrammCheckOutcome.UpToDate)
        assertEquals(14, (latest as YGrammCheckOutcome.UpToDate).runningVersionCode)
        assertTrue(
            YGrammUpdateCodec.parseLatestRelease(releaseJson(manifestBlock), 20) is YGrammCheckOutcome.UpToDate
        )
    }

    @Test
    fun `missing manifest or missing asset is reported as a reason`() {
        val noManifest = YGrammUpdateCodec.parseLatestRelease(releaseJson("релиз без манифеста"), 13)
        assertTrue(noManifest is YGrammCheckOutcome.Failed)
        assertTrue((noManifest as YGrammCheckOutcome.Failed).reason.contains("манифеста"))

        val wrongAsset = YGrammUpdateCodec.parseLatestRelease(
            releaseJson(manifestBlock, assetName = "Other.apk"),
            13
        )
        assertTrue(wrongAsset is YGrammCheckOutcome.Failed)
        assertTrue((wrongAsset as YGrammCheckOutcome.Failed).reason.contains("YGramm-arm64-v8a.apk"))
    }

    @Test
    fun `github error document is surfaced not swallowed`() {
        val outcome = YGrammUpdateCodec.parseLatestRelease("""{"message":"Not Found"}""", 13)
        assertTrue(outcome is YGrammCheckOutcome.Failed)
        assertTrue((outcome as YGrammCheckOutcome.Failed).reason.contains("Not Found"))

        val garbage = YGrammUpdateCodec.parseLatestRelease("<html>502</html>", 13)
        assertTrue(garbage is YGrammCheckOutcome.Failed)
    }

    @Test
    fun `release notes drop the machine block`() {
        val notes = YGrammUpdateCodec.releaseNotes("## YGramm 1.6.7\n\nЧто нового\n\n$manifestBlock\n\nХвост")
        assertFalse(notes.contains("versionCode"))
        assertTrue(notes.contains("Что нового"))
    }

    @Test
    fun `sha256 of a downloaded file matches the published digest`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("YGramm-", ".apk", context.cacheDir)
        file.writeBytes(byteArrayOf(1, 2, 3) + "YGramm".toByteArray())
        assertEquals(
            "5976873259299e9c0d08c3c2c40c50cf7a4a78bf711471856f509bd355cc857f",
            YGrammUpdateCodec.sha256HexOfFile(file)
        )
        assertFalse(YGrammUpdateCodec.isSha256Hex(SHA.uppercase()))
        file.delete()
    }

    @Test
    fun `pinned release certificate is a full lowercase digest`() {
        assertTrue(YGrammUpdateCodec.isSha256Hex(YGrammUpdateEngine.PINNED_RELEASE_CERT_SHA256_HEX))
    }

    // Both intents are started from the application context: without NEW_TASK Android throws
    // "Calling startActivity() from outside of an Activity context", which crashed the real
    // device run the moment the installer permission had not been granted yet.
    @Test
    fun `updater intents are startable from the application context`() {
        val settings = YGrammUpdateEngine.unknownSourcesSettingsIntent()
        assertTrue(settings.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // FileProvider maps a POSIX device path; under Robolectric on Windows the data dir comes
        // back with backslash separators that match no configured root. The flag and MIME
        // assertions below are what the crash regression is about, so keep them, and let the
        // resolver itself be covered on Linux CI.
        assumeTrue("FileProvider path mapping is POSIX-only under Robolectric", File.separatorChar == '/')
        val apk = File(YGrammUpdateEngine.updateDir(context), "YGramm-flag-check.apk")
            .apply { writeBytes(byteArrayOf(9)) }
        val install = YGrammUpdateEngine.installIntent(context, apk)
        assertTrue(install.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("application/vnd.android.package-archive", install.type)
        apk.delete()
    }

    private companion object {
        const val SHA = "8ca93549d6e68892ea2359bd0c41dec53e47ffd6b7c712f6037a47451eb413ed"
    }
}
