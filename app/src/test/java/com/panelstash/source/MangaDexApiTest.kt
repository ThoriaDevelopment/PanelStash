package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live API rejects an over-large page size with a 400 rather than clamping it, and a 400
 * used to mean "no chapters" with no visible reason. These assertions keep the request shapes
 * honest without needing a network.
 */
class MangaDexApiTest {

    private fun List<Pair<String, Any>>.values(key: String): List<Any> =
        filter { it.first == key }.map { it.second }

    @Test
    fun `chapter list is read from the manga feed`() {
        assertEquals("/manga/abc-123/feed", MangaDexApi.chapterFeedPath("abc-123"))
    }

    @Test
    fun `chapter page size stays within what the feed accepts`() {
        val limit = MangaDexApi.chapterFeedParams("en", 0).single { it.first == "limit" }.second as Int
        assertTrue("feed page size $limit must be in 1..500", limit in 1..500)
    }

    @Test
    fun `chapter paging stays under the offset ceiling and does not repeat a page`() {
        val offsets = generateSequence(0) { it + MangaDexApi.FEED_PAGE_SIZE }
            .takeWhile { it < MangaDexApi.MAX_OFFSET }
            .toList()
        assertTrue(MangaDexApi.MAX_OFFSET <= 10_000)
        assertEquals(offsets.size, offsets.distinct().size)
    }

    @Test
    fun `chapter feed asks for one language, one offset, and keeps external chapters`() {
        val params = MangaDexApi.chapterFeedParams("en", 500)
        assertEquals(listOf<Any>("en"), params.values("translatedLanguage[]"))
        assertEquals(listOf<Any>(500), params.values("offset"))
        // Licensed English chapters live behind externalUrl; filtering them out makes a title
        // look as if it has no English translation at all.
        assertTrue(params.none { it.first == "includeExternalUrl" })
    }

    @Test
    fun `browse returns manhwa only, readable in english`() {
        val params = MangaDexApi.browseParams(BrowseSort.LATEST, null, BrowseFilter.None, 0, 30)
        assertEquals(listOf<Any>("ko"), params.values("originalLanguage[]"))
        assertEquals(listOf<Any>("en"), params.values("availableTranslatedLanguage[]"))
        assertEquals(listOf<Any>("safe", "suggestive"), params.values("contentRating[]"))
        assertEquals(listOf<Any>("true"), params.values("hasAvailableChapters"))
    }

    @Test
    fun `browse only filters by genre once one is chosen`() {
        assertTrue(
            MangaDexApi.browseParams(BrowseSort.LATEST, null, BrowseFilter.None, 0, 30)
                .none { it.first == "includedTags[]" },
        )
        val tagged = MangaDexApi.browseParams(BrowseSort.LATEST, "391b0423-d847", BrowseFilter.None, 0, 30)
        assertEquals(listOf<Any>("391b0423-d847"), tagged.values("includedTags[]"))
    }

    @Test
    fun `browse filters carry status, year, rating and exclusions`() {
        val filter = BrowseFilter(
            status = StatusFilter.COMPLETED,
            year = 2019,
            safeOnly = true,
            excludedTagIds = setOf("tag-a", "tag-b"),
        )
        val params = MangaDexApi.browseParams(BrowseSort.LATEST, null, filter, 0, 30)
        assertEquals(listOf<Any>("completed"), params.values("status[]"))
        assertEquals(listOf<Any>(2019), params.values("year"))
        // Safe-only means suggestive is never requested.
        assertEquals(listOf<Any>("safe"), params.values("contentRating[]"))
        assertEquals(listOf("tag-a", "tag-b"), params.values("excludedTags[]"))
    }

    @Test
    fun `each sort maps to its own descending order key`() {
        fun order(sort: BrowseSort) =
            MangaDexApi.browseParams(sort, null, BrowseFilter.None, 0, 1).single { it.first.startsWith("order[") }
        assertEquals("order[latestUploadedChapter]" to "desc", order(BrowseSort.LATEST))
        assertEquals("order[followedCount]" to "desc", order(BrowseSort.POPULAR))
        assertEquals("order[rating]" to "desc", order(BrowseSort.TOP_RATED))
    }

    @Test
    fun `search is manhwa only and carries the typed title`() {
        val params = MangaDexApi.searchParams("solo leveling", 0, 30)
        assertEquals(listOf<Any>("ko"), params.values("originalLanguage[]"))
        assertEquals(listOf<Any>("solo leveling"), params.values("title"))
        assertEquals(listOf<Any>("en"), params.values("availableTranslatedLanguage[]"))
    }

    @Test
    fun `the manhwa language filter is korean`() {
        assertEquals("ko", MangaDexApi.MANHWA_ORIGINAL_LANGUAGE)
    }
}
