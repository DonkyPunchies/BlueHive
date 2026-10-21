package com.example.bluehive.webview.hls

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.example.bluehive.BlueHiveApplication
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.bluehive.api.ApiClient
import com.example.bluehive.api.RelayGrantRequest
import com.example.bluehive.api.SubtitleSyncRequest
import com.example.bluehive.auth.SessionManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.net.URLEncoder
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.example.bluehive.BuildConfig
import com.example.bluehive.IdleWatcher
import com.example.bluehive.webview.exoplayer_resources.ExoPlayerConfig
import com.example.bluehive.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import kotlin.math.abs
import java.util.concurrent.TimeUnit
import com.example.bluehive.api.extractStreamAwait

/**
 * Everything a player needs to put subtitles on the screen, minus the player.
 *
 * WHY A BASE CLASS AND NOT A HELPER OBJECT. This code reaches for `player`,
 * `playerView`, `intent`, `lifecycleScope` and `getSharedPreferences` on nearly
 * every other line. Threading those through a collaborator would have meant
 * rewriting all fourteen hundred lines of it to move them, and rewriting is
 * where the bugs come from; extending is a cut and a paste. The two players are
 * genuinely "a player activity, with subtitles", so the inheritance is not a
 * shortcut standing in for something else.
 *
 * WHAT IT OWNS: the caption layer and its ticker, the track picker, the fix
 * sequence search, appearance customization, the offset and rate it all resolves
 * to, the band's title strip and time remaining, and the Subtitles button
 * injected into the control bar.
 *
 * WHAT A SUBCLASS OWES IT: `player` and `playerView`, set before the controls
 * appear, plus the three `measure*` hooks below if it wants backend timing. It
 * must also call [attachBandChrome] from the PlayerView factory and place
 * [SubtitleLayer] inside the Box that holds the player.
 *
 * It lives in the `hls` package because the subtitle data layer, the cue matcher
 * and every overlay it composes already do. Moving the five of them to a neutral
 * package is a rename, not a design change, and it is not worth doing in the
 * same step as the extraction.
 */
abstract class SubtitlePlayerActivity : ComponentActivity() {

    /** Logcat tag. Subclasses override so their own lines stay findable. */
    protected open val TAG: String = "player-subs"

    /**
     * The player and its view. DECLARED HERE because every subtitle path reads
     * them: the caption ticker needs the clock, the fix-sequence transport seeks
     * on them, and the Subtitles button is injected into the view's control bar.
     * Both subclasses build and release their own.
     */
    /** Cues on screen right now. Compose state, so the live SubtitleView renders
     *  them — see the AndroidView in SubtitleLayer. */
    protected var activeCues by mutableStateOf<List<Cue>>(emptyList())

    protected var player: ExoPlayer? = null
    protected var playerView: PlayerView? = null

    // ── BACKING OUT OF THE PLAYER SOUNDS LIKE BACKING OUT ─────────────────
    //
    // The viewer's BACK out of a player plays the back-out note, the one every
    // other BACK in the app makes. Only BACK: an episode ending or a source
    // failing also closes the player, with nobody having pressed anything.
    //
    // It lets the close go on afterwards, instead of calling finish() itself, so
    // the close is still the platform's default -- same RESULT_CANCELED, and the
    // same path PlayerExitPolicyTest audits. BACK that an overlay consumes in
    // dispatchKeyEvent (subtitle menu, sync hint, quality list) never gets here.
    private val backOutCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            BlueHiveApplication.playBackOutSound()
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backOutCallback)
    }

    /**
     * What the backend measures a subtitle against: the stream it would pull
     * audio from, and the headers that stream needs.
     *
     * Open rather than abstract, and blank by default, because a player that
     * cannot supply them is not broken — measureTimingInBackground checks for a
     * blank url and returns, leaving manual sync as the only route. That is a
     * working player with one feature off, not a crash.
     */
    protected open val measureStreamUrl: String get() = ""
    protected open val measureReferer: String get() = ""
    protected open val measureUserAgent: String get() = ""

    // --- captions (dev state, self-rendered) ---
    protected var tmdbId: Int = 0
    // Subtitle the EXTRACTOR pulled off the streaming site (SUBTITLE_URL extra).
    // Preferred over OpenSubtitles: it's this exact release's caption track, so
    // it's already in sync and needs no imdb lookup.
    protected var streamSubtitleUrl: String? = null
    protected var parsedCues: List<TimedCue> = emptyList()
    protected var captionsLoaded = false

    /**
     * SNAPSHOT STATE for the same reason subtitleOffsetMs is: the subtitles menu
     * decides from this whether to offer Fix timing and Turn captions off at
     * all, so turning captions off while it is open has to reach composition.
     */
    protected var captionsOn by mutableStateOf(false)
    protected var lastCueKey: String = "\u0000"
    protected var toastText by mutableStateOf<String?>(null)
    protected var toastJob: Job? = null
    protected var captionTickerJob: Job? = null

    // --- English-subtitle picker (CC button) ---
    // OpenSubtitles lists several English subtitles per episode, each timed to a
    // different rip, and only one matches what we're streaming. The picker shows
    // them all so a drifting one can be swapped out mid-episode.
    protected var pickerOpen by mutableStateOf(false)
    protected var pickerLoading by mutableStateOf(false)
    protected var pickerOptions by mutableStateOf<List<HlsSubtitles.SubtitleChoice>>(emptyList())
    protected var pickerError by mutableStateOf<String?>(null)
    /** EnglishOption.index currently rendering, or -1 when captions are off. */
    protected var activeSubtitleIndex by mutableStateOf(-1)
    /** True only if WE paused to show the picker, so closing it can't resume a
     *  video the user had deliberately paused before pressing CC. */
    protected var pausedForPicker = false

    /**
     * Was playback running when this activity last went to the background?
     *
     * THE SAME RULE AS pausedForPicker, applied to the lifecycle. onResume used
     * to call play() unconditionally, so anything that backgrounded the activity
     * and came back resumed a film the user had deliberately paused. The one
     * trigger that is certain to do that on a television is the daydream: this
     * box has screensaver_enabled=1 and screen_off_timeout=600000, so pausing
     * and sitting still for ten minutes backgrounds the app, and the key press
     * that dismisses the screensaver brings it back — playing.
     *
     * playWhenReady, NOT isPlaying. isPlaying is briefly false mid-buffer (see
     * ExoPlayerConfig.togglePlayPause, which flips playWhenReady for exactly
     * this reason), so pausing during a stall would record "was not playing" and
     * the film would stay paused on return — trading one wrong behaviour for
     * another. playWhenReady is the user's intent rather than the decoder's
     * moment-to-moment state.
     */
    protected var wasPlayingBeforePause = false

    /**
     * Subtitle timing offset in ms: + shows later, - shows sooner.
     *
     * SNAPSHOT STATE, because the timing screen displays it. As a plain var the
     * cues moved on the next playback tick -- activeCues below is state, so the
     * subtitles themselves were always correct -- but the read-out on the
     * overlay did not recompose, so the number sat still while the value climbed
     * underneath it. Pressing a button that visibly does nothing reads as a dead
     * button, so people press it again, which is how a quarter-second step
     * reaches five seconds.
     */
    protected var subtitleOffsetMs by mutableStateOf(0L)
    /**
     * The control bar's single subtitle control. Was five views -- CC icon, two
     * nudge arrows, an offset read-out and a reset -- which cost five D-pad
     * presses to cross on the way to the gear, every time, for controls almost
     * nobody touches. The four timing ones now live behind this button.
     */
    protected var subtitlesButton: TextView? = null

    /** True while the subtitles menu (choose / fix sequence / off) is up. */
    protected var subMenuOpen by mutableStateOf(false)

    // -- Fix subtitle sequence (search for a line you heard) -----------------

    // -- Subtitle appearance -------------------------------------------------

    /**
     * Whether the track list was reached through the menu.
     *
     * Decides where BACK goes from it. Not snapshot state: nothing in
     * composition reads it, only the dismiss handler.
     */
    protected var pickerFromMenu: Boolean = true

    /** True while the customization screen owns the remote. */
    protected var styleOpen by mutableStateOf(false)

    /**
     * True while the control band is up, so the captions sit above it.
     *
     * Snapshot state: the caption AndroidView's update block reads it, which is
     * what makes the captions move the frame the bar appears.
     */
    protected var captionsRaised by mutableStateOf(false)

    /** Non-compose mirror of the same, for the ticker. */
    protected var controlsVisible: Boolean = false

    // ── "Out of sync?" hint ──────────────────────────────────────────────
    /**
     * Whether the hint is live. Drives the fade AND the key routing, so the bar
     * stops answering the remote the instant it starts fading rather than when
     * the animation finishes — "then it can't be clickable any more" has to
     * mean the moment it begins to go, or there is a half second where it looks
     * gone and still acts.
     */
    protected var syncHintUp by mutableStateOf(false)

    /**
     * Seconds left on the hint, for the bar to show.
     *
     * Snapshot state and nothing else: the timer below owns it, the bar reads
     * it, and no decision anywhere is made from it — the dismissal still comes
     * from the job finishing, so a dropped frame cannot leave the offer up.
     */
    protected var syncHintSeconds by mutableStateOf(0)

    /** Once per set of captions, not once per cue and not once per menu visit. */
    private var syncHintShown = false
    private var syncHintJob: Job? = null

    /** Long enough to read twice at a distance, short enough not to nag. */
    protected val SYNC_HINT_MS = 30_000L

    /** Attached once; see bindTitleToBand. */
    protected var titleFollowsBand = false
    protected var remainingJob: Job? = null

    /**
     * How the captions are drawn. Snapshot state, because the AndroidView's
     * update block reads it -- changing one of these has to reach the real
     * SubtitleView on the next recomposition, which is what makes the screen
     * usable without closing it to see the result.
     */
    protected var subSize by mutableStateOf(SubtitleSizeChoice.Small)
    protected var subFont by mutableStateOf(SubtitleFontChoice.InterSemiBold)
    protected var subEdge by mutableStateOf(SubtitleEdgeChoice.Shadow)

    /**
     * What was last pushed into the view.
     *
     * setCues runs on every cue change; restyling there too would rebuild the
     * paint a few times a second to say the same thing. This is the guard that
     * keeps the work to the moments the style actually changed.
     */
    protected var lastAppliedCaptionStyle: Triple<SubtitleSizeChoice, SubtitleFontChoice, SubtitleEdgeChoice>? = null

    /** True while the sequence-search screen owns the remote. */
    protected var fixSeqOpen by mutableStateOf(false)
    protected var fixSeqPhase by mutableStateOf<FixSeqPhase?>(null)
    protected var fixSeqQuery by mutableStateOf("")

    /** Built once per subtitle file, never when the screen opens. See applyChoice. */
    protected var fixSeqMatcher: CueMatcher? = null

    /**
     * WHERE THE PICTURE WAS WHEN THE SEARCH BOX WAS ENGAGED, and the single
     * number this whole screen computes against.
     *
     * Captured once, in engageFixSeqSearch, and never re-read from the player
     * when a row is picked. The viewer spends ten to thirty seconds typing and
     * reading four rows; taking currentPosition at the pick would fold all of
     * that into the offset.
     */
    protected var fixSeqPausedPosMs: Long = 0L
    protected var fixSeqJob: Job? = null

    /** Enough to undo one pick from the screen that made it. See applyFixSeqPick. */
    protected var fixSeqUndoOffsetMs: Long = 0L
    protected var fixSeqUndoRate: Double = 1.0

    /**
     * The gap between a line being spoken and the picture being parked on it.
     *
     * THE ONE GUESS IN THIS SCREEN, and isolated in a constant for exactly that
     * reason. There is no reaction time in it, because the clock is frozen
     * before anything is typed. What is left
     * is the authoring habit of starting a cue slightly ahead of the speech it
     * captions, pulling one way, against a hand landing on pause slightly after
     * the word it heard, pulling the other. If search-fixed subtitles read
     * consistently early or late on the real box, this is the only thing to
     * change.
     */
    protected val FIXSEQ_LEAD_MS = 150L

    /**
     * Mirrors the player's playing state for Compose.
     *
     * The timing screen needs it: it carries a play/pause button, and a button
     * that shows the wrong symbol is worse than none. Kept in a snapshot state
     * rather than read from the player on recomposition, because the player
     * changes underneath Compose without telling it.
     */
    protected var isPlayingState by mutableStateOf(false)
    protected val subPrefs by lazy { getSharedPreferences("vidapi_sub_offsets", MODE_PRIVATE) }

    /**
     * Subtitle RATE correction — the one fix the offset buttons cannot perform.
     *
     * An offset is a constant shift, and it handles the common case: the release a
     * subtitle was timed to had a different amount of distributor logo before the
     * first frame. It cannot touch the other case. A subtitle authored against a
     * 25fps PAL transfer, rendered over a 23.976fps source, is wrong by a RATIO
     * rather than a constant — 4.27%, which is ~3m50s by the end of a feature.
     * Sync the opening and the ending breaks; sync the ending and the opening
     * does. No number of nudges fixes it, because the error GROWS.
     *
     * So cue times are now scaled as well as shifted: videoTime = cue*rate + offset.
     * Because we render cues ourselves off the playback clock (see the class
     * header) this costs one division per tick and applies instantly, mid-film,
     * with no reload — the same property that made the offset buttons possible.
     *
     * NOBODY OPERATES THIS. There is no drift button, and the words "framerate"
     * and "drift" appear nowhere in the UI. The rate is MEASURED from the ◀ / ▶
     * nudges the viewer was already going to make — see [recordSyncPoint].
     *
     * That deletion is the whole design. The button this replaces made the
     * viewer diagnose their own problem first ("is this off by a constant, or by
     * a ratio?") before it could tell them which control to reach for. That is a
     * distinction for someone who knows what telecine is, and asking for it is
     * what made the feature unusable rather than the number of buttons.
     *
     * 1.0 means no correction, and must keep meaning exactly that — see
     * renderActiveCues.
     */
    protected var subtitleRate: Double = 1.0

    /**
     * One "this looks right HERE" observation: the playback position, and the
     * offset the viewer settled on at it.
     *
     * Settled is the load-bearing word. Reaching +2.00s takes eight presses, and
     * the seven offsets on the way there are not answers to anything — they are
     * someone still turning the dial. Only the value they stop at is data, which
     * is why these are recorded on a debounce rather than per press.
     */
    protected data class SyncPoint(val positionMs: Long, val offsetMs: Long)

    protected var lastSyncPoint: SyncPoint? = null

    /**
     * How far apart two picks must be before their slope means anything.
     *
     * SIX MINUTES, and the number comes from what a pick is worth. The picture
     * is frozen when one is made, so the error is not a reaction -- it is
     * wherever the viewer parked the playhead, roughly +/-250ms. The error in
     * the SLOPE is about twice that divided by the span, and a short arm turns a
     * small error into a large rate error applied across the whole film:
     *
     *     span    worst residual over a further 20 min
     *      3 min   1.9s
     *      6 min   0.9s   <- the knee
     *     15 min   0.35s
     *
     * Longer is still better, which is why nothing ever asks for a second pick.
     */
    protected val SYNC_MIN_SPAN_MS = 6 * 60_000L

    /**
     * The two picks must differ by at least this much.
     *
     * The noise floor: below it, two picks are one observation made twice, and
     * dividing that by the span yields a confident-looking rate built out of
     * rounding.
     */
    protected val SYNC_MIN_OFFSET_DELTA_MS = 600L

    /** Sanity band for a measured rate. Outside it, the pair was not drift. */
    protected val SYNC_RATE_MIN = 0.90
    protected val SYNC_RATE_MAX = 1.10

    protected data class TimedCue(val startMs: Long, val endMs: Long, val cue: Cue)

    /**
     * Wire the control bar's behaviour to this PlayerView.
     *
     * pv.apply, NOT a bare body: every findViewById in here has to resolve
     * against the PLAYER VIEW. Called on the activity it would hit
     * Activity.findViewById, which under setContent searches the Compose host —
     * close enough to work by accident today and to stop working silently later.
     *
     * Called from the factory that inflates the view, once per player.
     */
    /**
     * Stop everything the subtitle layer has running.
     *
     * All four are lifecycleScope jobs, so they die with the activity anyway;
     * cancelling them explicitly is about the window between onDestroy starting
     * and the scope actually closing, during which the caption ticker would
     * otherwise read a player that releasePlayer has already torn down.
     *
     * Called from both players' onDestroy, before the player goes.
     */
    protected fun releaseSubtitleUi() {
        toastJob?.cancel()
        captionTickerJob?.cancel()
        remainingJob?.cancel()
        fixSeqJob?.cancel()
        // Listed here as well as in dismissSyncHint: this runs before the
        // player is released, and a timer that fires into a torn-down activity
        // is the kind of thing that only shows up as a crash in the wild.
        syncHintJob?.cancel()
    }

    /** Set once the band's controls have been given their focus sound. */
    private var bandSoundWired = false

    /**
     * The same hover note the rest of the app plays, for the control bar.
     *
     * ATTACHED PER VIEW rather than through a global focus listener, because a
     * global one fires for the Compose overlays too — and those already play
     * their own, so every row in the subtitle menu would have clicked twice.
     *
     * Walked rather than listed: the Subtitles button is injected at runtime and
     * the quality button only exists on one of the two players, so any hand
     * written list is a list that goes stale. Called after
     * injectSubtitleControls so the injected button is already in the tree.
     */
    private fun attachBandNavSound(pv: PlayerView) {
        if (bandSoundWired) return
        val root = pv.findViewById<View>(androidx.media3.ui.R.id.exo_controller) ?: return
        fun wire(v: View) {
            if (v.isFocusable) {
                v.setOnFocusChangeListener { _, hasFocus ->
                    // ONLY WHILE THE PLAYER IS ON SCREEN. As a closing player
                    // comes down, Android hands focus to whichever band button
                    // is left, and that landing ticked like a press. Measured
                    // 2026-10-19, BACK out of VidNest: the tick fired 0.6 s after
                    // the press, as the close animation ended -- past the details
                    // screen's own 450 ms restore mute, so it played every time.
                    if (hasFocus && !isFinishing &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        BlueHiveApplication.playHoverSound()
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) wire(v.getChildAt(i))
            }
        }
        // ── A REBUILT BAND IS NOT A BUTTON PRESS ─────────────────────────
        //
        // Whatever takes focus in the moment after these listeners attach does
        // so because the view tree was just recreated, not because anyone
        // touched the remote -- and the listener above cannot tell those apart.
        //
        // MEASURED 2026-10-10: the system reclaimed the AAC decoder twice
        // (ERROR_CODE_DECODING_RESOURCES_RECLAIMED at 15:15 and 16:07), the
        // player rebuilt each time, and each rebuild produced one navigation
        // tick with the remote untouched.
        //
        // Yesterday's mute covered the player RETURNING to a details screen;
        // this is the same lie told from inside the player. The window is short
        // because the focus landing follows wiring within a frame or two, and a
        // longer one would start swallowing ticks the viewer earned.
        BlueHiveApplication.muteHoverSounds(600)
        wire(root)
        bandSoundWired = true
        Log.e(TAG, "subs: band navigation sound wired")
    }

    protected fun attachBandChrome(pv: PlayerView) {
        pv.apply {
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { vis ->
                    val up = vis == View.VISIBLE
                    // The strip is not a media3 view, so
                    // media3 neither slides nor hides it.
                    findViewById<View>(R.id.player_title_strip)
                        ?.visibility =
                        if (up) View.VISIBLE else View.GONE
                    if (up) {
                        fillPlayerTitle(this)
                        bindTitleToBand(this)
                        injectSubtitleControls(this)
                        // After the injection, so the Subtitles button is in
                        // the tree and gets a sound like everything else.
                        attachBandNavSound(this)
                        // Hide media3's own CC button — we use our own.
                        findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)
                            ?.visibility = View.GONE
                        // FOCUS IS PLACED, NOT LEFT TO FALL.
                        // media3 declares requestPlayPauseFocus
                        // and never calls it, so focus lands on
                        // whatever is first in tree order.
                        //
                        // NEVER WHILE A SUBTITLE PANEL IS UP,
                        // and that guard is the whole reason
                        // this reads the way it does. Pressing
                        // the Subtitles button is itself a key
                        // event, so it raises the controller and
                        // fires this listener a moment AFTER the
                        // panel has taken focus -- at which
                        // point the bar genuinely does not have
                        // focus, the test below passes, and the
                        // grab drags focus out of the panel and
                        // onto a play button behind it. The
                        // panel then sits there lit and dead.
                        //
                        // hasFocus() alone cannot tell "nothing
                        // has claimed this yet" from "something
                        // else owns it on purpose"; only the
                        // panel flags know that.
                        val panelUp =
                            subMenuOpen || pickerOpen ||
                                    fixSeqOpen || styleOpen
                        val bar = findViewById<View>(
                            androidx.media3.ui.R.id.exo_controller
                        )
                        if (!panelUp && (bar == null || !bar.hasFocus())) {
                            findViewById<View>(
                                androidx.media3.ui.R.id.exo_play_pause
                            )?.requestFocus()
                        }
                    }
                    // CAPTIONS MOVE OUT FROM UNDER THE BAND.
                    // The caption layer is a separate view
                    // stacked above this one, so its text draws
                    // straight through the controls; at the
                    // larger caption sizes it covers the
                    // scrubber outright.
                    captionsRaised = up
                    controlsVisible = up
                    // The hint deliberately SURVIVES the controls opening. It
                    // rides up with the captions on the same fraction, so it
                    // clears the band rather than colliding with it, and
                    // handleSyncHintKey stands aside while the band has the
                    // remote.
                    startOrStopRemainingTicker()
                }
            )
        }
    }

    /**
     * The caption layer and every subtitle panel, stacked above the player.
     *
     * A BoxScope extension because the toast aligns itself to the bottom end of
     * the player's own Box; it is the caller's Box that gives that meaning, so
     * the receiver is part of the contract rather than an implementation detail.
     *
     * Goes LAST inside that Box, so the panels draw over the picture and own the
     * d-pad while they are up.
     */
    @Composable
    protected fun BoxScope.SubtitleLayer() {
        // Self-rendered captions overlay (fed by the position ticker).
        //
        // Cues are pushed through Compose state rather than through a
        // cached SubtitleView reference. The ticker used to hold the
        // View the factory happened to build and call setCues on it
        // directly, which silently stopped working the moment Compose
        // handed us a different instance — the on-screen text froze,
        // and "turn captions off" couldn't clear it because it was
        // clearing a view nobody was looking at. update runs against
        // whatever view is live now, so that can't happen.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SubtitleView(ctx).apply {
                    // THE BLACK BOXES WERE THE DEVICE'S CHOICE, NOT
                    // OURS. setUserDefaultStyle reads the system
                    // captioning preferences, and on most TV boxes
                    // those default to white on an OPAQUE black
                    // background -- drawn per LINE, which is why the
                    // slabs behind a two-line cue have ragged
                    // different widths and a gap between them. The
                    // app never picked that look; it just never
                    // picked anything.
                    //
                    // So the style is stated instead of inherited.
                    // Transparent behind the text, and legibility
                    // carried by an OUTLINE: a drop shadow is the
                    // prettier choice and it is the one that fails,
                    // because it only separates text from what is
                    // behind it when what is behind it is darker.
                    // Half the frames in anything daylit are not.
                    //
                    // EDGE_TYPE_OUTLINE -> EDGE_TYPE_DROP_SHADOW is
                    // the one-line change if this reads too harsh.
                    applyCaptionStyle(this)
                    setBottomPaddingFraction(CAPTION_PADDING_IDLE)
                }
            },
            update = { view ->
                // RESTYLED ONLY WHEN THE STYLE CHANGED. This block
                // runs on every cue change; rebuilding the paint a
                // few times a second to say the same thing would be
                // work for nothing. Reading the three states here is
                // also what subscribes this view to them, so a choice
                // made on the customization screen reaches the real
                // captions on the next frame.
                if (lastAppliedCaptionStyle != Triple(subSize, subFont, subEdge)) {
                    applyCaptionStyle(view)
                }
                view.setBottomPaddingFraction(
                    if (captionsRaised) CAPTION_PADDING_CONTROLS
                    else CAPTION_PADDING_IDLE
                )
                view.setCues(activeCues)
            }
        )

        // English-subtitle picker (CC). Drawn above the player and the
        // caption layer so it owns D-pad focus while it's up.
        if (pickerOpen) {
            SubtitlePickerOverlay(
                loading     = pickerLoading,
                options     = pickerOptions,
                activeIndex = activeSubtitleIndex,
                durationMs  = player?.duration?.takeIf { it > 0 } ?: 0L,
                error       = pickerError,
                onPick      = { applyChoice(it) },
                // BACK to wherever this was opened from: the menu
                // when it came from there, out entirely when the
                // Subtitles button opened it directly.
                onDismiss   = {
                    if (pickerFromMenu) returnToSubtitleMenu()
                    else closeAllSubtitleUi()
                },
            )
        }

        // The menu the Subtitles button opens.
        if (subMenuOpen) {
            SubtitleMenuOverlay(
                activeLabel      = activeSubtitleLabel(),
                styleSummary     = subtitleStyleSummary(subSize, subFont),
                offsetMs         = subtitleOffsetMs,
                onChooseSubtitle = { subMenuOpen = false; openSubtitlePicker(fromMenu = true) },
                onFixSequence    = { subMenuOpen = false; openFixSeqScreen() },
                onCustomize      = { openStyleScreen() },
                onTurnOff        = { turnCaptionsOff(); closeAllSubtitleUi() },
                onDismiss        = { closeAllSubtitleUi() },
            )
        }

        // Fix subtitle sequence. Captions stay VISIBLE under this
        // one -- unlike by-ear, the viewer is comparing text with
        // text and needs to see what is currently showing.
        if (fixSeqOpen) {
            fixSeqPhase?.let { ph ->
                SubtitleFixSeqOverlay(
                    phase        = ph,
                    query        = fixSeqQuery,
                    isPlaying    = isPlayingState,
                    onEngage     = { engageFixSeqSearch() },
                    onDisengage  = { disengageFixSeqSearch() },
                    onQuery      = { onFixSeqQuery(it) },
                    onSeek       = { fixSeqSeek(it) },
                    onTogglePlay = { toggleFixSeqPlay() },
                    onPick       = { row -> applyFixSeqPick(row) },
                    onUndo       = { undoFixSeqPick() },
                    onAccept     = { acceptFixSeqPick() },
                    onDone       = { returnFromFixSeqToMenu() },
                )
            }
        }

        // The out-of-sync offer, above the caption line. Drawn before the
        // panels below so any of them covers it rather than the reverse.
        // captionsRaised, NOT controlsVisible: the two are set together from the
        // same listener, but only this one is snapshot state. Reading the plain
        // Boolean here would compose the right look once and never update it.
        SubtitleSyncHint(
            visible = syncHintUp,
            captionFraction =
                if (captionsRaised) CAPTION_PADDING_CONTROLS else CAPTION_PADDING_IDLE,
            active = !captionsRaised,
            seconds = syncHintSeconds,
        )

        // Subtitle customization. Top-anchored like the rest, so the
        // captions it is changing stay visible at the bottom.
        if (styleOpen) {
            SubtitleStyleOverlay(
                size    = subSize,
                font    = subFont,
                edge    = subEdge,
                onSize  = { subSize = it; persistCaptionStyle() },
                onFont  = { subFont = it; persistCaptionStyle() },
                onEdge  = { subEdge = it; persistCaptionStyle() },
                onReset = {
                    subSize = SubtitleSizeChoice.Small
                    subFont = SubtitleFontChoice.InterSemiBold
                    subEdge = SubtitleEdgeChoice.Shadow
                    persistCaptionStyle()
                },
                onDone  = { closeStyleScreen() },
            )
        }

        // Transient toast, lower-right corner (non-interactive overlay).
        toastText?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // Clears the control bar. At bottom = 28.dp a toast
                    // raised BY a control-bar button lands on top of the
                    // buttons that raised it — the timing read-out sits
                    // exactly there, which is the one thing you want to
                    // read afterwards. The width cap stops a long
                    // message spanning the screen instead of wrapping.
                    .padding(end = 28.dp, bottom = 96.dp)
                    .widthIn(max = 560.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }

        // "Still watching?" — LAST, so it draws above the captions, the
        // toast and the subtitle picker. It takes no focus and consumes
        // no input (any key dismisses it through the window hook), so
        // sitting on top costs the picker nothing if both are somehow up.
    }

    // ── Captions: CC button + self-rendering ─────────────────────────────────
    /** Sentinel for "nothing rendered yet" — no real cue key can be a NUL. */
    protected val noCueKey = 0.toChar().toString()

    /**
     * CC now opens the picker rather than silently loading the first English
     * subtitle it finds. There are usually several, only one is timed to the
     * release we're streaming, and until now the other four were fetched past,
     * discarded and never offered — leaving a drifting subtitle with no recourse
     * but the offset buttons, which cannot fix a framerate mismatch.
     */
    /**
     * The Subtitles button. It opens the track list, always.
     *
     * ONE DESTINATION, NOT TWO. An earlier version branched on whether captions
     * were already on -- list when off, menu when on -- which was defensible and
     * still wrong: a button that leads somewhere different depending on hidden
     * state has to be learned twice, and the thing people press it for is
     * overwhelmingly "show me the subtitles". Picking a track is the common
     * action; everything else on the menu is something you do once, if ever.
     *
     * THE MENU IS NOT ORPHANED: applyChoice lands on it, so it appears the
     * moment there is a subtitle for its other rows to act on. That does mean
     * reaching customization without changing track costs a trip through the
     * list -- the price of a predictable button, and cheap to revisit by giving
     * the list a row of its own that opens the menu.
     */
    protected fun onCcClicked() {
        when {
            subMenuOpen || pickerOpen || fixSeqOpen || styleOpen -> closeAllSubtitleUi()
            // Nothing on yet: the menu would be one row, so go where that row
            // leads.
            activeSubtitleLabel() == null -> openSubtitlePicker(fromMenu = false)
            // Something on: the menu is four screens and the track list is only
            // one of them. Pressing the button again to be shown the list a
            // second time is the app ignoring what already happened.
            else -> openSubtitleMenu()
        }
    }

    /**
     * What the Subtitles button opens: two questions asked separately, rather
     * than a track list with the timing control buried at the end of it.
     *
     * DOES NOT PAUSE. Only the track list does, because comparing candidates
     * takes a few seconds of reading. This menu is three rows, and everything
     * you might want to check after using it -- whether the new subtitle is the
     * right one, whether the timing landed -- needs the picture running to see.
     */
    protected fun openSubtitleMenu() {
        subMenuOpen = true
        claimRemoteForSubtitleUi()
    }

    /**
     * Take the remote for a subtitle panel.
     *
     * setControllerAutoShow(false) IS THE LOAD-BEARING LINE. PlayerView raises
     * its own controller whenever playback starts, pauses, ends or fails, and
     * raising it moves focus to play/pause -- so any panel that pauses the
     * picture hands its focus to the control bar underneath itself.
     *
     * THIS USED TO LIVE IN openSubtitleMenu ALONE, which was correct only while
     * every panel was reached through the menu. The moment the Subtitles button
     * began opening the track list directly, every path that skipped the menu
     * skipped this too: engaging the search box paused the video, the controller
     * came up, and the press meant for the keyboard landed on pause instead.
     * A guarantee that depended on one route is not a guarantee, so it is a
     * function every entry point calls.
     *
     * Undone only by closeAllSubtitleUi, which is the single way out.
     */
    protected fun claimRemoteForSubtitleUi() {
        playerView?.hideController()
        playerView?.setControllerAutoShow(false)
    }

    /** Leave the track list for the menu, resuming what the list paused. */
    protected fun returnToSubtitleMenu() {
        pickerOpen = false
        if (pausedForPicker) { player?.playWhenReady = true; pausedForPicker = false }
        subMenuOpen = true
    }

    /**
     * Read the saved appearance.
     *
     * STORED BY NAME, not by ordinal: an ordinal is a promise never to reorder
     * the enum, and the first person to insert a size in the middle would
     * silently change everyone's font. An unknown name falls back to the default
     * rather than throwing, so a setting removed in a later build degrades
     * instead of crashing the player on launch.
     */
    protected fun loadCaptionStyle() {
        fun <T : Enum<T>> read(key: String, values: Array<T>, fallback: T): T {
            val name = subPrefs.getString(key, null) ?: return fallback
            return values.firstOrNull { it.name == name } ?: fallback
        }
        subSize = read("style_size", SubtitleSizeChoice.values(), SubtitleSizeChoice.Small)
        subFont = read("style_font", SubtitleFontChoice.values(), SubtitleFontChoice.InterSemiBold)
        subEdge = read("style_edge", SubtitleEdgeChoice.values(), SubtitleEdgeChoice.Shadow)
        Log.e(TAG, "subs: caption style ${subSize.name}/${subFont.name}/${subEdge.name}")
    }

    /**
     * Keep the appearance, unlike the timing.
     *
     * Timing is deliberately thrown away at the end of every playback, because
     * an offset measured against one subtitle file means nothing against the
     * next. Appearance is the opposite: it is a statement about the viewer's
     * eyes and their television, and it is the same statement tomorrow.
     */
    protected fun persistCaptionStyle() {
        subPrefs.edit()
            .putString("style_size", subSize.name)
            .putString("style_font", subFont.name)
            .putString("style_edge", subEdge.name)
            .apply()
    }

    /** The Android typeface behind a [SubtitleFontChoice]. */
    protected fun typefaceFor(choice: SubtitleFontChoice): android.graphics.Typeface? =
        when (choice) {
            SubtitleFontChoice.InterSemiBold ->
                ResourcesCompat.getFont(this, R.font.inter_semibold)
            SubtitleFontChoice.InterBold ->
                ResourcesCompat.getFont(this, R.font.inter_bold)
            SubtitleFontChoice.Sans -> android.graphics.Typeface.SANS_SERIF
            SubtitleFontChoice.Serif -> android.graphics.Typeface.SERIF
            SubtitleFontChoice.Mono -> android.graphics.Typeface.MONOSPACE
        }

    /**
     * Push the current appearance into the caption view.
     *
     * setApplyEmbeddedStyles(false) so a subtitle file cannot overrule a choice
     * the viewer just made -- the whole point of this screen is that the setting
     * holds whatever the file says.
     */
    protected fun applyCaptionStyle(view: SubtitleView) {
        val black = android.graphics.Color.BLACK
        val clear = android.graphics.Color.TRANSPARENT
        view.setApplyEmbeddedStyles(false)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(
            CaptionStyleCompat(
                android.graphics.Color.WHITE,
                // Black box is the only choice that fills behind the text; the
                // other three leave the picture visible and carry legibility in
                // the edge instead.
                if (subEdge == SubtitleEdgeChoice.Box) black else clear,
                clear,
                when (subEdge) {
                    SubtitleEdgeChoice.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    SubtitleEdgeChoice.Shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                    SubtitleEdgeChoice.Box, SubtitleEdgeChoice.None ->
                        CaptionStyleCompat.EDGE_TYPE_NONE
                },
                black,
                typefaceFor(subFont),
            )
        )
        view.setFractionalTextSize(subSize.fraction)
        lastAppliedCaptionStyle = Triple(subSize, subFont, subEdge)
    }

    protected fun openStyleScreen() {
        subMenuOpen = false
        pickerOpen = false
        fixSeqOpen = false
        fixSeqPhase = null
        styleOpen = true
        claimRemoteForSubtitleUi()
        Log.e(TAG, "subs: customization opened")
    }

    /**
     * BACK out of customization, to the menu rather than to the film -- the same
     * contract the sequence screen follows, and for the same reason.
     */
    protected fun closeStyleScreen() {
        styleOpen = false
        persistCaptionStyle()
        returnToSubtitleMenu()
        Log.e(TAG, "subs: customization closed " +
                "(${subSize.name}/${subFont.name}/${subEdge.name})")
    }

    /**
     * Offer the timing fix, once, when the viewer is actually watching.
     *
     * NOT at the moment a track is chosen, which is where the old toast fired:
     * the menu is still up then, and nobody can judge sync against a panel. The
     * hint waits for every subtitle surface to close, so it lands on the first
     * frame of the thing it is asking about.
     */
    protected fun maybeShowSyncHint() {
        if (syncHintShown || !captionsOn) return
        if (subMenuOpen || pickerOpen || fixSeqOpen || styleOpen) return
        if (isFinishing || isDestroyed) return
        syncHintShown = true
        syncHintUp = true
        syncHintJob?.cancel()
        syncHintJob = lifecycleScope.launch {
            // Ticked rather than slept through, so the bar can show the count.
            // Each pass sleeps a second and decrements, which drifts a few
            // milliseconds over twenty — irrelevant to a number this size, and
            // cheaper than holding a deadline and recomputing against the clock.
            var left = (SYNC_HINT_MS / 1000L).toInt()
            syncHintSeconds = left
            while (left > 0) {
                delay(1_000L)
                left--
                syncHintSeconds = left
            }
            dismissSyncHint("timed out")
        }
        Log.e(TAG, "subs: sync hint shown")
    }

    /** Idempotent: every path that can end the hint calls this, some twice. */
    protected fun dismissSyncHint(why: String) {
        syncHintJob?.cancel()
        syncHintJob = null
        if (!syncHintUp) return
        syncHintUp = false
        Log.e(TAG, "subs: sync hint dismissed -- $why")
    }

    /**
     * The hint's answer to the remote, for both players' dispatchKeyEvent.
     *
     * SHARED, because the alternative is the same four-flag list written twice
     * and drifting apart — which is exactly what happened to the control bar.
     * Each activity spends one line on it.
     *
     * CENTRE IS CONSUMED ON BOTH DOWN AND UP. The fix screen it opens has a
     * search box that engages on a centre KeyUp, and a press whose halves land
     * on two different controls is the bug this file has already been bitten by
     * once today. Swallowing both halves means the KeyUp cannot arrive there at
     * all, on top of the pairing guard already in the box.
     *
     * ANYTHING ELSE STANDS IT DOWN and is then left alone. A viewer reaching
     * for the control bar or a seek has told us they are busy; the hint should
     * get out of the way without also eating the key that said so.
     *
     * @return true when the hint consumed the event.
     */
    protected fun handleSyncHintKey(event: android.view.KeyEvent): Boolean {
        if (!syncHintUp) return false

        // THE CONTROL BAR OUTRANKS THE HINT, and this is what lets the hint
        // stay on screen while the viewer uses it. With the band up the focused
        // button owns centre and back -- pressing OK must play or pause, not
        // open the timing screen -- so the hint answers nothing and simply
        // waits out the rest of its ten seconds. When the band fades it is live
        // again, which costs nothing because it never stopped being visible.
        //
        // controlsVisible, not isControllerFullyVisible: ours is set the moment
        // media3 reports VISIBLE, while "fully" is still false through the show
        // animation -- a window where a quick second press would have been
        // taken by the hint instead of the button under it.
        if (controlsVisible) {
            // ...BUT THE WAY BACK UP HAS TO EXIST. Pressing down off the hint
            // raises the band, and the band is the end of the line: nothing
            // above the scrubber is focusable, so up did nothing and the hint
            // sat there lit-but-unreachable for the rest of its twenty seconds.
            //
            // Only from the SCRUBBER, which is the top of the band's own focus
            // order. Taking up from the buttons would cost the step that moves
            // between the buttons and the scrubber, so the route reads as one
            // ladder: buttons, scrubber, hint.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            ) {
                val pv = playerView
                val bar = pv?.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                if (pv != null && bar != null && bar.hasFocus()) {
                    // Hiding the band IS the move: it puts the remote back on
                    // the hint, which lights the moment controlsVisible flips.
                    // raiseControllerOnDirectionKey only acts on ACTION_DOWN, so
                    // this key's own ACTION_UP cannot bounce the band back.
                    pv.hideController()
                    BlueHiveApplication.playHoverSound()
                    return true
                }
            }
            return false
        }

        val code = event.keyCode
        if (ExoPlayerConfig.isCenterKey(code)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                dismissSyncHint("taken up")
                openFixSeqScreen()
            }
            return true
        }
        if (code == android.view.KeyEvent.KEYCODE_BACK) {
            // Consumed so BACK dismisses the hint instead of leaving the
            // player, which is what it would do with nothing else open.
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                dismissSyncHint("dismissed with back")
            }
            return true
        }
        // EVERY OTHER KEY IS LEFT ALONE, and crucially does NOT retire the
        // hint. Reaching for rewind or play is not a decision about the offer;
        // it is the viewer doing the thing the offer is about. Taking it away
        // then means the one person most likely to notice bad timing loses the
        // fix just as they start looking at it. Only BACK, the countdown, or
        // the offer being taken ends it.
        return false
    }

    protected fun closeAllSubtitleUi() {
        subMenuOpen = false
        pickerOpen = false
        fixSeqOpen = false
        fixSeqPhase = null
        fixSeqQuery = ""
        fixSeqJob?.cancel()
        fixSeqJob = null
        if (styleOpen) { styleOpen = false; persistCaptionStyle() }
        if (pausedForPicker) { player?.playWhenReady = true; pausedForPicker = false }
        playerView?.setControllerAutoShow(true)
        // Last, so every flag above is already false and the guard inside sees
        // a clear screen.
        maybeShowSyncHint()
    }

    /** The label of whatever is rendering, for the menu to state. */
    protected fun activeSubtitleLabel(): String? = when {
        !captionsOn || activeSubtitleIndex < 0 -> null
        else -> pickerOptions.firstOrNull { it.option.index == activeSubtitleIndex }?.label ?: "On"
    }

    /** Cache key shared by every subtitle for this exact episode. */
    protected fun subtitleCacheKey(): String {
        val season  = intent.getIntExtra("SEASON_NUMBER", 0)
        val episode = intent.getIntExtra("EPISODE_NUMBER", 0)
        return if (season > 0) "${tmdbId}_s${season}e${episode}" else "$tmdbId"
    }

    /**
     * The track list.
     *
     * [fromMenu] records whether there is a menu behind this screen to go back
     * TO. Without it, BACK out of a list the Subtitles button opened directly
     * would conjure a menu the viewer had never seen -- a screen appearing on
     * the way OUT of one, which reads as the app losing its place.
     */
    protected fun openSubtitlePicker(fromMenu: Boolean = true) {
        pickerFromMenu = fromMenu
        if (tmdbId <= 0 && streamSubtitleUrl.isNullOrBlank()) {
            Log.e(TAG, "subs: no tmdbId passed — cannot fetch captions")
            showToast("Sorry, no subtitle for this media is available.")
            return
        }
        pickerOpen = true
        pickerError = null
        claimRemoteForSubtitleUi()

        // Pause while they choose — comparing candidates takes a few seconds and
        // the episode shouldn't run on underneath. Only resume what WE paused, so
        // closing the panel can't restart a video they had deliberately paused.
        player?.let { p ->
            if (p.playWhenReady) { p.playWhenReady = false; pausedForPicker = true }
        }

        // Already loaded once this session — reopen instantly, no re-download.
        if (pickerOptions.isNotEmpty()) return

        pickerLoading = true
        Log.d(TAG, "subs: ▶️ CC pressed → listing English subtitles for tmdb=$tmdbId")
        lifecycleScope.launch {
            val choices = withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(25_000) {
                        val key = subtitleCacheKey()
                        val fromStream = streamSubtitleUrl

                        // NOTHING IS DOWNLOADED HERE ANY MORE except the stream's
                        // own caption. This used to fan out and fetch every
                        // candidate at once — the comment claimed "~40 KB each",
                        // and measured on the onn box it peaked at 806MB and got
                        // the app killed by the low-memory killer mid-film.
                        //
                        // The backend lists them with their cue counts and timings
                        // already computed, so the picker renders from a few
                        // hundred bytes of JSON. The chosen file is fetched in
                        // applyChoice, one download instead of eight.
                        val listed = if (tmdbId > 0) HlsSubtitles.listChoices(
                            tmdbId    = tmdbId,
                            mediaType = intent.getStringExtra("MEDIA_TYPE") ?: "movie",
                            season    = intent.getIntExtra("SEASON_NUMBER", 0),
                            episode   = intent.getIntExtra("EPISODE_NUMBER", 0),
                        ) else emptyList()

                        // The stream caption still has to be fetched to be offered
                        // at all — it is the only way to know whether it has cues.
                        // One file, and it is the option most likely to be picked.
                        // The SOURCE is part of the cache key. Every site serves
                        // its own encode with its own caption, so one name per
                        // title handed the second site the first site's file.
                        val stream = if (!fromStream.isNullOrBlank())
                            HlsSubtitles.streamChoice(
                                context   = this@SubtitlePlayerActivity,
                                url       = fromStream,
                                key       = key,
                                sourceTag = intent.getStringExtra("EXTRACT_SOURCE") ?: "vidfast",
                            )
                        else null

                        listOfNotNull(stream) + listed
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "subs: listing threw — ${e.message}", e); null
                }
            }
            pickerLoading = false
            pickerOptions = choices.orEmpty()
            if (pickerOptions.isEmpty()) {
                pickerError = "Couldn't reach the subtitle service. Try again in a moment."
                Log.e(TAG, "subs: FAILED for tmdb=$tmdbId — nothing usable (see prior subs: logs)")
            } else {
                Log.d(TAG, "subs: picker ready — ${pickerOptions.size} option(s)")
                // Deliberately does NOT auto-apply the first option. Loading one
                // on open makes the panel a confirmation of a choice already made
                // rather than a choice — and the first entry is exactly the one
                // that might be drifting, which is why the picker exists.
            }
        }
    }

    protected fun closeSubtitlePicker() {
        pickerOpen = false
        if (pausedForPicker) { player?.playWhenReady = true; pausedForPicker = false }
    }

    /** Render [choice] from now on. Only ever called from the picker. */
    protected fun applyChoice(choice: HlsSubtitles.SubtitleChoice) {
        lifecycleScope.launch {
            val cues = withContext(Dispatchers.IO) {
                try {
                    // THE DOWNLOAD HAPPENS HERE NOW, for this one option only.
                    // Listing an option costs a row; fetching it costs a request,
                    // and only the option actually chosen ever pays that. The
                    // stream caption returns its existing path immediately.
                    val path = HlsSubtitles.ensureVtt(
                        context   = this@SubtitlePlayerActivity,
                        choice    = choice,
                        tmdbId    = tmdbId,
                        mediaType = intent.getStringExtra("MEDIA_TYPE") ?: "movie",
                        season    = intent.getIntExtra("SEASON_NUMBER", 0),
                        episode   = intent.getIntExtra("EPISODE_NUMBER", 0),
                        key       = subtitleCacheKey(),
                    )
                    if (path != null && File(path).exists()) parseVtt(path) else null
                } catch (e: Exception) {
                    Log.e(TAG, "subs: parse threw — ${e.message}", e); null
                }
            }
            if (cues.isNullOrEmpty()) {
                showToast("That subtitle couldn't be read — try another.")
                return@launch
            }
            parsedCues = cues
            // BUILT HERE, on the parse coroutine, not when the screen opens:
            // construction is almost all class-load and JIT (~42ms cold against
            // 0.3ms warm) and that does not belong on the critical path of
            // opening a modal. cueLineText is reused so the caption layer,
            // the by-ear prompt and the search rows cannot disagree about what
            // a line says.
            fixSeqMatcher = CueMatcher(
                cues.map {
                    CueMatcher.Entry(
                        cueLineText(it.cue.text?.toString() ?: ""),
                        it.startMs,
                        it.endMs,
                    )
                }
            )
            captionsLoaded = true
            captionsOn = true
            activeSubtitleIndex = choice.option.index
            // A different file has different timing, so an anchor taken against
            // the old one would pair with a point from the new one and measure
            // a drift that belongs to neither.
            lastSyncPoint = null
            lastCueKey = noCueKey
            updateCcButtonState()
            startCaptionTicker()
            Log.e(TAG, "subs: captions loaded — ${choice.label} (${cues.size} cues), " +
                    "offset=${subtitleOffsetMs}ms — returning to the menu")
            // BACK TO THE MENU, NOT OUT. Choosing a track is a step inside the
            // subtitle flow, not the end of it -- and the menu is where the
            // result is legible, since its row now reads the new label. Dumping
            // the viewer into the film gives no confirmation anything changed.
            returnToSubtitleMenu()
            measureTimingInBackground(choice)
            // NO TOAST. This used to say "<track> on. Out of sync? Fix subtitle
            // speed." in a corner, which is the notice the red bar above the
            // captions replaced — and it replaced it precisely because a corner
            // toast fired at selection time is read by nobody. Leaving both up
            // meant the weaker one covering the picture alongside the stronger
            // one. The menu already states which track is on.
        }
    }

    /**
     * Ask the backend to time this subtitle against this source's own audio.
     *
     * AFTER THE CAPTIONS ARE ALREADY ON SCREEN, never in front of them. A first
     * measurement takes ten to twenty seconds — three windows of audio pulled
     * and correlated — and nobody should watch a spinner for that to read a
     * line of dialogue. Once measured it is a row in a table and comes back
     * instantly for every later viewer of the same title and source.
     *
     * The result is applied ONLY when the viewer has not set a timing of their
     * own. Someone who has already nudged this title has told us what they
     * want, and a server measurement arriving half a minute later to overrule
     * them would be the app arguing with its user.
     */
    protected fun measureTimingInBackground(choice: HlsSubtitles.SubtitleChoice) {
        // The stream's own caption has no backend row to measure against — it
        // came from the extractor, not the subtitle service — and it is already
        // timed to this exact release anyway.
        if (choice.option.index == 0) return
        if (tmdbId <= 0 || measureStreamUrl.isBlank()) return
        if (subtitleOffsetMs != 0L || subtitleRate != 1.0) {
            Log.d(TAG, "subs: timing already set for this title — not measuring")
            return
        }

        val dur = player?.duration?.takeIf { it > 0 } ?: 0L
        val req = SubtitleSyncRequest(
            tmdb_id     = tmdbId,
            id          = choice.option.id,
            source      = intent.getStringExtra("EXTRACT_SOURCE") ?: "vidfast",
            media_type  = intent.getStringExtra("MEDIA_TYPE") ?: "movie",
            season      = intent.getIntExtra("SEASON_NUMBER", 0),
            episode     = intent.getIntExtra("EPISODE_NUMBER", 0),
            stream_url  = measureStreamUrl,
            referer     = measureReferer,
            user_agent  = measureUserAgent,
            duration_ms = dur,
        )
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    ApiClient.bluehiveApi.syncSubtitle(req)
                } catch (e: Exception) {
                    // Never surfaced. The subtitle the viewer chose is already
                    // playing; this was an attempt to improve it, and a backend
                    // that is busy or a measurement that found nothing are both
                    // ordinary outcomes rather than something to interrupt for.
                    Log.d(TAG, "subs: timing measurement unavailable — ${e.message}")
                    null
                }
            } ?: return@launch

            if (!res.ok || (res.offset_ms == 0L && res.rate == 1.0)) {
                Log.d(TAG, "subs: measured — no correction needed (${res.reason ?: "aligned"})")
                return@launch
            }
            // Re-check: the viewer may have nudged while we were measuring, and
            // that answer is worth more than this one.
            if (subtitleOffsetMs != 0L || subtitleRate != 1.0) {
                Log.d(TAG, "subs: viewer set a timing while measuring — keeping theirs")
                return@launch
            }
            subtitleOffsetMs = res.offset_ms.coerceIn(-60_000L, 60_000L)
            subtitleRate = res.rate
            lastSyncPoint = null           // the baseline moved; old anchor is stale
            persistTiming()
            renderActiveCues()
            showToast("Subtitles synced to this video.")
            Log.e(TAG, "subs: applied measured timing offset=${res.offset_ms}ms " +
                    "rate=${"%.5f".format(res.rate)} err=${res.median_err_ms}ms " +
                    "cached=${res.cached}")
        }
    }

    protected fun turnCaptionsOff() {
        // Captions off retires the hint and ARMS IT AGAIN: switching them back
        // on is a fresh decision, and the offer should stand for it.
        dismissSyncHint("captions off")
        syncHintShown = false
        captionsOn = false
        captionsLoaded = false
        activeSubtitleIndex = -1
        // Stop the ticker FIRST, then clear — otherwise the next tick (up to
        // 80ms away) can win the race and put the last cue straight back.
        captionTickerJob?.cancel()
        captionTickerJob = null
        parsedCues = emptyList()
        fixSeqMatcher = null
        activeCues = emptyList()
        lastCueKey = noCueKey
        updateCcButtonState()
        closeSubtitlePicker()
        Log.d(TAG, "captions OFF")
    }

    /** Recompute the active cue(s) from the playback clock (+ offset) ~12×/sec. */
    protected fun startCaptionTicker() {
        captionTickerJob?.cancel()
        captionTickerJob = lifecycleScope.launch {
            while (true) {
                renderActiveCues()
                delay(80)
            }
        }
    }

    protected fun renderActiveCues() {
        val p = player
        val active: List<Cue> =
            if (!captionsOn || p == null || parsedCues.isEmpty()) emptyList()
            else {
                // Map the playback clock into CUE space: videoTime = cue*rate +
                // offset, inverted. +offset ⇒ cue shows later; rate > 1 stretches
                // the subtitle to fit a longer video, fixing one that runs ahead.
                //
                // At rate 1.0 this is exactly the old expression: a Long position
                // converts to Double losslessly below 2^53 ms, and dividing by 1.0
                // is the identity, so the uncorrected path did not change.
                val t = ((p.currentPosition - subtitleOffsetMs) / subtitleRate).toLong()
                parsedCues.filter { t >= it.startMs && t <= it.endMs }.map { it.cue }
            }
        val key = active.joinToString("") { it.text ?: "" }
        if (key != lastCueKey) { activeCues = active; lastCueKey = key }
    }

    // ── Subtitle timing controls (buttons in the bottom control bar) ──────────
    /**
     * How far off the bottom the captions sit, as a fraction of the view height.
     *
     * Two values because the band only exists some of the time. 0.10 is what the
     * player has always used; the raised figure clears the 60dp band plus the
     * scrubber above it with room to spare at the largest caption size.
     */
    protected val CAPTION_PADDING_IDLE = 0.10f
    protected val CAPTION_PADDING_CONTROLS = 0.22f

    /**
     * Keep the time-remaining read-out current while the band is up.
     *
     * TWICE A SECOND, and only while visible. media3 drives exo_position itself
     * but has no concept of counting down, so this is ours; running it when
     * nothing is on screen would be a wakeup per tick for a view nobody sees.
     *
     * Util.getStringForTime, NOT fmtClock, and the difference is visible:
     * media3 formats the position as %02d:%02d and fmtClock formats as %d:%02d,
     * so the two halves of the read-out would disagree about leading zeros and
     * the whole row would shift sideways as the remaining time crossed 10:00.
     */
    protected fun startOrStopRemainingTicker() {
        remainingJob?.cancel()
        if (!controlsVisible) return
        remainingJob = lifecycleScope.launch {
            val sb = StringBuilder()
            val fm = java.util.Formatter(sb, java.util.Locale.getDefault())
            while (true) {
                val view = playerView?.findViewById<TextView>(R.id.player_time_remaining)
                val p = player
                if (view != null && p != null) {
                    val left = (p.duration - p.currentPosition).coerceAtLeast(0L)
                    view.text = if (p.duration <= 0L) ""
                    else "− " + androidx.media3.common.util.Util.getStringForTime(sb, fm, left) + " left"
                }
                delay(500)
            }
        }
    }

    /**
     * Put the show and episode into the band's title strip.
     *
     * Filled on every show rather than once, because the views belong to the
     * control bar and the bar is inflated by media3 after the PlayerView exists
     * -- there is no earlier moment at which findViewById would return them.
     * Cheap enough to repeat: two setText calls against values already in the
     * intent.
     */
    protected fun fillPlayerTitle(pv: PlayerView) {
        val show = intent.getStringExtra("MEDIA_TITLE")?.takeIf { it.isNotBlank() }
        val season = intent.getIntExtra("SEASON_NUMBER", 0).takeIf { it > 0 }
        val episode = intent.getIntExtra("EPISODE_NUMBER", 0).takeIf { it > 0 }
        val epName = intent.getStringExtra("EPISODE_NAME")?.takeIf { it.isNotBlank() }

        // ── WHICH SOURCE IS THIS? ────────────────────────────────────────
        // Prefixed to the title as "(MoviesAPI) South Park", for the question
        // that cannot otherwise be answered from the couch: four buttons can
        // play the same episode and only one of them is the one that worked.
        //
        // IT NAMES WHAT PLAYED, NOT WHAT WAS PRESSED, and with failover those
        // differ: a press on VidFast that falls through to MoviesAPI arrives
        // here as MoviesAPI, because each chain step launches the player with
        // its OWN source name. That is the useful answer — the stream on
        // screen came from MoviesAPI whatever the viewer tapped.
        //
        // Dimmer and unbolded against the show title, so it reads as a tag
        // rather than as part of the name. Same colour as the episode line
        // beside it.
        val source = intent.getStringExtra("SOURCE_NAME")
            // Inner brackets are flattened rather than nested: the anime path
            // sends "Miruro (Dub)", and "(Miruro (Dub))" is a worse label than
            // "(Miruro Dub)".
            ?.replace("(", "")?.replace(")", "")
            ?.trim()?.takeIf { it.isNotEmpty() }

        pv.findViewById<TextView>(R.id.player_title_show)?.text =
            if (source == null) show ?: "" else {
                val tag = "($source) "
                android.text.SpannableStringBuilder(tag).append(show ?: "").apply {
                    setSpan(
                        android.text.style.ForegroundColorSpan(0xB3C6D2E8.toInt()),
                        0, tag.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.NORMAL),
                        0, tag.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }

        // A film has no second half to this line, and an empty view would still
        // take its margin -- so it goes GONE rather than blank.
        val sub = buildString {
            if (season != null && episode != null) append("S$season E$episode")
            if (epName != null) {
                if (isNotEmpty()) append("  \u00B7  ")
                append(epName)
            }
        }
        pv.findViewById<TextView>(R.id.player_title_episode)?.apply {
            text = sub
            visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * Make the title strip travel with the band.
     *
     * The strip has to be a SIBLING of exo_bottom_bar rather than a child: the
     * band cannot grow past 60dp, because media3 slides it out by that exact
     * figure read from a library dimen, and a taller one leaves its top strip
     * painted on the picture. But a sibling is not a media3 view, so media3
     * animates the band out from under it and the title just hangs there,
     * floating over the film with everything else gone.
     *
     * There is no callback for that. media3's visibility listener fires for the
     * whole controller, not for the band's own slide, and translationY changes
     * trigger no layout pass. So the strip copies the band's translation every
     * frame instead -- one float read and two writes, cheaper than the
     * animation it is shadowing.
     *
     * Attached once. The listener outlives each show, and adding another per
     * show would stack them silently.
     */
    protected fun bindTitleToBand(pv: PlayerView) {
        if (titleFollowsBand) return
        val band = pv.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar) ?: return
        val strip = pv.findViewById<View>(R.id.player_title_strip) ?: return
        val travel = 60f * resources.displayMetrics.density
        titleFollowsBand = true
        strip.viewTreeObserver.addOnPreDrawListener {
            val t = band.translationY
            strip.translationY = t
            // Faded as well as moved: the strip sits ABOVE the band, so sliding
            // alone would walk it down over the picture rather than off it.
            strip.alpha = (1f - (t / travel)).coerceIn(0f, 1f)
            true
        }
    }

    protected fun injectSubtitleControls(pv: PlayerView) {
        if (subtitlesButton != null) return                // inject once
        val bar = pv.findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls) ?: return
        val inflater = LayoutInflater.from(pv.context)

        // ONE CONTROL, CARRYING ITS OWN STATE IN WORDS.
        //
        // The timing controls are gone from here. They were four views wide,
        // permanently docked beside play/pause, and the read-out said "+0.00s"
        // for all but a few seconds of a film's life -- a number nobody can act
        // on, because you cannot tell whether +1.2 is right until you watch.
        // Everything subtitle-related lives behind this one button now.
        val button = (inflater.inflate(R.layout.vidapi_subtitles_button, bar, false) as TextView)
            .apply { setOnClickListener { onCcClicked() } }

        subtitlesButton = button
        updateCcButtonState()

        bar.addView(button, 0)
        // Log.e: R8 strips Log.d from release, and this is the one line that
        // proves the custom control bar still carries the subtitle entry point.
        Log.e(TAG, "subs: injected subtitles button into control bar")
    }

    /**
     * The whole drift feature, with no UI attached.
     *
     * A viewer fixing a subtitle tells us, without meaning to, exactly what is
     * wrong with it. Each time they match a line they heard against a frozen
     * frame they are asserting "at THIS moment, the right shift is THIS much".
     * One such assertion is a constant offset and we already honour it. Two of
     * them, far enough apart, are a LINE — and the slope of that line is the timing error we could not
     * otherwise see.
     *
     *     needed offset at p   o(p) = p·(1 − 1/rate) + delta/rate
     *     slope                m    = (o₂ − o₁) / (p₂ − p₁) = 1 − 1/rate
     *     so                   rate = 1 / (1 − m)
     *     and                  delta = rate·(o₁ − p₁·m)
     *
     * Both fall out exactly; nothing is guessed and no preset is involved. Note
     * it does NOT need them to have fully corrected the drift either time — only
     * to have matched a line they were happy with, twice.
     */
    protected fun recordSyncPoint(positionMs: Long, offsetMs: Long) {
        val point = SyncPoint(positionMs, offsetMs)
        val prev = lastSyncPoint
        val span = positionMs - (prev?.positionMs ?: 0L)

        // Too close together, or they seeked backwards: this is the new anchor
        // rather than the far end of a measurement.
        if (prev == null || span < SYNC_MIN_SPAN_MS) {
            lastSyncPoint = point
            Log.d(TAG, "subs: sync anchor at ${positionMs}ms offset=${offsetMs}ms")
            return
        }

        // Under the noise floor: they settled on effectively the same answer
        // twice, which means the subtitle is NOT drifting. Leave it alone.
        if (abs(offsetMs - prev.offsetMs) < SYNC_MIN_OFFSET_DELTA_MS) {
            lastSyncPoint = point
            return
        }

        val m = (offsetMs - prev.offsetMs).toDouble() / span.toDouble()
        val rate = 1.0 / (1.0 - m)
        if (rate.isNaN() || rate !in SYNC_RATE_MIN..SYNC_RATE_MAX) {
            // Not a rate error — more likely they simply wanted it shifted twice.
            // Keep the newer point; the next one may still form a sane pair.
            lastSyncPoint = point
            Log.d(TAG, "subs: sync pair implied rate ${"%.4f".format(rate)} — out of band, ignored")
            return
        }

        subtitleRate = rate
        subtitleOffsetMs = (rate * (prev.offsetMs - prev.positionMs * m))
            .toLong().coerceIn(-60_000L, 60_000L)
        lastSyncPoint = null                               // measured; start over
        persistTiming()
        renderActiveCues()

        // The one sentence the viewer ever sees about any of this. It names no
        // mechanism — but it DOES name the way out, because something that
        // changes your subtitles on its own has to be undoable by someone who
        // never asked for it and does not know what it did.
        Log.d(TAG, "subs: measured drift from 2 sync points — " +
                "rate=${"%.5f".format(rate)} offset=${subtitleOffsetMs}ms " +
                "(p1=${prev.positionMs} o1=${prev.offsetMs}, p2=$positionMs o2=$offsetMs)")
    }

    /**
     * Cue text with its markup removed, line breaks kept.
     *
     * VTT and SRT carry inline tags (<i>, <b>, <v Speaker>, <c.yellow>) and some
     * files carry ASS override blocks. None of it was ever handled, so a line
     * written "<i>[theme music]</i>" reached the screen with its angle brackets
     * intact. Stripped at parse time so there is ONE copy of the rule and the
     * caption layer and the by-ear prompt cannot disagree about what a line says.
     *
     * Entities are decoded before "&amp;", not after: the other way round turns
     * a literal "&amp;lt;" into "<".
     */
    protected fun stripCueMarkup(s: String): String =
        s.replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\{[^}]*\\}"), "")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .trim()

    /** One line on one line: the prompt has no room for a cue's own breaks. */
    protected fun cueLineText(s: String): String =
        stripCueMarkup(s).replace(Regex("\\s+"), " ").trim()

    /**
     * Open the sequence-search screen.
     *
     * DOES NOT PAUSE. The viewer may still need to hear the line they are about
     * to search for; the pause happens when they engage the box, which is the
     * moment they have heard it. And it does not route through
     * closeAllSubtitleUi, which would restore setControllerAutoShow(true) and
     * let the control bar steal focus out from under the panel.
     */
    protected fun openFixSeqScreen() {
        if (fixSeqMatcher == null) {
            showToast("Turn a subtitle on first \u2014 there is nothing to search.")
            return
        }
        subMenuOpen = false
        pickerOpen = false
        fixSeqQuery = ""
        fixSeqPhase = FixSeqPhase.Searching(CueMatcher.Status.TOO_SHORT, emptyList(), null)
        fixSeqOpen = true
        claimRemoteForSubtitleUi()
        Log.e(TAG, "subs: sequence search opened at ${subtitleOffsetMs}ms")
    }

    /**
     * The "pause the second I engage the search bar" hook.
     *
     * Everything this screen computes rests on fixSeqPausedPosMs, and this is
     * where it is taken. pausedForPicker is the existing "we paused it, so we
     * may resume it" token, so closing can never restart a film the viewer had
     * stopped themselves.
     */
    protected fun engageFixSeqSearch() {
        val p = player ?: return
        fixSeqPausedPosMs = p.currentPosition
        if (p.playWhenReady) {
            p.playWhenReady = false
            pausedForPicker = true
        }
        // Re-asserted rather than assumed: this is the exact moment the
        // controller would otherwise appear, and the press that follows is meant
        // for the keyboard.
        claimRemoteForSubtitleUi()
        // A FRESH FIELD, the way the media search bar behaves. Engaging with
        // the previous query still in it meant backspacing a line you had
        // already found before you could type the one you just heard — and on a
        // remote that is a dozen presses before the first useful keystroke.
        //
        // Clearing re-derives the phase on its own, which is why the refresh
        // below is the other branch rather than a line after it: with no query
        // there are no rows whose shift could need recomputing.
        if (fixSeqQuery.isNotEmpty()) {
            onFixSeqQuery("")
        } else {
            // The anchor just moved, so every listed row now implies a
            // different shift. Leaving the old numbers on screen would have
            // them describing a position the picture is no longer at.
            refreshFixSeqShifts()
        }
        Log.e(TAG, "subs: sequence search engaged, playhead parked at ${fixSeqPausedPosMs}ms")
    }

    /**
     * Rank on a background dispatcher, debounced, cancel-and-relaunch.
     *
     * NOT FOR COST -- a full re-rank is well under a millisecond. The caption
     * ticker already recomposes this tree about twelve times a second and the
     * button glows are animating; the debounce keeps keystroke work out of that.
     * CueMatcher reuses its scratch rows across candidates and is explicitly not
     * thread safe, so this must stay ONE cancellable job.
     */
    protected fun onFixSeqQuery(text: String) {
        fixSeqQuery = text
        fixSeqJob?.cancel()
        val matcher = fixSeqMatcher ?: return
        val pos = fixSeqPausedPosMs
        fixSeqJob = lifecycleScope.launch {
            delay(120)
            val phase = withContext(Dispatchers.Default) {
                buildFixSeqPhase(matcher, text, pos)
            }
            fixSeqPhase = phase
        }
    }

    /** Everything a row shows, computed off the UI thread. */
    protected fun buildFixSeqPhase(
        matcher: CueMatcher,
        text: String,
        posMs: Long,
    ): FixSeqPhase.Searching {
        val ranking = matcher.rank(text, posMs, subtitleRate)
        val queryTokens = CueMatcher.normalize(text).split(' ').filter { it.isNotEmpty() }
        val rows = ranking.hits.map { hit ->
            val c = hit.candidate
            val implied = (posMs - FIXSEQ_LEAD_MS - c.startMs * subtitleRate).toLong()
            val repeats = matcher.identicalCount(c)
            FixSeqRow(
                cueStartMs = c.startMs,
                text = c.text,
                tailText = c.tailText,
                // Only when it is close enough to be the same exchange. Further
                // back it is from another scene and would mislead rather than
                // disambiguate.
                prevText = parsedCues.getOrNull(c.index - 1)
                    ?.takeIf { c.startMs - it.endMs < 6_000L }
                    ?.let { cueLineText(it.cue.text?.toString() ?: "") }
                    ?.takeIf { it.isNotBlank() },
                timeLabel = fmtClock(c.startMs),
                shiftLabel = fixSeqShiftLabel(implied),
                identicalCount = repeats,
                tooFar = implied < -60_000L || implied > 60_000L,
                matchTokens = queryTokens,
            )
        }
        val worst = rows.maxOfOrNull { it.identicalCount } ?: 0
        val hint = if (ranking.status == CueMatcher.Status.WEAK && worst > 3)
            "$worst lines read the same \u2014 type the words just before or after it too."
        else null
        return FixSeqPhase.Searching(ranking.status, rows, hint)
    }

    /** What picking a row would do, in the vocabulary the rest of the UI uses. */
    protected fun fixSeqShiftLabel(impliedOffsetMs: Long): String {
        val delta = impliedOffsetMs.coerceIn(-60_000L, 60_000L) - subtitleOffsetMs
        return when {
            delta == 0L -> "no change"
            delta > 0 -> "moves ${fmtOffsetSecs(delta)}s later"
            else -> "moves ${fmtOffsetSecs(delta)}s sooner"
        }
    }

    protected fun fmtOffsetSecs(ms: Long): String =
        String.format(java.util.Locale.US, "%.2f", abs(ms) / 1000.0)
            .trimEnd('0').trimEnd('.')

    /** A cue's own place in the subtitle file, for telling repeats apart. */
    protected fun fmtClock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }

    /**
     * Move the picture, and move the measurement with it.
     *
     * USES seekBy's RETURN VALUE, which is the clamped landing position: near
     * t=0 a rewind silently stops at zero, and an offset computed from where we
     * ASKED to be rather than where we are would be wrong by the difference.
     */
    protected fun fixSeqSeek(deltaMs: Long) {
        val p = player ?: return
        playerView?.hideController()
        fixSeqPausedPosMs = ExoPlayerConfig.seekBy(p, deltaMs)
        refreshFixSeqShifts()
    }

    /**
     * Restate what each listed row would do, from the current anchor.
     *
     * THE RANKING IS LEFT ALONE. Re-ranking would reorder rows under the
     * viewer's thumb between deciding and pressing, which is a silently wrong
     * answer. Only the consequence of each row changes, never which rows there
     * are or what order they are in.
     */
    protected fun refreshFixSeqShifts() {
        val current = fixSeqPhase as? FixSeqPhase.Searching ?: return
        fixSeqPhase = current.copy(
            rows = current.rows.map { row ->
                val implied =
                    (fixSeqPausedPosMs - FIXSEQ_LEAD_MS - row.cueStartMs * subtitleRate).toLong()
                row.copy(
                    shiftLabel = fixSeqShiftLabel(implied),
                    tooFar = implied < -60_000L || implied > 60_000L,
                )
            }
        )
    }

    /**
     * BACK out of the search box: give the picture back.
     *
     * ONLY WHAT WE STOPPED. pausedForPicker is set by engageFixSeqSearch and
     * only when the film was actually running at the time, so someone who had
     * paused it themselves before opening the box gets it back paused -- the
     * same contract every other close path in here honours.
     *
     * resumePlaybackSafely rather than a bare playWhenReady, because this panel
     * can sit open for a minute and the player may have ended, failed or gone
     * away in the meantime.
     */
    protected fun disengageFixSeqSearch() {
        if (!pausedForPicker) return
        resumePlaybackSafely("left the search box")
    }

    /**
     * The panel's own play/pause.
     *
     * Clearing pausedForPicker on an explicit resume closes a hazard the timing
     * screen still carries: engage the box (we pause, token set), press play
     * here, back out, and closeAllSubtitleUi would resume against the viewer.
     */
    protected fun toggleFixSeqPlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        if (p.playWhenReady) {
            pausedForPicker = false
        } else {
            fixSeqPausedPosMs = p.currentPosition
        }
        playerView?.hideController()
    }

    /**
     * Solve the offset from the chosen line.
     *
     *     cueStart * rate + offset = pausedPosition - lead
     *
     * ORDER MATTERS: the half-second rewind happens LAST and never feeds back
     * into the arithmetic, which is computed from fixSeqPausedPosMs. And
     * recordSyncPoint means two picks far enough apart also solve the RATE --
     * drift correction for free, from a screen that never mentions drift.
     */
    protected fun applyFixSeqPick(row: FixSeqRow) {
        val p = player ?: return
        val pos = fixSeqPausedPosMs
        val wanted = (pos - FIXSEQ_LEAD_MS - row.cueStartMs * subtitleRate).toLong()

        fixSeqUndoOffsetMs = subtitleOffsetMs
        fixSeqUndoRate = subtitleRate

        val rateBefore = subtitleRate
        val applied = wanted.coerceIn(-60_000L, 60_000L)
        // REPORTED AFTER THE CLAMP, as catchAnchor does: a correction that ran
        // into the ceiling must not be announced as one that landed.
        val movedBy = applied - subtitleOffsetMs
        subtitleOffsetMs = applied
        recordSyncPoint(pos, subtitleOffsetMs)
        persistTiming()
        renderActiveCues()
        fixSeqPhase = FixSeqPhase.Applied(movedBy, drift = subtitleRate != rateBefore)

        // The half second the viewer asked for, so the line plays again with the
        // new timing and they can see it land rather than take our word for it.
        ExoPlayerConfig.seekBy(p, (pos - 500L).coerceAtLeast(0L) - p.currentPosition)

        // UNCONDITIONAL. This used to resume only when the picture had been
        // running at the moment the search box was engaged, which meant anyone
        // who paused first — to read a line, or to find the moment they wanted —
        // got a panel promising "rewound half a second so you can see it land"
        // over a frozen frame. The rewind only means anything if it plays, so
        // the pick starts it whatever state it was in.
        //
        // Through resumePlaybackSafely rather than playWhenReady directly, which
        // is where the guards live: a released player, an activity on its way
        // out or already behind something else, a stream that ended while the
        // panel was open, a playback that already failed. Setting the flag
        // through any of those either does nothing or starts audio under a
        // screen the viewer cannot see to stop.
        val started = resumePlaybackSafely("sequence pick")

        // THEN CHECK IT TOOK. The seek immediately above can land while the
        // player is still settling, and a resume that quietly fails leaves the
        // viewer staring at a still frame with no way to tell whether the pick
        // worked. One re-assertion, logged, so a future regression here is
        // visible in logcat instead of silent.
        //
        // Stands down if anything happened since: the phase object is replaced
        // by Done, by "Not that one" and by backing out, so an unchanged one is
        // proof the viewer is still looking at the result of THIS pick. The
        // Applied panel has no transport of its own, so there is no legitimate
        // pause to fight.
        val phaseAtPick = fixSeqPhase
        lifecycleScope.launch {
            delay(400)
            if (fixSeqPhase !== phaseAtPick) return@launch
            val pl = player ?: return@launch
            if (!pl.playWhenReady) {
                Log.e(TAG, "subs: pick did not start the picture " +
                        "(started=$started, state=${pl.playbackState}) -- re-asserting")
                resumePlaybackSafely("sequence pick re-assert")
            }
        }
        playerView?.hideController()
        Log.e(TAG, "subs: sequence pick cue=${row.cueStartMs}ms at pos=${pos}ms -- " +
                "offset=${subtitleOffsetMs}ms (moved ${movedBy}ms) " +
                "rate=${"%.5f".format(subtitleRate)}")
    }

    /**
     * Put back what the pick replaced.
     *
     * Undo matters more here than on the by-ear screen, where a wrong press was
     * bounded by the lead. A wrong row here can move the subtitles by minutes,
     * and the way back has to be on this screen rather than three menus away
     * under Reset.
     */
    protected fun undoFixSeqPick() {
        subtitleOffsetMs = fixSeqUndoOffsetMs
        subtitleRate = fixSeqUndoRate
        lastSyncPoint = null
        persistTiming()
        renderActiveCues()
        fixSeqPhase = FixSeqPhase.Searching(CueMatcher.Status.TOO_SHORT, emptyList(), null)
        onFixSeqQuery(fixSeqQuery)
        Log.e(TAG, "subs: sequence pick undone, back to ${subtitleOffsetMs}ms")
    }

    /**
     * BACK out of the sequence screen, to the MENU rather than to the film.
     *
     * Searching for a line is a step inside the subtitle flow, not the end of
     * it: someone who backs out of it has abandoned this attempt, not decided
     * they are finished with subtitles. Dropping them into the film means
     * pressing the Subtitles button again to get anywhere, which is the thing
     * the menu exists to avoid.
     *
     * returnToSubtitleMenu restores the playback this screen paused, and only
     * that -- a film the viewer had stopped themselves stays stopped.
     */
    protected fun returnFromFixSeqToMenu() {
        fixSeqOpen = false
        fixSeqPhase = null
        fixSeqQuery = ""
        fixSeqJob?.cancel()
        fixSeqJob = null
        returnToSubtitleMenu()
        Log.e(TAG, "subs: sequence search backed out to the menu")
    }

    /**
     * Done: close the screen and go back to watching.
     *
     * THE ONE PLACE THAT DELIBERATELY OVERRIDES THE pausedForPicker CONTRACT.
     * Every other close path resumes only what it paused itself, which is right
     * for BACK -- someone who had stopped the film themselves should get it back
     * stopped. But Done is pressed from a screen that has just said the picture
     * was rewound half a second so the correction can be seen landing, and a
     * frozen frame makes that untrue. Pressing Done means "I am finished, show
     * me".
     */
    protected fun acceptFixSeqPick() {
        Log.e(TAG, "subs: sequence pick accepted at ${subtitleOffsetMs}ms")
        closeAllSubtitleUi()
        resumePlaybackSafely("sequence pick accepted")
    }

    /**
     * Start the picture again, if starting it is a sane thing to do.
     *
     * FOUR WAYS "just set playWhenReady = true" IS WRONG, and every one of them
     * is reachable from the sequence screen, because that screen can sit open
     * for a minute while somebody types on a remote:
     *
     *   - THE PLAYER IS GONE. onDestroy releases it, and the overlay's callbacks
     *     outlive nothing but they can still be invoked mid-teardown.
     *   - THE ACTIVITY IS LEAVING, or is already behind something else. This is
     *     the one that actually hurts a viewer: audio starting under another
     *     screen, from a player they can no longer see to stop.
     *   - THE STREAM ENDED while the panel was up. playWhenReady does nothing at
     *     STATE_ENDED, so setting it leaves the flag claiming a playback that is
     *     not happening, and the next thing to read it believes the lie.
     *   - PLAYBACK FAILED. Same, plus the error path owns the screen.
     *
     * Returns whether it actually started, so a caller can tell the difference
     * between "resumed" and "declined" rather than assuming.
     */
    protected fun resumePlaybackSafely(reason: String): Boolean {
        val p = player
        val refusal = when {
            p == null -> "player released"
            isFinishing || isDestroyed -> "activity is going away"
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> "activity not visible"
            p.playerError != null -> "playback already failed"
            p.playbackState == Player.STATE_IDLE -> "player idle"
            p.playbackState == Player.STATE_ENDED -> "playback ended"
            else -> null
        }
        if (refusal != null || p == null) {
            Log.e(TAG, "subs: not resuming after $reason -- $refusal")
            return false
        }
        // Cleared so no later close path toggles playback a second time.
        pausedForPicker = false
        p.playWhenReady = true
        Log.e(TAG, "subs: resumed after $reason")
        return true
    }

    /**
     * Timing no longer outlives the playback that set it.
     *
     * This used to write the offset and rate against the title so the next
     * session could restore them. It clears instead, which also wipes values
     * saved by older builds -- otherwise a stale offset from months ago would
     * still be sitting there for any title ever adjusted.
     */
    protected fun persistTiming() {
        if (tmdbId <= 0) return
        subPrefs.edit()
            .remove(tmdbId.toString())
            .remove("${tmdbId}_ratef")
            .remove("${tmdbId}_rate")
            .apply()
    }

    /**
     * The button states itself rather than implying it.
     *
     * The old CC icon showed "off" by dropping to 45% opacity, which at three
     * metres is indistinguishable from "disabled" -- people could not tell
     * whether subtitles were off or the button was.
     */
    /**
     * What the Subtitles button says.
     *
     * THE TRACK, NOT A BOOLEAN. "Subtitles: On" answers a question nobody asked
     * -- the captions are visible on screen, so whether they are on is already
     * known. Which of five English tracks is running is the thing that is not,
     * and it is what you came to this button to change.
     *
     * The track name is tinted rather than appended in the same colour, so the
     * button reads as one label with a value in it, the same shape the menu rows
     * behind it use.
     */
    protected fun updateCcButtonState() {
        val btn = subtitlesButton ?: return
        val label = activeSubtitleLabel()

        val head = "Subtitles"
        val sep = ": "
        val out = android.text.SpannableStringBuilder(head)
            .append(sep)
            .append(label ?: "Off")

        // THE COLON IN PURE WHITE, brighter than either side of it. The label
        // is #DBE4F6 and the value is blue, so the separator is the only part
        // that is not already tinted; at 14sp from across a room it is what
        // makes the button read as "name: value" rather than two words.
        out.setSpan(
            android.text.style.ForegroundColorSpan(0xFFFFFFFF.toInt()),
            head.length,
            head.length + 1,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (label != null) {
            out.setSpan(
                android.text.style.ForegroundColorSpan(0xFF4477FF.toInt()),
                head.length + sep.length,
                out.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        btn.text = out
    }

    protected fun parseVtt(path: String): List<TimedCue> {
        val text = File(path).readText(Charsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n")
        val tsRe = Regex("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})")
        val out = ArrayList<TimedCue>()
        for (block in text.split("\n\n")) {
            val lines = block.trim().split("\n")
            if (lines.isEmpty()) continue
            var idx = -1
            var m: MatchResult? = null
            for ((i, l) in lines.withIndex()) {
                val mm = tsRe.find(l)
                if (mm != null) { idx = i; m = mm; break }
            }
            val g = m?.groupValues ?: continue
            val start = ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[4].toLong()
            val end   = ((g[5].toLong() * 60 + g[6].toLong()) * 60 + g[7].toLong()) * 1000 + g[8].toLong()
            val cueText = stripCueMarkup(lines.drop(idx + 1).joinToString("\n"))
            if (cueText.isEmpty()) continue
            out.add(TimedCue(start, end, Cue.Builder().setText(cueText).build()))
        }
        // SORTED, because pickAnchorCue walks this list and stops at the first
        // cue past its horizon. VTT files are chronological in practice, so this
        // costs nothing and removes the assumption.
        return out.sortedBy { it.startMs }
    }

    protected fun showToast(msg: String) {
        toastText = msg
        toastJob?.cancel()
        toastJob = lifecycleScope.launch { delay(5_000); toastText = null }
    }
}
