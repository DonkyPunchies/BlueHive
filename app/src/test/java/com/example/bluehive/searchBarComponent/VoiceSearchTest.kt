package com.example.bluehive.searchBarComponent

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks what the viewer is TOLD when voice search comes back empty, and the two
 * small readings the Voice key is drawn from.
 *
 * The messages matter more than they look: each one tells the viewer what to do
 * next, and the wrong one sends them somewhere useless — "check the internet"
 * for a mic they never allowed, or "try again" for a permission that will keep
 * refusing however many times they press.
 */
class VoiceSearchTest {

    // ── what the viewer is told ─────────────────────────────────────────────

    @Test
    fun `silence and mumbling both ask for another go`() {
        assertEquals(VoiceFailure.NOTHING_HEARD,
            voiceFailureFor(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(VoiceFailure.NOTHING_HEARD,
            voiceFailureFor(SpeechRecognizer.ERROR_NO_MATCH))
    }

    @Test
    fun `a refused microphone points at the permission, not the network`() {
        assertEquals(VoiceFailure.NO_PERMISSION,
            voiceFailureFor(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `every connection failure reads as offline`() {
        listOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            11,     // ERROR_SERVER_DISCONNECTED, API 31
        ).forEach { assertEquals("error $it", VoiceFailure.OFFLINE, voiceFailureFor(it)) }
    }

    @Test
    fun `a busy recognizer says so`() {
        assertEquals(VoiceFailure.BUSY,
            voiceFailureFor(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }

    @Test
    fun `anything else is a plain failure, never a crash`() {
        listOf(SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT, 12, 13, 99, -1)
            .forEach { assertEquals("error $it", VoiceFailure.FAILED, voiceFailureFor(it)) }
    }

    // ── the pulse ───────────────────────────────────────────────────────────

    @Test
    fun `level runs from silence to loud speech and stays inside 0 to 1`() {
        assertEquals(0f, voiceLevel(-2f), 0.001f)
        assertEquals(1f, voiceLevel(10f), 0.001f)
        assertEquals(0.5f, voiceLevel(4f), 0.001f)
        assertEquals(0f, voiceLevel(-60f), 0.001f)
        assertEquals(1f, voiceLevel(30f), 0.001f)
    }

    // ── the words ───────────────────────────────────────────────────────────

    @Test
    fun `the first real guess wins, trimmed`() {
        assertEquals("Black Clover",
            bestTranscript(listOf("  Black Clover ", "blood clover", "block clover")))
    }

    @Test
    fun `blank guesses are skipped, not typed into the box`() {
        assertEquals("testing", bestTranscript(listOf("", "   ", "testing")))
    }

    @Test
    fun `spoken text loses the full stop the recognizer adds`() {
        assertEquals("Black Clover", cleanSpoken("Black Clover."))
        assertEquals("naruto", cleanSpoken("  naruto  "))
    }

    @Test
    fun `no guesses at all is nothing heard`() {
        assertNull(bestTranscript(null))
        assertNull(bestTranscript(emptyList()))
        assertNull(bestTranscript(listOf(" ", "")))
    }
}
