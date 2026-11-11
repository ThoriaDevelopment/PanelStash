package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sources write a chapter's label in a handful of shapes, and some chapters carry no number at
 * all. Every one of them has to become a number text and a place in the reading order, because a
 * chapter that cannot be placed is a chapter a reader cannot reach.
 */
class ChapterLabelTest {

    private fun label(raw: String): ChapterLabel = chapterLabel(raw)!!

    @Test
    fun `a bare number is the chapter number`() {
        assertEquals(ChapterLabel("12", 12.0), label("12"))
        assertEquals(ChapterLabel("10.5", 10.5), label("10.5"))
        assertEquals(ChapterLabel("7", 7.0), label("#7"))
    }

    @Test
    fun `the word chapter in front of the number is dropped`() {
        assertEquals("186", label("Chapter 186").numberText)
        assertEquals(186.0, label("Chapter 186").sortKey!!, 0.0001)
        assertEquals("3", label("Ch. 3").numberText)
        assertEquals("2.5", label("chapter 2.5").numberText)
    }

    @Test
    fun `a prologue sorts in front of chapter 1`() {
        assertEquals(ChapterLabel("Prologue", 0.0), label("Prologue"))
        assertEquals("Prologue", label("PROLOGUE").numberText)
        assertEquals(0.0, label("Prelude").sortKey!!, 0.0001)
    }

    @Test
    fun `side stories and extras sort after the numbered run`() {
        val side = label("Side Story 2")
        assertEquals("Side Story 2", side.numberText)
        assertNull(side.sortKey)

        assertEquals("Side Story", label("side-story").numberText)
        assertEquals("Epilogue", label("Epilogue").numberText)
        assertEquals("Extras", label("Extras").numberText)
        assertNull(label("Extras").sortKey)
    }

    @Test
    fun `a label that names no chapter is skipped`() {
        assertNull(chapterLabel(""))
        assertNull(chapterLabel("   "))
        assertNull(chapterLabel("Volume 2"))
        assertNull(chapterLabel("Display Info"))
    }

    @Test
    fun `a label taken from a path finds the chapter inside it`() {
        // Mgeko and Asura address a chapter by slug only when the API omits its number.
        assertEquals("Prologue", label("the god of wealth prologue").numberText)
        assertEquals("40", label("9m32 bandit king chapter 40 eng li").numberText)
    }
}
