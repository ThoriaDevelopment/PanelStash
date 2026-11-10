package com.panelstash.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same series is routinely titled differently site to site. Sources publish the alternates,
 * so a match is any name of one title being any name of the other — not a guess at word overlap.
 */
class TitleMatchTest {

    /** MangaDex lists the Asura title among the alternates of the Baskerville one, and back. */
    private val mangaDexNames = listOf(
        "Revenge of the Baskerville Bloodhound",
        "Revenge of the Iron-Blooded Sword Hound",
        "Return of the Iron-Blooded Sword Hound",
        "Regression of the Ironblade Hound",
    )

    @Test
    fun `two titles match through a shared alternate`() {
        assertTrue(sameTitle(mangaDexNames, listOf("Revenge of the Iron-Blooded Sword Hound")))
        assertTrue(sameTitle(mangaDexNames, listOf("Revenge of the Baskerville Bloodhound")))
    }

    @Test
    fun `punctuation and capitalisation do not make a different title`() {
        assertTrue(
            sameTitle(
                listOf("Revenge of the Iron-Blooded Sword Hound"),
                listOf("Revenge Of The Iron Blooded Sword Hound!"),
            ),
        )
    }

    @Test
    fun `a sequel is not the same series`() {
        // The trap a fuzzy score falls into: one name contains the other, but they are two works.
        assertFalse(sameTitle(listOf("Solo Leveling"), listOf("Solo Leveling: Ragnarok")))
        assertFalse(sameTitle(listOf("Solo Leveling"), listOf("Solo Leveling Ragnarok")))
    }

    @Test
    fun `a different series with a similar name does not match`() {
        assertFalse(
            sameTitle(
                listOf("My Daughter is the Final Boss"),
                listOf("My Daughter is a Genius"),
            ),
        )
    }

    @Test
    fun `a blank title matches nothing`() {
        assertFalse(sameTitle(listOf("", "   "), listOf("")))
    }
}
