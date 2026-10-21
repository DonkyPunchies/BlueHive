package com.example.bluehive.searchBarComponent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// ══════════════════════════════════════════════════════════════════════════════
//  VOICE SEARCH — BlueHive's own mic (the Voice pill on the honeycomb search)
// ══════════════════════════════════════════════════════════════════════════════
//
// WHY THIS EXISTS. The remote's mic button belongs to Google TV, not to us: with
// a text box open it is handed to Gboard's voice typing, and Gboard keeps ONE
// speech recognizer alive for its whole process. After days of uptime that
// recognizer goes deaf -- startListening is cancelled within 2-3 ms -- and voice
// silently stops working in BlueHive until Gboard restarts (or the box is
// unplugged). An app cannot restart Gboard.
//
// This takes Gboard out of the path. Every press gets a FRESH SpeechRecognizer,
// destroyed as soon as it finishes, so there is nothing long-lived to go stale.
//
// NO BUTTON ON THE REMOTE IS NEEDED. MEASURED 2026-10-15 on the onn 4K box:
// starting a recognizer makes Google TV's remote service open the remote's mic
// by itself (AtvRemote "ad hoc audio policy"), so the viewer presses OK on
// Voice and just talks. Listening 150-700 ms after the press; the words arrive
// 20-50 ms after they stop. Neither Gboard nor Google TV's voice search wakes.
//
// NOT RecognizerIntent: on this box it opens a "choose an app" screen first.

private const val TAG = "VoiceSearch"

/** The recognizer said nothing at all for this long: it is not going to. */
private const val READY_TIMEOUT_MS = 6_000L

/** SpeechRecognizer.ERROR_SERVER_DISCONNECTED, which is API 31. */
private const val ERROR_SERVER_DISCONNECTED = 11

/** Why a voice search came back empty, worded for the viewer. */
internal enum class VoiceFailure(val message: String) {
    NOTHING_HEARD("Didn't catch that. Press Voice and try again."),
    NO_PERMISSION("Voice search needs the mic. Allow it, or turn it on in " +
            "Settings > Apps > BlueHive."),
    BUSY("The microphone is busy. Try again in a moment."),
    OFFLINE("Voice search can't connect. Check the internet."),
    FAILED("Voice search didn't work. Try again."),
}

internal fun voiceFailureFor(error: Int): VoiceFailure = when (error) {
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    SpeechRecognizer.ERROR_NO_MATCH -> VoiceFailure.NOTHING_HEARD
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.NO_PERMISSION
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceFailure.BUSY
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER,
    ERROR_SERVER_DISCONNECTED -> VoiceFailure.OFFLINE
    else -> VoiceFailure.FAILED
}

/** Recognizers report about -2 dB for silence up to 10 dB for loud speech. */
internal fun voiceLevel(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

/** The recognizer's best guess, or null if every guess is blank. */
internal fun bestTranscript(guesses: List<String>?): String? =
    guesses?.firstOrNull { it.isNotBlank() }?.trim()

/** What the recognizer heard, minus the full stop it likes to add. */
internal fun cleanSpoken(text: String): String =
    text.trim().trimEnd('.', '!', '?', ',').trim()

@Stable
internal class VoiceSearchState(
    private val context: Context,
    /** False on a box with no speech service, where the button is not shown. */
    val available: Boolean,
) {
    var listening by mutableStateOf(false)
        private set

    /** 0..1, how loud the viewer is right now. Drives the pulse. */
    var level by mutableFloatStateOf(0f)
        private set

    internal var onStart: () -> Unit = {}
    internal var onPartial: (String) -> Unit = {}
    internal var onFinal: (String) -> Unit = {}
    internal var onFailed: (VoiceFailure) -> Unit = {}
    internal var askPermission: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var ready = false

    /** Bumped whenever a session ends, so a late callback from it is ignored. */
    private var session = 0

    /** The Voice key: starts listening, or a second press finishes early. */
    fun toggle() {
        if (listening) stop() else start()
    }

    private fun start() {
        if (!available) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            askPermission()      // comes back through listenNow() if allowed
            return
        }
        listenNow()
    }

    internal fun listenNow() {
        if (listening) return
        teardown()
        val id = ++session
        ready = false
        level = 0f
        listening = true
        onStart()

        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(Listener(id))
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        )

        // A RECOGNIZER THAT NEVER STARTS MUST NOT LOOK LIKE ONE THAT IS LISTENING.
        // That silent non-start is exactly the Gboard failure this replaces, and
        // without this the button would pulse forever while hearing nothing.
        main.postDelayed({
            if (id == session && !ready) {
                Log.e(TAG, "recognizer never became ready - giving up")
                end()
                onFailed(VoiceFailure.FAILED)
            }
        }, READY_TIMEOUT_MS)
    }

    /** Finish now with whatever was heard. Results still arrive and end it. */
    private fun stop() {
        recognizer?.stopListening()
    }

    /** Throw the session away: BACK, leaving the screen, or the app going away. */
    fun cancel() {
        if (listening || recognizer != null) end()
    }

    private fun end() {
        session++
        listening = false
        level = 0f
        teardown()
    }

    private fun teardown() {
        val r = recognizer ?: return
        recognizer = null
        // On the next loop, never from inside the recognizer's own callback.
        main.post { runCatching { r.cancel(); r.destroy() } }
    }

    private inner class Listener(private val id: Int) : RecognitionListener {
        private fun live() = id == session

        override fun onReadyForSpeech(params: Bundle?) {
            if (live()) ready = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (live()) level = voiceLevel(rmsdB)
        }

        override fun onEndOfSpeech() {
            if (live()) level = 0f
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live()) return
            bestTranscript(partialResults?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION))?.let { onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            if (!live()) return
            val text = bestTranscript(results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION))
            end()
            if (text != null) onFinal(text) else onFailed(VoiceFailure.NOTHING_HEARD)
        }

        override fun onError(error: Int) {
            if (!live()) return
            Log.e(TAG, "voice search failed: error $error")
            end()
            onFailed(voiceFailureFor(error))
        }

        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

/**
 * Voice search for one screen. Cancelled when the screen goes to the
 * background or leaves the tree, so the mic is never left open behind it.
 */
@Composable
internal fun rememberVoiceSearch(
    onStart:   () -> Unit,
    onPartial: (String) -> Unit,
    onFinal:   (String) -> Unit,
    onFailed:  (VoiceFailure) -> Unit,
): VoiceSearchState {
    val context = LocalContext.current
    val state = remember {
        VoiceSearchState(context, SpeechRecognizer.isRecognitionAvailable(context))
    }

    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Allowed: carry straight on, the viewer pressed the mic to talk.
        if (granted) state.listenNow() else state.onFailed(VoiceFailure.NO_PERMISSION)
    }

    SideEffect {
        state.onStart = onStart
        state.onPartial = onPartial
        state.onFinal = onFinal
        state.onFailed = onFailed
        state.askPermission = { askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.cancel()
        }
    }
    return state
}
