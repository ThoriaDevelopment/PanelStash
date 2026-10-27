package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterOrderingTest {

    private fun chapter(number: String, pages: Int = 20, external: Boolean = false) = SourceChapter(
        remoteId = "id-$number-$pages",
        numberText = number,
        sortKey = chapterSortKey(number),
        title = null,
        language = "en",
        pageCount = pages,
        externalOnly = external,
    )

    @Test
    fun `decimal chapter numbers sort between integers`() {
        val ordered = dedupeChapters(
            listOf(chapter("12"), chapter("10.5"), chapter("2"), chapter("10")),
        ).map { it.numberText }
        assertEquals(listOf("2", "10", "10.5", "12"), ordered)
    }

    @Test
    fun `non numeric chapters sort last and keep their text`() {
        val ordered = dedupeChapters(listOf(chapter("Extra"), chapter("3"))).map { it.numberText }
        assertEquals(listOf("3", "Extra"), ordered)
    }

    @Test
    fun `non numeric chapters have no sort key`() {
        assertNull(chapterSortKey("Extra"))
        assertEquals(10.5, chapterSortKey("10.5")!!, 0.0001)
    }

    @Test
    fun `duplicate numbers collapse to the copy with the most pages`() {
        val collapsed = dedupeChapters(listOf(chapter("11", 18), chapter("11", 22)))
        assertEquals(1, collapsed.size)
        assertEquals(22, collapsed.single().pageCount)
    }

    @Test
    fun `external chapters never reach the chapter list`() {
        val collapsed = dedupeChapters(listOf(chapter("1", 0, external = true), chapter("2", 20)))
        assertEquals(listOf("2"), collapsed.map { it.numberText })
    }
}
