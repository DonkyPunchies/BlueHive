package com.example.bluehive.utilities

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.bluehive.auth.SessionManager

/**
 * PLAYBACK PREFERENCES — settings that change how a streaming source is opened.
 *
 * PER PROFILE, NOT PER DEVICE. Each profile's choice is stored under its own
 * key, so one viewer turning the prompt on does not turn it on for everyone
 * sharing the box. A profile that has never chosen gets [DEFAULT_ASK_EACH_TIME],
 * which is what makes every newly created profile start on the built-in player.
 *
 * WHY THE VALUE IS COMPOSE STATE. The details screens read it during
 * composition. A plain SharedPreferences read would give the right answer but
 * would not recompose when the overlay flips it, so a screen already on-stage
 * when the setting changes would keep the old behaviour until it was rebuilt.
 * Backing it with mutableStateOf makes the change visible immediately, and the
 * SharedPreferences write behind it is what makes it survive a restart.
 */
object PlaybackPrefs {

    private const val PREFS = "bluehive_playback"
    private const val KEY_PREFIX = "ask_player_each_time_"

    /**
     * What a profile gets before it has ever chosen: go STRAIGHT to the built-in
     * player, no prompt.
     *
     * The prompt is the better teacher and the worse default. It is worth seeing
     * once, but it stands between the viewer and playback on every single
     * episode, and the overwhelming majority of the time the answer is the
     * built-in player — it is lighter, it resumes, and it does not keep a browser
     * engine resident for the whole title. Opening the site directly stays one
     * setting away for the cases where extraction cannot cope.
     */
    const val DEFAULT_ASK_EACH_TIME = false

    private var store: SharedPreferences? = null

    /** Which profile [askPlayerEachTime] currently reflects. */
    private var loadedProfileId: Int? = null

    /**
     * Whether choosing a backend-extracted streaming source raises the
     * "our player, or open the site directly?" prompt.
     */
    var askPlayerEachTime by mutableStateOf(DEFAULT_ASK_EACH_TIME)
        private set

    /**
     * Guarded rather than bare: this runs from Application.onCreate and from
     * screen composition, and on at least one of those paths SessionManager may
     * not be initialised yet. -1 is the same "no profile" value SessionManager
     * itself returns, so an early call simply reads the signed-out slot instead
     * of taking the process down.
     */
    private fun currentProfileId(): Int =
        runCatching { SessionManager.get().lastProfileId }.getOrDefault(-1)

    /**
     * Read the active profile's setting. Cheap and idempotent — re-reads only
     * when the profile has actually changed, so calling it on every details
     * screen entry (which is how a profile switch gets picked up) costs nothing.
     */
    fun load(context: Context) {
        val pid = currentProfileId()
        if (store != null && loadedProfileId == pid) return
        val s = store ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { store = it }
        loadedProfileId = pid
        askPlayerEachTime = s.getBoolean(KEY_PREFIX + pid, DEFAULT_ASK_EACH_TIME)
    }

    /**
     * Takes a Context rather than relying on [load] having run, so a write can
     * never be silently dropped into a null store — that failure mode looks
     * exactly like "the setting does not save" and is miserable to track down.
     */
    fun setAskPlayerEachTime(context: Context, value: Boolean) {
        load(context)
        askPlayerEachTime = value
        store?.edit()?.putBoolean(KEY_PREFIX + currentProfileId(), value)?.apply()
    }
}
