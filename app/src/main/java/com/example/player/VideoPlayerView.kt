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
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
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
    modifier: Modifier = Modifier
) {
    OTTVideoPlayer(
        videoUrl = videoUrl,
        title = title,
        initialProgress = initialProgress,
        onProgressUpdate = onProgressUpdate,
        onBackClick = onBackClick,
        onMinimizeClick = onMinimizeClick,
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

@OptIn(UnstableApi::class)
@Composable
fun OTTVideoPlayer(
    videoUrl: String,
    title: String,
    initialProgress: Float = 0f,
    onProgressUpdate: (Float) -> Unit = {},
    onBackClick: () -> Unit,
    onMinimizeClick: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var orientationMode by remember { mutableStateOf(OrientationMode.LANDSCAPE) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableStateOf(1f) }
    
    var showBrightness by remember { mutableStateOf(false) }
    var brightnessLevel by remember { mutableStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }
    
    var showVolume by remember { mutableStateOf(false) }
    var volumeLevel by remember { 
        mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    LaunchedEffect(showControls, isPlaying, isLocked) {
        if (showControls && isPlaying && !isLocked) {
            delay(4000)
            showControls = false
        }
        if (isLocked) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(orientationMode) {
        try {
            activity?.requestedOrientation = when (orientationMode) {
                OrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } catch (e: Exception) {}
    }

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

    val trackSelector = remember { androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context) }
    val exoPlayer = remember {
        trustAllHostsAndCertificates()
        val dshf = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
        val dsf = androidx.media3.datasource.DefaultDataSource.Factory(context, dshf)
        val rf = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val aa = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(context, rf)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsf))
            .setTrackSelector(trackSelector)
            .setAudioAttributes(aa, true)
            .build().apply {
                playWhenReady = true
            }
    }

    var audioTrackGroups by remember { mutableStateOf<List<androidx.media3.common.Tracks.Group>>(emptyList()) }
    var textTrackGroups by remember { mutableStateOf<List<androidx.media3.common.Tracks.Group>>(emptyList()) }
    var videoTrackGroups by remember { mutableStateOf<List<androidx.media3.common.Tracks.Group>>(emptyList()) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }

    var currentVideoUrl by remember(videoUrl) { mutableStateOf(videoUrl) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var hasSeeked by remember { mutableStateOf(false) }

    val fallbackSources = remember {
        listOf(
            Pair("Server 1 (HLS 1080p - Sintel Demo)", "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8"),
            Pair("Server 2 (HLS 1080p - Mux Trailer)", "https://test-streams.mux.dev/x36xhg/x36xhg.m3u8"),
            Pair("Server 3 (HLS 720p - Tears of Steel)", "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"),
            Pair("Server 4 (MP4 - Elephants Dream)", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"),
            Pair("Server 5 (MP4 - Big Buck Bunny)", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
        )
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioTrackGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                textTrackGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                videoTrackGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    playbackError = null
                    if (initialProgress > 0f && !hasSeeked) {
                        val dur = exoPlayer.duration
                        if (dur > 0) {
                            exoPlayer.seekTo((dur * initialProgress).toLong())
                            hasSeeked = true
                        }
                    }
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("VideoPlayerView", "Playback failed on URL: $currentVideoUrl", error)
                isBuffering = false
                playbackError = "Unable to load stream. The server might be offline or the URL has expired.\n\nError: ${error.localizedMessage ?: "Unknown connection failure"}"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(currentVideoUrl) {
        hasSeeked = false
        playbackError = null
        if (currentVideoUrl.isNotBlank()) {
            isBuffering = true
            val uri = if (currentVideoUrl.startsWith("/")) android.net.Uri.fromFile(java.io.File(currentVideoUrl)) else android.net.Uri.parse(currentVideoUrl)
            val builder = MediaItem.Builder().setUri(uri)
            val lower = currentVideoUrl.lowercase().trim()
            val mimeType = when {
                lower.contains("m3u8") -> "application/x-mpegURL"
                lower.contains("mpd") -> "application/dash+xml"
                lower.contains("ism") -> "application/vnd.ms-sstr+xml"
                lower.contains("mp4") -> "video/mp4"
                lower.contains("mkv") -> "video/x-matroska"
                else -> null
            }
            if (mimeType != null) builder.setMimeType(mimeType)
            
            exoPlayer.setMediaItem(builder.build())
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            isBuffering = false
            playbackError = "No streaming URL is configured for this title in the database.\n\nPlease select one of the high-speed public CDN backup servers below to watch and test!"
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(1000)
            if (exoPlayer.playbackState != Player.STATE_IDLE) {
                duration = exoPlayer.duration.coerceAtLeast(0L)
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (duration > 0) {
                    onProgressUpdate(currentPosition.toFloat() / duration)
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                        if (exoPlayer.isPlaying) {
                            try {
                                val params = PictureInPictureParams.Builder()
                                    .setAspectRatio(Rational(16, 9))
                                    .build()
                                activity.enterPictureInPictureMode(params)
                            } catch(e: Exception){}
                        } else {
                            exoPlayer.playWhenReady = false
                        }
                    } else {
                        exoPlayer.playWhenReady = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    exoPlayer.playWhenReady = true
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
            .pointerInput(isLocked) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            val screenWidth = size.width
                            if (offset.x < screenWidth / 2) {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                                currentPosition = exoPlayer.currentPosition
                            } else {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(duration))
                                currentPosition = exoPlayer.currentPosition
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

                            val delta = -dragAmount.y / 500f 

                            if (isLeftSide) {
                                showBrightness = true
                                brightnessLevel = (brightnessLevel + delta).coerceIn(0f, 1f)
                                activity?.window?.let { window ->
                                    val attributes = window.attributes
                                    if(brightnessLevel > 0) attributes.screenBrightness = brightnessLevel
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
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { playerView ->
                playerView.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (playbackError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .padding(24.dp)
                    .pointerInput(Unit) { detectTapGestures { } } // Block touches behind
                    .safeDrawingPadding(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.widthIn(max = 520.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Playback Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Server Connection Failed",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = playbackError ?: "",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Text(
                        text = "Switch to a Working Streaming Server:",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
                    )
                    
                    androidx.compose.foundation.lazy.LazyRow(
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
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.DarkGray,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(source.first.substringBefore(" ("))
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
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                        ) {
                            Text("Go Back")
                        }
                        
                        Button(
                            onClick = {
                                val temp = currentVideoUrl
                                currentVideoUrl = ""
                                currentVideoUrl = temp
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }
                }
            }
        }

        if (showBrightness) {
            GestureIndicator(icon = Icons.Default.BrightnessMedium, level = brightnessLevel, modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp))
        }
        if (showVolume) {
            GestureIndicator(icon = Icons.Default.VolumeUp, level = volumeLevel, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp))
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .safeDrawingPadding() 
            ) {
                if (isLocked) {
                    IconButton(
                        onClick = { isLocked = false },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                            .background(Color.Black.copy(alpha=0.6f), CircleShape)
                    ) {
                        Icon(Icons.Default.LockOpen, "Unlock", tint = Color.White)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                        if (onMinimizeClick != null) {
                            IconButton(onClick = { onMinimizeClick(currentPosition) }) {
                                Icon(Icons.Default.FullscreenExit, "Minimize", tint = Color.White)
                            }
                        }
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
                        )
                        
                        IconButton(onClick = { showQualityDialog = true }) {
                            Icon(Icons.Default.Settings, "Stream Quality", tint = Color.White)
                        }

                        IconButton(onClick = { showTrackDialog = true }) {
                            Icon(Icons.Default.Subtitles, "Audio and Subtitles", tint = Color.White)
                        }

                        IconButton(onClick = { showServerDialog = true }) {
                            Icon(Icons.Default.Dns, "Switch Streaming Server", tint = Color.White)
                        }

                        IconButton(onClick = {
                            resizeMode = when(resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }) {
                            Icon(Icons.Default.AspectRatio, "Resize", tint = Color.White)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            IconButton(onClick = {
                                try {
                                    val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                                    activity?.enterPictureInPictureMode(params)
                                } catch (e: Exception){}
                            }) {
                                Icon(Icons.Default.PictureInPictureAlt, "PiP", tint = Color.White)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(Icons.Default.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                        
                        Spacer(modifier = Modifier.width(32.dp))

                        IconButton(
                            onClick = {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier.size(80.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(64.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(32.dp))

                        IconButton(
                            onClick = {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(duration))
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(Icons.Default.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        ) {
                            Text(formatTime(currentPosition), color = Color.White, fontSize = 14.sp)
                            Text(formatTime(duration), color = Color.White, fontSize = 14.sp)
                        }
                        
                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                            onValueChange = { currentPosition = (it * duration).toLong() },
                            onValueChangeFinished = {
                                exoPlayer.seekTo(currentPosition)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = { isLocked = true }) {
                                Icon(Icons.Default.Lock, "Lock", tint = Color.White)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { showSpeedDialog = true }) {
                                    Text("${playbackSpeed}x", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = {
                                    orientationMode = when (orientationMode) {
                                        OrientationMode.SENSOR -> OrientationMode.PORTRAIT
                                        OrientationMode.PORTRAIT -> OrientationMode.LANDSCAPE
                                        OrientationMode.LANDSCAPE -> OrientationMode.SENSOR
                                    }
                                }) {
                                    Icon(Icons.Default.ScreenRotation, "Rotate", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

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
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Stream Quality", style = MaterialTheme.typography.titleLarge)
                    }
                },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 1. Auto Selection Option
                        item {
                            Surface(
                                onClick = {
                                    trackSelector.setParameters(
                                        trackSelector.buildUponParameters()
                                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                                    )
                                    showQualityDialog = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAutoSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                } else {
                                    Color.Transparent
                                },
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
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary
                                        )
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

                        // Divider between Auto and Manual options
                        item {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                        }

                        // 2. Manual Options
                        if (videoTrackGroups.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No quality options available",
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
                                            else -> "Quality Option ${i + 1}"
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
                                                            .setOverrideForType(
                                                                androidx.media3.common.TrackSelectionOverride(
                                                                    group.mediaTrackGroup,
                                                                    i
                                                                )
                                                            )
                                                    )
                                                    showQualityDialog = false
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                                } else {
                                                    Color.Transparent
                                                },
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
                                                        colors = RadioButtonDefaults.colors(
                                                            selectedColor = MaterialTheme.colorScheme.primary
                                                        )
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
                                                                "Bitrate: ${(bitrate / 1000f / 1000f).let { String.format(java.util.Locale.US, "%.2f", it) }} Mbps",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                    if (isCurrentlyPlaying) {
                                                        Box(
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                "Active",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.primary,
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
                    TextButton(onClick = { showQualityDialog = false }) {
                        Text("Close", style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        }

        if (showTrackDialog) {
            AlertDialog(
                onDismissRequest = { showTrackDialog = false },
                title = { Text("Audio & Subtitles") },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        if (audioTrackGroups.isNotEmpty()) {
                            item { Text("Audio Tracks", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                            audioTrackGroups.forEach { group ->
                                for (i in 0 until group.length) {
                                    val name = group.getTrackFormat(i).language ?: "Audio ${i+1}"
                                    item {
                                        TextButton(onClick = {
                                            trackSelector.setParameters(
                                                trackSelector.buildUponParameters()
                                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                            )
                                            showTrackDialog = false
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Text(name, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                        if (textTrackGroups.isNotEmpty()) {
                            item { Text("Subtitles", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                            textTrackGroups.forEach { group ->
                                for (i in 0 until group.length) {
                                    val name = group.getTrackFormat(i).language ?: "Subtitle ${i+1}"
                                    item {
                                        TextButton(onClick = {
                                            trackSelector.setParameters(
                                                trackSelector.buildUponParameters()
                                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                            )
                                            showTrackDialog = false
                                        }, modifier = Modifier.fillMaxWidth()) {
                                            Text(name, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                            item {
                                TextButton(onClick = {
                                    trackSelector.setParameters(
                                        trackSelector.buildUponParameters()
                                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                                    )
                                    showTrackDialog = false
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Off", color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showTrackDialog = false }) { Text("Close") } }
            )
        }

        if (showSpeedDialog) {
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = { Text("Playback Speed") },
                text = {
                    Column {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            TextButton(onClick = {
                                playbackSpeed = speed
                                exoPlayer.setPlaybackSpeed(speed)
                                showSpeedDialog = false
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("${speed}x", color = if (speed == playbackSpeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Close") } }
            )
        }

        if (showServerDialog) {
            AlertDialog(
                onDismissRequest = { showServerDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Switch Streaming Server")
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "If the current server is buffering or fails to play, select an alternative CDN stream from our high-speed public backup servers:",
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
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = source.first,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Done,
                                            contentDescription = "Active",
                                            tint = MaterialTheme.colorScheme.primary,
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
fun GestureIndicator(icon: androidx.compose.ui.graphics.vector.ImageVector, level: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = level,
            modifier = Modifier.width(60.dp).height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.White.copy(alpha = 0.3f),
        )
    }
}
