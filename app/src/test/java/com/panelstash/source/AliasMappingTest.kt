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
    fun `weeb central associated names become aliases`() {
        val html = """
            <h1>Solo Leveling</h1>
            <li>
              <strong>Associated Name(s)</strong>
              <ul class="list-disc list-inside">
                <li>Only I level up</li>
                <li>Na Honjaman Level-Up</li>
              </ul>
            </li>
        """.trimIndent()
        val series = parseWeebSeries(html, "01J76XY/Solo-Leveling")
        assertEquals(listOf("Only I level up", "Na Honjaman Level-Up"), series.aliases)
    }

    @Test
    fun `a series without associated names has none`() {
        val series = parseWeebSeries("<h1>Solo Leveling</h1>", "01J76XY/Solo-Leveling")
        assertEquals(emptyList<String>(), series.aliases)
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
