// File: app/src/test/java/com/example/engine/YggCoreDialListTest.kt
package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dial list the whitelist switch has to rewrite is the core's, not the probe flag.
 *
 * On an allow-listed uplink no TCP probe answers, so a pass keyed on reachability parked and
 * restored zero peers while the node went on dialling the cleartext endpoints the profile exists
 * to stop. These tests pin what the node reports as dialled, including the sockets that never
 * completed a handshake - which is exactly the set the profile has to act on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggCoreDialListTest {

    @Test
    fun `a dial the core holds is listed even when it never answered`() {
        val raw = """
            {"ok":true,"upCount":1,"total":3,"peers":[
              {"URI":"tls://Example.COM:443?maxbackoff=20s","Up":true,
               "Key":"uzPfp2F7XkOu9gPpXWAERrB9y5AaXr3M3SbYS6u4m3M="},
              {"URI":"tcp://185.31.240.19:443?maxbackoff=20s","Up":false},
              {"URI":"tls://ygg.zbin.eu:7743","Up":true},
              {"URI":"","Up":true}
            ]}
        """.trimIndent()
        assertEquals(
            "Every endpoint the node is configured to dial counts, in the form the database " +
                "stores it, and a blank URI is not an endpoint",
            setOf("tls://example.com:443", "tcp://185.31.240.19:443", "tls://ygg.zbin.eu:7743"),
            YggNativeCoreBridge.readConfiguredPeerUris(raw)
        )
    }

    @Test
    fun `a dial list read is never invented from a broken answer`() {
        assertTrue(
            "A core that answered with garbage has no known dials, so the switch must not " +
                "start adding or removing peers it cannot see",
            YggNativeCoreBridge.readConfiguredPeerUris("{not json").isEmpty()
        )
    }
}
