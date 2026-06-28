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
    var showTrackDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(videoUrl) {
        if (videoUrl.isNotBlank()) {
            val uri = if (videoUrl.startsWith("/")) android.net.Uri.fromFile(java.io.File(videoUrl)) else android.net.Uri.parse(videoUrl)
            val builder = MediaItem.Builder().setUri(uri)
            val lower = videoUrl.lowercase().trim()
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

            var hasSeeked = false
            exoPlayer.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    audioTrackGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                    textTrackGroups = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY && initialProgress > 0f && !hasSeeked) {
                        val dur = exoPlayer.duration
                        if (dur > 0) {
                            exoPlayer.seekTo((dur * initialProgress).toLong())
                            hasSeeked = true
                        }
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            })
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
                        
                        IconButton(onClick = { showTrackDialog = true }) {
                            Icon(Icons.Default.Subtitles, "Audio and Subtitles", tint = Color.White)
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
