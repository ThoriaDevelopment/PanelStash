package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameMappingTest {

    private val json = Network.json

    /** Captured from /_next/data/.../latest.json on 2026-10-28 and cut down to the fields we read. */
    private val cataloguePayload = """
        {"allSeries":[
          {"series_id":131,"title":"Star-Fostered Swordmaster","likes":577,"status":"Ongoing",
           "cover":"thumbnail.webp","type":"Manhwa","last_edit":1766499141,
           "categories":["Action","Fantasy"],
           "chapters":[{"chapter":"143.00","release_date":1793198288,"token":"07cd5191afc269fb"}]},
          {"series_id":57,"title":"IRL Quest","likes":905,"status":"Hiatus",
           "cover":"thumbnail.webp","type":"Manhwa","last_edit":1767616340,
           "categories":["Drama"],
           "chapters":[{"chapter":"215.00","release_date":1793180558,"token":"01689f98f5d69203"}]},
          {"series_id":77,"title":"Solo Forging","likes":300,"status":"Cancelled",
           "cover":"thumbnail.webp","type":"Manhua","last_edit":1700000000,
           "categories":["Action","School Life"],
           "chapters":[{"chapter":"12.00","release_date":1700000100,"token":"aa11bb22"}]},
          {"series_id":12,"title":"A Web Novel","likes":9,"status":"Ongoing","type":"Web Novel"},
          {"series_id":13,"title":"A Novel","likes":9,"status":"Ongoing","type":"Novel"}
        ]}
    """.trimIndent()

    private val seriesPayload = """
        {"series":{"series_id":131,"title":"Star-Fostered Swordmaster",
          "description":"<p>A street urchin of the slums.</p><p>He wants a sword.</p>",
          "cover":"thumbnail.webp","type":"Manhwa","status":"Ongoing","year":2024},
         "chapters":[
          {"chapter_id":12378,"chapter":"143.00","title":"","token":"07cd5191afc269fb","release_date":1793198288},
          {"chapter_id":12374,"chapter":"10.50","title":"","token":"93836edf1a5f97af","release_date":1793124876},
          {"chapter_id":12370,"chapter":"10.50","title":"","token":"56fb5aabdbff0097","release_date":1793121301},
          {"chapter_id":12360,"chapter":"","title":"","token":"","release_date":0}
         ]}
    """.trimIndent()

    private val chapterPayload = """
        {"chapter":{"series_id":131,"chapter_id":12378,"chapter":"143.00","token":"07cd5191afc269fb",
          "images":{
            "10":{"name":"SFS-143-10.jpg","width":800,"height":12000},
            "2":{"name":"SFS-143-02.jpg","width":800,"height":16000},
            "0":{"name":"SFS-143 cover.jpg","width":1778,"height":1000}
          }}}
    """.trimIndent()

    private fun catalogue(): List<FlameSeriesDto> =
        json.decodeFromString<FlameCataloguePage>(cataloguePayload).allSeries

    @Test
    fun `catalogue drops prose and orders by the newest chapter`() {
        val rows = catalogue().flameCatalogue(BrowseSort.LATEST, BrowseFilter.None)

        assertEquals(
            listOf("Star-Fostered Swordmaster", "IRL Quest", "Solo Forging"),
            rows.map { it.title },
        )
        assertEquals("131", rows.first().remoteId)
        assertEquals(
            "https://cdn.flamecomics.xyz/uploads/images/series/131/thumbnail.webp",
            rows.first().coverUrl,
        )
        // Descriptions live on the series page only, so a browse row carries none.
        assertNull(rows.first().description)
    }

    @Test
    fun `popular is ordered by likes, not by release`() {
        val rows = catalogue().flameCatalogue(BrowseSort.POPULAR, BrowseFilter.None)

        assertEquals(listOf("IRL Quest", "Star-Fostered Swordmaster", "Solo Forging"), rows.map { it.title })
    }

    @Test
    fun `status filter accepts the site's titlecase names`() {
        val rows = catalogue()

        assertEquals(
            listOf("IRL Quest"),
            rows.flameCatalogue(BrowseSort.LATEST, BrowseFilter(status = StatusFilter.HIATUS)).map { it.title },
        )
        assertEquals(
            listOf("Solo Forging"),
            rows.flameCatalogue(BrowseSort.LATEST, BrowseFilter(status = StatusFilter.CANCELLED)).map { it.title },
        )
        assertTrue(rows.flameCatalogue(BrowseSort.LATEST, BrowseFilter(status = StatusFilter.COMPLETED)).isEmpty())
    }

    @Test
    fun `search ignores case and puts prefix matches first`() {
        val rows = catalogue()

        assertEquals(listOf("Solo Forging"), rows.flameSearch("SOLO").map { it.title })
        assertEquals(listOf("Star-Fostered Swordmaster"), rows.flameSearch("sword").map { it.title })
        // A blank query is a browse page, not an empty screen.
        assertEquals(3, rows.flameSearch("  ").size)
        assertTrue(rows.flameSearch("nothing like this").isEmpty())
    }

    @Test
    fun `genre filter uses the categories the catalogue publishes`() {
        val rows = catalogue()
        val index = rows.flameGenreIndex()

        assertEquals(
            listOf("Star-Fostered Swordmaster", "Solo Forging"),
            rows.flameCatalogue(BrowseSort.LATEST, BrowseFilter.None, "Action", index).map { it.title },
        )
        assertEquals(
            listOf("Star-Fostered Swordmaster", "IRL Quest"),
            rows.flameCatalogue(
                sort = BrowseSort.LATEST,
                filter = BrowseFilter(excludedTagIds = setOf("School Life")),
                tagId = null,
                categories = index,
            ).map { it.title },
        )
        assertTrue(rows.flameCatalogue(BrowseSort.LATEST, BrowseFilter.None, "Isekai", index).isEmpty())
        // A tag with no map to resolve it matches nothing, which is why browse only passes one
        // after the genre index has actually loaded.
        assertTrue(rows.flameCatalogue(BrowseSort.LATEST, BrowseFilter.None, "Action").isEmpty())
    }

    @Test
    fun `the browse route names its list differently and carries the categories`() {
        // /latest calls the list allSeries; /browse calls it series. Reading the wrong key is how
        // the genre list first came back empty against the live site.
        val payload = """
            {"series":[{"series_id":5,"title":"A Categorised Manhwa","type":"Manhwa",
              "categories":["Action","Drama"]}],"initialFilters":{"status":["all"]}}
        """.trimIndent()

        val rows = json.decodeFromString<FlameBrowsePage>(payload).series

        assertEquals(listOf("Action", "Drama"), rows.single().categories)
        assertEquals(listOf("Action", "Drama"), rows.flameGenreNames().map { it.id })
        assertEquals(setOf("Action", "Drama"), rows.flameGenreIndex()[5])
    }

    @Test
    fun `genre names are deduped and sorted`() {
        assertEquals(
            listOf("Action", "Drama", "Fantasy", "School Life"),
            catalogue().flameGenreNames().map { it.id },
        )
    }

    @Test
    fun `chapters are scoped to the series and lose their padding`() {
        val page = json.decodeFromString<FlameSeriesPage>(seriesPayload)
        val chapters = page.chapters.mapNotNull { it.toSourceChapter(131) }

        // The mapper keeps the site's order; dedupe collapses the duplicate and sorts ascending.
        assertEquals(listOf("10.5", "143"), dedupeChapters(chapters).map { it.numberText })
        assertEquals("131|07cd5191afc269fb", chapters.first().remoteId)
        assertEquals(143.0, chapters.first().sortKey!!, 0.0)
        assertEquals(10.5, chapters[1].sortKey!!, 0.0)
        // A chapter with no number and no token cannot be opened, so it is dropped.
        assertEquals(3, chapters.size)
    }

    @Test
    fun `series detail keeps the synopsis and the year`() {
        val series = json.decodeFromString<FlameSeriesPage>(seriesPayload).series!!
        val manhwa = series.toSourceManhwa()

        assertEquals("131", manhwa.remoteId)
        assertEquals("Star-Fostered Swordmaster", manhwa.title)
        assertEquals("A street urchin of the slums.\nHe wants a sword.", manhwa.description)
        assertEquals(2024, manhwa.year)
        assertEquals("Ongoing", manhwa.status)
        assertEquals("https://cdn.flamecomics.xyz/uploads/images/series/131/thumbnail.webp", manhwa.coverUrl)
    }

    @Test
    fun `the series page's categories become genres`() {
        val payload = """
            {"series":{"series_id":9,"title":"Categorised","type":"Manhwa",
              "categories":["Fantasy","Action","Fantasy","  "]},"chapters":[]}
        """.trimIndent()

        val manhwa = json.decodeFromString<FlameSeriesPage>(payload).series!!.toSourceManhwa()

        // Duplicates and blanks would otherwise become duplicate rail subtitles.
        assertEquals(listOf("Fantasy", "Action"), manhwa.genres.map { it.name })
        assertEquals("Fantasy", manhwa.genres.first().id)
    }

    @Test
    fun `pages follow their numeric key, not their string order`() {
        val chapter = json.decodeFromString<FlameChapterPage>(chapterPayload).chapter!!
        val pages = flamePageImages(131, "07cd5191afc269fb", chapter.images)

        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        assertEquals(
            listOf(
                "https://cdn.flamecomics.xyz/uploads/images/series/131/07cd5191afc269fb/SFS-143%20cover.jpg",
                "https://cdn.flamecomics.xyz/uploads/images/series/131/07cd5191afc269fb/SFS-143-02.jpg",
                "https://cdn.flamecomics.xyz/uploads/images/series/131/07cd5191afc269fb/SFS-143-10.jpg",
            ),
            pages.map { it.url },
        )
        assertEquals(1000, pages.first().height)
        assertEquals(16000, pages[1].height)
    }

    @Test
    fun `chapter ids round-trip through the one string the contract carries`() {
        val parsed = parseFlameChapterId("131|07cd5191afc269fb")

        assertNotNull(parsed)
        assertEquals(131, parsed!!.first)
        assertEquals("07cd5191afc269fb", parsed.second)
        assertNull(parseFlameChapterId("131"))
        assertNull(parseFlameChapterId("not-a-number|token"))
        assertNull(parseFlameChapterId("131|"))
    }
}
