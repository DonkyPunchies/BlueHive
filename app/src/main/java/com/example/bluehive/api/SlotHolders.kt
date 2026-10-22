package com.example.bluehive.api

import kotlin.math.ceil

/**
 * One TV holding a streaming slot, as the server's workspace_full 429 lists it
 * (device_sessions.py `_slot_holders`, added 2026-10-22 for v360).
 *
 * The seconds are as of [SlotHolders.receivedAtMs]. The lockout screen counts
 * them down itself between the SSE retries that bring a fresh list.
 */
data class SlotHolder(
    val device:         String,
    val state:          State,
    val profileName:    String? = null,
    val avatar:         String? = null,
    /** LEAVING: seconds left of the Home grace when the list arrived. */
    val freesInSeconds: Int?    = null,
    /** WATCHING: "movie" or "tv", when known. */
    val media:          String? = null,
    /** WATCHING: seconds of it left, when the player reported its length. */
    val endsInSeconds:  Int?    = null,
) {
    enum class State {
        LEAVING, WATCHING, CHOOSING, BROWSING;

        companion object {
            fun of(wire: String?): State? = when (wire) {
                "leaving"  -> LEAVING
                "watching" -> WATCHING
                "choosing" -> CHOOSING
                "browsing" -> BROWSING
                else       -> null
            }
        }
    }
}

/** One 429's list, and when it arrived (SystemClock.elapsedRealtime). */
data class SlotHolders(
    val holders:      List<SlotHolder>,
    val graceSeconds: Int,
    val receivedAtMs: Long,
)

/**
 * Everything the lockout screen says about who holds the slots. Plain Kotlin,
 * so the wording and the order are unit-tested (SlotHolderTextTest).
 */
object SlotHolderText {

    data class Banner(val title: String, val detail: String, val progress: Float?)

    /**
     * Soonest free first, as in the design: TVs that left the app (by time
     * left), then the picker, then browsing, then watching (films ending
     * soonest first). Only a TV that left the app has a time that is certain.
     */
    fun sorted(holders: List<SlotHolder>): List<SlotHolder> =
        holders.sortedWith(
            compareBy<SlotHolder>(
                { rank(it.state) },
                { it.freesInSeconds ?: Int.MAX_VALUE },
                { it.endsInSeconds ?: Int.MAX_VALUE },
            )
        )

    private fun rank(state: SlotHolder.State): Int = when (state) {
        SlotHolder.State.LEAVING  -> 0
        SlotHolder.State.CHOOSING -> 1
        SlotHolder.State.BROWSING -> 2
        SlotHolder.State.WATCHING -> 3
    }

    /** [seconds] as of the list, less the time since it arrived; never below 0. */
    fun remaining(seconds: Int, elapsedMs: Long): Int =
        (seconds - elapsedMs / 1000).coerceAtLeast(0).toInt()

    /** Whole minutes, rounded UP, so the screen never promises a slot early. */
    private fun minutes(seconds: Int): Int = ceil(seconds / 60.0).toInt()

    fun who(h: SlotHolder): String = when {
        h.state == SlotHolder.State.CHOOSING -> "No profile yet"
        h.profileName != null                -> h.profileName
        // Outside this TV's household, or not reported (see _slot_holders).
        else                                 -> "Someone"
    }

    /**
     * The registered name, made readable. The app registers maker + model, so
     * "onn onn. 4K Streaming Box" repeats the maker, and Amazon's model is a
     * code ("Amazon AFTSS").
     */
    fun device(name: String): String {
        val n = name.trim()
        val parts = n.split(' ', limit = 2)
        if (parts.size == 2) {
            val maker = parts[0].trimEnd('.').lowercase()
            val model = parts[1].trim()
            if (maker.isNotEmpty() && model.lowercase().startsWith(maker)) return model
            if (maker == "amazon" && model.startsWith("AFT")) return "Fire TV ($model)"
        }
        return n.ifEmpty { "TV" }
    }

    fun status(h: SlotHolder, elapsedMs: Long): String = when (h.state) {
        SlotHolder.State.LEAVING -> {
            val left = remaining(h.freesInSeconds ?: 0, elapsedMs)
            when {
                left <= 0 -> "freeing now"
                left < 60 -> "frees in under a minute"
                else      -> "frees in ${minutes(left)} min"
            }
        }
        SlotHolder.State.WATCHING -> {
            val what = when (h.media) {
                "movie" -> "movie "
                "tv"    -> "episode "
                else    -> ""
            }
            val ends = h.endsInSeconds?.let { remaining(it, elapsedMs) }
            when {
                ends == null      -> when (h.media) {
                    "movie" -> "watching a movie"
                    "tv"    -> "watching a show"
                    else    -> "watching"
                }
                ends <= 0         -> "${what}ending now"
                ends < 60         -> "${what}ends in under a minute"
                else              -> "${what}ends in about ${minutes(ends)} min"
            }
        }
        SlotHolder.State.CHOOSING -> "choosing a profile"
        SlotHolder.State.BROWSING -> "browsing"
    }

    /**
     * The blue banner. A countdown only for a TV that has left the app: that
     * is the one slot with a known free time (the server's Home grace). Films
     * get an estimate on their own row, never here, because the end of a film
     * is not the end of the slot.
     */
    fun banner(list: SlotHolders, elapsedMs: Long): Banner {
        val next = list.holders
            .filter { it.state == SlotHolder.State.LEAVING }
            .minByOrNull { it.freesInSeconds ?: Int.MAX_VALUE }
            ?: return Banner(
                title    = "No slot is due to free yet",
                detail   = "Every TV using a slot is still in the app.",
                progress = null,
            )
        val grace = list.graceSeconds.coerceAtLeast(1)
        val left  = remaining(next.freesInSeconds ?: 0, elapsedMs)
        val graceMinutes = minutes(grace)
        return Banner(
            title = when {
                left <= 0 -> "Next slot is freeing now"
                left < 60 -> "Next slot frees in under a minute"
                else      -> "Next slot frees in about ${minutes(left)} min"
            },
            detail = "${device(next.device)} left the app, so its slot is only held for " +
                    "$graceMinutes minute${if (graceMinutes == 1) "" else "s"}.",
            progress = (1f - left.toFloat() / grace).coerceIn(0f, 1f),
        )
    }

    /** The headline, in the server's count. One slot is "the only" one, not "all 1". */
    fun headline(limit: Int?): String = when (limit) {
        null -> "All streaming slots are in use"
        1    -> "The only streaming slot is in use"
        else -> "All $limit streaming slots are in use"
    }

    /** The explanation paragraph, worded for the server's count. */
    fun body(limit: Int?, afterIdleRelease: Boolean): String {
        val one = limit == 1
        return if (afterIdleRelease) {
            val taken = when (limit) {
                null -> "All slots have"
                1    -> "The only slot has"
                else -> "All $limit slots have"
            }
            "This TV was left idle, so its streaming slot was released for others " +
                    "on your account. $taken since been taken. Please wait for " +
                    "${if (one) "it" else "one"} to free up, then tap Refresh to try again."
        } else {
            val occupied = when (limit) {
                null -> "all streaming slots on this account are occupied"
                1    -> "the only streaming slot on this account is occupied"
                else -> "all $limit streaming slots on this account are occupied"
            }
            "You cannot enter the app right now because $occupied. Slots are active " +
                    "instances of another person using the app at this moment. Please " +
                    "wait for ${if (one) "it" else "a slot"} to free up, then tap Refresh " +
                    "to try again."
        }
    }
}
