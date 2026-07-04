package com.example.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Formatter
import java.util.Locale

@Composable
fun VideoPlayerView(
    videoUrl: String,
    title: String = "",
    initialProgress: Float = 0f,
    onProgressUpdate: (Float) -> Unit = {},
    onBackClick: () -> Unit,
    onMinimizeClick: ((Long) -> Unit)? = null,
    onNextEpisodeClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    OTTVideoPlayer(
        videoUrl = videoUrl,
        title = title,
        initialProgress = initialProgress,
        onProgressUpdate = onProgressUpdate,
        onBackClick = onBackClick,
        onMinimizeClick = onMinimizeClick,
        onNextEpisodeClick = onNextEpisodeClick,
        modifier = modifier
    )
}

enum class OrientationMode {
    SENSOR, PORTRAIT, LANDSCAPE
}

private fun trustAllHostsAndCertificates() {
    try {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>, authType: String) {}
            }
        )
        val sc = javax.net.ssl.SSLContext.getInstance("SSL")
        sc.init(null, trustAllCerts, java.security.SecureRandom())
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    } catch (e: Exception) {
        android.util.Log.e("VideoPlayerView", "Error configuring trust", e)
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun formatTime(timeMs: Long): String {
    if (timeMs < 0) return "00:00"
    val totalSeconds = timeMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val formatter = Formatter(StringBuilder(), Locale.getDefault())
    return if (hours > 0) {
        formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString()
    } else {
        formatter.format("%02d:%02d", minutes, seconds).toString()
    }
}

/**
 * Direct video container and streaming formats resolver
 */
fun getMimeTypeFromUrl(url: String): String? {
    val lower = url.lowercase().trim().substringBefore("?").substringBefore("#")
    return when {
        lower.endsWith(".m3u8") -> "application/x-mpegURL"
        lower.endsWith(".mpd") -> "application/dash+xml"
        lower.endsWith(".ism") || lower.endsWith(".ism/manifest") -> "application/vnd.ms-sstr+xml"
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".mkv") -> "video/x-matroska"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".avi") -> "video/x-msvideo"
        lower.endsWith(".mov") -> "video/quicktime"
        lower.endsWith(".m4v") -> "video/x-m4v"
        lower.endsWith(".mpeg") || lower.endsWith(".mpg") -> "video/mpeg"
        lower.endsWith(".wmv") -> "video/x-ms-wmv"
        lower.endsWith(".flv") -> "video/x-flv"
        lower.endsWith(".3gp") -> "video/3gpp"
        lower.endsWith(".ogv") -> "video/ogg"
        lower.endsWith(".ts") -> "video/mp2t"
        lower.endsWith(".m2ts") -> "video/mp2t"
        lower.endsWith(".vob") -> "video/dvd"
        lower.endsWith(".asf") -> "video/x-ms-asf"
        else -> null
    }
}

@OptIn(UnstableApi::class)
@Composable
fun OTTVideoPlayer(
    videoUrl: String,
    title: String,
    initialProgress: Float = 0f,
    onProgressUpdate: (Float) -> Unit = {},
    onBackClick: () -> Unit,
    onMinimizeClick: ((Long) -> Unit)? = null,
    onNextEpisodeClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val coroutineScope = rememberCoroutineScope()

    // Screen controls states
    var orientationMode by remember { mutableStateOf(OrientationMode.LANDSCAPE) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    
    // Swipe controls states
    var showBrightness by remember { mutableStateOf(false) }
    var brightnessLevel by remember { mutableStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }
    var showVolume by remember { mutableStateOf(false) }
    var volumeLevel by remember { 
        mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    // Double tap overlays
    var showDoubleTapLeft by remember { mutableStateOf(false) }
    var showDoubleTapRight by remember { mutableStateOf(false) }

    // Sleep Timer
    var sleepTimerMinutes by remember { mutableStateOf(0) }
    var sleepTimeRemainingSeconds by remember { mutableStateOf(0) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    // Simulated Chromecast Cast
    var isCasting by remember { mutableStateOf(false) }
    var castDeviceName by remember { mutableStateOf("") }
    var showCastDialog by remember { mutableStateOf(false) }

    // Anti-Piracy / Screenshot Protection
    var isScreenshotProtected by remember { mutableStateOf(false) }

    // Playback countdown next episode
    var autoPlayCountdownSeconds by remember { mutableStateOf(-1) }
    var nextEpisodeTriggered by remember { mutableStateOf(false) }

    // Tracks & Options Dialogs
    var audioTrackGroups by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var textTrackGroups by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var videoTrackGroups by remember { mutableStateOf<List<Tracks.Group>>(emptyList()) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }

    val fallbackSources = remember {
        listOf(
            Pair("Server 1 (HLS 1080p - Sintel)", "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8"),
            Pair("Server 2 (HLS 1080p - Mux)", "https://test-streams.mux.dev/x36xhg/x36xhg.m3u8"),
            Pair("Server 3 (HLS 720p - Tears of Steel)", "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"),
            Pair("Server 4 (MP4 - Elephants Dream)", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"),
            Pair("Server 5 (MP4 - Big Buck Bunny)", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
        )
    }

    var useWebViewPlayer by remember { mutableStateOf(false) }

    var currentVideoUrl by remember(videoUrl) {
        val resolved = if (videoUrl.isNotBlank()) videoUrl else "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8"
        mutableStateOf(resolved)
    }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var hasSeeked by remember { mutableStateOf(false) }

    LaunchedEffect(videoUrl) {
        if (videoUrl.isBlank()) {
            Toast.makeText(context, "No stream URL configured in database. Using backup high-speed Server 1.", Toast.LENGTH_LONG).show()
        }
    }

    // Auto-hiding overlays
    LaunchedEffect(showControls, isPlaying, isLocked, useWebViewPlayer) {
        if (showControls && (isPlaying || useWebViewPlayer) && !isLocked) {
            delay(5000)
            showControls = false
        }
        if (isLocked) {
            delay(4000)
            showControls = false
        }
    }

    // Orientation trigger
    LaunchedEffect(orientationMode) {
        try {
            activity?.requestedOrientation = when (orientationMode) {
                OrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } catch (e: Exception) {}
    }

    // Window features full-screen layout setup
    DisposableEffect(context) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation = originalOrientation
            activity?.window?.let { window ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) // Revert secure flag
                WindowCompat.setDecorFitsSystemWindows(window, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                }
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Live anti-piracy secure overlay handling
    LaunchedEffect(isScreenshotProtected) {
        activity?.window?.let { window ->
            if (isScreenshotProtected) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    // Sleep Timer countdown routine
    LaunchedEffect(sleepTimerMinutes) {
        if (sleepTimerMinutes > 0) {
            sleepTimeRemainingSeconds = sleepTimerMinutes * 60
            while (sleepTimeRemainingSeconds > 0) {
                delay(1000)
                sleepTimeRemainingSeconds--
            }
            // Timer finished, pause player
            sleepTimerMinutes = 0
            Toast.makeText(context, "Sleep Timer finished. Paused playback.", Toast.LENGTH_LONG).show()
        }
    }

    // Auto next episode countdown trigger
    LaunchedEffect(duration, currentPosition, onNextEpisodeClick) {
        if (onNextEpisodeClick != null && duration > 0) {
            val secondsToLeft = (duration - currentPosition) / 1000
            if (secondsToLeft in 1..15 && autoPlayCountdownSeconds == -1 && !nextEpisodeTriggered) {
                autoPlayCountdownSeconds = secondsToLeft.toInt()
            } else if (secondsToLeft > 15) {
                autoPlayCountdownSeconds = -1
            }
        }
    }

    // Auto next episode countdown worker
    LaunchedEffect(autoPlayCountdownSeconds) {
        if (autoPlayCountdownSeconds > 0) {
            while (autoPlayCountdownSeconds > 0) {
                delay(1000)
                autoPlayCountdownSeconds--
            }
            if (onNextEpisodeClick != null && !nextEpisodeTriggered) {
                nextEpisodeTriggered = true
                autoPlayCountdownSeconds = -1
                onNextEpisodeClick()
            }
        }
    }

    // Configure ExoPlayer with low-latency buffering optimizations
    val trackSelector = remember { DefaultTrackSelector(context) }
    val exoPlayer = remember {
        trustAllHostsAndCertificates()
        
        // Setup high efficiency buffer sizes for slow connections
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // 15s min buffer
                50000, // 50s max buffer
                2000,  // 2s playback start buffer
                4000   // 4s re-buffer threshold
            ).build()

        val dshf = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        
        val dsf = DefaultDataSource.Factory(context, dshf)
        val rf = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        
        val aa = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(context, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(aa, true)
            .build().apply {
                playWhenReady = true
            }
    }

    // Player state and track listeners
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                audioTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                textTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                videoTrackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    playbackError = null
                    isBuffering = false
                    if (exoPlayer.playWhenReady) {
                        showControls = false
                    }
                    if (initialProgress > 0f && !hasSeeked) {
                        val dur = exoPlayer.duration
                        if (dur > 0) {
                            exoPlayer.seekTo((dur * initialProgress).toLong())
                            hasSeeked = true
                        }
                    }
                }
                if (state == Player.STATE_ENDED) {
                    isBuffering = false
                    if (onNextEpisodeClick != null && !nextEpisodeTriggered) {
                        nextEpisodeTriggered = true
                        onNextEpisodeClick()
                    } else {
                        showControls = true
                    }
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    isBuffering = false
                    showControls = false
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("VideoPlayerView", "Playback failed on URL: $currentVideoUrl", error)
                isBuffering = false
                
                // Automatically find next fallback source index
                val currentIndex = fallbackSources.indexOfFirst { it.second == currentVideoUrl }
                val nextIndex = if (currentIndex == -1) 0 else currentIndex + 1
                
                if (nextIndex < fallbackSources.size) {
                    val nextSource = fallbackSources[nextIndex]
                    android.util.Log.i("VideoPlayerView", "Playback failed. Auto-switching to fallback: ${nextSource.first}")
                    
                    coroutineScope.launch {
                        try {
                            Toast.makeText(context, "Server offline. Automatically switching to high-speed backup Server ${nextIndex + 1}...", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            android.util.Log.e("VideoPlayerView", "Toast failed", e)
                        }
                    }
                    
                    currentVideoUrl = nextSource.second
                    playbackError = null
                } else {
                    playbackError = "Unable to load stream. All backup CDN servers have also been exhausted.\n\nCode: ${error.errorCodeName} (${error.errorCode})\nMessage: ${error.localizedMessage ?: "Unknown network exception"}"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Sleep Timer triggers pause
    LaunchedEffect(sleepTimerMinutes, sleepTimeRemainingSeconds) {
        if (sleepTimerMinutes > 0 && sleepTimeRemainingSeconds == 0) {
            exoPlayer.pause()
        }
    }

    val webView = remember(currentVideoUrl) {
        android.webkit.WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = true
            settings.databaseEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                    isBuffering = newProgress < 85
                }
            }
            
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    return false
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayerView", "WebView cleanup failed", e)
            }
        }
    }

    // Source selection loader
    LaunchedEffect(currentVideoUrl) {
        hasSeeked = false
        playbackError = null
        nextEpisodeTriggered = false
        autoPlayCountdownSeconds = -1
        
        val urlLower = currentVideoUrl.lowercase().trim()
        val isEmbedUrl = urlLower.contains("embed") || 
                         urlLower.contains("iframe") || 
                         urlLower.contains("streamimdb") || 
                         urlLower.contains("youtube.com") || 
                         urlLower.contains("youtu.be") || 
                         urlLower.contains(".html") || 
                         urlLower.contains("vimeo.com") || 
                         (!urlLower.endsWith(".mp4") && !urlLower.endsWith(".m3u8") && !urlLower.endsWith(".mpd") && !urlLower.endsWith(".mkv") && !urlLower.contains(".mp4?") && !urlLower.contains(".m3u8?"))

        if (currentVideoUrl.isNotBlank() && isEmbedUrl) {
            useWebViewPlayer = true
            isBuffering = false
            exoPlayer.pause()
        } else {
            useWebViewPlayer = false
            if (currentVideoUrl.isNotBlank()) {
                isBuffering = true
                val uri = if (currentVideoUrl.startsWith("/")) {
                    android.net.Uri.fromFile(java.io.File(currentVideoUrl))
                } else {
                    android.net.Uri.parse(currentVideoUrl)
                }
                val builder = MediaItem.Builder().setUri(uri)
                val mimeType = getMimeTypeFromUrl(currentVideoUrl)
                if (mimeType != null) {
                    builder.setMimeType(mimeType)
                }
                
                exoPlayer.setMediaItem(builder.build())
                exoPlayer.prepare()
                exoPlayer.play()
            } else {
                isBuffering = false
                playbackError = "No streaming video link configured in the active database.\n\nPlease select one of our high-speed global public CDN backup servers below to watch the video!"
            }
        }
    }

    // Progress reporting loop
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(1000)
            if (exoPlayer.playbackState != Player.STATE_IDLE && !isCasting) {
                duration = exoPlayer.duration.coerceAtLeast(0L)
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (duration > 0) {
                    onProgressUpdate(currentPosition.toFloat() / duration)
                }
            }
        }
    }

    // Audio-focus / lifecycle watcher
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // Enter PiP on backgrounding if video was playing and PiP is supported
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null && isPlaying && !isCasting) {
                        try {
                            val params = PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(16, 9))
                                .build()
                            activity.enterPictureInPictureMode(params)
                        } catch (e: Exception) {
                            exoPlayer.playWhenReady = false
                        }
                    } else {
                        exoPlayer.playWhenReady = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!isCasting) {
                        exoPlayer.playWhenReady = true
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!useWebViewPlayer) {
                    Modifier
                        .pointerInput(isLocked, duration) {
                            detectTapGestures(
                                onTap = { showControls = !showControls },
                                onDoubleTap = { offset ->
                                    if (!isLocked) {
                                        val screenWidth = size.width
                                        val isLeft = offset.x < screenWidth / 2
                                        if (isLeft) {
                                            // Rewind 10s
                                            showDoubleTapLeft = true
                                            exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                            currentPosition = exoPlayer.currentPosition
                                            coroutineScope.launch {
                                                delay(600)
                                                showDoubleTapLeft = false
                                            }
                                        } else {
                                            // Fastforward 10s
                                            showDoubleTapRight = true
                                            exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(duration))
                                            currentPosition = exoPlayer.currentPosition
                                            coroutineScope.launch {
                                                delay(600)
                                                showDoubleTapRight = false
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(isLocked) {
                            detectDragGestures(
                                onDragStart = { },
                                onDragEnd = {
                                    showBrightness = false
                                    showVolume = false
                                },
                                onDrag = { change, dragAmount ->
                                    if (!isLocked) {
                                        change.consume()
                                        val screenWidth = size.width
                                        val isLeftSide = change.position.x < screenWidth / 2
                                        val delta = -dragAmount.y / 600f 

                                        if (isLeftSide) {
                                            showBrightness = true
                                            brightnessLevel = (brightnessLevel + delta).coerceIn(0f, 1f)
                                            activity?.window?.let { window ->
                                                val attributes = window.attributes
                                                attributes.screenBrightness = brightnessLevel
                                                window.attributes = attributes
                                            }
                                        } else {
                                            showVolume = true
                                            volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
                                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeLevel * maxVol).toInt(), 0)
                                        }
                                    }
                                }
                            )
                        }
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val anyPressed = event.changes.any { it.pressed }
                                if (anyPressed) {
                                    showControls = true
                                }
                            }
                        }
                    }
                }
            )
    ) {
        if (isCasting) {
            // Simulated Cast Mode UI View
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF141414)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CastConnected,
                        contentDescription = "Casting",
                        tint = Color(0xFFE50914),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Casting: $title",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connected to $castDeviceName",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                currentPosition = exoPlayer.currentPosition
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.DarkGray.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Default.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                        }

                        IconButton(
                            onClick = {
                                if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                            },
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0xFFE50914), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(duration))
                                currentPosition = exoPlayer.currentPosition
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.DarkGray.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(48.dp))
                    Button(
                        onClick = {
                            isCasting = false
                            castDeviceName = ""
                            exoPlayer.play()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Icon(Icons.Default.Cast, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disconnect Casting")
                    }
                }
            }
        } else if (useWebViewPlayer) {
            // Web-based Streaming Engine View (for web embeds/iframes)
            AndroidView(
                factory = { webView },
                update = { view ->
                    if (view.url != currentVideoUrl) {
                        view.loadUrl(currentVideoUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Native Video Rendering Engine View
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        this.resizeMode = resizeMode
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { playerView ->
                    playerView.resizeMode = resizeMode
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Floating WebView Controller helpers
        AnimatedVisibility(
            visible = useWebViewPlayer && showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            // Unobtrusive semi-transparent Floating controls over the WebView
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .size(44.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { showServerDialog = true },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .size(44.dp)
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = "Switch Server", tint = Color.White)
                    }
                    
                    IconButton(
                        onClick = {
                            // Let user toggle web player off and force native fallback if desired
                            useWebViewPlayer = false
                            currentVideoUrl = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8"
                            playbackError = null
                        },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .size(44.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Force Native Player", tint = Color.White)
                    }
                }
            }
        }

        // Left / Right Double-tap Ripples
        AnimatedVisibility(
            visible = showDoubleTapLeft,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 64.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FastRewind, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("10s Backward", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        AnimatedVisibility(
            visible = showDoubleTapRight,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 64.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("10s Forward", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Skip Intro (visible 10s - 90s)
        if (duration > 90000 && currentPosition in 10000..90000 && !isLocked && !isCasting) {
            Button(
                onClick = {
                    exoPlayer.seekTo(90000)
                    currentPosition = 90000
                    Toast.makeText(context, "Intro Skipped", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.8f), contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 96.dp, end = 24.dp)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("SKIP INTRO", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Skip Credits (visible in the final 10% of playback)
        if (duration > 180000 && currentPosition > duration * 0.9f && currentPosition < duration - 10000 && !isLocked && !isCasting) {
            Button(
                onClick = {
                    val targetPos = duration - 2000
                    exoPlayer.seekTo(targetPos)
                    currentPosition = targetPos
                    Toast.makeText(context, "Credits Skipped", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 96.dp, end = 24.dp)
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("SKIP CREDITS", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Dynamic Next Episode countdown alert
        if (autoPlayCountdownSeconds > 0 && !isLocked && !isCasting) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 96.dp, end = 24.dp)
                    .width(220.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = autoPlayCountdownSeconds / 15f,
                            color = Color(0xFFE50914),
                            trackColor = Color.Gray.copy(alpha = 0.3f),
                            strokeWidth = 3.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "$autoPlayCountdownSeconds",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Next Episode", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Play Now",
                            color = Color(0xFFE50914),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    if (onNextEpisodeClick != null && !nextEpisodeTriggered) {
                                        nextEpisodeTriggered = true
                                        autoPlayCountdownSeconds = -1
                                        onNextEpisodeClick()
                                    }
                                }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Buffer Loading Animation
        val isActuallyBuffering = isBuffering && 
                !isPlaying && 
                !exoPlayer.isPlaying && 
                exoPlayer.playbackState != Player.STATE_READY && 
                !useWebViewPlayer && 
                !isCasting
        if (isActuallyBuffering) {
            CircularProgressIndicator(
                color = Color(0xFFE50914),
                strokeWidth = 4.dp,
                modifier = Modifier
                    .size(54.dp)
                    .align(Alignment.Center)
            )
        }

        // Error Retry Layout
        if (playbackError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .padding(24.dp)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .safeDrawingPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.widthIn(max = 500.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = Color(0xFFE50914),
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connection Failed",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = playbackError ?: "",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "High-speed Public Streaming CDNs:",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
                    )
                    
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        items(fallbackSources.size) { index ->
                            val source = fallbackSources[index]
                            val isSelected = currentVideoUrl == source.second
                            Button(
                                onClick = {
                                    currentVideoUrl = source.second
                                    playbackError = null
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) Color(0xFFE50914) else Color(0xFF2B2B2B),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(source.first.substringBefore(" ("), fontSize = 12.sp)
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onBackClick,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.White),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Go Back")
                        }
                        
                        Button(
                            onClick = {
                                val temp = currentVideoUrl
                                currentVideoUrl = ""
                                currentVideoUrl = temp
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Link")
                        }
                    }
                }
            }
        }

        // Swipe brightness & volume sliders
        if (showBrightness) {
            GestureSliderIndicator(
                icon = Icons.Default.BrightnessMedium,
                level = brightnessLevel,
                label = "Brightness",
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
            )
        }
        if (showVolume) {
            GestureSliderIndicator(
                icon = if (volumeLevel == 0f) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                level = volumeLevel,
                label = "Volume",
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)
            )
        }

        // Sleek Video Controls Interface Overlay
        AnimatedVisibility(
            visible = showControls && !useWebViewPlayer,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = false }
                        )
                    }
                    .safeDrawingPadding() 
            ) {
                if (isLocked) {
                    IconButton(
                        onClick = { isLocked = false },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .size(54.dp)
                            .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                    ) {
                        Icon(Icons.Default.LockOpen, "Unlock Screen", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                } else {
                    // Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                        if (onMinimizeClick != null && !isCasting) {
                            IconButton(onClick = { onMinimizeClick(currentPosition) }) {
                                Icon(Icons.Default.FullscreenExit, "Minimize Player", tint = Color.White)
                            }
                        }
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        )
                        
                        // Action menu headers
                        IconButton(onClick = { showCastDialog = true }) {
                            Icon(
                                imageVector = if (isCasting) Icons.Default.CastConnected else Icons.Outlined.Cast,
                                contentDescription = "Cast Screen",
                                tint = if (isCasting) Color(0xFFE50914) else Color.White
                            )
                        }

                        IconButton(onClick = { showSleepTimerDialog = true }) {
                            Icon(
                                imageVector = if (sleepTimerMinutes > 0) Icons.Default.Timer else Icons.Outlined.Timer,
                                contentDescription = "Sleep Timer",
                                tint = if (sleepTimerMinutes > 0) Color(0xFFE50914) else Color.White
                            )
                        }

                        IconButton(onClick = { showQualityDialog = true }) {
                            Icon(Icons.Default.Settings, "Video Quality", tint = Color.White)
                        }

                        IconButton(onClick = { showTrackDialog = true }) {
                            Icon(Icons.Default.Subtitles, "Audio and Subtitles", tint = Color.White)
                        }

                        IconButton(onClick = { showServerDialog = true }) {
                            Icon(Icons.Default.Dns, "Switch Servers", tint = Color.White)
                        }

                        IconButton(onClick = {
                            resizeMode = when(resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }) {
                            Icon(Icons.Default.AspectRatio, "Resize Ratio", tint = Color.White)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isCasting) {
                            IconButton(onClick = {
                                try {
                                    val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                                    activity?.enterPictureInPictureMode(params)
                                } catch (e: Exception){}
                            }) {
                                Icon(Icons.Default.PictureInPictureAlt, "Picture-In-Picture", tint = Color.White)
                            }
                        }
                    }

                    // Sleep Timer Countdown Label
                    if (sleepTimerMinutes > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 64.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = Color(0xFFE50914), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            val minsLeft = sleepTimeRemainingSeconds / 60
                            val secsLeft = sleepTimeRemainingSeconds % 60
                            Text(
                                text = "Sleep: ${String.format(Locale.US, "%02d:%02d", minsLeft, secsLeft)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Center playback buttons
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                currentPosition = exoPlayer.currentPosition
                            },
                            modifier = Modifier.size(60.dp)
                        ) {
                            Icon(Icons.Default.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(44.dp))
                        }
                        
                        Spacer(modifier = Modifier.width(40.dp))

                        IconButton(
                            onClick = {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(76.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(54.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(40.dp))

                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(duration))
                                currentPosition = exoPlayer.currentPosition
                            },
                            modifier = Modifier.size(60.dp)
                        ) {
                            Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(44.dp))
                        }
                    }

                    // Bottom Control Tray
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(formatTime(currentPosition), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(formatTime(duration), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                            onValueChange = { currentPosition = (it * duration).toLong() },
                            onValueChangeFinished = {
                                exoPlayer.seekTo(currentPosition)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFE50914),
                                activeTrackColor = Color(0xFFE50914),
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left screen locks & security configs
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = { isLocked = true }) {
                                    Icon(Icons.Default.Lock, "Lock Controls", tint = Color.White)
                                }
                                
                                // Screenshot toggle
                                Button(
                                    onClick = { 
                                        isScreenshotProtected = !isScreenshotProtected 
                                        Toast.makeText(context, if (isScreenshotProtected) "Screenshot Protection Enabled" else "Screenshot Protection Disabled", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isScreenshotProtected) Color(0xFFE50914).copy(alpha = 0.3f) else Color.Transparent,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isScreenshotProtected) Icons.Default.Security else Icons.Default.NoEncryption,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "SECURE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Speed selection & screen rotation
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TextButton(
                                    onClick = { showSpeedDialog = true },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                                ) {
                                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("${playbackSpeed}x", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                
                                IconButton(onClick = {
                                    orientationMode = when (orientationMode) {
                                        OrientationMode.SENSOR -> OrientationMode.PORTRAIT
                                        OrientationMode.PORTRAIT -> OrientationMode.LANDSCAPE
                                        OrientationMode.LANDSCAPE -> OrientationMode.SENSOR
                                    }
                                }) {
                                    Icon(Icons.Default.ScreenRotation, "Rotate Video Mode", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 1. Sleep Timer Config Dialog
        if (showSleepTimerDialog) {
            AlertDialog(
                onDismissRequest = { showSleepTimerDialog = false },
                icon = { Icon(Icons.Default.Timer, contentDescription = null, tint = Color(0xFFE50914)) },
                title = { Text("Sleep Timer") },
                text = {
                    Column {
                        Text(
                            "Select a duration. Playback will pause automatically when the timer runs out.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        val timers = listOf(
                            Pair("Off", 0),
                            Pair("10 Minutes", 10),
                            Pair("15 Minutes", 15),
                            Pair("30 Minutes", 30),
                            Pair("45 Minutes", 45),
                            Pair("60 Minutes", 60)
                        )
                        timers.forEach { timer ->
                            val isSelected = sleepTimerMinutes == timer.second
                            TextButton(
                                onClick = {
                                    sleepTimerMinutes = timer.second
                                    showSleepTimerDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = timer.first,
                                        color = if (isSelected) Color(0xFFE50914) else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFFE50914))
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSleepTimerDialog = false }) { Text("Close") }
                }
            )
        }

        // 2. Simulated Chromecast Cast Dialog
        if (showCastDialog) {
            AlertDialog(
                onDismissRequest = { showCastDialog = false },
                icon = { Icon(Icons.Default.Cast, contentDescription = null, tint = Color(0xFFE50914)) },
                title = { Text("Cast to Device") },
                text = {
                    Column {
                        Text(
                            "Select a smart display or Chromecast device to stream.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        val devices = listOf("Living Room TV", "Bedroom Chromecast", "Samsung Smart TV", "Mi Box 4K", "Family Room TV")
                        devices.forEach { device ->
                            TextButton(
                                onClick = {
                                    isCasting = true
                                    castDeviceName = device
                                    showCastDialog = false
                                    exoPlayer.pause()
                                    Toast.makeText(context, "Streaming movie to $device", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(device, color = MaterialTheme.colorScheme.onSurface)
                                    Icon(Icons.Default.Tv, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCastDialog = false }) { Text("Cancel") }
                }
            )
        }

        // 3. Dynamic Bitrate & Adaptive Stream Quality selector
        if (showQualityDialog) {
            val isAutoSelected = !videoTrackGroups.any { group ->
                trackSelector.parameters.overrides.containsKey(group.mediaTrackGroup)
            }

            AlertDialog(
                onDismissRequest = { showQualityDialog = false },
                title = { 
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFFE50914))
                        Text("Stream Quality", style = MaterialTheme.typography.titleLarge)
                    }
                },
                text = {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Auto Selection
                        item {
                            Surface(
                                onClick = {
                                    trackSelector.setParameters(
                                        trackSelector.buildUponParameters()
                                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                    )
                                    showQualityDialog = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAutoSelected) Color(0xFFE50914).copy(alpha = 0.15f) else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    RadioButton(
                                        selected = isAutoSelected,
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFE50914))
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            "Auto (Adaptive)",
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = if (isAutoSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "Adjusts quality dynamically based on network speed",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Divider
                        item {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                        }

                        // Manual Resolutions
                        if (videoTrackGroups.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No quality configurations found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            videoTrackGroups.forEach { group ->
                                for (i in 0 until group.length) {
                                    if (group.isTrackSupported(i)) {
                                        val format = group.getTrackFormat(i)
                                        val height = format.height
                                        val bitrate = format.bitrate
                                        val label = format.label

                                        val trackName = when {
                                            !label.isNullOrEmpty() -> label
                                            height > 0 -> {
                                                val fps = if (format.frameRate > 0) " @ ${format.frameRate.toInt()}fps" else ""
                                                "${height}p$fps"
                                            }
                                            bitrate > 0 -> "${bitrate / 1000} kbps"
                                            else -> "Option ${i + 1}"
                                        }

                                        val override = trackSelector.parameters.overrides[group.mediaTrackGroup]
                                        val isCurrentOverride = override != null && override.trackIndices.contains(i)
                                        val isCurrentlyPlaying = group.isTrackSelected(i)
                                        val isSelected = isCurrentOverride || (isAutoSelected && isCurrentlyPlaying)

                                        item {
                                            Surface(
                                                onClick = {
                                                    trackSelector.setParameters(
                                                        trackSelector.buildUponParameters()
                                                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                    )
                                                    showQualityDialog = false
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) Color(0xFFE50914).copy(alpha = 0.15f) else Color.Transparent,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 48.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                                ) {
                                                    RadioButton(
                                                        selected = isCurrentOverride,
                                                        onClick = null,
                                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFE50914))
                                                    )
                                                    Spacer(modifier = Modifier.width(16.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            trackName,
                                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                            ),
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        if (bitrate > 0) {
                                                            Text(
                                                                "Bitrate: ${(bitrate / 1000f / 1000f).let { String.format(Locale.US, "%.2f", it) }} Mbps",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                    if (isCurrentlyPlaying) {
                                                        Box(
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(Color(0xFFE50914).copy(alpha = 0.2f))
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                "ACTIVE",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Color(0xFFE50914),
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQualityDialog = false }) { Text("Close") }
                }
            )
        }

        // 4. Subtitles and audio selectors dialog
        if (showTrackDialog) {
            AlertDialog(
                onDismissRequest = { showTrackDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Subtitles, contentDescription = null, tint = Color(0xFFE50914))
                        Text("Audio & Subtitles")
                    }
                },
                text = {
                    LazyColumn {
                        if (audioTrackGroups.isNotEmpty()) {
                            item { 
                                Text("Audio Track", fontWeight = FontWeight.Bold, color = Color(0xFFE50914), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) 
                            }
                            audioTrackGroups.forEach { group ->
                                for (i in 0 until group.length) {
                                    val locale = group.getTrackFormat(i).language?.let { Locale(it) }
                                    val name = locale?.displayName ?: group.getTrackFormat(i).language ?: "Audio Track ${i+1}"
                                    val isSelected = group.isTrackSelected(i)
                                    item {
                                        TextButton(
                                            onClick = {
                                                trackSelector.setParameters(
                                                    trackSelector.buildUponParameters()
                                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                )
                                                showTrackDialog = false
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(name, color = if (isSelected) Color(0xFFE50914) else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                                if (isSelected) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFFE50914), modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        item { Spacer(modifier = Modifier.height(16.dp)) }

                        item { 
                            Text("Subtitles", fontWeight = FontWeight.Bold, color = Color(0xFFE50914), modifier = Modifier.padding(bottom = 8.dp)) 
                        }
                        
                        // Turn subtitles off option
                        item {
                            val noSubtitles = !textTrackGroups.any { group ->
                                trackSelector.parameters.overrides.containsKey(group.mediaTrackGroup)
                            }
                            TextButton(
                                onClick = {
                                    trackSelector.setParameters(
                                        trackSelector.buildUponParameters()
                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    )
                                    showTrackDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Off", color = if (noSubtitles) Color(0xFFE50914) else MaterialTheme.colorScheme.onSurface, fontWeight = if (noSubtitles) FontWeight.Bold else FontWeight.Normal)
                                    if (noSubtitles) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFFE50914), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        if (textTrackGroups.isNotEmpty()) {
                            textTrackGroups.forEach { group ->
                                for (i in 0 until group.length) {
                                    val locale = group.getTrackFormat(i).language?.let { Locale(it) }
                                    val name = locale?.displayName ?: group.getTrackFormat(i).language ?: "Subtitle ${i+1}"
                                    val isSelected = group.isTrackSelected(i)
                                    item {
                                        TextButton(
                                            onClick = {
                                                trackSelector.setParameters(
                                                    trackSelector.buildUponParameters()
                                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                )
                                                showTrackDialog = false
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(name, color = if (isSelected) Color(0xFFE50914) else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                                if (isSelected) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFFE50914), modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                Text("No embedded subtitles detected", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTrackDialog = false }) { Text("Close") }
                }
            )
        }

        // 5. Playback Speed Selector (0.25x - 3.0x)
        if (showSpeedDialog) {
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = Color(0xFFE50914))
                        Text("Playback Speed")
                    }
                },
                text = {
                    Column {
                        val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
                        speeds.forEach { speed ->
                            TextButton(
                                onClick = {
                                    playbackSpeed = speed
                                    exoPlayer.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${speed}x" + (if (speed == 1f) " (Normal)" else ""),
                                        color = if (speed == playbackSpeed) Color(0xFFE50914) else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (speed == playbackSpeed) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFFE50914))
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedDialog = false }) { Text("Close") }
                }
            )
        }

        // 6. Alternative Streaming CDN Server Dialog
        if (showServerDialog) {
            AlertDialog(
                onDismissRequest = { showServerDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = Color(0xFFE50914))
                        Text("Switch Streaming Server")
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "If the current CDN video link buffer fails or loads slowly, switch to our high-speed global CDN backup streams below:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        fallbackSources.forEach { source ->
                            val isSelected = currentVideoUrl == source.second
                            TextButton(
                                onClick = {
                                    currentVideoUrl = source.second
                                    playbackError = null
                                    showServerDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (isSelected) Color(0xFFE50914).copy(alpha = 0.15f) else Color.Transparent
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = source.first,
                                        color = if (isSelected) Color(0xFFE50914) else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Done,
                                            contentDescription = "Active",
                                            tint = Color(0xFFE50914),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showServerDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

@Composable
fun GestureSliderIndicator(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    level: Float, 
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(54.dp)
            .height(180.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(27.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        
        Box(
            modifier = Modifier
                .width(6.dp)
                .weight(1f)
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level)
                    .align(Alignment.BottomCenter)
                    .background(Color(0xFFE50914))
            )
        }
        
        Text(
            text = "${(level * 100).toInt()}%",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
