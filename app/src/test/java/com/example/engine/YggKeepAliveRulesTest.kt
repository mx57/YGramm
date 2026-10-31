// File: app/src/test/java/com/example/engine/YggKeepAliveRulesTest.kt
package com.example.engine

import com.example.data.ConversationEntity
import com.example.data.YggRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The keep-alive exists because a mesh route ages out when nothing crosses it, and the next message
 * then pays for a fresh search — on an uplink that filters searches, that is how a contact starts
 * looking gone. These rules decide who gets a frame and what a reply is allowed to say, so the
 * decisions worth arguing about are here rather than behind a socket.
 */
class YggKeepAliveRulesTest {

    private val peerKey = "a".repeat(64)
    private val now = 1_700_000_000_000L

    private fun state(
        proofAt: Long = now - 60_000L,
        pendingSentAt: Long? = null,
        unanswered: Int = 0
    ) = KeepAlivePeerState(
        proofAt = proofAt,
        // A ping still waiting was itself sent at that moment; the two fields never disagree in the
        // engine, and the tests say so once here instead of in every case.
        lastPingSentAt = pendingSentAt ?: 0L,
        pendingSentAt = pendingSentAt,
        unanswered = unanswered
    )

    private fun conversation(
        chatId: String = "chat_peer",
        profileId: String = "profile_primary",
        nodeKey: String = peerKey,
        chatType: String = "DIRECT",
        lastSeenAt: Long = now - 60_000L
    ) = ConversationEntity(
        chatId = chatId,
        title = "Пир",
        yggIpv6Address = "200:a8a7:dfee:0:0:0:0:1",
        publicKeyHex = nodeKey,
        protocolType = "YGG_X25519_AESGCM",
        isVerified = false,
        sealedSenderEnabled = true,
        disappearingTtlSeconds = 0,
        unreadCount = 0,
        lastMessagePreview = "",
        lastTimestamp = 1L,
        isOnlineInMesh = false,
        avatarColorHex = "#00AA88",
        bioStatus = "",
        routeTransportSummary = "",
        ownerProfileId = profileId,
        chatType = chatType,
        peerLastSeenAt = lastSeenAt
    )

    @Test
    fun aDialogNothingProvesIsSearchedAtTheSlowestCadence() {
        // A chat row is a contact card the user accepted, so a dialog the database has nothing about
        // is not a stranger: it is a peer whose route this app has never managed to hold. For it the
        // frame is a route search, and the search is the only way back to a node that went quiet
        // before anything was recorded. It gets the ceiling spacing, never the minute one.
        val unproven = state(proofAt = 0L)
        assertEquals(KeepAliveVerdict.PING, planOverlayPing(peerKey, unproven, now))
        assertEquals(
            KeepAliveVerdict.TOO_SOON,
            planOverlayPing(
                peerKey,
                unproven.copy(lastPingSentAt = now - 60_000L, pendingSentAt = now - 60_000L),
                now
            )
        )
        assertEquals(
            KeepAliveVerdict.PING,
            planOverlayPing(
                peerKey,
                unproven.copy(lastPingSentAt = now - KEEPALIVE_MAX_INTERVAL_MS),
                now
            )
        )
    }

    @Test
    fun aContactQuietForAnHourIsStillHeldButSlowly() {
        // The route ages out while a chat is quiet, and the quiet chat is exactly where the next
        // message would otherwise pay for a fresh search. Stopping the pings the moment a peer stops
        // talking therefore cancels the feature on the case it exists for. What changes with the
        // silence is the cadence, not the decision.
        val quietForHalfAnHour = state(proofAt = now - 30 * 60_000L)
        assertEquals(KeepAliveVerdict.PING, planOverlayPing(peerKey, quietForHalfAnHour, now))
        // The same peer, probed a minute ago, is not due yet: outside the freshness window the
        // spacing is the ceiling, so an hour-old contact costs four frames an hour.
        assertEquals(
            KeepAliveVerdict.TOO_SOON,
            planOverlayPing(
                peerKey,
                quietForHalfAnHour.copy(lastPingSentAt = now - 60_000L, pendingSentAt = now - 60_000L),
                now
            )
        )
        assertEquals(
            KEEPALIVE_MAX_INTERVAL_MS,
            keepAliveIntervalMs(0, proofAgeMs = 30 * 60_000L)
        )
        assertEquals(KEEPALIVE_PING_INTERVAL_MS, keepAliveIntervalMs(0, proofAgeMs = 60_000L))
    }

    @Test
    fun aContactQuietedForDaysIsLeftAlone() {
        // Holding a warm path and reviving a dead node are different jobs. A peer nobody has seen for
        // a day is a node that is gone, and a frame a quarter of an hour forever is the idle traffic
        // this app was told to stop producing.
        assertEquals(
            KeepAliveVerdict.STALE,
            planOverlayPing(
                peerKey,
                state(proofAt = now - KEEPALIVE_HOLD_HORIZON_MS - 1_000L),
                now
            )
        )
        assertEquals(
            KeepAliveVerdict.PING,
            planOverlayPing(
                peerKey,
                state(proofAt = now - KEEPALIVE_HOLD_HORIZON_MS + 60_000L),
                now
            )
        )
    }

    @Test
    fun aDamagedNodeKeyIsNotAddressed() {
        // The mesh routes by the node key. A legacy placeholder cannot be addressed at all, and
        // pinging it would log a route failure that has nothing to do with the real peer.
        assertEquals(KeepAliveVerdict.NO_KEY, planOverlayPing("aa42", state(), now))
        assertEquals(KeepAliveVerdict.NO_KEY, planOverlayPing("", state(), now))
    }

    @Test
    fun anAnsweringPeerIsPingedOncePerInterval() {
        // The tick fires twice as often as the spacing on purpose: a peer answered between ticks, so
        // the next frame must wait for the interval rather than double up.
        assertEquals(
            KeepAliveVerdict.TOO_SOON,
            planOverlayPing(peerKey, state(pendingSentAt = now - 30_000L), now)
        )
        assertEquals(
            KeepAliveVerdict.PING,
            planOverlayPing(peerKey, state(pendingSentAt = now - 61_000L), now)
        )
    }

    @Test
    fun aSilentPeerIsPingedLessOftenButNotNever() {
        // Every unanswered ping doubles the wait up to a quarter of an hour. The frame still has
        // value without a reply — it keeps the sender's own route warm on the way out — so the
        // streak stretches it, it does not cancel it.
        assertEquals(KEEPALIVE_PING_INTERVAL_MS, keepAliveIntervalMs(0, proofAgeMs = 60_000L))
        assertEquals(KEEPALIVE_PING_INTERVAL_MS * 2, keepAliveIntervalMs(1, proofAgeMs = 60_000L))
        assertEquals(KEEPALIVE_PING_INTERVAL_MS * 4, keepAliveIntervalMs(2, proofAgeMs = 60_000L))
        assertEquals(KEEPALIVE_MAX_INTERVAL_MS, keepAliveIntervalMs(9, proofAgeMs = 60_000L))
        // One unanswered ping doubles the wait: two minutes is still too early, and the same frame
        // is due the moment that wait expires — stretched, never cancelled.
        assertEquals(
            KeepAliveVerdict.TOO_SOON,
            planOverlayPing(peerKey, state(pendingSentAt = now - 90_000L, unanswered = 1), now)
        )
        assertEquals(
            KeepAliveVerdict.PING,
            planOverlayPing(peerKey, state(pendingSentAt = now - 120_000L, unanswered = 1), now)
        )
    }

    @Test
    fun theFirstPingDoesNotCountItselfUnanswered() {
        // Nothing has been ignored yet: the streak counts a ping that replaced an unanswered one, so
        // a peer that answers the second probe is never punished for the first.
        val first = notePingSent(state(), now)
        assertEquals(0, first.unanswered)
        assertEquals(now, first.pendingSentAt)
        val second = notePingSent(first, now + KEEPALIVE_PING_INTERVAL_MS)
        assertEquals(1, second.unanswered)
    }

    @Test
    fun aFastAnswerDoesNotLicenseAnotherPingOnTheNextTick() {
        // The loop wakes twice as often as the spacing. A peer that answers in a hundred milliseconds
        // has not earned a second frame: the wait is measured from the ping that left, not from
        // whether it is still awaiting an answer. Without this, a healthy contact is probed every
        // thirty seconds and the keep-alive becomes the traffic it exists to avoid.
        val answered = notePongReceived(notePingSent(state(), now))
        assertEquals(0, answered.unanswered)
        assertEquals(KeepAliveVerdict.TOO_SOON, planOverlayPing(peerKey, answered, now + 30_000L))
        assertEquals(KeepAliveVerdict.PING, planOverlayPing(peerKey, answered, now + 61_000L))
    }

    @Test
    fun aPongResetsTheStreakToTheFastCadence() {
        val stretched = state(pendingSentAt = now - 60_000L, unanswered = 4)
        val answered = notePongReceived(stretched)
        assertEquals(0, answered.unanswered)
        assertNull(answered.pendingSentAt)
        assertEquals(KeepAliveVerdict.PING, planOverlayPing(peerKey, answered, now + 1_000L))
    }

    @Test
    fun aPongMeasuresOnlyWhatItEchoes() {
        // An RTT is a measurement of this device's path only if the reply carries the stamp this
        // device put on the ping. Some other round, no stamp, or a stamp from the future means the
        // journal has no number to print.
        assertEquals(180L, keepAliveRttMs(nowMs = now + 180L, echoedSentAtMs = now, pendingSentAtMs = now)!!)
        assertNull(keepAliveRttMs(nowMs = now + 180L, echoedSentAtMs = null, pendingSentAtMs = now))
        assertNull(keepAliveRttMs(nowMs = now + 180L, echoedSentAtMs = now - 1L, pendingSentAtMs = now))
        assertNull(keepAliveRttMs(nowMs = now + 180L, echoedSentAtMs = now, pendingSentAtMs = null))
        assertNull(keepAliveRttMs(nowMs = now, echoedSentAtMs = now + 5_000L, pendingSentAtMs = now + 5_000L))
    }

    @Test
    fun aRealFrameMovesProofForwardAndAnOlderOneLeavesIt() {
        // Proof is monotone: a frame that arrived now is the fact the freshness window reads, and a
        // late or replayed one must not rewind it into STALE. The streak is deliberately untouched —
        // a peer that talks but never answers pings has said something about its build, not about
        // its route.
        val withStreak = state(proofAt = now - 300_000L, pendingSentAt = now - 60_000L, unanswered = 2)
        assertEquals(now + 1_000L, notePeerProof(withStreak, now + 1_000L).proofAt)
        assertEquals(2, notePeerProof(withStreak, now + 1_000L).unanswered)
        assertEquals(now - 300_000L, notePeerProof(withStreak, now - 400_000L).proofAt)
    }

    @Test
    fun groupsAndOwnDialogsAreNotPinged() {
        val candidates = keepAliveCandidates(
            conversations = listOf(
                conversation(),
                // A 1:1 the ingest opened for a sender it had no contact row for carries the profile
                // name in its id; that is a contact's route, worth holding like any other.
                conversation(chatId = "chat_profile_primary_3735928559", nodeKey = "b".repeat(64)),
                conversation(chatId = "chat_profile_primary_grp_0123456789abcdef012345", chatType = "GROUP"),
                conversation(chatId = YggRepository.SELF_NODE_CHAT_ID),
                // This device's own node, stored upper case by the profile row: comparing the keys
                // as they came out of the database would make the node ping itself all day.
                conversation(nodeKey = "f".repeat(64)),
                conversation(profileId = "profile_secondary"),
                conversation(nodeKey = "not-a-key")
            ),
            liveProfileIds = setOf("profile_primary"),
            ownNodeKeys = setOf("F".repeat(64))
        )
        // Two rows survive: the direct chats of a running profile whose nodes are not this device and
        // whose keys the mesh can address. A group's members are already addressed by the fan-out, so
        // pinging the group too would send one peer two frames per pass.
        assertEquals(listOf("chat_peer", "chat_profile_primary_3735928559"), candidates.map { it.chatId })
        assertEquals(peerKey, candidates.first().nodeKeyHex)
        assertEquals("profile_primary", candidates.first().profileId)
    }
}
