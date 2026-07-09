package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SportMatchDao {
    @Query("SELECT * FROM matches ORDER BY id DESC")
    fun getAllMatches(): Flow<List<SportMatch>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(match: SportMatch)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMatches(matches: List<SportMatch>)

    @Update
    suspend fun updateMatch(match: SportMatch)

    @Delete
    suspend fun deleteMatch(match: SportMatch)

    @Query("DELETE FROM matches WHERE id = :id")
    suspend fun deleteMatchById(id: Long)

    @Query("DELETE FROM matches")
    suspend fun deleteAllMatches()
}

@Dao
interface LiveChannelDao {
    @Query("SELECT * FROM channels ORDER BY id DESC")
    fun getAllChannels(): Flow<List<LiveChannel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: LiveChannel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllChannels(channels: List<LiveChannel>)

    @Update
    suspend fun updateChannel(channel: LiveChannel)

    @Delete
    suspend fun deleteChannel(channel: LiveChannel)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteChannelById(id: Long)

    @Query("DELETE FROM channels")
    suspend fun deleteAllChannels()
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights ORDER BY id DESC")
    fun getAllHighlights(): Flow<List<Highlight>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: Highlight)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllHighlights(highlights: List<Highlight>)

    @Update
    suspend fun updateHighlight(highlight: Highlight)

    @Delete
    suspend fun deleteHighlight(highlight: Highlight)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlightById(id: Long)

    @Query("DELETE FROM highlights")
    suspend fun deleteAllHighlights()
}

@Dao
interface NoticeDao {
    @Query("SELECT * FROM notices ORDER BY id DESC")
    fun getAllNotices(): Flow<List<Notice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotice(notice: Notice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllNotices(notices: List<Notice>)

    @Update
    suspend fun updateNotice(notice: Notice)

    @Delete
    suspend fun deleteNotice(notice: Notice)

    @Query("DELETE FROM notices WHERE id = :id")
    suspend fun deleteNoticeById(id: Long)

    @Query("DELETE FROM notices")
    suspend fun deleteAllNotices()
}

@Dao
interface BannerAdDao {
    @Query("SELECT * FROM banner_ads WHERE id = 1 LIMIT 1")
    fun getBannerAdFlow(): Flow<BannerAd?>

    @Query("SELECT * FROM banner_ads WHERE id = 1 LIMIT 1")
    suspend fun getBannerAd(): BannerAd?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(bannerAd: BannerAd)
}
