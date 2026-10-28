// File: app/src/test/java/com/example/data/YggVoiceProofTest.kt
package com.example.data

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.engine.DerivedIdentityHierarchy
import com.example.engine.IncomingTwinPacket
import com.example.engine.YggAudioMediaEngine
import com.example.engine.YggCryptoEngine
import com.example.engine.YggVoiceCodec
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val localWords = listOf(
    "harbor", "cedar", "glacier", "helium", "cascade", "nebula",
    "cobalt", "lattice", "photon", "quartz", "onyx", "vertex"
)
private val peerWords = listOf(
    "tulip", "lantern", "cobweb", "marble", "quiver", "fathom",
    "harvest", "pinnacle", "tundra", "cinder", "beetle", "willow"
)
private val strangerWords = listOf(
    "obsidian", "cipher", "yggdrasil", "vector", "emerald", "quantum",
    "ratchet", "bastion", "entropy", "merkle", "shadow", "vortex"
)

private fun identity(words: List<String>) = YggCryptoEngine.deriveHierarchyFromMnemonic(words, epoch = 1)

/** A frame written by the peer, sealed to this device — the only kind the repository accepts. */
private fun sealAsPeer(peer: DerivedIdentityHierarchy, recipientXPubHex: String, plain: String): String =
    requireNotNull(
        YggCryptoEngine.sealConversationEnvelope(
            plainText = plain,
            senderX25519PrivateKey = YggCryptoEngine.x25519PrivateKeyFromMnemonic(peerWords),
            senderEd25519PublicKeyHex = peer.yggNodePublicKeyHex,
            peerX25519PublicKeyHex = recipientXPubHex,
            keyEpoch = 1,
            envelopeVersion = 2
        )
    ) { "кадр под тестом обязан быть настоящим конвертом" }

/**
 * Who is allowed to believe that a peer speaks Opus.
 *
 * Compression only pays off when the other side can open the file, so the flag behind it has to
 * come from evidence: an ACK that named the codec, or bytes only a codec could have written.
 * These tests drive the real repository over an in-memory database and check that an empty
 * advertisement, a stranger's ACK and a plain WAV leave the dialog exactly as they found it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YggVoiceProofTest {

    private class Device(
        val identity: DerivedIdentityHierarchy,
        val repo: YggRepository,
        val db: YggDatabase
    )

    private suspend fun device(): Device {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(app, YggDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ident = identity(localWords)
        val repo = YggRepository(db.yggDao())
        repo.ensureInitialized(ident)
        return Device(ident, repo, db)
    }

    private suspend fun contact(repo: YggRepository, peer: DerivedIdentityHierarchy): String =
        repo.createNewConversation(
            title = "Узел собеседника",
            yggIpv6Input = peer.yggIpv6Address,
            disappearingSeconds = 0,
            directEndpointHint = "",
            peerX25519PublicKeyHex = peer.x25519IdentityPubHex,
            peerEd25519PublicKeyHex = peer.yggNodePublicKeyHex
        )

    private fun voiceFile(dir: File, opus: Boolean): File {
        val bytes = if (opus) {
            YggVoiceCodec.writeContainer(
                YggVoiceCodec.CODEC_OPUS,
                listOf(byteArrayOf(1, 2, 3, 4), ByteArray(64) { (it % 7).toByte() })
            )
        } else {
            ByteArray(44 + 640) { (it % 13).toByte() }
        }
        val suffix = (System.nanoTime() % 100000).toString()
        return File(dir, "voice_${if (opus) "opus" else "wav"}_$suffix").apply { writeBytes(bytes) }
    }

    private fun frame(
        peer: DerivedIdentityHierarchy,
        envelope: String,
        groupId: String = "",
        membersCsv: String = "",
        voice: File? = null
    ): IncomingTwinPacket = IncomingTwinPacket(
        targetChatId = "",
        messageId = "wire_${groupId}_${System.nanoTime()}",
        senderNodeLabel = "Узел собеседника",
        senderIpv6 = peer.yggIpv6Address,
        senderPubKeyHex = peer.yggNodePublicKeyHex,
        plainText = "открытым текстом на проводе быть не должно",
        cipherPayloadHex = envelope,
        hashcashToken = "1:20:yggdrasil",
        attachmentName = voice?.name,
        attachmentSizeBytes = voice?.length() ?: 0,
        attachmentType = if (voice != null) "VOICE" else null,
        attachmentUri = voice?.toURI()?.toString(),
        voiceDurationSec = if (voice != null) 1 else 0,
        groupId = groupId,
        groupMembersCsv = membersCsv,
        groupTitle = if (groupId.isBlank()) "" else "Группа проверки",
        rttMs = 40L,
        timestamp = System.currentTimeMillis()
    )

    @Test
    fun `an ack naming opus proves the codec and nothing about the envelope`() = runBlocking {
        val peer = identity(peerWords)
        val me = device()
        try {
            val chatId = contact(me.repo, peer)
            assertFalse(
                "пустая реклама ничего не доказывает",
                me.repo.rememberPeerCapabilities(chatId, peer.yggNodePublicKeyHex, "", "")
            )
            var conv = requireNotNull(me.repo.getConversation(chatId))
            assertFalse(conv.peerVoiceOpusProven)
            assertFalse(conv.peerEnvelopeV3Proven)

            assertTrue(me.repo.rememberPeerCapabilities(chatId, peer.yggNodePublicKeyHex, "", "opus"))
            conv = requireNotNull(me.repo.getConversation(chatId))
            assertTrue("кодек доказан", conv.peerVoiceOpusProven)
            assertFalse("а про конверт в этом ACK не было ни слова", conv.peerEnvelopeV3Proven)

            // Two separate proofs on one dialog: neither may erase the other.
            assertTrue(me.repo.rememberPeerCapabilities(chatId, peer.yggNodePublicKeyHex, "2,3", ""))
            conv = requireNotNull(me.repo.getConversation(chatId))
            assertTrue(conv.peerVoiceOpusProven)
            assertTrue(conv.peerEnvelopeV3Proven)

            assertFalse(
                "диалога с таким id нет — доказательство некому приписать",
                me.repo.rememberPeerCapabilities("chat_нет_такого", peer.yggNodePublicKeyHex, "", "opus")
            )
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a group ack writes the proof into the member's own dialog`() = runBlocking {
        val peer = identity(peerWords)
        val me = device()
        try {
            val dialogId = contact(me.repo, peer)
            val groupId = "grp_voice_check"
            val groupChatId = requireNotNull(
                me.repo.recordIncomingTwinMessage(
                    packet = frame(
                        peer,
                        sealAsPeer(peer, me.identity.x25519IdentityPubHex, "в группе"),
                        groupId = groupId,
                        membersCsv = "${peer.yggIpv6Address}|${peer.yggNodePublicKeyHex}"
                    ),
                    activeChatId = null
                )
            )
            assertEquals("GROUP", me.repo.getConversation(groupChatId)?.chatType)

            assertTrue(
                me.repo.rememberPeerCapabilities(
                    groupChatId,
                    peer.yggNodePublicKeyHex,
                    "",
                    YggVoiceCodec.NAME_OPUS
                )
            )
            assertTrue(
                "fan-out печатает по личному ключу участника — там и доказательство",
                requireNotNull(me.repo.getConversation(dialogId)).peerVoiceOpusProven
            )
            assertFalse(
                "у строки группы общего ключа собеседника нет",
                requireNotNull(me.repo.getConversation(groupChatId)).peerVoiceOpusProven
            )

            val stranger = identity(strangerWords)
            assertFalse(
                "ACK от узла вне состава — это не доказательство ни про кого",
                me.repo.rememberPeerCapabilities(groupChatId, stranger.yggNodePublicKeyHex, "", "opus")
            )
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a note that arrived compressed proves its author, a wav proves nothing`() = runBlocking {
        val peer = identity(peerWords)
        val me = device()
        val dir = ApplicationProvider.getApplicationContext<Application>().filesDir
        try {
            val chatId = contact(me.repo, peer)
            val wav = voiceFile(dir, opus = false)
            me.repo.recordIncomingTwinMessage(
                packet = frame(
                    peer,
                    sealAsPeer(peer, me.identity.x25519IdentityPubHex, "голос без сжатия"),
                    voice = wav
                ),
                activeChatId = null
            )
            assertFalse(
                "WAV присылает и старая сборка: прав писать сжатым он не даёт",
                requireNotNull(me.repo.getConversation(chatId)).peerVoiceOpusProven
            )

            val container = voiceFile(dir, opus = true)
            assertEquals(
                "контейнер опознаётся по первым байтам, без обещаний в кадре",
                YggVoiceCodec.NAME_OPUS,
                YggAudioMediaEngine.voiceCodecOf(container.toURI().toString())
            )
            me.repo.recordIncomingTwinMessage(
                packet = frame(
                    peer,
                    sealAsPeer(peer, me.identity.x25519IdentityPubHex, "голос со сжатием"),
                    voice = container
                ),
                activeChatId = null
            )
            assertTrue(
                "только кодер этой сборки пишет YGO1 — значит он у собеседника есть",
                requireNotNull(me.repo.getConversation(chatId)).peerVoiceOpusProven
            )
        } finally {
            me.db.close()
        }
    }

    @Test
    fun `a stored note takes the extension its bytes can back up`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = YggVoiceCodec.writeContainer(
            YggVoiceCodec.CODEC_OPUS,
            listOf(ByteArray(40) { (it % 5).toByte() })
        )
        val saved = YggAudioMediaEngine.saveIncomingP2PAttachment(
            context = app,
            attachmentName = "voice_note.wav",
            attachmentType = "VOICE",
            attachmentBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(container),
            fallbackUri = null,
            voiceDurationSec = 2,
            voiceWaveformCsv = null
        )
        assertNotNull(saved)
        assertTrue("расширение следует за байтами, а не за именем отправителя", saved!!.endsWith(".opus"))
        assertEquals(YggVoiceCodec.NAME_OPUS, YggAudioMediaEngine.voiceCodecOf(saved))

        val wav = ByteArray(44 + 320) { (it % 11).toByte() }
        val savedWav = YggAudioMediaEngine.saveIncomingP2PAttachment(
            context = app,
            attachmentName = "voice_note.opus",
            attachmentType = "VOICE",
            attachmentBase64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(wav),
            fallbackUri = null,
            voiceDurationSec = 1,
            voiceWaveformCsv = null
        )
        assertTrue("имя без байт — это ещё не кодек", savedWav!!.endsWith(".wav"))
        assertEquals("", YggAudioMediaEngine.voiceCodecOf(savedWav))
        assertTrue(Uri.parse(savedWav).path!!.isNotBlank())
    }
}
