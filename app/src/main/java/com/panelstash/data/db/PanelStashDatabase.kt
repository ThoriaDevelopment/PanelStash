package com.panelstash.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 2,
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

        @Volatile
        private var instance: PanelStashDatabase? = null

        fun get(context: Context): PanelStashDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PanelStashDatabase::class.java,
                    "panelstash.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
