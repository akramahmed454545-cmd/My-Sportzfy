package com.example.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Highlight
import com.example.data.LiveChannel
import com.example.data.SportMatch
import com.example.data.BannerAd
import com.example.ui.components.VideoPlayer
import com.example.ui.viewmodel.SportzfyTab
import com.example.ui.viewmodel.SportzfyViewModel
import com.example.ui.viewmodel.SyncState
import com.example.ui.viewmodel.PlayerEngine
import com.example.webserver.NetworkUtils
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: SportzfyViewModel,
    modifier: Modifier = Modifier
) {
    val maintenanceState by viewModel.maintenanceState.collectAsStateWithLifecycle()

    if (maintenanceState.enabled) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0B111E)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFFFF1744).copy(alpha = 0.1f), shape = CircleShape)
                            .border(1.dp, Color(0xFFFF1744).copy(alpha = 0.3f), shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Maintenance Icon",
                            tint = Color(0xFFFF1744),
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "System Maintenance",
                        color = Color(0xFF00E5FF),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .height(2.dp)
                            .width(60.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF00E5FF), Color(0xFF2979FF))
                                )
                            )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1826)),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color(0xFF1E2D4A))
                    ) {
                        Text(
                            text = maintenanceState.message,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        text = "THANK YOU FOR YOUR PATIENCE",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val context = LocalContext.current

    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedSportFilter by viewModel.selectedSportFilter.collectAsStateWithLifecycle()
    val selectedStatusFilter by viewModel.selectedStatusFilter.collectAsStateWithLifecycle()

    val matches by viewModel.allMatches.collectAsStateWithLifecycle()
    val channels by viewModel.allChannels.collectAsStateWithLifecycle()
    val highlights by viewModel.allHighlights.collectAsStateWithLifecycle()
    val notices by viewModel.allNotices.collectAsStateWithLifecycle()

    val activeStreamUrl by viewModel.activeStreamUrl.collectAsStateWithLifecycle()
    val activeStreamTitle by viewModel.activeStreamTitle.collectAsStateWithLifecycle()
    val currentBackupUrls by viewModel.currentBackupUrls.collectAsStateWithLifecycle()
    val currentBackupIndex by viewModel.currentBackupIndex.collectAsStateWithLifecycle()
    val selectedPlayerEngine by viewModel.selectedPlayerEngine.collectAsStateWithLifecycle()
    val webServerUrl by viewModel.webServerUrl.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val bannerAd by viewModel.bannerAd.collectAsStateWithLifecycle()
    val subscribedMatchIds by viewModel.subscribedMatchIds.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Notifications allowed! You will be alerted when subscribed matches go live.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Notification permission is required to receive match alerts.", Toast.LENGTH_SHORT).show()
        }
    }

    // Filter logic
    val activeNotice = notices.firstOrNull { it.active }?.content ?: "(Ads Free). Keep supporting us for the best sports streaming experience!"

    val filteredMatches = matches.filter { match ->
        val matchesSport = selectedSportFilter == "All" || match.sport.equals(selectedSportFilter, ignoreCase = true)
        val matchesStatus = selectedStatusFilter == "All" || match.status.equals(selectedStatusFilter, ignoreCase = true)
        val matchesSearch = searchQuery.isEmpty() || match.title.contains(searchQuery, ignoreCase = true) ||
                match.team1Name.contains(searchQuery, ignoreCase = true) || match.team2Name.contains(searchQuery, ignoreCase = true)
        matchesSport && matchesStatus && matchesSearch
    }

    val filteredChannels = channels.filter { channel ->
        searchQuery.isEmpty() || channel.name.contains(searchQuery, ignoreCase = true) || channel.category.contains(searchQuery, ignoreCase = true)
    }

    val filteredHighlights = highlights.filter { highlight ->
        searchQuery.isEmpty() || highlight.title.contains(searchQuery, ignoreCase = true) ||
                highlight.team1Name.contains(searchQuery, ignoreCase = true) || highlight.team2Name.contains(searchQuery, ignoreCase = true)
    }

    // Modal display flags
    var showNoticeDialog by remember { mutableStateOf(false) }
    var showCopyrightDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var showPlaylistsDialog by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var showEngineDialog by remember { mutableStateOf(false) }
    var showFloatingPlayerDialog by remember { mutableStateOf(false) }
    var showCrashLogDialog by remember { mutableStateOf(false) }
    var showNoUpdateDialog by remember { mutableStateOf(false) }

    if (showPlaylistsDialog) {
        AlertDialog(
            onDismissRequest = { showPlaylistsDialog = false },
            title = { Text("Seeded Playlists") },
            text = {
                Column {
                    Text("Curated HLS / M3U8 Live Streams:")
                    Spacer(modifier = Modifier.height(12.dp))
                    listOf(
                        "🏆 Live Sports Multiplex" to "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8",
                        "🏏 World T20 Stream A" to "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8",
                        "⚽ VIP Sports Arena" to "https://test-streams.mux.dev/x36xhf/x36xhf.m3u8"
                    ).forEach { (title, url) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    viewModel.playStream(url, title)
                                    showPlaylistsDialog = false
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2D4A))
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF00E5FF))
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("Format: HLS Adaptive M3U8", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistsDialog = false }) { Text("Close") }
            }
        )
    }

    if (showSyncDialog) {
        val remoteSyncUrl by viewModel.remoteSyncUrl.collectAsStateWithLifecycle()
        val remoteApiKey by viewModel.remoteApiKey.collectAsStateWithLifecycle()
        val syncState by viewModel.syncState.collectAsStateWithLifecycle()

        var inputUrl by remember { mutableStateOf(remoteSyncUrl) }
        var inputKey by remember { mutableStateOf(remoteApiKey) }

        AlertDialog(
            onDismissRequest = { 
                showSyncDialog = false 
                viewModel.resetSyncState()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Remote Sync Center",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Connect to your central Node.js Express admin panel to download matches, live IPTV feeds, and announcement tickers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray
                    )

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        label = { Text("Server Host URL") },
                        placeholder = { Text("e.g. http://192.168.1.100:3000") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1E2D4A),
                            unfocusedLabelColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("sync_host_input")
                    )

                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        label = { Text("Secure API Key") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF1E2D4A),
                            unfocusedLabelColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("sync_key_input")
                    )

                    val stateVal = syncState
                    when (stateVal) {
                        SyncState.Idle -> {
                            Text(
                                "Status: Idle",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        SyncState.Loading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color(0xFF00E5FF),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    "Connecting and fetching stream database...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF00E5FF),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        is SyncState.Success -> {
                            Text(
                                "✅ Sync Successful! Imported ${stateVal.count} items into local database.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.1f))
                                    .padding(8.dp)
                                    .fillMaxWidth()
                            )
                        }
                        is SyncState.Error -> {
                            Text(
                                "❌ Sync Failed: ${stateVal.message}\nCheck address, server status, and key.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF1744),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color(0xFFFF1744).copy(alpha = 0.1f))
                                    .padding(8.dp)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateRemoteSyncUrl(inputUrl)
                        viewModel.updateRemoteApiKey(inputKey)
                        viewModel.syncWithRemoteServer()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = syncState !is SyncState.Loading,
                    modifier = Modifier.testTag("sync_now_button")
                ) {
                    Text("Sync Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showSyncDialog = false 
                        viewModel.resetSyncState()
                    }
                ) {
                    Text("Close", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF0F1826)
        )
    }

    if (showFloatingPlayerDialog) {
        AlertDialog(
            onDismissRequest = { showFloatingPlayerDialog = false },
            title = { Text("Floating PiP Player Mode") },
            text = {
                Column {
                    Text("Watch streams in PIP (Picture-in-Picture) mode!")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Simply tap the Home button while playing any video stream inside the app, and the video will automatically float on your screen layout.", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { showFloatingPlayerDialog = false }) { Text("OK") }
            }
        )
    }

    if (showCrashLogDialog) {
        AlertDialog(
            onDismissRequest = { showCrashLogDialog = false },
            title = { Text("Diagnostic Console & Logs") },
            text = {
                Column {
                    Text("Active Web Engine Status & Metrics:")
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("ENGINE_STATUS: ACTIVE", color = Color.Green, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("LOCAL_CONSOLE: $webServerUrl", color = Color.Green, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("TOTAL_SEEDED_MATCHES: ${matches.size}", color = Color.Cyan, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("TOTAL_SEEDED_CHANNELS: ${channels.size}", color = Color.Cyan, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("SYSTEM_TIME: 2026-06-28", color = Color.Yellow, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text("No background crash logs detected.", color = Color.White, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCrashLogDialog = false }) { Text("Dismiss") }
            }
        )
    }

    if (updateAvailable != null) {
        val update = updateAvailable!!
        AlertDialog(
            onDismissRequest = {
                if (!update.isMandatory) {
                    viewModel.dismissUpdate()
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Update Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(if (update.isMandatory) "Mandatory Update Required!" else "New Version Available!")
                }
            },
            text = {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "Version: v${update.versionName} (Build ${update.versionCode})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Changelog / Release Notes:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = update.changelog,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (update.isMandatory) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ This is a critical mandatory update. You must install this update to continue using the application.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Would you like to download and install this update automatically from the website?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(update.apkUrl))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Error opening link: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                        }
                        if (!update.isMandatory) {
                            viewModel.dismissUpdate()
                        }
                    }
                ) {
                    Text("Update Now (Download APK)")
                }
            },
            dismissButton = if (!update.isMandatory) {
                {
                    TextButton(onClick = { viewModel.dismissUpdate() }) {
                        Text("Maybe Later")
                    }
                }
            } else null
        )
    }

    if (showNoUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showNoUpdateDialog = false },
            title = { Text("App Update Check") },
            text = { Text("You are currently running the latest version: Sportzfy Live v1.0.0.\n\nEnjoy ads-free streaming and real-time dashboard editing capabilities!") },
            confirmButton = {
                TextButton(onClick = { showNoUpdateDialog = false }) { Text("Great") }
            }
        )
    }

    if (showNoticeDialog) {
        AlertDialog(
            onDismissRequest = { showNoticeDialog = false },
            title = { Text("Sportzfy Announcement") },
            text = { Text(activeNotice) },
            confirmButton = {
                TextButton(onClick = { showNoticeDialog = false }) { Text("Dismiss") }
            }
        )
    }

    if (showCopyrightDialog) {
        AlertDialog(
            onDismissRequest = { showCopyrightDialog = false },
            title = { Text("Copyright Disclaimer") },
            text = { Text("Sportzfy respects intellectual property rights. All live television channels and match feeds are synced from public endpoints managed via our user console website. Please contact us via email if you find any infringing materials.") },
            confirmButton = {
                TextButton(onClick = { showCopyrightDialog = false }) { Text("OK") }
            }
        )
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Join Telegram Channel") },
            text = { Text("Keep supporting us to receive immediate channel announcements, backup domain addresses, and the latest releases. Tap below to subscribe to our group!") },
            confirmButton = {
                TextButton(onClick = { showJoinDialog = false }) { Text("Join Telegram") }
            },
            dismissButton = {
                TextButton(onClick = { showJoinDialog = false }) { Text("Close") }
            }
        )
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Video Quality Settings") },
            text = {
                Column {
                    Text("Select stream fallback player profile:")
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf("Auto (Recommended)", "1080p Full HD", "720p HD", "480p SD", "360p Low-Data").forEach { quality ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showQualityDialog = false }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = quality.startsWith("Auto"), onClick = { showQualityDialog = false })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(quality, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    if (showEngineDialog) {
        val engines = PlayerEngine.values()
        AlertDialog(
            onDismissRequest = { showEngineDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Streaming Engine Suite",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Select your active streaming engine software to run live video feeds. Each engine is optimized with distinct hardware decoders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    engines.forEach { engine ->
                        val isSelected = engine == selectedPlayerEngine
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.08f)
                                    else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    viewModel.updatePlayerEngine(engine)
                                    showEngineDialog = false
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    viewModel.updatePlayerEngine(engine)
                                    showEngineDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color(0xFF00E5FF),
                                    unselectedColor = Color.Gray
                                )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = engine.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color(0xFF00E5FF) else Color.White
                                )
                                Text(
                                    text = engine.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEngineDialog = false }) {
                    Text("Close", color = Color(0xFF00E5FF))
                }
            },
            containerColor = Color(0xFF0F1826)
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF0F1826),
                modifier = Modifier.width(300.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Drawer Header with styled Logo
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SportzfyLogoText(fontSize = 32.sp)
                    }

                    HorizontalDivider(color = Color(0xFF1E2D4A))
                    Spacer(modifier = Modifier.height(16.dp))

                    // Menu items
                    val menuItems = listOf(
                        DrawerItem("Network Stream", Icons.Default.PlayArrow, { viewModel.playStream("https://test-streams.mux.dev/x36xhf/x36xhf.m3u8", "Manual Network Stream") }),
                        DrawerItem("Remote Sync Panel", Icons.Default.Refresh, { showSyncDialog = true }),
                        DrawerItem("Playlists", Icons.Default.List, { showPlaylistsDialog = true }),
                        DrawerItem("Floating Player", Icons.Default.PlayArrow, { showFloatingPlayerDialog = true }),
                        DrawerItem("Video Quality Setting", Icons.Default.Settings, { showQualityDialog = true }),
                        DrawerItem("Select Streaming Player", Icons.Default.PlayArrow, { showEngineDialog = true }),
                        DrawerItem("Crash Log Dialog", Icons.Default.Build, { showCrashLogDialog = true }),
                        DrawerItem("Notice", Icons.Default.Notifications, { showNoticeDialog = true }),
                        DrawerItem("Join Us", Icons.Default.Person, { showJoinDialog = true }),
                        DrawerItem("Copyright", Icons.Default.Info, { showCopyrightDialog = true }),
                        DrawerItem("Share Our App", Icons.Default.Share, {
                            try {
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Sportzfy Live App")
                                    putExtra(android.content.Intent.EXTRA_TEXT, "Watch Live Sports & TV on Sportzfy Live!\nWeb Console: $webServerUrl")
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Sportzfy Live"))
                            } catch (e: Exception) {}
                        }),
                        DrawerItem("Email", Icons.Default.Email, {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                                    data = android.net.Uri.parse("mailto:")
                                    putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("support@sportzfy.live"))
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Feedback on Sportzfy Live")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {}
                        }),
                        DrawerItem("Check for Updates", Icons.Default.Refresh, {
                            viewModel.checkForUpdates()
                            scope.launch {
                                android.widget.Toast.makeText(context, "Checking for latest release from server...", android.widget.Toast.LENGTH_SHORT).show()
                                kotlinx.coroutines.delay(1200)
                                if (viewModel.updateAvailable.value == null) {
                                    showNoUpdateDialog = true
                                }
                            }
                        }),
                        DrawerItem("Exit", Icons.Default.ExitToApp, { System.exit(0) })
                    )

                    menuItems.forEach { item ->
                        NavigationDrawerItem(
                            icon = { Icon(item.icon, contentDescription = item.label, tint = Color(0xFF00E5FF)) },
                            label = { Text(item.label, color = Color.White, style = MaterialTheme.typography.bodyMedium) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                item.onClick()
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Color(0xFF0B111E),
            topBar = {
                // Main Header conforming to the design
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1826))
                        .padding(top = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu drawer", tint = Color.White)
                        }

                        // Logo matching layout
                        SportzfyLogoText()

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { /* Search trigger */ }) {
                                Icon(Icons.Default.Search, contentDescription = "Search icon", tint = Color.White)
                            }
                            IconButton(onClick = { showNoticeDialog = true }) {
                                Icon(Icons.Default.Star, contentDescription = "Favorites", tint = Color.White)
                            }
                            IconButton(onClick = { showNoticeDialog = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                            }
                        }
                    }

                    // Notice horizontal Marquee scrolling band
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF050A12))
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeNotice,
                            color = Color(0xFF00E5FF),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Visible
                        )
                    }
                }
            },
            bottomBar = {
                // Bottom Tab Icons matching image
                NavigationBar(
                    containerColor = Color(0xFF0F1826),
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == SportzfyTab.Home,
                        onClick = { viewModel.selectTab(SportzfyTab.Home) },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00E5FF),
                            selectedTextColor = Color(0xFF00E5FF),
                            indicatorColor = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == SportzfyTab.Categories,
                        onClick = { viewModel.selectTab(SportzfyTab.Categories) },
                        icon = { Icon(Icons.Default.List, contentDescription = "Categories") },
                        label = { Text("Categories") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00E5FF),
                            selectedTextColor = Color(0xFF00E5FF),
                            indicatorColor = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == SportzfyTab.Highlights,
                        onClick = { viewModel.selectTab(SportzfyTab.Highlights) },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Highlights") },
                        label = { Text("Highlights") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00E5FF),
                            selectedTextColor = Color(0xFF00E5FF),
                            indicatorColor = Color(0xFF00E5FF).copy(alpha = 0.15f),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )

                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color(0xFF0B111E))
            ) {
                Column {
                    // Inline Video Player shown globally if a stream is active
                    AnimatedVisibility(visible = activeStreamUrl != null) {
                        activeStreamUrl?.let { url ->
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                VideoPlayer(
                                    videoUrl = url,
                                    title = activeStreamTitle ?: "Live Feed",
                                    selectedEngine = selectedPlayerEngine,
                                    onClose = { viewModel.playStream(null, null) },
                                    onError = { errMsg ->
                                        viewModel.reportPlaybackError(url, activeStreamTitle ?: "Live Feed", errMsg)
                                    }
                                )

                                if (currentBackupUrls.size > 1) {
                                    val currentLabel = if (currentBackupIndex == 0) "Primary Line" else "Backup Line ${currentBackupIndex + 1}"
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = Color(0xFF0F1826)
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2D4A))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(Color(0xFF00E5FF), CircleShape)
                                                )
                                                Column {
                                                    Text(
                                                        text = "ACTIVE LINE",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.Gray
                                                    )
                                                    Text(
                                                        text = currentLabel,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = Color(0xFF00E5FF)
                                                    )
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    val line = viewModel.cycleFallbackStream()
                                                    if (line != null) {
                                                        Toast.makeText(context, "Switched to $line", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF1E2D4A),
                                                    contentColor = Color.White
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "Switch Stream Line",
                                                    modifier = Modifier.size(14.dp),
                                                    tint = Color(0xFF00E5FF)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "CYCLE BACKUP",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Black,
                                                    letterSpacing = 0.5.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                /*
                                // Direct Quick Player Engine Switcher row
                                Text(
                                    text = "Quick Engine Switch:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    PlayerEngine.values().forEach { engine ->
                                        val isSelected = engine == selectedPlayerEngine
                                        Card(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { viewModel.updatePlayerEngine(engine) },
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.2f)
                                                else Color(0xFF0F1826)
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF)) else null
                                        ) {
                                            Text(
                                                text = when(engine) {
                                                    PlayerEngine.EXOPLAYER -> "ExoPlayer"
                                                    PlayerEngine.NATIVE_VIDEO_VIEW -> "System"
                                                    PlayerEngine.WEB_EMBED -> "Web-Embed"
                                                    PlayerEngine.EXTERNAL_APP -> "External"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) Color(0xFF00E5FF) else Color.White,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                                */

                                // Custom Banner Ad Player managed from Admin Panel with redirect link
                                BannerAdView(bannerAd = bannerAd)
                            }
                        }
                    }

                    // Active screen content switcher
                    when (currentTab) {
                        SportzfyTab.Home -> HomeScreenContent(
                            viewModel = viewModel,
                            filteredMatches = filteredMatches,
                            matchesList = matches
                        )
                        SportzfyTab.Categories -> CategoriesScreenContent(
                            viewModel = viewModel,
                            filteredChannels = filteredChannels
                        )
                        SportzfyTab.Highlights -> HighlightsScreenContent(
                            viewModel = viewModel,
                            filteredHighlights = filteredHighlights
                        )

                    }
                }
            }
        }
    }
}

// ---------------- SUB SCREENS ----------------

@Composable
fun HomeScreenContent(
    viewModel: SportzfyViewModel,
    filteredMatches: List<SportMatch>,
    matchesList: List<SportMatch>
) {
    val selectedSportFilter by viewModel.selectedSportFilter.collectAsStateWithLifecycle()
    val selectedStatusFilter by viewModel.selectedStatusFilter.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val subscribedMatchIds by viewModel.subscribedMatchIds.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Notifications allowed! You will be alerted when subscribed matches go live.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Notification permission is required to receive match alerts.", Toast.LENGTH_SHORT).show()
        }
    }
    val webServerUrl by viewModel.webServerUrl.collectAsStateWithLifecycle()
    var activeObsStreams by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val obsDir = java.io.File(context.cacheDir, "obs_stream")
                val activeList = mutableListOf<String>()
                if (obsDir.exists() && obsDir.isDirectory) {
                    val subdirs = obsDir.listFiles { file -> file.isDirectory }
                    if (subdirs != null) {
                        for (subdir in subdirs) {
                            val indexFile = java.io.File(subdir, "index.m3u8")
                            if (indexFile.exists() && indexFile.length() > 0) {
                                activeList.add(subdir.name)
                            }
                        }
                    }
                }
                activeObsStreams = activeList
            } catch (e: Exception) {
                e.printStackTrace()
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    // Counts for Category Badges
    val totalCount = matchesList.size
    val cricketCount = matchesList.count { it.sport.equals("Cricket", ignoreCase = true) }
    val footballCount = matchesList.count { it.sport.equals("Football", ignoreCase = true) }
    val motorSportsCount = matchesList.count { it.sport.equals("MotorSports", ignoreCase = true) }
    val wrestlingCount = matchesList.count { it.sport.equals("Wrestling", ignoreCase = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Category circle selectors exactly as in image
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CategoryCircleBadge("All", "ALL", totalCount, selectedSportFilter == "All") { viewModel.selectSportFilter("All") }
                CategoryCircleBadge("Cricket", "🏏", cricketCount, selectedSportFilter == "Cricket") { viewModel.selectSportFilter("Cricket") }
                CategoryCircleBadge("Football", "⚽", footballCount, selectedSportFilter == "Football") { viewModel.selectSportFilter("Football") }
                CategoryCircleBadge("MotorSports", "🏎️", motorSportsCount, selectedSportFilter == "MotorSports") { viewModel.selectSportFilter("MotorSports") }
                CategoryCircleBadge("Wrestling", "🤼", wrestlingCount, selectedSportFilter == "Wrestling") { viewModel.selectSportFilter("Wrestling") }
            }
        }

        // 2. Horizontal Filter status buttons
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusFilterButton("Recent", selectedStatusFilter == "Recent") { viewModel.selectStatusFilter("Recent") }
                StatusFilterButton("Live", selectedStatusFilter == "Live") { viewModel.selectStatusFilter("Live") }
                StatusFilterButton("Upcoming", selectedStatusFilter == "Upcoming") { viewModel.selectStatusFilter("Upcoming") }
                StatusFilterButton("All Matches", selectedStatusFilter == "All") { viewModel.selectStatusFilter("All") }
            }
        }

        // Display beautiful active streams like a premium online player
        activeObsStreams.forEach { streamKey ->
            item(key = "obs_active_$streamKey") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable {
                            val url = "${webServerUrl}/obs/$streamKey/index.m3u8"
                            viewModel.playStream(url, "Local Stream • $streamKey")
                        }
                        .testTag("obs_live_player_card_$streamKey"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151F32)),
                    border = BorderStroke(1.5.dp, Color(0xFF00E5FF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Live Glowing Icon
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Live Broadcast",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00E5FF))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "LIVE LOCAL BROADCAST",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "OBS Broadcast: ${streamKey.uppercase()}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Tap to tune in and play stream instantly",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray
                                )
                            }
                        }
                        // Play button circle
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play stream",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. Match feed cards
        if (filteredMatches.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Info, contentDescription = "No match", tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No streams match your filter", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            items(filteredMatches) { match ->
                val matchIdStr = match.id.toString()
                val isSubscribed = subscribedMatchIds.contains(matchIdStr)
                MatchFeedCard(
                    match = match,
                    isSubscribed = isSubscribed,
                    onSubscribeToggle = {
                        if (!isSubscribed) {
                            // Request notification permission if enabling subscription
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                
                                if (!hasPermission) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.toggleSubscription(matchIdStr)
                                    Toast.makeText(context, "Alert set for ${match.team1Name} vs ${match.team2Name}!", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                viewModel.toggleSubscription(matchIdStr)
                                Toast.makeText(context, "Alert set for ${match.team1Name} vs ${match.team2Name}!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            viewModel.toggleSubscription(matchIdStr)
                            Toast.makeText(context, "Alert cancelled.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClick = { viewModel.playStream(match.streamUrl, match.title) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun CategoriesScreenContent(
    viewModel: SportzfyViewModel,
    filteredChannels: List<LiveChannel>
) {
    var showSourcePickerChannel by remember { mutableStateOf<LiveChannel?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Live TV & Categories",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (filteredChannels.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No TV channels available.", color = Color.Gray)
            }
        } else {
            // Group by category to mirror TV Guide layout
            val grouped = filteredChannels.groupBy { it.category }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                grouped.forEach { (category, list) ->
                    item {
                        Text(
                            text = category.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.height(((list.size + 2) / 3 * 110).dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            userScrollEnabled = false
                        ) {
                            items(list) { channel ->
                                ChannelItem(
                                    channel = channel,
                                    onClick = {
                                        if (channel.streamUrl2.isNotBlank()) {
                                            showSourcePickerChannel = channel
                                        } else {
                                            viewModel.playStream(channel.streamUrl, channel.name)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSourcePickerChannel != null) {
        val selectedChan = showSourcePickerChannel!!
        val sources = listOf(
            selectedChan.streamUrl to "Server 1 (Primary)",
            selectedChan.streamUrl2 to "Server 2 (Backup)",
            selectedChan.streamUrl3 to "Server 3 (Backup)",
            selectedChan.streamUrl4 to "Server 4 (Backup)",
            selectedChan.streamUrl5 to "Server 5 (Backup)"
        ).filter { it.first.isNotBlank() }

        AlertDialog(
            onDismissRequest = { showSourcePickerChannel = null },
            title = {
                Text(
                    text = "Select Stream Source",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Multiple sources are available for ${selectedChan.name}. Please select below:",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    sources.forEachIndexed { index, (url, label) ->
                        Card(
                            onClick = {
                                viewModel.playStream(url, selectedChan.name)
                                showSourcePickerChannel = null
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF1E2D4A)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourcePickerChannel = null }) {
                    Text("Close", color = Color(0xFF00E5FF))
                }
            },
            containerColor = Color(0xFF0F1826),
            tonalElevation = 6.dp
        )
    }
}

@Composable
fun HighlightsScreenContent(
    viewModel: SportzfyViewModel,
    filteredHighlights: List<Highlight>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Match Highlights Feed",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (filteredHighlights.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No match highlights saved.", color = Color.Gray)
                }
            }
        } else {
            items(filteredHighlights) { highlight ->
                HighlightFeedCard(
                    highlight = highlight,
                    onClick = { viewModel.playStream(highlight.streamUrl, highlight.title) }
                )
            }
        }
    }
}

// ---------------- UI LEAF COMPONENTS ----------------

@Composable
fun SportzfyLogoText(fontSize: androidx.compose.ui.unit.TextUnit = 24.sp) {
    Text(
        text = "Sportzfy",
        fontSize = fontSize,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = FontFamily.SansSerif,
        color = Color(0xFF00E5FF),
        style = LocalTextStyle.current.copy(
            shadow = Shadow(
                color = Color(0xFFFF1744),
                blurRadius = 3f,
                offset = androidx.compose.ui.geometry.Offset(2f, 2f)
            )
        )
    )
}

@Composable
fun CategoryCircleBadge(
    label: String,
    emoji: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(Color(0xFF0F1826), shape = CircleShape)
                .border(
                    BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E2D4A)
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 28.sp)

            // Red count bubble exactly like reference image
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        .background(Color(0xFFFF1744), shape = CircleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = count.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = if (isSelected) Color(0xFF00E5FF) else Color.White,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun StatusFilterButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF0F1826)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E2D4A)
        ),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color(0xFF00E5FF) else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun MatchFeedCard(
    match: SportMatch,
    isSubscribed: Boolean,
    onSubscribeToggle: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("match_item_card"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1826)),
        border = BorderStroke(1.dp, Color(0xFF1E2D4A)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = match.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))

                // Pulsing/Live indicator badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = match.time,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold
                    )
                    if (match.status.equals("Live", ignoreCase = true)) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFF1744), shape = RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Live",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Teams Row (Team 1 VS Team 2)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Team 1
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f), shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(match.team1Name.take(3).uppercase(), fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = match.team1Name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "VS",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                // Team 2
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = match.team2Name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFFF1744).copy(alpha = 0.15f), shape = CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(match.team2Name.take(3).uppercase(), fontSize = 11.sp, color = Color(0xFFFF1744), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = Color(0xFF1E2D4A).copy(alpha = 0.5f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Badge displaying status/sport
                Box(
                    modifier = Modifier
                        .background(Color(0xFF00E5FF).copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = match.sport,
                        color = Color(0xFF00E5FF),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Right side: Bell icon + label (Subscribe / Alert Set)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSubscribeToggle() }
                        .background(if (isSubscribed) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isSubscribed) Color(0xFF00E5FF) else Color(0xFF1E2D4A),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = if (isSubscribed) "Cancel alert" else "Set alert",
                        tint = if (isSubscribed) Color(0xFF00E5FF) else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isSubscribed) "Alert Set" else "Subscribe",
                        color = if (isSubscribed) Color(0xFF00E5FF) else Color.Gray,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelItem(
    channel: LiveChannel,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1826)),
        border = BorderStroke(1.dp, Color(0xFF1E2D4A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(105.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0xFF070B11), shape = RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, Color(0xFF1E2D4A)), shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                var isError by remember { mutableStateOf(false) }
                if (channel.logoUrl.isNotBlank() && !isError) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        onError = { isError = true }
                    )
                } else {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "alpha"
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(Color(0xFFFF1744).copy(alpha = alpha), shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "LIVE",
                    color = Color(0xFFFF1744),
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun HighlightFeedCard(
    highlight: Highlight,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1826)),
        border = BorderStroke(1.dp, Color(0xFF1E2D4A)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = "Cup Icon", tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = highlight.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.LightGray,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = highlight.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Teams Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF050A12), shape = RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(highlight.team1Name, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("VS", color = Color(0xFFFF1744), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold)
                Text(highlight.team2Name, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// Model classes
data class DrawerItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit
)

@Composable
fun BannerAdView(bannerAd: BannerAd?, modifier: Modifier = Modifier) {
    if (bannerAd == null || !bannerAd.enabled || bannerAd.mediaUrl.isBlank()) return

    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable {
                if (bannerAd.clickUrl.isNotBlank()) {
                    try {
                        uriHandler.openUri(bannerAd.clickUrl)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1826)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2D4A))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp, max = 150.dp),
            contentAlignment = Alignment.Center
        ) {
            when (bannerAd.mediaType.lowercase()) {
                "video" -> {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.VideoView(ctx).apply {
                                setVideoURI(android.net.Uri.parse(bannerAd.mediaUrl))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    mp.setVolume(0f, 0f) // Muted loop
                                    start()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                    )
                }
                "gif" -> {
                    AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = false
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    domStorageEnabled = false
                                }
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                webViewClient = android.webkit.WebViewClient()
                                val html = """
                                    <html>
                                    <body style="margin:0;padding:0;background:transparent;display:flex;justify-content:center;align-items:center;height:100vh;">
                                        <img src="${bannerAd.mediaUrl}" style="max-width:100%;max-height:100%;object-fit:contain;border-radius:12px;" />
                                    </body>
                                    </html>
                                """.trimIndent()
                                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                    )
                }
                else -> {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(bannerAd.mediaUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Advertisement Banner",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 130.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                    )
                }
            }

            if (bannerAd.clickUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Ad • Visit Link",
                        color = Color(0xFF00E5FF),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
