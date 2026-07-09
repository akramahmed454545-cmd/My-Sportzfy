package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.ui.viewmodel.PlayerEngine

@Composable
fun VideoPlayer(
    videoUrl: String,
    title: String,
    selectedEngine: PlayerEngine,
    onClose: () -> Unit,
    onError: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Trigger auto-launch if engine is set to External App
    LaunchedEffect(videoUrl, selectedEngine) {
        if (selectedEngine == PlayerEngine.EXTERNAL_APP) {
            launchExternalPlayer(context, videoUrl, title)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1826)),
        elevation = CardDefaults.cardElevation(8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Now Streaming via ${selectedEngine.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF00E5FF)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedEngine != PlayerEngine.EXTERNAL_APP) {
                        IconButton(onClick = { launchExternalPlayer(context, videoUrl, title) }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Open in external software",
                                tint = Color(0xFF00E5FF)
                            )
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close player",
                            tint = Color.White
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                when (selectedEngine) {
                    PlayerEngine.EXOPLAYER -> {
                        ExoPlayerWidget(videoUrl = videoUrl, onError = onError)
                    }
                    PlayerEngine.NATIVE_VIDEO_VIEW -> {
                        NativeVideoViewWidget(videoUrl = videoUrl, onError = onError)
                    }
                    PlayerEngine.WEB_EMBED -> {
                        WebEmbedWidget(videoUrl = videoUrl)
                    }
                    PlayerEngine.EXTERNAL_APP -> {
                        ExternalAppWidget(
                            videoUrl = videoUrl,
                            title = title,
                            onLaunch = { launchExternalPlayer(context, videoUrl, title) }
                        )
                    }
                }
            }
            
            /*
            // Brief engine details helper
            Text(
                text = selectedEngine.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            */
        }
    }
}

@Composable
fun ExoPlayerWidget(videoUrl: String, onError: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
        
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        isLoading = (state == Player.STATE_BUFFERING)
                        if (state == Player.STATE_READY) {
                            errorMessage = null
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        isLoading = false
                        val msg = error.localizedMessage ?: "Unknown streaming error"
                        errorMessage = msg
                        android.util.Log.e("ExoPlayerWidget", "Playback error: ${error.errorCodeName}", error)
                        onError(msg)
                    }
                })
            }
    }

    LaunchedEffect(videoUrl) {
        errorMessage = null
        isLoading = true
        val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isLoading && errorMessage == null) {
            CircularProgressIndicator(color = Color(0xFF00E5FF))
        }
        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Stream Connection Error",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Red,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        errorMessage = null
                        isLoading = true
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                ) {
                    Text("Retry Stream", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun NativeVideoViewWidget(videoUrl: String, onError: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    var isLoading by remember { mutableStateOf(true) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                }
            },
            update = { view ->
                val currentTag = view.tag as? String
                if (currentTag != videoUrl) {
                    view.tag = videoUrl
                    isLoading = true
                    view.setVideoURI(Uri.parse(videoUrl))
                    view.setOnPreparedListener { mp ->
                        isLoading = false
                        mp.isLooping = true
                        view.start()
                    }
                    view.setOnErrorListener { _, what, extra ->
                        isLoading = false
                        onError("Native VideoView Error code: what=$what extra=$extra")
                        false
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isLoading) {
            CircularProgressIndicator(color = Color(0xFF00E5FF))
        }
    }
}

@Composable
fun WebEmbedWidget(videoUrl: String, modifier: Modifier = Modifier) {
    var isLoading by remember { mutableStateOf(true) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                        }
                    }
                    webChromeClient = WebChromeClient()
                }
            },
            update = { view ->
                val currentTag = view.tag as? String
                if (currentTag != videoUrl) {
                    view.tag = videoUrl
                    isLoading = true
                    view.loadUrl(videoUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isLoading) {
            CircularProgressIndicator(color = Color(0xFF00E5FF))
        }
    }
}

@Composable
fun ExternalAppWidget(
    videoUrl: String,
    title: String,
    onLaunch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF14223C))
            .clickable { onLaunch() }
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Color(0xFF00E5FF),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Launched Stream in External Player",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "If your external app (VLC, MX Player) did not open automatically, tap anywhere inside this box to retry.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onLaunch,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Open Stream Now", fontWeight = FontWeight.Bold)
        }
    }
}

fun launchExternalPlayer(context: Context, videoUrl: String, title: String) {
    val uri = Uri.parse(videoUrl)
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/*")
        putExtra("title", title)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val vlcIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setPackage("org.videolan.vlc")
        setDataAndType(uri, "video/*")
        putExtra("title", title)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val mxIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setPackage("com.mxtech.videoplayer.ad")
        setDataAndType(uri, "video/*")
        putExtra("title", title)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(vlcIntent)
    } catch (e1: Exception) {
        try {
            context.startActivity(mxIntent)
        } catch (e2: Exception) {
            try {
                val chooser = android.content.Intent.createChooser(intent, "Play Live Stream with:")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e3: Exception) {
                Toast.makeText(context, "No external video player found!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
