package com.panelstash.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asura Scans serves "Revenge of the Baskervilles" in ~800 x 13,500 strips, which used to decode as
 * one screen-width software bitmap each: about 70 MB per page, so a chapter of them ran the phone
 * out of memory around page 13 and the reader could not get past it.
 */
class PageBandsTest {

    @Test
    fun `a 13k strip is cut into bands of about a screen`() {
        val aspect = 800f / 13_475f
        val bands = pageBandCount(widthPx = 1080, heightPx = 2340, aspect = aspect, measured = true)
        assertTrue("expected bands, got $bands", bands > 1)
        // No single band comes near the texture limit, so each one can be a hardware bitmap.
        val bandHeight = 1080 / (aspect * bands)
        assertTrue("band too tall: $bandHeight px", bandHeight <= 4_096f)
    }

    @Test
    fun `an ordinary page stays one bitmap`() {
        val bands = pageBandCount(widthPx = 1080, heightPx = 2340, aspect = 800f / 1_200f, measured = true)
        assertEquals(1, bands)
    }

    @Test
    fun `a page nobody measured is banded once it is several screens tall`() {
        assertEquals(1, pageBandCount(1080, 2340, aspect = 1080f / 3_000f, measured = false))
        assertTrue(pageBandCount(1080, 2340, aspect = 1080f / 6_000f, measured = false) > 1)
    }

    @Test
    fun `an unknown shape is never banded`() {
        assertEquals(1, pageBandCount(1080, 2340, aspect = null, measured = false))
    }
}
