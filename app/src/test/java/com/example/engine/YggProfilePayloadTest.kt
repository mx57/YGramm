// File: app/src/test/java/com/example/engine/YggProfilePayloadTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PROFILE body is the one frame field a remote node picks entirely on its own, so the bounds
 * are checked where its bytes are read - once, here - rather than at every place that displays it.
 */
class YggProfilePayloadTest {

    private fun body(
        name: String = "Мира",
        handle: String = "@mira.ygg",
        status: String = "пишу с телефона",
        avatarHash: String = "",
        version: Long = 1_700_000_000_000L
    ) = YggProfilePayload.ProfileBody(name, handle, status, avatarHash, version)

    @Test
    fun `a profile body round-trips through the wire encoding`() {
        val original = body(avatarHash = "a".repeat(64))
        val decoded = YggProfilePayload.decode(YggProfilePayload.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `cyrillic and spaces survive the encoding`() {
        val decoded = YggProfilePayload.decode(
            YggProfilePayload.encode(body(name = "Пётр Юрьевич", status = "он не «тише воды»"))
        )
        assertEquals("Пётр Юрьевич", decoded?.displayName)
        assertEquals("он не «тише воды»", decoded?.statusText)
    }

    @Test
    fun `a body without a version is not a profile`() {
        // Without a version the receiver cannot tell this copy from a newer one it already
        // applied, so it would have to guess which identity to keep.
        assertNull(YggProfilePayload.decode("dn=${b64("Кто-то")}|pv=${b64("0")}"))
        assertNull(YggProfilePayload.decode("dn=${b64("Кто-то")}"))
    }

    @Test
    fun `control characters and zero-width joins never reach the interface`() {
        val decoded = YggProfilePayload.decode(
            YggProfilePayload.encode(
                body(name = "Мира\u0000\u001Bкрасный", status = "привет\u200B\uFEFFмир")
            )
        )
        val name = decoded?.displayName.orEmpty()
        assertEquals("Мира красный", name)
        assertEquals("привет мир", decoded?.statusText)
        assertTrue(
            "Ни одного управляющего символа в сохранённом поле",
            name.none { it.isISOControl() || it == '\u200B' || it == '\uFEFF' }
        )
    }

    @Test
    fun `an over-long field is dropped whole instead of cut mid-word`() {
        val decoded = YggProfilePayload.decode(
            YggProfilePayload.encode(
                body(name = "И".repeat(500), handle = "@y".repeat(60), status = "с".repeat(500))
            )
        )
        assertEquals("", decoded?.displayName)
        assertEquals("", decoded?.handle)
        assertEquals("", decoded?.statusText)
        assertEquals(1_700_000_000_000L, decoded?.version)
    }

    @Test
    fun `a non-hex avatar hash is refused rather than stored`() {
        val payload = YggProfilePayload.encode(body()).replace(
            "av=${b64("")}",
            "av=${b64("../../files/hack")}"
        )
        assertNull(YggProfilePayload.decode(payload))
    }

    private fun b64(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
}
