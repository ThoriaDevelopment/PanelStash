package com.panelstash.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are cut from orion-scans.com's API on 2026-11-10, trimmed to the fields the source
 * reads. The site's pages are client-rendered now, so the API is what the app depends on.
 */
class OrionMappingTest {

    private val json = Network.json

    private val queryPayload = """
        {
          "posts": [
            {
              "id": 331,
              "slug": "how-to-walk-on-a-broken-earth",
              "postTitle": "#How to Walk on a Broken Earth ",
              "featuredImage": "https://storage.orion-scans.com/public/upload/series/featured/x/cover.png",
              "seriesType": "MANGA",
              "seriesStatus": "ONGOING",
              "genres": [{"id": 2, "name": "Fantasy"}],
              "chapters": [{"slug": "chapter-1", "number": 1}]
            },
            {
              "id": 12,
              "slug": "some-web-novel",
              "postTitle": "Some Web Novel",
              "seriesType": "NOVEL",
              "seriesStatus": "COMPLETED"
            },
            {
              "id": 13,
              "slug": "announced-but-empty",
              "postTitle": "Announced But Empty",
              "seriesType": "MANHWA",
              "seriesStatus": "ONGOING",
              "chapters": []
            }
          ],
          "totalCount": 325
        }
    """.trimIndent()

    private val detailPayload = """
        {
          "totalChapterCount": 6,
          "post": {
            "id": 339,
            "slug": "how-to-walk-on-a-broken-earth",
            "postTitle": "#How to Walk on a Broken Earth ",
            "postContent": "<p>A shattered world.</p><br><br>A traveler walks.",
            "featuredImage": "https://storage.orion-scans.com/public/upload/series/featured/x/cover.png",
            "seriesType": "MANGA",
            "seriesStatus": "ONGOING",
            "releaseDate": "2022",
            "alternativeTitles": "Broken Earth\nWalk On",
            "genres": [{"id": 2, "name": "Fantasy"}]
          }
        }
    """.trimIndent()

    private val chaptersPayload = """
        {
          "post": {
            "chapters": [
              {
                "slug": "chapter-6",
                "number": 6,
                "title": "A locked extra",
                "isLocked": true,
                "isAccessible": false,
                "isPermanentlyLocked": true,
                "price": 100
              },
              {
                "slug": "chapter-5.5",
                "number": 5.5,
                "title": null,
                "isLocked": false,
                "isAccessible": true,
                "isPermanentlyLocked": false,
                "price": 0
              },
              {
                "slug": "chapter-1",
                "number": 1,
                "title": null,
                "isLocked": false,
                "isAccessible": true,
                "isPermanentlyLocked": false,
                "price": 0
              }
            ]
          },
          "totalChapterCount": 3
        }
    """.trimIndent()

    private val readerPayload = """
        {
          "chapter": {
            "slug": "chapter-1",
            "isLocked": false,
            "isAccessible": true,
            "isPermanentlyLocked": false,
            "images": [
              {
                "url": "https://storage.orion-scans.com/public/upload/series/demo/abc/page-0002_01.jpg",
                "width": 800,
                "height": 1200,
                "order": 1
              },
              {
                "url": "https://storage.orion-scans.com/public/upload/series/demo/abc/page-0001_00.jpg",
                "width": 1600,
                "height": 900,
                "order": 0
              },
              {
                "url": "   ",
                "width": 0,
                "height": 0,
                "order": 2
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `catalogue payload keys series by slug and drops novels and empty series`() {
        val posts = json.decodeFromString<OrionQueryResponse>(queryPayload).posts
        val manhwa = posts.toSourceManhwa()

        assertEquals(1, manhwa.size)
        assertEquals("how-to-walk-on-a-broken-earth", manhwa.single().remoteId)
        assertEquals("#How to Walk on a Broken Earth", manhwa.single().title)
        assertEquals("ONGOING", manhwa.single().status)
        // The synopsis is only on the detail route.
        assertNull(manhwa.single().description)
        assertEquals(listOf("Fantasy"), manhwa.single().genres.map { it.name })
    }

    @Test
    fun `detail carries the synopsis, year, aliases and genres`() {
        val post = json.decodeFromString<OrionPostDetailResponse>(detailPayload).post!!
        val manhwa = post.toSourceManhwa()

        assertEquals("how-to-walk-on-a-broken-earth", manhwa.remoteId)
        assertEquals("#How to Walk on a Broken Earth", manhwa.title)
        assertEquals("A shattered world.\n\nA traveler walks.", manhwa.description)
        assertEquals(
            "https://storage.orion-scans.com/public/upload/series/featured/x/cover.png",
            manhwa.coverUrl,
        )
        assertEquals(2022, manhwa.year)
        assertEquals("ONGOING", manhwa.status)
        assertEquals(listOf("Broken Earth", "Walk On"), manhwa.aliases)
        assertEquals(listOf("Fantasy"), manhwa.genres.map { it.name })
    }

    @Test
    fun `chapter list is scoped to the series and marks coin-locked chapters unreadable`() {
        val chapters = json.decodeFromString<OrionChaptersResponse>(chaptersPayload)
            .post!!.chapters.mapNotNull { it.toSourceChapter("how-to-walk-on-a-broken-earth") }

        assertEquals(listOf("6", "5.5", "1"), chapters.map { it.numberText })
        assertEquals("how-to-walk-on-a-broken-earth|chapter-1", chapters.last().remoteId)
        assertTrue(chapters.first().externalOnly)
        assertFalse(chapters.last().externalOnly)
        assertEquals("A locked extra", chapters.first().title)
        assertNull(chapters.last().title)
        // A chapter only reports its page count once it has been opened.
        assertEquals(0, chapters.last().pageCount)
    }

    @Test
    fun `dedupe drops the unreadable chapters`() {
        val chapters = json.decodeFromString<OrionChaptersResponse>(chaptersPayload)
            .post!!.chapters.mapNotNull { it.toSourceChapter("how-to-walk-on-a-broken-earth") }

        assertEquals(listOf("1", "5.5"), dedupeChapters(chapters).map { it.numberText })
    }

    @Test
    fun `reader images are ordered by the order field and skip blank urls`() {
        val chapter = json.decodeFromString<OrionChapterPageResponse>(readerPayload).chapter!!
        val images = chapter.toPageImages()

        assertEquals(
            listOf(
                "https://storage.orion-scans.com/public/upload/series/demo/abc/page-0001_00.jpg",
                "https://storage.orion-scans.com/public/upload/series/demo/abc/page-0002_01.jpg",
            ),
            images.map { it.url },
        )
        assertEquals(listOf(0, 1), images.map { it.index })
        assertEquals(1600, images.first().width)
        assertEquals(1200, images.last().height)
        assertEquals(
            "https://storage.orion-scans.com",
            images.first().url.substringBefore("/public/upload/"),
        )
    }

    @Test
    fun `a reader payload with no images yields nothing`() {
        val chapter = json.decodeFromString<OrionChapterPageResponse>(
            """{"chapter":{"images":[]}}""",
        ).chapter!!

        assertTrue(chapter.toPageImages().isEmpty())
    }

    @Test
    fun `release dates without a year are ignored`() {
        assertNull(yearOf(""))
        assertNull(yearOf("-"))
        assertEquals(2022, yearOf("2022"))
        assertEquals(2024, yearOf("2024-12-31T23:59:59.000Z"))
    }
}
