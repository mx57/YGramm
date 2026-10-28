package com.example.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class YGrammPeerRelayTest {

    private val request = ApkRelayRequest(
        transferId = "apk_m3k9x1_a1b2c3d4",
        chatId = "chat_profile_primary_7",
        apkAssetName = "YGramm-arm64-v8a.apk",
        sha256Hex = SHA,
        versionCode = 23
    )

    private fun nonEmptyTempApk(): File {
        val file = File.createTempFile("YGramm-relay", ".apk")
        file.writeBytes(ByteArray(4096) { (it % 251).toByte() })
        file.deleteOnExit()
        return file
    }

    @Test
    fun `request survives a wire round trip`() {
        val parsed = YGrammPeerRelay.parseRequest(YGrammPeerRelay.requestFields(request))
        assertEquals(request, parsed)
    }

    @Test
    fun `a peer cannot choose where the serving device looks`() {
        for (name in listOf("../../etc/passwd.apk", "/abs/YGramm.apk", "YGramm-arm64.apk.exe", "", "a b.apk")) {
            val fields = YGrammPeerRelay.requestFields(request) + ("apk" to name)
            assertNull("refusing $name", YGrammPeerRelay.parseRequest(fields))
        }
    }

    @Test
    fun `every request field is validated before it is used`() {
        assertNull(YGrammPeerRelay.parseRequest(YGrammPeerRelay.requestFields(request) + ("type" to "PING")))
        assertNull(YGrammPeerRelay.parseRequest(YGrammPeerRelay.requestFields(request) + ("sha256" to "not-a-digest")))
        assertNull(YGrammPeerRelay.parseRequest(YGrammPeerRelay.requestFields(request) + ("transferId" to "short")))
        assertNull(YGrammPeerRelay.parseRequest(YGrammPeerRelay.requestFields(request) + ("versionCode" to "0")))
        assertNull(YGrammPeerRelay.parseRequest(YGrammPeerRelay.requestFields(request) + ("versionCode" to "abc")))

        // An uppercase digest is accepted after normalisation: the case of a hex string is not a
        // security boundary, the bytes the digest names are.
        val upper = YGrammPeerRelay.parseRequest(
            YGrammPeerRelay.requestFields(request) + ("sha256" to SHA.uppercase())
        )
        assertEquals(SHA, upper?.sha256Hex)
    }

    @Test
    fun `refusal always reaches the requester as text`() {
        val serving = YGrammPeerRelay.parseReply(YGrammPeerRelay.replyFields(request.transferId, true))
        assertEquals(true to "", serving)

        val refused = YGrammPeerRelay.parseReply(
            YGrammPeerRelay.replyFields(request.transferId, false, "раздача выключена")
        )
        assertEquals(false to "раздача выключена", refused)

        val mute = YGrammPeerRelay.parseReply(YGrammPeerRelay.replyFields(request.transferId, false))
        assertNotNull(mute)
        assertFalse(mute!!.first)
        assertTrue(mute.second.isNotBlank())

        assertNull(YGrammPeerRelay.parseReply(mapOf("apkReply" to "serving")))
        assertNull(YGrammPeerRelay.parseReply(mapOf("transferId" to request.transferId)))
    }

    @Test
    fun `nothing is served while the user has not turned sharing on`() {
        val decision = YGrammPeerRelay.decideServe(
            request = request,
            offerEnabled = false,
            hasDialogWithRequester = true,
            heldFile = nonEmptyTempApk(),
            heldFileSha256Hex = SHA
        )
        assertTrue(decision is YGrammRelayDecision.Refuse)
    }

    @Test
    fun `serving needs a dialog a file and the very digest that was asked for`() {
        val held = nonEmptyTempApk()

        assertTrue(
            YGrammPeerRelay.decideServe(
                request = null, offerEnabled = true, hasDialogWithRequester = true,
                heldFile = held, heldFileSha256Hex = SHA
            ) is YGrammRelayDecision.Refuse
        )
        assertTrue(
            YGrammPeerRelay.decideServe(
                request = request, offerEnabled = true, hasDialogWithRequester = false,
                heldFile = held, heldFileSha256Hex = SHA
            ) is YGrammRelayDecision.Refuse
        )
        assertTrue(
            YGrammPeerRelay.decideServe(
                request = request, offerEnabled = true, hasDialogWithRequester = true,
                heldFile = null, heldFileSha256Hex = SHA
            ) is YGrammRelayDecision.Refuse
        )
        assertTrue(
            YGrammPeerRelay.decideServe(
                request = request, offerEnabled = true, hasDialogWithRequester = true,
                heldFile = held, heldFileSha256Hex = null
            ) is YGrammRelayDecision.Refuse
        )
        assertTrue(
            YGrammPeerRelay.decideServe(
                request = request, offerEnabled = true, hasDialogWithRequester = true,
                heldFile = held, heldFileSha256Hex = OTHER_SHA
            ) is YGrammRelayDecision.Refuse
        )

        val serve = YGrammPeerRelay.decideServe(
            request = request, offerEnabled = true, hasDialogWithRequester = true,
            heldFile = held, heldFileSha256Hex = SHA
        )
        assertEquals(YGrammRelayDecision.Serve, serve)
    }

    @Test
    fun `an empty leftover file is not something to share`() {
        val empty = File.createTempFile("YGramm-relay-empty", ".apk")
        empty.deleteOnExit()
        val decision = YGrammPeerRelay.decideServe(
            request = request, offerEnabled = true, hasDialogWithRequester = true,
            heldFile = empty, heldFileSha256Hex = SHA
        )
        assertTrue(decision is YGrammRelayDecision.Refuse)
    }

    @Test
    fun `the local manifest anchor survives a write and rejects damage`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ygg-relay-${System.nanoTime()}")
        val manifest = YGrammUpdateManifest(23, "1.7.6", "YGramm-arm64-v8a.apk", SHA)
        assertNull(YGrammPeerRelay.readLocalManifest(dir))

        assertTrue(YGrammPeerRelay.saveLocalManifest(dir, manifest, 1_730_000_000_000L))
        val stored = YGrammPeerRelay.readLocalManifest(dir)
        assertEquals(manifest, stored?.manifest)
        assertEquals(1_730_000_000_000L, stored?.savedAtMs)

        // Damage the anchor the way a peer could not: the file stays, its content stops meaning
        // anything, and the reader must say "no anchor" instead of trusting a fragment.
        YGrammPeerRelay.localManifestFile(dir).writeText("{\"versionCode\":23,\"versionName\":\"1.7.6\"}")
        assertNull(YGrammPeerRelay.readLocalManifest(dir))

        YGrammPeerRelay.localManifestFile(dir).writeText("this is not json")
        assertNull(YGrammPeerRelay.readLocalManifest(dir))
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private companion object {
        const val SHA = "8ca93549d6e68892ea2359bd0c41dec53e47ffd6b7c712f6037a47451eb413ed"
        const val OTHER_SHA = "0000000000000000000000000000000000000000000000000000000000000001"
    }
}
