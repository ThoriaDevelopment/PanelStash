package com.panelstash.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesTest {

    @Test
    fun `tap scroll offers 300, 500 and 700 ms`() {
        assertEquals(listOf(300, 500, 700), TapScroll.entries.map { it.durationMs })
        assertEquals("300 ms", TapScroll.NORMAL.label)
        assertEquals(TapScroll.RELAXED, TapScroll.fromName("RELAXED"))
        assertEquals(TapScroll.NORMAL, TapScroll.fromName("not-a-value"))
    }

    @Test
    fun `the pure canvas is named for the theme it is in`() {
        assertEquals("Pure black", CanvasStyle.OLED.labelFor(ThemeMode.DARK))
        assertEquals("Pure white", CanvasStyle.OLED.labelFor(ThemeMode.LIGHT))
        assertEquals("Glass", CanvasStyle.GLASS.labelFor(ThemeMode.LIGHT))
    }

    @Test
    fun `the theme mode survives an unknown stored value`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromName("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromName(null))
    }
}
