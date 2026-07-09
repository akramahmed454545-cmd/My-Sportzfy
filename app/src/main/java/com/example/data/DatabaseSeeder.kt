package com.example.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull

object DatabaseSeeder {
    suspend fun seedDatabaseIfEmpty(repository: AppRepository) = withContext(Dispatchers.IO) {
        // Only seed if notices or matches are empty to avoid duplication
        val hasNotices = repository.allNotices.firstOrNull()?.isNotEmpty() ?: false
        if (hasNotices) return@withContext

        // Seed default BannerAd setting
        repository.insertOrUpdateBannerAd(
            BannerAd(
                id = 1,
                mediaType = "gif",
                mediaUrl = "https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExM2Z5eWZ2OHdpeGtxNDlhMGlxMTI4eTVoZHkyeXB5dTRrZHNkczBybyZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/3o6vY6b2M3N66b8V5S/giphy.gif",
                clickUrl = "https://sportzfy.com",
                enabled = true
            )
        )

        // 1. Seed notice banner content
        repository.insertNotice(Notice(content = "(Ads Free). Keep supporting us for the best sports streaming experience!"))

        // 2. Seed Matches
        val sampleMatches = listOf(
            SportMatch(
                title = "MotorSports || MotoGP",
                sport = "MotorSports",
                team1Name = "MotoGP",
                team1Logo = "motogp",
                team2Name = "MotoGP",
                team2Logo = "motogp",
                time = "05:24:23",
                status = "Live",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            SportMatch(
                title = "Cricket || ICC Women World Twenty20",
                sport = "Cricket",
                team1Name = "PAK-W",
                team1Logo = "pakistan",
                team2Name = "NED-W",
                team2Logo = "netherlands",
                time = "03:54:23",
                status = "Live",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            SportMatch(
                title = "Cricket || Crowe-Thorpe Trophy",
                sport = "Cricket",
                team1Name = "ENG",
                team1Logo = "england",
                team2Name = "NZ",
                team2Logo = "newzealand",
                time = "03:24:23",
                status = "Live",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            SportMatch(
                title = "MotorSports || Live Events | Formula 1",
                sport = "MotorSports",
                team1Name = "Formula 1",
                team1Logo = "f1",
                team2Name = "Formula 1",
                team2Logo = "f1",
                time = "02:54:23",
                status = "Live",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            SportMatch(
                title = "Cricket || ICC Women World Twenty20",
                sport = "Cricket",
                team1Name = "WI-W",
                team1Logo = "westindies",
                team2Name = "IRE-W",
                team2Logo = "ireland",
                time = "07:30 PM 27/06/2026",
                status = "Upcoming",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            )
        )
        for (match in sampleMatches) {
            repository.insertMatch(match)
        }

        // 3. Seed Live TV Channels
        val sampleChannels = listOf(
            LiveChannel(name = "Sports VIP", category = "VIP", logoUrl = "sports_vip", streamUrl = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"),
            LiveChannel(name = "Movies VIP", category = "VIP", logoUrl = "movies_vip", streamUrl = "https://bitmovin-a.akamaihd.net/content/sintel/hls/playlist.m3u8"),
            LiveChannel(name = "Dramas VIP", category = "VIP", logoUrl = "dramas_vip", streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"),
            LiveChannel(name = "CNN VIP", category = "VIP", logoUrl = "cnn_vip", streamUrl = "https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8"),
            LiveChannel(name = "Somoy TV", category = "News", logoUrl = "somoy_tv", streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"),
            LiveChannel(name = "ndent TV", category = "News", logoUrl = "ndent_tv", streamUrl = "https://dwstream4-lh.akamaihd.net/i/dwstream4_live@131329/master.m3u8"),
            LiveChannel(name = "Desh TV", category = "News", logoUrl = "desh_tv", streamUrl = "https://ntv1.akamaized.net/hls/live/2014027/NASA-NTV1-HLS/master.m3u8"),
            LiveChannel(name = "Jamuna TV", category = "News", logoUrl = "jamuna_tv", streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"),
            LiveChannel(name = "ATN News", category = "News", logoUrl = "atn_news", streamUrl = "https://skynews-skynews-main-1-gb.samsung.wurl.tv/playlist.m3u8"),
            LiveChannel(name = "ATN Bangla", category = "News", logoUrl = "atn_bangla", streamUrl = "https://bitmovin-a.akamaihd.net/content/sintel/hls/playlist.m3u8"),
            LiveChannel(name = "Nexus TV", category = "Entertainment", logoUrl = "nexus_tv", streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"),
            LiveChannel(name = "Movie Bangla", category = "Entertainment", logoUrl = "movie_bangla", streamUrl = "https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,640x360_1500,.mp4.csmil/master.m3u8"),
            LiveChannel(name = "Mohona TV", category = "Local TV", logoUrl = "mohona_tv", streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"),
            LiveChannel(name = "Ananda TV", category = "Local TV", logoUrl = "ananda_tv", streamUrl = "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"),
            LiveChannel(name = "Bijoy TV", category = "Local TV", logoUrl = "bijoy_tv", streamUrl = "https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8"),
            LiveChannel(name = "Global TV", category = "Local TV", logoUrl = "global_tv", streamUrl = "https://dwstream4-lh.akamaihd.net/i/dwstream4_live@131329/master.m3u8"),
            LiveChannel(name = "Channel S", category = "Local TV", logoUrl = "channel_s", streamUrl = "https://ntv1.akamaized.net/hls/live/2014027/NASA-NTV1-HLS/master.m3u8"),
            LiveChannel(name = "Bangla TV", category = "Local TV", logoUrl = "bangla_tv", streamUrl = "https://skynews-skynews-main-1-gb.samsung.wurl.tv/playlist.m3u8"),
            LiveChannel(name = "Asian TV", category = "Local TV", logoUrl = "asian_tv", streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"),
            LiveChannel(name = "Channel i", category = "Local TV", logoUrl = "channel_i", streamUrl = "https://bitmovin-a.akamaihd.net/content/sintel/hls/playlist.m3u8"),
            LiveChannel(name = "Ekhon TV", category = "News", logoUrl = "ekhon_tv", streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"),
            LiveChannel(name = "Rajdhani TV", category = "News", logoUrl = "rajdhani_tv", streamUrl = "https://multiplatform-f.akamaihd.net/i/multi/will/bunny/big_buck_bunny_,640x360_400,640x360_700,640x360_1000,640x360_1500,.mp4.csmil/master.m3u8"),
            LiveChannel(name = "Islamic TV", category = "News", logoUrl = "islamic_tv", streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"),
            LiveChannel(name = "Ekattor TV", category = "News", logoUrl = "ekattor_tv", streamUrl = "https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8")
        )
        for (channel in sampleChannels) {
            repository.insertChannel(channel)
        }

        // 4. Seed Highlights
        val sampleHighlights = listOf(
            Highlight(
                title = "Football | FIFA World Cup",
                team1Name = "Mexico",
                team1Logo = "mexico",
                team2Name = "South Africa",
                team2Logo = "southafrica",
                date = "11/06/2026",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            Highlight(
                title = "Football | FIFA World Cup",
                team1Name = "South Korea",
                team1Logo = "southkorea",
                team2Name = "Czechia",
                team2Logo = "czechrepublic",
                date = "12/06/2026",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            Highlight(
                title = "Football | FIFA World Cup",
                team1Name = "Canada",
                team1Logo = "canada",
                team2Name = "BiH",
                team2Logo = "bosnia",
                date = "12/06/2026",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            Highlight(
                title = "Football | FIFA World Cup",
                team1Name = "USA",
                team1Logo = "usa",
                team2Name = "Paraguay",
                team2Logo = "paraguay",
                date = "13/06/2026",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            Highlight(
                title = "Football | FIFA World Cup",
                team1Name = "Qatar",
                team1Logo = "qatar",
                team2Name = "Switzerland",
                team2Logo = "switzerland",
                date = "13/06/2026",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            Highlight(
                title = "Football | FIFA World Cup",
                team1Name = "Brazil",
                team1Logo = "brazil",
                team2Name = "Morocco",
                team2Logo = "morocco",
                date = "13/06/2026",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            ),
            Highlight(
                title = "Football | FIFA World Cup",
                team1Name = "Germany",
                team1Logo = "germany",
                team2Name = "Curaçao",
                team2Logo = "curacao",
                date = "14/06/2026",
                streamUrl = "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
            )
        )
        for (highlight in sampleHighlights) {
            repository.insertHighlight(highlight)
        }
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstOrNull(): T? {
        var result: T? = null
        try {
            kotlinx.coroutines.withTimeout(1500) {
                this@firstOrNull.collect {
                    result = it
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        } catch (e: Exception) {
            // Cancellation expected
        }
        return result
    }

    fun cleanChannelName(rawName: String): String {
        var name = rawName.lowercase().trim()
        
        // Remove bracketed info like (Local) or (Local UK) or [xx] or IN | or ENT | or MOV | or NEWS |
        name = name.replace(Regex("\\(.*\\)"), "")
        name = name.replace(Regex("\\[.*\\]"), "")
        name = name.replace("in |", "")
        name = name.replace("ent |", "")
        name = name.replace("mov |", "")
        name = name.replace("news |", "")
        
        // Custom specific replacements for spelling variants
        name = name.replace("ekushay", "ekushey")
        name = name.replace("enterr", "enter")
        name = name.replace(" ", "") // remove spaces
        
        // Replace common suffix/prefix words
        val wordsToRemove = listOf("hd", "vip", "tv", "news", "bd", "local", "uk", "national", "sports", "channel", "live", "feed", "online", "stream", "classic", "gold", "world", "ctg", "asia", "hq", "sd")
        for (word in wordsToRemove) {
            name = name.replace(word, "")
        }
        
        // Remove all non-alphanumeric characters
        name = name.replace(Regex("[^a-zA-Z0-9]"), "")
        
        return name.trim()
    }

    fun mergeChannels(originalList: List<LiveChannel>, newList: List<LiveChannel>): List<LiveChannel> {
        val mergedMap = LinkedHashMap<String, LiveChannel>() // Key: cleanName
        
        // 1. Put all original channels in the map
        for (channel in originalList) {
            val clean = cleanChannelName(channel.name)
            val key = if (clean.isNotEmpty()) clean else channel.name.lowercase().trim()
            mergedMap[key] = channel
        }
        
        // 2. Merge new channels
        for (newChan in newList) {
            val clean = cleanChannelName(newChan.name)
            val key = if (clean.isNotEmpty()) clean else newChan.name.lowercase().trim()
            
            val existing = mergedMap[key]
            if (existing != null) {
                // Match found! Check if this stream URL is already present in any of the existing streams
                val urls = listOf(existing.streamUrl, existing.streamUrl2, existing.streamUrl3, existing.streamUrl4, existing.streamUrl5)
                    .filter { it.isNotBlank() }
                    
                if (!urls.contains(newChan.streamUrl)) {
                    // Not a duplicate stream URL! Add it as streamUrl2, 3, 4, or 5
                    val updated = when {
                        existing.streamUrl2.isBlank() -> existing.copy(streamUrl2 = newChan.streamUrl)
                        existing.streamUrl3.isBlank() -> existing.copy(streamUrl3 = newChan.streamUrl)
                        existing.streamUrl4.isBlank() -> existing.copy(streamUrl4 = newChan.streamUrl)
                        existing.streamUrl5.isBlank() -> existing.copy(streamUrl5 = newChan.streamUrl)
                        else -> existing // Already full, keep existing
                    }
                    mergedMap[key] = updated
                }
            } else {
                // No match found, add it as a brand new channel!
                mergedMap[key] = newChan
            }
        }
        
        return mergedMap.values.toList()
    }

    fun determineCategory(name: String, parsedCategory: String): String {
        if (parsedCategory.isNotEmpty() && parsedCategory != "General") {
            return parsedCategory
        }
        val lower = name.lowercase()
        return when {
            lower.contains("sports") || lower.contains("sport") || lower.contains("cup") || 
            lower.contains("cricket") || lower.contains("ten") || lower.contains("willow") || 
            lower.contains("bein") || lower.contains("golf") || lower.contains("espn") || 
            lower.contains("tsn") || lower.contains("football") || lower.contains("nfl") || 
            lower.contains("wtc") || lower.contains("epl") || lower.contains("bfl") ||
            lower.contains("gp") || lower.contains("finals") -> "Sports"
            
            lower.contains("news") || lower.contains("khabar") || lower.contains("bbc") || 
            lower.contains("jazeera") || lower.contains("aaj tak") || lower.contains("cnbc") || 
            lower.contains("msnbc") || lower.contains("abc") || lower.contains("dw") || 
            lower.contains("wion") || lower.contains("independent") || lower.contains("somoy") || 
            lower.contains("dbc") || lower.contains("news18") || lower.contains("republic") || 
            lower.contains("times now") || lower.contains("kolkata tv") || lower.contains("zee 24") ||
            lower.contains("cbs") || lower.contains("cp 24") || lower.contains("business") ||
            lower.contains("weather") || lower.contains("accuweather") -> "News"
            
            lower.contains("kids") || lower.contains("cartoon") || lower.contains("nick") || 
            lower.contains("pogo") || lower.contains("disney") || lower.contains("toon") || 
            lower.contains("goggles") || lower.contains("moonbug") -> "Kids"
            
            lower.contains("quran") || lower.contains("islamic") || lower.contains("deen") || 
            lower.contains("eman") || lower.contains("madani") -> "Islamic"
            
            lower.contains("movie") || lower.contains("cinema") || lower.contains("max") || 
            lower.contains("goldmines") || lower.contains("epix") || lower.contains("hbo") || 
            lower.contains("action") || lower.contains("bollywood") || lower.contains("sphere") ||
            lower.contains("amc") || lower.contains("pix") -> "Movies"
            
            lower.contains("vip") -> "VIP"
            
            else -> "Local TV"
        }
    }

    suspend fun seedM3uPlaylist(context: Context, repository: AppRepository) = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences("database_seed_prefs", Context.MODE_PRIVATE)
        val isSeeded = sharedPrefs.getBoolean("m3u_playlist_seeded_v5", false)
        val hasChannels = repository.allChannels.firstOrNull()?.isNotEmpty() ?: false
        if (isSeeded && hasChannels) return@withContext

        try {
            // 1. Read playlist.m3u
            val inputStream = context.assets.open("playlist.m3u")
            val content = inputStream.bufferedReader().use { it.readText() }
            val originalChannels = parseM3uFromString(content, "General")

            // 2. Read extra_playlist.m3u
            var extraChannels = emptyList<LiveChannel>()
            try {
                val extraInputStream = context.assets.open("extra_playlist.m3u")
                val extraContent = extraInputStream.bufferedReader().use { it.readText() }
                extraChannels = parseM3uFromString(extraContent, "General")
            } catch (extraEx: Exception) {
                android.util.Log.e("DatabaseSeeder", "Error reading extra_playlist.m3u", extraEx)
            }

            // 3. Merge them using our deduplication & multi-source logic
            val mergedChannels = mergeChannels(originalChannels, extraChannels)

            if (mergedChannels.isNotEmpty()) {
                // Clear any existing database channels so we write the pristine merged version
                repository.deleteAllChannels()
                repository.insertAllChannels(mergedChannels)
                sharedPrefs.edit().putBoolean("m3u_playlist_seeded_v5", true).apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("DatabaseSeeder", "Error seeding M3U from assets: ${e.localizedMessage}", e)
        }
    }

    private fun parseM3uFromString(content: String, defaultCategory: String): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        try {
            val reader = java.io.BufferedReader(java.io.StringReader(content))
            var line: String?
            var currentMetadata: String? = null
            var simpleCounter = 1
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXTINF:")) {
                    currentMetadata = trimmed
                } else if (trimmed.startsWith("#EXTM3U")) {
                    continue
                } else if (trimmed.startsWith("#")) {
                    continue
                } else {
                    if (currentMetadata != null) {
                        var name = "Unnamed Channel"
                        var logoUrl = ""
                        var category = defaultCategory

                        val commaIdx = currentMetadata.lastIndexOf(',')
                        if (commaIdx != -1) {
                            name = currentMetadata.substring(commaIdx + 1).trim()
                        }

                        val logoRegex = """tvg-logo="([^"]*)"""".toRegex()
                        val logoMatch = logoRegex.find(currentMetadata)
                        if (logoMatch != null) {
                            logoUrl = logoMatch.groupValues[1]
                        }

                        val groupRegex = """group-title="([^"]*)"""".toRegex()
                        val groupMatch = groupRegex.find(currentMetadata)
                        if (groupMatch != null) {
                            val parsedCat = groupMatch.groupValues[1].trim()
                            if (parsedCat.isNotEmpty()) {
                                category = parsedCat
                            }
                        }

                        name = name.removeSurrounding("\"").trim()
                        if (name.isEmpty()) name = "Channel $simpleCounter"
                        
                        val finalCategory = determineCategory(name, category)

                        if (trimmed.startsWith("http") || trimmed.startsWith("rtmp") || trimmed.startsWith("rtsp")) {
                            channels.add(
                                LiveChannel(
                                    name = name,
                                    category = finalCategory,
                                    logoUrl = logoUrl,
                                    streamUrl = trimmed
                                )
                            )
                            simpleCounter++
                        }
                        currentMetadata = null
                    } else {
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtmp://") || trimmed.startsWith("rtsp://")) {
                            val finalCategory = determineCategory("Channel $simpleCounter", defaultCategory)
                            channels.add(
                                LiveChannel(
                                    name = "Channel $simpleCounter",
                                    category = finalCategory,
                                    logoUrl = "",
                                    streamUrl = trimmed
                                )
                            )
                            simpleCounter++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DatabaseSeeder", "Error parsing M3U: ${e.localizedMessage}", e)
        }
        return channels
    }
}
