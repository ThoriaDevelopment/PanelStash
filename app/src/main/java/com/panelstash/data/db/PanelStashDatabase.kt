package com.panelstash.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ManhwaEntity::class,
        ChapterEntity::class,
        ResumeEntity::class,
        FollowEntity::class,
        ReadLaterEntity::class,
        DownloadEntity::class,
    ],
    version = 3,
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
        /** Adds the external-chapter link without throwing away an existing library. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapter ADD COLUMN externalUrl TEXT")
            }
        }

        /**
         * Chapters hosted on a publisher's site are no longer listed at all, so the rows stored
         * by earlier versions are removed. The column stays: dropping a column is not portable
         * across the SQLite builds Android ships, and an empty column costs nothing. Resume and
         * download rows for those chapters go with them through the existing cascade.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM chapter WHERE externalUrl IS NOT NULL")
            }
        }

        @Volatile
        private var instance: PanelStashDatabase? = null

        fun get(context: Context): PanelStashDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PanelStashDatabase::class.java,
                    "panelstash.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(object : Callback() {
                        /**
                         * A destructive fallback drops every row, which leaves the downloaded
                         * chapter files and the image cache with nothing pointing at them. They
                         * are cleared here so the app does not silently keep the space forever.
                         */
                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                            runCatching {
                                File(context.filesDir, "downloads").deleteRecursively()
                                File(context.cacheDir, "images").deleteRecursively()
                            }
                        }
                    })
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
