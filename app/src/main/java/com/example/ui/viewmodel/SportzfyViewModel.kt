package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.webserver.AdminWebServer
import com.example.webserver.NetworkUtils
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class SyncState {
    object Idle : SyncState()
    object Loading : SyncState()
    data class Success(val count: Int) : SyncState()
    data class Error(val message: String) : SyncState()
}

enum class PlayerEngine(val displayName: String, val description: String) {
    EXOPLAYER("ExoPlayer (High-Performance)", "Best buffering & adaptive live HLS streaming (Recommended)"),
    NATIVE_VIDEO_VIEW("Native System Player", "Lightweight default system media player fallback"),
    WEB_EMBED("Web-Embed Player", "Sandbox WebView player for web-hosted feeds"),
    EXTERNAL_APP("External Player App", "Launches streams in external software like VLC, MX Player, or Playit")
}

class SportzfyViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = AppRepository(
        database.sportMatchDao(),
        database.liveChannelDao(),
        database.highlightDao(),
        database.noticeDao(),
        database.bannerAdDao()
    )

    private val sharedPrefs = application.getSharedPreferences("sportzfy_prefs", Context.MODE_PRIVATE)

    private val _remoteSyncUrl = MutableStateFlow(sharedPrefs.getString("remote_sync_url", "http://192.168.1.100:3000") ?: "http://192.168.1.100:3000")
    val remoteSyncUrl: StateFlow<String> = _remoteSyncUrl.asStateFlow()

    private val _remoteApiKey = MutableStateFlow(sharedPrefs.getString("remote_api_key", "sportzfy_secret_key") ?: "sportzfy_secret_key")
    val remoteApiKey: StateFlow<String> = _remoteApiKey.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun updateRemoteSyncUrl(url: String) {
        _remoteSyncUrl.value = url
        sharedPrefs.edit().putString("remote_sync_url", url).apply()
    }

    fun updateRemoteApiKey(key: String) {
        _remoteApiKey.value = key
        sharedPrefs.edit().putString("remote_api_key", key).apply()
    }

    fun resetSyncState() {
        _syncState.value = SyncState.Idle
    }

    fun syncWithRemoteServer() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _syncState.value = SyncState.Loading
            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val baseUrl = _remoteSyncUrl.value.trim().removeSuffix("/")
            val syncUrl = "$baseUrl/api/secure/data?key=${_remoteApiKey.value.trim()}"

            val request = Request.Builder()
                .url(syncUrl)
                .addHeader("X-API-KEY", _remoteApiKey.value.trim())
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _syncState.value = SyncState.Error("Server returned code ${response.code}: ${response.message}")
                        return@launch
                    }

                    val bodyString = response.body?.string()
                    if (bodyString.isNullOrEmpty()) {
                        _syncState.value = SyncState.Error("Server response is empty")
                        return@launch
                    }

                    val json = JSONObject(bodyString)
                    
                    // Parse Matches
                    val matchesArray = json.optJSONArray("matches") ?: JSONArray()
                    val matchesList = mutableListOf<SportMatch>()
                    for (i in 0 until matchesArray.length()) {
                        val mObj = matchesArray.getJSONObject(i)
                        matchesList.add(SportMatch(
                            id = mObj.optLong("id", 0),
                            title = mObj.optString("title", "Unnamed"),
                            sport = mObj.optString("sport", "All"),
                            team1Name = mObj.optString("team1Name", ""),
                            team1Logo = mObj.optString("team1Logo", ""),
                            team2Name = mObj.optString("team2Name", ""),
                            team2Logo = mObj.optString("team2Logo", ""),
                            time = mObj.optString("time", "LIVE"),
                            status = mObj.optString("status", "Upcoming"),
                            streamUrl = mObj.optString("streamUrl", "")
                        ))
                    }

                    // Parse Channels
                    val channelsArray = json.optJSONArray("channels") ?: JSONArray()
                    val channelsList = mutableListOf<LiveChannel>()
                    for (i in 0 until channelsArray.length()) {
                        val cObj = channelsArray.getJSONObject(i)
                        channelsList.add(LiveChannel(
                            id = cObj.optLong("id", 0),
                            name = cObj.optString("name", "Unnamed"),
                            category = cObj.optString("category", "Sports"),
                            logoUrl = cObj.optString("logoUrl", ""),
                            streamUrl = cObj.optString("streamUrl", "")
                        ))
                    }

                    // Parse Highlights
                    val highlightsArray = json.optJSONArray("highlights") ?: JSONArray()
                    val highlightsList = mutableListOf<Highlight>()
                    for (i in 0 until highlightsArray.length()) {
                        val hObj = highlightsArray.getJSONObject(i)
                        highlightsList.add(Highlight(
                            id = hObj.optLong("id", 0),
                            title = hObj.optString("title", ""),
                            team1Name = hObj.optString("team1Name", ""),
                            team1Logo = hObj.optString("team1Logo", ""),
                            team2Name = hObj.optString("team2Name", ""),
                            team2Logo = hObj.optString("team2Logo", ""),
                            date = hObj.optString("date", ""),
                            streamUrl = hObj.optString("streamUrl", "")
                        ))
                    }

                    // Parse Notices
                    val noticesArray = json.optJSONArray("notices") ?: JSONArray()
                    val noticesList = mutableListOf<Notice>()
                    for (i in 0 until noticesArray.length()) {
                        val nObj = noticesArray.getJSONObject(i)
                        noticesList.add(Notice(
                            id = nObj.optLong("id", 0),
                            content = nObj.optString("content", ""),
                            active = nObj.optBoolean("active", true)
                        ))
                    }

                    // Parse and insert/update Banner Ad if present in JSON payload
                    val adObj = json.optJSONObject("bannerAd")
                    if (adObj != null) {
                        val parsedAd = BannerAd(
                            id = adObj.optLong("id", 1),
                            mediaType = adObj.optString("mediaType", "image"),
                            mediaUrl = adObj.optString("mediaUrl", ""),
                            clickUrl = adObj.optString("clickUrl", ""),
                            enabled = adObj.optBoolean("enabled", false)
                        )
                        repository.insertOrUpdateBannerAd(parsedAd)
                    }

                    // Perform database bulk operations in room
                    repository.deleteAllMatches()
                    repository.insertAllMatches(matchesList)

                    repository.deleteAllChannels()
                    repository.insertAllChannels(channelsList)

                    repository.deleteAllHighlights()
                    repository.insertAllHighlights(highlightsList)

                    repository.deleteAllNotices()
                    repository.insertAllNotices(noticesList)

                    val totalSynced = matchesList.size + channelsList.size + highlightsList.size + noticesList.size
                    _syncState.value = SyncState.Success(totalSynced)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _syncState.value = SyncState.Error(e.message ?: "Unknown connection error")
            }
        }
    }

    // Raw database flows
    val allMatches: StateFlow<List<SportMatch>> = repository.allMatches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allChannels: StateFlow<List<LiveChannel>> = repository.allChannels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allHighlights: StateFlow<List<Highlight>> = repository.allHighlights
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allNotices: StateFlow<List<Notice>> = repository.allNotices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bannerAd: StateFlow<BannerAd?> = repository.bannerAdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // UI state states
    private val _currentTab = MutableStateFlow(SportzfyTab.Home)
    val currentTab: StateFlow<SportzfyTab> = _currentTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedSportFilter = MutableStateFlow("All") // "All", "Cricket", "Football", "MotorSports", "Wrestling"
    val selectedSportFilter: StateFlow<String> = _selectedSportFilter.asStateFlow()

    private val _selectedStatusFilter = MutableStateFlow("Live") // "Recent", "Live", "Upcoming"
    val selectedStatusFilter: StateFlow<String> = _selectedStatusFilter.asStateFlow()

    // Active media state
    private val _activeStreamUrl = MutableStateFlow<String?>(null)
    val activeStreamUrl: StateFlow<String?> = _activeStreamUrl.asStateFlow()

    private val _activeStreamTitle = MutableStateFlow<String?>(null)
    val activeStreamTitle: StateFlow<String?> = _activeStreamTitle.asStateFlow()

    private val _currentBackupUrls = MutableStateFlow<List<String>>(emptyList())
    val currentBackupUrls: StateFlow<List<String>> = _currentBackupUrls.asStateFlow()

    private val _currentBackupIndex = MutableStateFlow<Int>(0)
    val currentBackupIndex: StateFlow<Int> = _currentBackupIndex.asStateFlow()

    private val _selectedPlayerEngine = MutableStateFlow<PlayerEngine>(
        try {
            PlayerEngine.valueOf(sharedPrefs.getString("player_engine", PlayerEngine.EXOPLAYER.name) ?: PlayerEngine.EXOPLAYER.name)
        } catch (e: Exception) {
            PlayerEngine.EXOPLAYER
        }
    )
    val selectedPlayerEngine: StateFlow<PlayerEngine> = _selectedPlayerEngine.asStateFlow()

    fun updatePlayerEngine(engine: PlayerEngine) {
        _selectedPlayerEngine.value = engine
        sharedPrefs.edit().putString("player_engine", engine.name).apply()
    }

    // Notification & Subscription Logic
    private val _subscribedMatchIds = MutableStateFlow<Set<String>>(
        sharedPrefs.getStringSet("subscribed_matches", emptySet()) ?: emptySet()
    )
    val subscribedMatchIds: StateFlow<Set<String>> = _subscribedMatchIds.asStateFlow()

    fun toggleSubscription(matchId: String) {
        val currentSet = _subscribedMatchIds.value.toMutableSet()
        if (currentSet.contains(matchId)) {
            currentSet.remove(matchId)
        } else {
            currentSet.add(matchId)
        }
        _subscribedMatchIds.value = currentSet
        sharedPrefs.edit().putStringSet("subscribed_matches", currentSet).apply()
    }

    // Web Server info
    private val _webServerUrl = MutableStateFlow("Starting...")
    val webServerUrl: StateFlow<String> = _webServerUrl.asStateFlow()

    private val webServer = AdminWebServer(application, repository)

    data class UpdateConfig(
        val versionCode: Int,
        val versionName: String,
        val changelog: String,
        val apkUrl: String,
        val isMandatory: Boolean
    )

    private val _updateAvailable = MutableStateFlow<UpdateConfig?>(null)
    val updateAvailable: StateFlow<UpdateConfig?> = _updateAvailable.asStateFlow()

    fun dismissUpdate() {
        _updateAvailable.value = null
    }

    data class MaintenanceState(
        val enabled: Boolean = false,
        val message: String = "We are currently performing scheduled server maintenance. We'll be back shortly!"
    )

    private val _maintenanceState = MutableStateFlow(MaintenanceState())
    val maintenanceState: StateFlow<MaintenanceState> = _maintenanceState.asStateFlow()

    fun checkMaintenanceMode() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val baseUrl = _remoteSyncUrl.value.trim().removeSuffix("/")
            val url = "$baseUrl/api/maintenance"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string()
                        if (!bodyString.isNullOrEmpty()) {
                            val json = JSONObject(bodyString)
                            val enabled = json.optBoolean("enabled", false)
                            val message = json.optString("message", "We are currently performing scheduled server maintenance. We'll be back shortly!")
                            _maintenanceState.value = MaintenanceState(enabled, message)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SportzfyViewModel", "Error checking maintenance mode: ${e.message}")
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val baseUrl = _remoteSyncUrl.value.trim().removeSuffix("/")
            val checkUrl = if (baseUrl.isNotEmpty() && !baseUrl.contains("192.168.1.100")) {
                "$baseUrl/api/app-update"
            } else {
                "http://127.0.0.1:8080/api/app-update"
            }

            val request = Request.Builder()
                .url(checkUrl)
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string()
                        if (!bodyString.isNullOrEmpty()) {
                            val json = JSONObject(bodyString)
                            val serverVersionCode = json.optInt("versionCode", 1)
                            val serverVersionName = json.optString("versionName", "1.0.0")
                            val changelog = json.optString("changelog", "No release notes.")
                            val apkUrl = json.optString("apkUrl", "")
                            val isMandatory = json.optBoolean("isMandatory", false)

                            val pInfo = getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0)
                            val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                pInfo.longVersionCode
                            } else {
                                pInfo.versionCode.toLong()
                            }

                            if (serverVersionCode > currentVersionCode) {
                                _updateAvailable.value = UpdateConfig(
                                    versionCode = serverVersionCode,
                                    versionName = serverVersionName,
                                    changelog = changelog,
                                    apkUrl = apkUrl,
                                    isMandatory = isMandatory
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SportzfyViewModel", "Error checking for updates", e)
            }
        }
    }

    init {
        viewModelScope.launch {
            // Start parallel periodic maintenance checks
            launch(kotlinx.coroutines.Dispatchers.IO) {
                while (true) {
                    checkMaintenanceMode()
                    kotlinx.coroutines.delay(10000)
                }
            }

            // 1. Seed if empty
            DatabaseSeeder.seedDatabaseIfEmpty(repository)
            DatabaseSeeder.seedM3uPlaylist(application, repository)

            // 2. Start web server
            val ip = NetworkUtils.getLocalIpAddress()
            val url = webServer.start(8080)
            if (url.startsWith("http")) {
                _webServerUrl.value = "http://$ip:8080"
            } else {
                _webServerUrl.value = url
            }

            // 3. Delay and check for updates on startup
            kotlinx.coroutines.delay(2000)
            checkForUpdates()
        }
    }

    override fun onCleared() {
        super.onCleared()
        webServer.stop()
    }

    // Setters
    fun selectTab(tab: SportzfyTab) {
        _currentTab.value = tab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectSportFilter(sport: String) {
        _selectedSportFilter.value = sport
    }

    fun selectStatusFilter(status: String) {
        _selectedStatusFilter.value = status
    }

    fun playStream(url: String?, title: String?) {
        _activeStreamUrl.value = url
        _activeStreamTitle.value = title
        if (url == null) {
            _currentBackupUrls.value = emptyList()
            _currentBackupIndex.value = 0
        } else {
            val ch = allChannels.value.find { it.streamUrl == url || it.name == title }
            val list = mutableListOf<String>()
            if (ch != null) {
                if (ch.streamUrl.isNotBlank()) list.add(ch.streamUrl)
                if (ch.streamUrl2.isNotBlank()) list.add(ch.streamUrl2)
                if (ch.streamUrl3.isNotBlank()) list.add(ch.streamUrl3)
                if (ch.streamUrl4.isNotBlank()) list.add(ch.streamUrl4)
                if (ch.streamUrl5.isNotBlank()) list.add(ch.streamUrl5)
            } else {
                list.add(url)
            }
            _currentBackupUrls.value = list
            val idx = list.indexOf(url)
            _currentBackupIndex.value = if (idx >= 0) idx else 0
        }
    }

    fun cycleFallbackStream(): String? {
        val urls = _currentBackupUrls.value
        if (urls.size <= 1) return null
        val nextIndex = (_currentBackupIndex.value + 1) % urls.size
        _currentBackupIndex.value = nextIndex
        _activeStreamUrl.value = urls[nextIndex]
        return if (nextIndex == 0) "Primary Line" else "Backup Line ${nextIndex + 1}"
    }

    fun reportPlaybackError(streamUrl: String, title: String, errorMsg: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val baseUrl = _remoteSyncUrl.value.trim().removeSuffix("/")
            val reportUrl = "$baseUrl/api/stream-report"

            val json = JSONObject().apply {
                put("channelName", title)
                put("streamUrl", streamUrl)
                put("error", errorMsg)
            }

            try {
                val dbChannels = repository.allChannels.first()
                val matchedCh = dbChannels.find { it.streamUrl == streamUrl || it.name == title }
                if (matchedCh != null) {
                    json.put("channelId", matchedCh.id)
                }
            } catch (e: Exception) {
                Log.e("SportzfyViewModel", "Error resolving channel ID for report: ${e.message}")
            }

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(reportUrl)
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d("SportzfyViewModel", "Successfully reported stream error to backend: $streamUrl")
                    } else {
                        Log.e("SportzfyViewModel", "Failed to report stream error to backend. Status: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("SportzfyViewModel", "Network error reporting stream failure: ${e.message}")
            }
        }
    }

    fun updateBannerAd(mediaType: String, mediaUrl: String, clickUrl: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.insertOrUpdateBannerAd(BannerAd(
                id = 1,
                mediaType = mediaType,
                mediaUrl = mediaUrl,
                clickUrl = clickUrl,
                enabled = enabled
            ))
        }
    }

    // Notice modification directly (for testing/convenience)
    fun updateNotice(content: String) {
        viewModelScope.launch {
            val list = repository.allNotices.first()
            if (list.isNotEmpty()) {
                repository.updateNotice(list[0].copy(content = content))
            } else {
                repository.insertNotice(Notice(content = content))
            }
        }
    }

    private suspend fun <T> Flow<T>.first(): T {
        var result: T? = null
        try {
            this.collect {
                result = it
                throw kotlinx.coroutines.CancellationException()
            }
        } catch (e: Exception) {
            // expected
        }
        return result ?: throw IllegalStateException("Flow was empty")
    }
}

enum class SportzfyTab {
    Home,
    Categories,
    Highlights
}
