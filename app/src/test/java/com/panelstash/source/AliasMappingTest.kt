package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A source's alternate titles are what let the same series be recognised on another site that
 * names it differently, so each source's published shape is read the way it is actually sent.
 */
class AliasMappingTest {

    @Test
    fun `line separated names are split, trimmed and deduplicated`() {
        assertEquals(
            listOf("Only I Level Up", "Solo Leveling"),
            aliasList("\n Only I Level Up \nSolo Leveling\nSolo Leveling\n\n"),
        )
        assertEquals(emptyList<String>(), aliasList(null))
        assertEquals(emptyList<String>(), aliasList("   \n  "))
    }

    @Test
    fun `flame alternative titles become aliases`() {
        val series = FlameSeriesDto(
            seriesId = 7,
            title = "Solo Leveling",
            altTitles = listOf(" Only I Level Up ", "Solo Leveling", ""),
        )
        assertEquals(listOf("Only I Level Up", "Solo Leveling"), series.toSourceManhwa().aliases)
    }
}
