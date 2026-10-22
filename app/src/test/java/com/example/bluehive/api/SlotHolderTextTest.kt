package com.example.bluehive.api

import com.example.bluehive.api.SlotHolder.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotHolderTextTest {

    private val leaving  = SlotHolder("onn onn. 4K Streaming Box", State.LEAVING, "poo shiesty", "avatar13", freesInSeconds = 540)
    private val choosing = SlotHolder("Amazon AFTSS", State.CHOOSING)
    private val browsing = SlotHolder("TV", State.BROWSING, "Rhona", "avatar2")
    private val watching = SlotHolder("TV", State.WATCHING, "marlan", null, media = "movie", endsInSeconds = 2400)

    @Test fun `order is the design's - leaving, picker, browsing, watching`() {
        assertEquals(
            listOf(leaving, choosing, browsing, watching),
            SlotHolderText.sorted(listOf(watching, browsing, choosing, leaving)),
        )
    }

    @Test fun `two leaving TVs - the one freeing first comes first`() {
        val later = leaving.copy(freesInSeconds = 800)
        assertEquals(listOf(leaving, later), SlotHolderText.sorted(listOf(later, leaving)))
    }

    @Test fun `films ending sooner come first, unknown ends last`() {
        val soon    = watching.copy(endsInSeconds = 300)
        val unknown = watching.copy(endsInSeconds = null)
        assertEquals(listOf(soon, watching, unknown), SlotHolderText.sorted(listOf(unknown, watching, soon)))
    }

    @Test fun `statuses count down from when the list arrived`() {
        assertEquals("frees in 9 min", SlotHolderText.status(leaving, 0))
        assertEquals("frees in 8 min", SlotHolderText.status(leaving, 60_000))
        assertEquals("frees in under a minute", SlotHolderText.status(leaving, 500_000))
        assertEquals("freeing now", SlotHolderText.status(leaving, 600_000))
        assertEquals("movie ends in about 40 min", SlotHolderText.status(watching, 0))
        assertEquals("episode ends in about 40 min", SlotHolderText.status(watching.copy(media = "tv"), 0))
        assertEquals("watching a movie", SlotHolderText.status(watching.copy(endsInSeconds = null), 0))
        assertEquals("watching a show", SlotHolderText.status(watching.copy(media = "tv", endsInSeconds = null), 0))
        assertEquals("choosing a profile", SlotHolderText.status(choosing, 0))
        assertEquals("browsing", SlotHolderText.status(browsing, 0))
    }

    @Test fun `minutes round up so a slot is never promised early`() {
        assertEquals("frees in 10 min", SlotHolderText.status(leaving.copy(freesInSeconds = 541), 0))
    }

    @Test fun `who - the picker has no profile, an unknown one is someone`() {
        assertEquals("poo shiesty", SlotHolderText.who(leaving))
        assertEquals("No profile yet", SlotHolderText.who(choosing))
        assertEquals("Someone", SlotHolderText.who(browsing.copy(profileName = null, avatar = null)))
    }

    @Test fun `device names lose the repeated maker, Amazon codes say what they are`() {
        assertEquals("onn. 4K Streaming Box", SlotHolderText.device("onn onn. 4K Streaming Box"))
        assertEquals("Fire TV (AFTSS)", SlotHolderText.device("Amazon AFTSS"))
        assertEquals("NVIDIA SHIELD Android TV", SlotHolderText.device("NVIDIA SHIELD Android TV"))
        assertEquals("TV", SlotHolderText.device("  "))
    }

    @Test fun `banner counts down the TV that left the app`() {
        val b = SlotHolderText.banner(SlotHolders(listOf(watching, leaving), 900, 0), 0)
        assertEquals("Next slot frees in about 9 min", b.title)
        assertEquals("onn. 4K Streaming Box left the app, so its slot is only held for 15 minutes.", b.detail)
        assertEquals(0.4f, b.progress!!, 0.001f)
    }

    @Test fun `banner never counts down a film`() {
        val b = SlotHolderText.banner(SlotHolders(listOf(watching, browsing), 900, 0), 0)
        assertEquals("No slot is due to free yet", b.title)
        assertNull(b.progress)
    }

    @Test fun `one slot is the only one, not all 1`() {
        assertEquals("The only streaming slot is in use", SlotHolderText.headline(1))
        assertEquals("All 4 streaming slots are in use", SlotHolderText.headline(4))
        assertEquals("All streaming slots are in use", SlotHolderText.headline(null))
        assertEquals(
            "You cannot enter the app right now because the only streaming slot on this " +
                "account is occupied. Slots are active instances of another person using the " +
                "app at this moment. Please wait for it to free up, then tap Refresh to try again.",
            SlotHolderText.body(1, afterIdleRelease = false),
        )
        assertEquals(
            "You cannot enter the app right now because all 4 streaming slots on this " +
                "account are occupied. Slots are active instances of another person using the " +
                "app at this moment. Please wait for a slot to free up, then tap Refresh to try again.",
            SlotHolderText.body(4, afterIdleRelease = false),
        )
    }

    @Test fun `timed-out copy also counts one slot right`() {
        assertEquals(
            "This TV was left idle, so its streaming slot was released for others on your " +
                "account. The only slot has since been taken. Please wait for it to free up, " +
                "then tap Refresh to try again.",
            SlotHolderText.body(1, afterIdleRelease = true),
        )
    }
}
