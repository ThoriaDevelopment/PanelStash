package com.panelstash.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ManhwaEntity::class,
        ChapterEntity::class,
        ResumeEntity::class,
        FollowEntity::class,
        ReadLaterEntity::class,
        DownloadEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PanelStashDatabase : RoomDatabase() {
    abstract fun manhwaDao(): ManhwaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun resumeDao(): ResumeDao
    abstract fun followDao(): FollowDao
    abstract fun readLaterDao(): ReadLaterDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: PanelStashDatabase? = null

        fun get(context: Context): PanelStashDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PanelStashDatabase::class.java,
                    "panelstash.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
