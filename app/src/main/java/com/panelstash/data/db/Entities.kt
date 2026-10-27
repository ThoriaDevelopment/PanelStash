package com.panelstash.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "manhwa",
    indices = [Index(value = ["sourceId", "remoteId"], unique = true)],
)
data class ManhwaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val remoteId: String,
    val title: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val year: Int? = null,
    val status: String? = null,
    val lastFetchedAt: Long = 0,
)

@Entity(
    tableName = "chapter",
    indices = [
        Index(value = ["sourceId", "remoteId"], unique = true),
        Index("manhwaId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ManhwaEntity::class,
            parentColumns = ["id"],
            childColumns = ["manhwaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val manhwaId: Long,
    val sourceId: String,
    val remoteId: String,
    val numberText: String,
    val sortKey: Double? = null,
    val title: String? = null,
    val language: String = "en",
    val pageCount: Int = 0,
    /** Set only when the chapter is finished. Null means unread. */
    val readAt: Long? = null,
)

@Entity(
    tableName = "resume",
    foreignKeys = [
        ForeignKey(
            entity = ManhwaEntity::class,
            parentColumns = ["id"],
            childColumns = ["manhwaId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ResumeEntity(
    @PrimaryKey val manhwaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "follow",
    foreignKeys = [
        ForeignKey(
            entity = ManhwaEntity::class,
            parentColumns = ["id"],
            childColumns = ["manhwaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FollowEntity(
    @PrimaryKey val manhwaId: Long,
    val addedAt: Long,
)

@Entity(
    tableName = "read_later",
    foreignKeys = [
        ForeignKey(
            entity = ManhwaEntity::class,
            parentColumns = ["id"],
            childColumns = ["manhwaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadLaterEntity(
    @PrimaryKey val manhwaId: Long,
    val addedAt: Long,
)

/** Download bookkeeping. Absent row means "not downloaded". */
@Entity(
    tableName = "download",
    indices = [Index("manhwaId")],
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadEntity(
    @PrimaryKey val chapterId: Long,
    val manhwaId: Long,
    val quality: String,
    val meteredAllowed: Boolean,
    val state: String,
    val byteSize: Long = 0,
    val localDir: String? = null,
    val error: String? = null,
    val updatedAt: Long = 0,
)

object DownloadState {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val COMPLETE = "COMPLETE"
    const val FAILED = "FAILED"
}
