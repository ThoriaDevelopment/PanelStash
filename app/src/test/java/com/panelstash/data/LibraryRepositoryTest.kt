package com.panelstash.data

import com.panelstash.data.db.ChapterDao
import com.panelstash.data.db.DownloadDao
import com.panelstash.data.db.FollowDao
import com.panelstash.data.db.ManhwaDao
import com.panelstash.data.db.ManhwaEntity
import com.panelstash.data.db.PanelStashDatabase
import com.panelstash.data.db.ReadLaterDao
import com.panelstash.data.db.ResumeDao
import com.panelstash.source.BrowseFilter
import com.panelstash.source.BrowseSort
import com.panelstash.source.PageQuality
import com.panelstash.source.PageSet
import com.panelstash.source.Source
import com.panelstash.source.SourceCapabilities
import com.panelstash.source.SourceChapter
import com.panelstash.source.SourceError
import com.panelstash.source.SourceGenre
import com.panelstash.source.SourceManhwa
import com.panelstash.source.SourceRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * All-source browse and search are the only paths where one failure must not take the rest of
 * the catalogue with it, and where every source's offset has to survive a "load more". Fake
 * sources make those two behaviours visible without a network or a real database.
 */
class LibraryRepositoryTest {

    private lateinit var db: PanelStashDatabase
    private lateinit var manhwaDao: ManhwaDao
    private var nextId = 1L
    private val stored = mutableMapOf<Long, ManhwaEntity>()

    @Before
    fun setUp() {
        db = mockk()
        manhwaDao = mockk()
        every { db.manhwaDao() } returns manhwaDao
        every { db.chapterDao() } returns mockk<ChapterDao>(relaxed = true)
        every { db.resumeDao() } returns mockk<ResumeDao>(relaxed = true)
        every { db.followDao() } returns mockk<FollowDao>(relaxed = true)
        every { db.readLaterDao() } returns mockk<ReadLaterDao>(relaxed = true)
        every { db.downloadDao() } returns mockk<DownloadDao>(relaxed = true)

        coEvery { manhwaDao.byRemote(any(), any()) } returns null
        coEvery { manhwaDao.insert(any()) } coAnswers {
            val entity = firstArg<ManhwaEntity>()
            val id = nextId++
            stored[id] = entity.copy(id = id)
            id
        }
        coEvery { manhwaDao.byId(any()) } coAnswers { stored[firstArg<Long>()] }
    }

    private fun repository(vararg sources: Source) =
        LibraryRepository(SourceRegistry(sources.toList()), db)

    @Test
    fun `browseAll reads every source and advances each offset separately`() = runBlocking {
        val a = FakeSource(
            id = "a",
            browsePages = mapOf(
                0 to listOf(manhwa("a1"), manhwa("a2")),
                2 to listOf(manhwa("a3")),
            ),
        )
        val b = FakeSource(id = "b", browsePages = mapOf(0 to listOf(manhwa("b1"))))
        val repository = repository(a, b)

        val first = repository.browseAll(BrowseSort.LATEST, null, BrowseFilter.None, emptyMap(), limit = 2)

        assertEquals(listOf("a1", "a2", "b1"), first.items.map { it.title })
        assertEquals(mapOf("a" to 2, "b" to 1), first.nextOffsets)
        // b is done, but a still has a second page to read.
        assertFalse(first.exhausted)

        val second = repository.browseAll(
            BrowseSort.LATEST,
            null,
            BrowseFilter.None,
            first.nextOffsets,
            limit = 2,
        )

        assertEquals(listOf("a3"), second.items.map { it.title })
        assertEquals(mapOf("a" to 3, "b" to 1), second.nextOffsets)
        assertTrue(second.exhausted)
        assertEquals(listOf(0, 2), a.browseOffsets)
        assertEquals(listOf(0, 1), b.browseOffsets)
    }

    @Test
    fun `browseAll keeps the healthy sources when one fails`() = runBlocking {
        val broken = FakeSource(id = "broken", browseFailure = SourceError.Transient())
        val working = FakeSource(id = "working", browsePages = mapOf(0 to listOf(manhwa("w1"))))

        val page = repository(broken, working)
            .browseAll(BrowseSort.LATEST, null, BrowseFilter.None, emptyMap(), limit = 30)

        assertEquals(listOf("w1"), page.items.map { it.title })
        assertEquals(mapOf("working" to 1), page.nextOffsets)
        // The failed source must not be marked exhausted: load-more retries it.
        assertFalse(page.exhausted)
    }

    @Test
    fun `browseAll fails only when every source failed`() {
        val one = FakeSource(id = "one", browseFailure = SourceError.Transient())
        val two = FakeSource(id = "two", browseFailure = SourceError.RateLimited(null))

        assertThrows(SourceError.Transient::class.java) {
            runBlocking {
                repository(one, two)
                    .browseAll(BrowseSort.LATEST, null, BrowseFilter.None, emptyMap(), limit = 30)
            }
        }
    }

    @Test
    fun `searchAll searches every source and skips a failed one`() = runBlocking {
        val first = FakeSource(id = "first", searchHits = listOf(manhwa("hit-one")))
        val second = FakeSource(id = "second", searchFailure = SourceError.Transient())

        val results = repository(first, second).searchAll("mount hua")

        assertEquals(listOf("hit-one"), results.map { it.title })
        assertEquals(listOf("mount hua"), first.searchQueries)
        assertEquals(listOf("mount hua"), second.searchQueries)
    }

    @Test
    fun `searchAll with an empty query browses the latest list instead`() = runBlocking {
        val source = FakeSource(id = "only", browsePages = mapOf(0 to listOf(manhwa("latest"))))

        val results = repository(source).searchAll("")

        assertEquals(listOf("latest"), results.map { it.title })
        assertEquals(listOf(0), source.browseOffsets)
        assertTrue(source.searchQueries.isEmpty())
    }

    private fun manhwa(remoteId: String) = SourceManhwa(
        remoteId = remoteId,
        title = remoteId,
        description = null,
        coverUrl = null,
        year = null,
        status = null,
    )

    private class FakeSource(
        override val id: String,
        private val browsePages: Map<Int, List<SourceManhwa>> = emptyMap(),
        private val searchHits: List<SourceManhwa> = emptyList(),
        private val browseFailure: Throwable? = null,
        private val searchFailure: Throwable? = null,
    ) : Source {

        override val name = id

        override val capabilities = SourceCapabilities()

        val browseOffsets = mutableListOf<Int>()
        val searchQueries = mutableListOf<String>()

        override suspend fun browse(
            sort: BrowseSort,
            tagId: String?,
            filter: BrowseFilter,
            offset: Int,
            limit: Int,
        ): List<SourceManhwa> {
            browseOffsets += offset
            browseFailure?.let { throw it }
            return browsePages[offset].orEmpty()
        }

        override suspend fun search(query: String, offset: Int, limit: Int): List<SourceManhwa> {
            searchQueries += query
            searchFailure?.let { throw it }
            return searchHits
        }

        override suspend fun genres(): List<SourceGenre> = emptyList()

        override suspend fun manhwa(remoteId: String): SourceManhwa =
            throw UnsupportedOperationException("not used")

        override suspend fun chapters(manhwaRemoteId: String, language: String): List<SourceChapter> =
            throw UnsupportedOperationException("not used")

        override suspend fun pages(chapterRemoteId: String, quality: PageQuality): PageSet =
            throw UnsupportedOperationException("not used")
    }
}
