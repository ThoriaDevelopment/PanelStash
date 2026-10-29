package com.panelstash.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ContinueRow(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val chapterId: Long,
    val chapterNumber: String,
    val chapterTitle: String?,
    val pageIndex: Int,
    val updatedAt: Long,
)

data class ResumePoint(
    val chapterId: Long,
    val pageIndex: Int,
    val updatedAt: Long,
)

/** A title plus when the reader last touched it, in any way. Drives the Browse recommendations. */
data class TouchedRow(
    @Embedded val manhwa: ManhwaEntity,
    val touchedAt: Long,
)

/** A followed title plus the counts that make the list worth reading at a glance. */
data class FollowRow(
    @Embedded val manhwa: ManhwaEntity,
    val unread: Int,
    val total: Int,
    val latestChapter: String?,
    val addedAt: Long,
)

data class DownloadRow(
    val chapterId: Long,
    val manhwaId: Long,
    val chapterNumber: String,
    val chapterTitle: String?,
    val manhwaTitle: String,
    val coverUrl: String?,
    val state: String,
    val byteSize: Long,
    val localDir: String?,
    val updatedAt: Long,
)

@Dao
interface ManhwaDao {
    @Insert
    suspend fun insert(manhwa: ManhwaEntity): Long

    @Update
    suspend fun update(manhwa: ManhwaEntity)

    @Query("SELECT * FROM manhwa WHERE sourceId = :sourceId AND remoteId = :remoteId LIMIT 1")
    suspend fun byRemote(sourceId: String, remoteId: String): ManhwaEntity?

    @Query("SELECT * FROM manhwa WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ManhwaEntity?

    @Query("SELECT * FROM manhwa WHERE id = :id LIMIT 1")
    fun observe(id: Long): Flow<ManhwaEntity?>

    @Query(
        """
        SELECT m.id AS id, m.title AS title, m.coverUrl AS coverUrl,
               r.chapterId AS chapterId, c.numberText AS chapterNumber,
               c.title AS chapterTitle, r.pageIndex AS pageIndex, r.updatedAt AS updatedAt
        FROM manhwa m
        JOIN resume r ON r.manhwaId = m.id
        JOIN chapter c ON c.id = r.chapterId
        ORDER BY r.updatedAt DESC
        """,
    )
    fun observeContinueReading(): Flow<List<ContinueRow>>

    /**
     * The most recently touched titles: resumed, finished, followed or saved, whichever is newer.
     * Recommendations are seeded from these, so reading counts even if a title is never followed.
     */
    @Query(
        """
        SELECT m.*, MAX(t.at) AS touchedAt
        FROM manhwa m
        JOIN (
            SELECT manhwaId, updatedAt AS at FROM resume
            UNION ALL
            SELECT manhwaId, readAt AS at FROM chapter WHERE readAt IS NOT NULL
            UNION ALL
            SELECT manhwaId, addedAt AS at FROM follow
            UNION ALL
            SELECT manhwaId, addedAt AS at FROM read_later
        ) t ON t.manhwaId = m.id
        GROUP BY m.id
        ORDER BY touchedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun recentlyTouched(limit: Int): List<TouchedRow>

    /** Titles the reader already has a relationship with; never recommended back to them. */
    @Query(
        """
        SELECT title FROM manhwa m
        WHERE EXISTS (SELECT 1 FROM resume r WHERE r.manhwaId = m.id)
           OR EXISTS (SELECT 1 FROM chapter c WHERE c.manhwaId = m.id AND c.readAt IS NOT NULL)
           OR EXISTS (SELECT 1 FROM follow f WHERE f.manhwaId = m.id)
           OR EXISTS (SELECT 1 FROM read_later l WHERE l.manhwaId = m.id)
        """,
    )
    suspend fun consumedTitles(): List<String>

    /**
     * Drops catalogue rows the reader never touched. Browse, search and the rails store every
     * title they see, so without this the table grows for as long as the app is installed.
     */
    @Query(
        """
        DELETE FROM manhwa
        WHERE lastFetchedAt < :before
          AND NOT EXISTS (SELECT 1 FROM resume r WHERE r.manhwaId = manhwa.id)
          AND NOT EXISTS (SELECT 1 FROM chapter c WHERE c.manhwaId = manhwa.id AND c.readAt IS NOT NULL)
          AND NOT EXISTS (SELECT 1 FROM follow f WHERE f.manhwaId = manhwa.id)
          AND NOT EXISTS (SELECT 1 FROM read_later l WHERE l.manhwaId = manhwa.id)
          AND NOT EXISTS (SELECT 1 FROM download d WHERE d.manhwaId = manhwa.id)
        """,
    )
    suspend fun pruneUnused(before: Long): Int
}

@Dao
interface ChapterDao {
    @Insert
    suspend fun insert(chapter: ChapterEntity): Long

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Query("SELECT * FROM chapter WHERE sourceId = :sourceId AND remoteId = :remoteId LIMIT 1")
    suspend fun byRemote(sourceId: String, remoteId: String): ChapterEntity?

    @Query("SELECT * FROM chapter WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ChapterEntity?

    @Query(
        """
        SELECT * FROM chapter WHERE manhwaId = :manhwaId
        ORDER BY CASE WHEN sortKey IS NULL THEN 1 ELSE 0 END, sortKey ASC, numberText ASC
        """,
    )
    fun observeForManhwa(manhwaId: Long): Flow<List<ChapterEntity>>

    @Query(
        """
        SELECT * FROM chapter WHERE manhwaId = :manhwaId
        ORDER BY CASE WHEN sortKey IS NULL THEN 1 ELSE 0 END, sortKey ASC, numberText ASC
        """,
    )
    suspend fun listForManhwa(manhwaId: Long): List<ChapterEntity>

    @Query("UPDATE chapter SET readAt = :readAt WHERE id = :chapterId")
    suspend fun setReadAt(chapterId: Long, readAt: Long?)

    @Query(
        "SELECT * FROM chapter WHERE manhwaId = :manhwaId AND externalUrl IS NULL " +
            "ORDER BY sortKey ASC LIMIT 1",
    )
    suspend fun firstChapter(manhwaId: Long): ChapterEntity?
}

@Dao
interface ResumeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(resume: ResumeEntity)

    @Query("SELECT * FROM resume WHERE manhwaId = :manhwaId LIMIT 1")
    fun observe(manhwaId: Long): Flow<ResumeEntity?>

    @Query("SELECT * FROM resume WHERE manhwaId = :manhwaId LIMIT 1")
    suspend fun get(manhwaId: Long): ResumeEntity?

    @Query("SELECT chapterId, pageIndex, updatedAt FROM resume WHERE manhwaId = :manhwaId LIMIT 1")
    fun observePoint(manhwaId: Long): Flow<ResumePoint?>

    @Query("DELETE FROM resume WHERE manhwaId = :manhwaId")
    suspend fun clear(manhwaId: Long)
}

@Dao
interface FollowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(follow: FollowEntity)

    @Query("DELETE FROM follow WHERE manhwaId = :manhwaId")
    suspend fun remove(manhwaId: Long)

    @Query("SELECT * FROM follow WHERE manhwaId = :manhwaId LIMIT 1")
    fun observe(manhwaId: Long): Flow<FollowEntity?>

    /**
     * Followed titles with how much is left to read and the newest chapter, so the tab is a
     * tracker rather than an alphabetical list nobody needs to open.
     */
    @Query(
        """
        SELECT m.*, f.addedAt AS addedAt,
               (SELECT COUNT(*) FROM chapter c
                  WHERE c.manhwaId = m.id AND c.readAt IS NULL AND c.externalUrl IS NULL) AS unread,
               (SELECT COUNT(*) FROM chapter c
                  WHERE c.manhwaId = m.id AND c.externalUrl IS NULL) AS total,
               (SELECT c.numberText FROM chapter c WHERE c.manhwaId = m.id
                  ORDER BY CASE WHEN c.sortKey IS NULL THEN 1 ELSE 0 END,
                           c.sortKey DESC, c.numberText DESC LIMIT 1) AS latestChapter
        FROM manhwa m JOIN follow f ON f.manhwaId = m.id
        ORDER BY f.addedAt DESC
        """,
    )
    fun observeAll(): Flow<List<FollowRow>>
}

@Dao
interface ReadLaterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(marker: ReadLaterEntity)

    @Query("DELETE FROM read_later WHERE manhwaId = :manhwaId")
    suspend fun remove(manhwaId: Long)

    @Query("SELECT * FROM read_later WHERE manhwaId = :manhwaId LIMIT 1")
    fun observe(manhwaId: Long): Flow<ReadLaterEntity?>

    @Query("SELECT m.* FROM manhwa m JOIN read_later l ON l.manhwaId = m.id ORDER BY l.addedAt DESC")
    fun observeAll(): Flow<List<ManhwaEntity>>
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(download: DownloadEntity)

    @Query("SELECT * FROM download WHERE chapterId = :chapterId LIMIT 1")
    suspend fun byChapter(chapterId: Long): DownloadEntity?

    @Query("SELECT * FROM download WHERE chapterId = :chapterId LIMIT 1")
    fun observeByChapter(chapterId: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM download WHERE manhwaId = :manhwaId")
    fun observeForManhwa(manhwaId: Long): Flow<List<DownloadEntity>>

    @Query("DELETE FROM download WHERE chapterId = :chapterId")
    suspend fun delete(chapterId: Long)

    @Query("SELECT * FROM download WHERE manhwaId = :manhwaId")
    suspend fun byManhwa(manhwaId: Long): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE state = 'RUNNING'")
    suspend fun running(): List<DownloadEntity>

    @Query("DELETE FROM download WHERE manhwaId = :manhwaId")
    suspend fun deleteForManhwa(manhwaId: Long)

    @Query(
        """
        SELECT d.chapterId AS chapterId, d.manhwaId AS manhwaId, c.numberText AS chapterNumber,
               c.title AS chapterTitle, m.title AS manhwaTitle, m.coverUrl AS coverUrl,
               d.state AS state, d.byteSize AS byteSize, d.localDir AS localDir, d.updatedAt AS updatedAt
        FROM download d
        JOIN chapter c ON c.id = d.chapterId
        JOIN manhwa m ON m.id = d.manhwaId
        ORDER BY d.updatedAt DESC
        """,
    )
    fun observeAll(): Flow<List<DownloadRow>>

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM download WHERE state = 'COMPLETE'")
    fun observeTotalBytes(): Flow<Long>
}
