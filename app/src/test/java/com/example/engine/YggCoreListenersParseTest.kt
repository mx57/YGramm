// File: app/src/test/java/com/example/engine/YggCoreListenersParseTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The listener report is only honest if the fields the Go core writes are the fields the
 * screen reads. A key that drifts yields an empty list, and an empty list on the network
 * screen looks like "no listeners" rather than like a parsing mistake.
 *
 * The document below is the exact shape of YggGramGetListenersImpl's output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggCoreListenersParseTest {

    @Test
    fun `reads every bind the core reported with its own verdict`() {
        val raw = """
            {"ok":true,"profileId":"p1","upCount":1,"listeners":[
              {"scheme":"tcp","uri":"tcp://0.0.0.0:5985","bound":true,
               "addr":"[::]:5985","error":"","recordedAt":1730000000000},
              {"scheme":"tls","uri":"tls://0.0.0.0:443","bound":false,"addr":"",
               "error":"listen tcp 0.0.0.0:443: bind: permission denied","recordedAt":1730000000001},
              {"scheme":"quic","uri":"quic://0.0.0.0:443","bound":false,"addr":"",
               "error":"","recordedAt":1730000000002}
            ]}
        """.trimIndent()

        val listeners = YggNativeCoreBridge.readCoreListeners(raw)
        assertEquals(3, listeners.size)
        assertEquals("tcp", listeners[0].scheme)
        assertTrue(listeners[0].bound)
        assertEquals("tcp слушает [::]:5985", listeners[0].describeRu())
        assertTrue(listeners[1].describeRu().contains("permission denied"))
        // An empty error with bound=false cannot be shown as a blank line: the fallback names
        // the two reasons this actually happens on a phone.
        assertEquals("quic не слушает: порт занят или прав не хватает", listeners[2].describeRu())
    }

    @Test
    fun `a core without listeners or a damaged document parses to an empty list`() {
        assertEquals(0, YggNativeCoreBridge.readCoreListeners("""{"ok":true,"listeners":[]}""").size)
        assertEquals(0, YggNativeCoreBridge.readCoreListeners("not json at all").size)
    }
}
