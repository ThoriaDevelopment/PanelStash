package com.panelstash.data.db

import androidx.room.Dao
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

    @Query("SELECT COUNT(*) FROM chapter WHERE manhwaId = :manhwaId AND readAt IS NOT NULL")
    fun observeReadCount(manhwaId: Long): Flow<Int>

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

    @Query("SELECT m.* FROM manhwa m JOIN follow f ON f.manhwaId = m.id ORDER BY f.addedAt DESC")
    fun observeAll(): Flow<List<ManhwaEntity>>
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
