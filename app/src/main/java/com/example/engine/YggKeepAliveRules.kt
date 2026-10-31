// File: app/src/main/java/com/example/engine/YggKeepAliveRules.kt
package com.example.engine

import com.example.data.ConversationEntity
import com.example.data.YggRepository

/**
 * Decisions behind the overlay keep-alive, kept away from sockets so they can be argued about in a
 * unit test.
 *
 * Why a ping at all: the mesh forwards a datagram only while its pathfinder holds a route to the
 * destination key. A route is learned by searching and ages out once nothing crosses it, so a chat
 * that went quiet for a while costs the next message a fresh search — and on an uplink that filters
 * those searches, a lookup nobody answered is how a contact starts looking gone. One small frame a
 * minute across a link the node already holds keeps the route warm, and a peer nothing has proved
 * gets the same frame at the slowest spacing, where it doubles as the search that finds it again.
 *
 * What this never claims: a ping is not a delivery and not a peer's health report. Only a PONG that
 * echoes the stamp this device put on the ping measured anything. A peer that answers nothing is
 * answered with patience, not with invented numbers — its interval stretches, and the journal says
 * «RTT не измерен». An older build has no overlay PONG to send at all, so silence is expected
 * traffic, not evidence of a dead node.
 */

/** Spacing between pings to a peer that answers them. */
internal const val KEEPALIVE_PING_INTERVAL_MS = 60_000L

/** Ceiling for a peer that never answers: the path still gets refreshed, just quietly. */
internal const val KEEPALIVE_MAX_INTERVAL_MS = 15 * 60_000L

/**
 * While a peer was heard from inside this window, its path is measured at the fast cadence; outside
 * it, the same frames are still sent, only at the ceiling spacing.
 *
 * The tempting rule — stop as soon as a contact stops looking live — defeats itself. A route ages out
 * while a chat is quiet, and the quiet chat is exactly where the next message would otherwise pay for
 * a fresh search. So a peer silent for an hour is held, slowly.
 */
internal const val KEEPALIVE_CONTACT_FRESH_MS = 15 * 60_000L

/** Beyond this a peer is left alone entirely: keep-alive holds a path, it does not revive a node. */
internal const val KEEPALIVE_HOLD_HORIZON_MS = 24 * 60 * 60_000L

/** Wakeups of the keep-alive loop; per-peer spacing is what [keepAliveIntervalMs] says. */
internal const val KEEPALIVE_TICK_MS = 30_000L

/** How many peers one tick may address, so fifty chats do not turn a quiet minute into a flood. */
internal const val KEEPALIVE_MAX_PER_TICK = 6

internal enum class KeepAliveVerdict {
    PING,

    /** No usable Ed25519 node key: the mesh has nothing to address. */
    NO_KEY,

    /** A peer once reached and silent past the horizon: holding a path cannot revive a gone node. */
    STALE,

    /** Pinged too recently for another frame to count as keep-alive rather than noise. */
    TOO_SOON,
}

/** What this device remembers about one peer's path. */
internal data class KeepAlivePeerState(
    /** The last moment this device had real proof of the peer, from the database or from traffic. */
    val proofAt: Long = 0L,
    /** The last ping this device actually put on the wire, answered or not. */
    val lastPingSentAt: Long = 0L,
    /** A ping still waiting for its answer. */
    val pendingSentAt: Long? = null,
    /** Pings put on the wire since the last real PONG. */
    val unanswered: Int = 0,
)

/**
 * How long this peer waits between pings.
 *
 * Three rungs, and none of them cancels the frame. A node that does not answer is probed less often:
 * the ping keeps its own route warm on the way out, so the measurement is what the reply would add,
 * not the frame itself. A node we have not heard from since the freshness window is probed at the
 * ceiling too — its route is the one that has had the longest to age out. And a dialog nothing has
 * ever proved gets the ceiling as well, because for it the frame is a search, not a measurement.
 */
internal fun keepAliveIntervalMs(
    unanswered: Int,
    proofAgeMs: Long,
    baseMs: Long = KEEPALIVE_PING_INTERVAL_MS,
    maxMs: Long = KEEPALIVE_MAX_INTERVAL_MS,
    freshMs: Long = KEEPALIVE_CONTACT_FRESH_MS,
): Long {
    val backoff = if (unanswered <= 0) baseMs
    else (baseMs * (1L shl unanswered.coerceAtMost(8))).coerceAtMost(maxMs)
    val hold = if (proofAgeMs > freshMs) maxMs else baseMs
    return maxOf(backoff, hold)
}

internal fun planOverlayPing(
    nodeKeyHex: String,
    state: KeepAlivePeerState,
    nowMs: Long,
    freshMs: Long = KEEPALIVE_CONTACT_FRESH_MS,
    horizonMs: Long = KEEPALIVE_HOLD_HORIZON_MS,
): KeepAliveVerdict {
    val proven = state.proofAt > 0L
    val proofAgeMs = if (proven) nowMs - state.proofAt else Long.MAX_VALUE
    return when {
        !YggCryptoEngine.isEd25519Hex(nodeKeyHex.trim()) -> KeepAliveVerdict.NO_KEY
        // A dialog the user still keeps is worth one frame an hour even with nothing on record: the
        // chat row came from a contact card they accepted, and the frame doubles as the route search
        // that re-finds the node. A node nobody has answered for since the horizon is different — the
        // user stopped hearing from a peer they once reached, and holding is not reviving.
        proven && proofAgeMs > horizonMs -> KeepAliveVerdict.STALE
        state.lastPingSentAt > 0L &&
            nowMs - state.lastPingSentAt <
            keepAliveIntervalMs(state.unanswered, proofAgeMs, freshMs = freshMs) -> KeepAliveVerdict.TOO_SOON
        else -> KeepAliveVerdict.PING
    }
}

/**
 * Records a ping this device really put on the wire.
 *
 * The spacing is keyed on the ping itself, not on whether it is still unanswered: a peer that
 * answers inside half a second would otherwise be probed again on the very next tick, which is how
 * a "keep-alive" turns into the traffic it was meant to prevent. Replacing a ping that never got an
 * answer is what the streak counts — the frame left, the path stayed, and the next interval has to
 * be longer.
 */
internal fun notePingSent(state: KeepAlivePeerState, sentAtMs: Long): KeepAlivePeerState =
    state.copy(
        lastPingSentAt = sentAtMs,
        pendingSentAt = sentAtMs,
        unanswered = if (state.pendingSentAt != null) state.unanswered + 1 else state.unanswered,
    )

/** The only thing that ends the streak: an answer from that node. */
internal fun notePongReceived(state: KeepAlivePeerState): KeepAlivePeerState =
    state.copy(pendingSentAt = null, unanswered = 0)

/**
 * Any real frame from the peer moves the contact proof forward, which is what pulls it back inside
 * the freshness window and gives its path the fast cadence again. It does not touch the streak: a
 * peer that talks but never answers pings has said something about its build, not about its route.
 */
internal fun notePeerProof(state: KeepAlivePeerState, proofAtMs: Long): KeepAlivePeerState =
    if (proofAtMs > state.proofAt) state.copy(proofAt = proofAtMs) else state

/**
 * The RTT a PONG measured, or null when it measured nothing.
 *
 * A reply is a measurement of this device's path only if it carries back the stamp this device sent:
 * an echo of some other round, a stamp from the future, or no stamp at all means "no number", and
 * the journal says that instead of printing a plausible one.
 */
internal fun keepAliveRttMs(nowMs: Long, echoedSentAtMs: Long?, pendingSentAtMs: Long?): Long? {
    val sentAt = echoedSentAtMs ?: return null
    if (sentAt <= 0L || pendingSentAtMs == null || sentAt != pendingSentAtMs) return null
    if (sentAt > nowMs) return null
    return nowMs - sentAt
}

/**
 * A peer the keep-alive may address.
 *
 * Group rows are left out on purpose: a group fan-out already crosses the mesh for every member, so
 * pinging from the group as well would address one node twice per pass. The node's own dialog is
 * excluded because a ping to yourself measures the loopback of your own process.
 */
internal data class KeepAliveCandidate(
    val profileId: String,
    val chatId: String,
    val nodeKeyHex: String,
    val proofAt: Long,
)

internal fun keepAliveCandidates(
    conversations: List<ConversationEntity>,
    liveProfileIds: Set<String>,
    ownNodeKeys: Set<String>,
): List<KeepAliveCandidate> {
    val ownKeys = ownNodeKeys.map { it.trim().lowercase() }.toSet()
    return conversations
        .filter { it.chatType.equals("DIRECT", ignoreCase = true) }
        .filter { it.ownerProfileId in liveProfileIds }
        .filter { !YggRepository.isSelfChatId(it.chatId) }
        .map { it.publicKeyHex.trim().lowercase() to it }
        .filter { (key, _) -> YggCryptoEngine.isEd25519Hex(key) && key !in ownKeys }
        .map { (key, conversation) ->
            KeepAliveCandidate(
                profileId = conversation.ownerProfileId,
                chatId = conversation.chatId,
                nodeKeyHex = key,
                proofAt = conversation.peerLastSeenAt,
            )
        }
}
