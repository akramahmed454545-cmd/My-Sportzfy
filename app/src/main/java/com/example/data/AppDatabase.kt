package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SportMatch::class, LiveChannel::class, Highlight::class, Notice::class, BannerAd::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sportMatchDao(): SportMatchDao
    abstract fun liveChannelDao(): LiveChannelDao
    abstract fun highlightDao(): HighlightDao
    abstract fun noticeDao(): NoticeDao
    abstract fun bannerAdDao(): BannerAdDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sportzfy_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
