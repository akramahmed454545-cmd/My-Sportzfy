package com.example.data

import kotlinx.coroutines.flow.Flow

class AppRepository(
    private val sportMatchDao: SportMatchDao,
    private val liveChannelDao: LiveChannelDao,
    private val highlightDao: HighlightDao,
    private val noticeDao: NoticeDao,
    private val bannerAdDao: BannerAdDao
) {
    val allMatches: Flow<List<SportMatch>> = sportMatchDao.getAllMatches()
    val allChannels: Flow<List<LiveChannel>> = liveChannelDao.getAllChannels()
    val allHighlights: Flow<List<Highlight>> = highlightDao.getAllHighlights()
    val allNotices: Flow<List<Notice>> = noticeDao.getAllNotices()
    val bannerAdFlow: Flow<BannerAd?> = bannerAdDao.getBannerAdFlow()

    // Banner Ads
    suspend fun getBannerAd(): BannerAd? = bannerAdDao.getBannerAd()
    suspend fun insertOrUpdateBannerAd(bannerAd: BannerAd) = bannerAdDao.insertOrUpdate(bannerAd)

    // Sport Matches
    suspend fun insertMatch(match: SportMatch) = sportMatchDao.insertMatch(match)
    suspend fun insertAllMatches(matches: List<SportMatch>) = sportMatchDao.insertAllMatches(matches)
    suspend fun updateMatch(match: SportMatch) = sportMatchDao.updateMatch(match)
    suspend fun deleteMatch(match: SportMatch) = sportMatchDao.deleteMatch(match)
    suspend fun deleteMatchById(id: Long) = sportMatchDao.deleteMatchById(id)
    suspend fun deleteAllMatches() = sportMatchDao.deleteAllMatches()

    // Live Channels
    suspend fun insertChannel(channel: LiveChannel) = liveChannelDao.insertChannel(channel)
    suspend fun insertAllChannels(channels: List<LiveChannel>) = liveChannelDao.insertAllChannels(channels)
    suspend fun updateChannel(channel: LiveChannel) = liveChannelDao.updateChannel(channel)
    suspend fun deleteChannel(channel: LiveChannel) = liveChannelDao.deleteChannel(channel)
    suspend fun deleteChannelById(id: Long) = liveChannelDao.deleteChannelById(id)
    suspend fun deleteAllChannels() = liveChannelDao.deleteAllChannels()

    // Highlights
    suspend fun insertHighlight(highlight: Highlight) = highlightDao.insertHighlight(highlight)
    suspend fun insertAllHighlights(highlights: List<Highlight>) = highlightDao.insertAllHighlights(highlights)
    suspend fun updateHighlight(highlight: Highlight) = highlightDao.updateHighlight(highlight)
    suspend fun deleteHighlight(highlight: Highlight) = highlightDao.deleteHighlight(highlight)
    suspend fun deleteHighlightById(id: Long) = highlightDao.deleteHighlightById(id)
    suspend fun deleteAllHighlights() = highlightDao.deleteAllHighlights()

    // Notices
    suspend fun insertNotice(notice: Notice) = noticeDao.insertNotice(notice)
    suspend fun insertAllNotices(notices: List<Notice>) = noticeDao.insertAllNotices(notices)
    suspend fun updateNotice(notice: Notice) = noticeDao.updateNotice(notice)
    suspend fun deleteNotice(notice: Notice) = noticeDao.deleteNotice(notice)
    suspend fun deleteNoticeById(id: Long) = noticeDao.deleteNoticeById(id)
    suspend fun deleteAllNotices() = noticeDao.deleteAllNotices()
}
