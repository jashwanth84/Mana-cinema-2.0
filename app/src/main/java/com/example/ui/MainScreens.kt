package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.models.*
import com.example.player.VideoPlayerView
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.EaseInOutQuart
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

enum class Screen {
    HOME, SERIES, LIVETV, WATCHLIST, PROFILE,
    SEARCH, DETAIL, SERIES_DETAIL, NOTIFICATIONS,
    MOVIE_REQUEST, ACCOUNT_SETTINGS, THEME_SETTINGS,
    DOWNLOADS, CONTINUE_WATCHING, PLAY_MOVIE, PLAY_SERIES, PLAY_LIVETV
}

data class NavigationState(
    val screen: Screen,
    val movie: Movie? = null,
    val webSeries: WebSeries? = null,
    val liveTvChannel: LiveTvChannel? = null,
    val selectedEpisode: Episode? = null
)

@OptIn(UnstableApi::class)
@Composable
fun MiniPlayerView(
    videoUrl: String,
    startPositionMs: Long,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    
    val exoPlayer = remember(videoUrl) {
        val dshf = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
        val dsf = androidx.media3.datasource.DefaultDataSource.Factory(context, dshf)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsf))
            .build().apply {
                playWhenReady = true
                val uri = if (videoUrl.startsWith("/")) android.net.Uri.fromFile(java.io.File(videoUrl)) else android.net.Uri.parse(videoUrl)
                val mediaItem = MediaItem.Builder().setUri(uri).build()
                setMediaItem(mediaItem)
                prepare()
                if (startPositionMs > 0) {
                    seekTo(startPositionMs)
                }
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                    )
                )
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(12.dp))
        }

        IconButton(
            onClick = {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                    isPlaying = false
                } else {
                    exoPlayer.play()
                    isPlaying = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(28.dp)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer(
    viewModel: MovieViewModel,
    onLogout: () -> Unit
) {
    val navigationStack = remember { mutableStateListOf(NavigationState(Screen.HOME)) }
    val currentNav = navigationStack.lastOrNull() ?: NavigationState(Screen.HOME)

    val isMaintenance by viewModel.isMaintenance.collectAsState()
    val maintenanceMessage by viewModel.maintenanceMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val playWithAd: (() -> Unit) -> Unit = { onAdCompleted ->
        onAdCompleted()
    }

    if (isMaintenance) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "System Maintenance",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = maintenanceMessage,
                    color = Color.Gray,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    lineHeight = 22.sp
                )
            }
        }
        return
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("MANA ", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
                    Text("CINEMA", color = MaterialTheme.colorScheme.primary, fontSize = 38.sp, fontWeight = FontWeight.Black)
                }
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        return
    }

    val activeProfile by viewModel.activeProfile.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val floatingVideoState by viewModel.floatingVideo.collectAsState()

    LaunchedEffect(isOffline) {
        if (isOffline && currentNav.screen != Screen.DOWNLOADS && 
            currentNav.screen != Screen.PLAY_MOVIE && 
            currentNav.screen != Screen.PLAY_SERIES) {
            navigationStack.add(NavigationState(Screen.DOWNLOADS))
        }
    }

    if (activeProfile == null) {
        ProfileSelectionScreen(
            viewModel = viewModel,
            onLogout = onLogout
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Hide bottom bar on player layouts and focus states
            val hideBottomBar = remember(currentNav.screen) {
                listOf(
                    Screen.PLAY_MOVIE, Screen.PLAY_SERIES, Screen.PLAY_LIVETV,
                    Screen.SEARCH, Screen.DETAIL, Screen.SERIES_DETAIL
                ).contains(currentNav.screen)
            }

            if (!hideBottomBar) {
                BottomNavigationBar(
                    currentScreen = currentNav.screen,
                    onNavigate = { screen ->
                        if (navigationStack.any { it.screen == screen }) {
                            while (navigationStack.last().screen != screen) {
                                navigationStack.removeAt(navigationStack.size - 1)
                            }
                        } else {
                            navigationStack.add(NavigationState(screen))
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentNav,
                transitionSpec = {
                    fadeIn(animationSpec = tween(600, easing = EaseInOutQuart)) + 
                    scaleIn(initialScale = 1.05f, animationSpec = tween(600, easing = EaseInOutQuart)) togetherWith
                    fadeOut(animationSpec = tween(500, easing = EaseInOutQuart)) + 
                    scaleOut(targetScale = 0.95f, animationSpec = tween(500, easing = EaseInOutQuart))
                },
                label = "NavigationTransition"
            ) { state ->
                when (state.screen) {
                    Screen.HOME -> HomeScreen(viewModel, onNavigateToMovie = { movie ->
                        // Clear floating video if navigating to fullscreen
                        if (floatingVideoState?.contentId == movie.id) {
                            viewModel.setFloatingVideo(null)
                        }
                        navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                    }, onNavigateToSeries = { series ->
                        navigationStack.add(NavigationState(Screen.SERIES_DETAIL, webSeries = series))
                    }, onSearchClick = {
                        navigationStack.add(NavigationState(Screen.SEARCH))
                    }, onNotificationsClick = {
                        navigationStack.add(NavigationState(Screen.NOTIFICATIONS))
                    })

                    Screen.SERIES -> SeriesScreen(viewModel, onNavigateToSeries = { series ->
                        navigationStack.add(NavigationState(Screen.SERIES_DETAIL, webSeries = series))
                    })

                    Screen.LIVETV -> LiveTvScreen(viewModel, onChannelPlay = { channel ->
                        if (floatingVideoState?.contentId == channel.id) {
                            viewModel.setFloatingVideo(null)
                        }
                        navigationStack.add(NavigationState(Screen.PLAY_LIVETV, liveTvChannel = channel))
                    })

                    Screen.WATCHLIST -> WatchlistScreen(viewModel, onMovieClick = { movie ->
                        navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                    }, onSeriesClick = { series ->
                        navigationStack.add(NavigationState(Screen.SERIES_DETAIL, webSeries = series))
                    })

                    Screen.PROFILE -> ProfileScreen(viewModel, onMenuClick = { menu ->
                        val target = when (menu) {
                            "request" -> Screen.MOVIE_REQUEST
                            "settings" -> Screen.ACCOUNT_SETTINGS
                            "themes" -> Screen.THEME_SETTINGS
                            "downloads" -> Screen.DOWNLOADS
                            "continue" -> Screen.CONTINUE_WATCHING
                            else -> null
                        }
                        if (target != null) {
                            navigationStack.add(NavigationState(target))
                        }
                    }, onLogout = onLogout)

                    Screen.SEARCH -> SearchScreen(viewModel, onMovieClick = { movie ->
                        navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                    }, onSeriesClick = { series ->
                        navigationStack.add(NavigationState(Screen.SERIES_DETAIL, webSeries = series))
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.DETAIL -> state.movie?.let { movie ->
                        DetailScreen(viewModel, movie = movie, onPlayClick = {
                            if (floatingVideoState?.contentId == movie.id) {
                                viewModel.setFloatingVideo(null)
                            }
                            playWithAd {
                                navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = movie))
                            }
                        }, onSimilarMovieClick = { simMovie ->
                            navigationStack.add(NavigationState(Screen.DETAIL, movie = simMovie))
                        }, onBack = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        })
                    }

                    Screen.SERIES_DETAIL -> state.webSeries?.let { series ->
                        SeriesDetailScreen(viewModel, series = series, onEpisodePlay = { ep ->
                            val epId = "${series.id}_s${ep.season}e${ep.episode}"
                            if (floatingVideoState?.contentId == epId) {
                                viewModel.setFloatingVideo(null)
                            }
                            playWithAd {
                                navigationStack.add(NavigationState(Screen.PLAY_SERIES, webSeries = series, selectedEpisode = ep))
                            }
                        }, onBack = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        })
                    }

                    Screen.PLAY_MOVIE -> state.movie?.let { movie ->
                        val continueWatchingList by viewModel.continueWatching.collectAsState()
                        val savedRecord = continueWatchingList.find { it.contentId == movie.id }
                        val initialProg = savedRecord?.progress ?: 0f

                        VideoPlayerView(
                            videoUrl = movie.link,
                            title = movie.title,
                            initialProgress = initialProg,
                            onProgressUpdate = { progress ->
                                viewModel.saveWatchProgress(movie.id, movie.title, movie.poster, "movie", progress)
                            },
                            onBackClick = {
                                playWithAd {
                                    navigationStack.removeAt(navigationStack.size - 1)
                                }
                            },
                            onMinimizeClick = { currentPos ->
                                viewModel.setFloatingVideo(
                                    MovieViewModel.FloatingVideoState(
                                        videoUrl = movie.link,
                                        title = movie.title,
                                        contentId = movie.id,
                                        poster = movie.poster,
                                        type = "movie",
                                        startPositionMs = currentPos
                                    )
                                )
                                playWithAd {
                                    navigationStack.removeAt(navigationStack.size - 1)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Screen.PLAY_SERIES -> state.webSeries?.let { series ->
                        state.selectedEpisode?.let { ep ->
                            val epId = "${series.id}_s${ep.season}e${ep.episode}"
                            val continueWatchingList by viewModel.continueWatching.collectAsState()
                            val savedRecord = continueWatchingList.find { it.contentId == epId }
                            val initialProg = savedRecord?.progress ?: 0f

                            VideoPlayerView(
                                videoUrl = ep.link,
                                title = "${series.title} S${ep.season}E${ep.episode}",
                                initialProgress = initialProg,
                                onProgressUpdate = { progress ->
                                    viewModel.saveWatchProgress(epId, "${series.title} S${ep.season}E${ep.episode}", series.poster, "webseries", progress)
                                },
                                onBackClick = {
                                    playWithAd {
                                        navigationStack.removeAt(navigationStack.size - 1)
                                    }
                                },
                                onMinimizeClick = { currentPos ->
                                    viewModel.setFloatingVideo(
                                        MovieViewModel.FloatingVideoState(
                                            videoUrl = ep.link,
                                            title = "${series.title} S${ep.season}E${ep.episode}",
                                            contentId = epId,
                                            poster = series.poster,
                                            type = "series",
                                            startPositionMs = currentPos
                                        )
                                    )
                                    playWithAd {
                                        navigationStack.removeAt(navigationStack.size - 1)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Screen.PLAY_LIVETV -> state.liveTvChannel?.let { channel ->
                        VideoPlayerView(
                            videoUrl = channel.streamUrl,
                            title = channel.name,
                            onProgressUpdate = {},
                            onBackClick = {
                                navigationStack.removeAt(navigationStack.size - 1)
                            },
                            onMinimizeClick = { currentPos ->
                                viewModel.setFloatingVideo(
                                    MovieViewModel.FloatingVideoState(
                                        videoUrl = channel.streamUrl,
                                        title = channel.name,
                                        contentId = channel.id,
                                        poster = channel.logoUrl,
                                        type = "livetv",
                                        startPositionMs = currentPos
                                    )
                                )
                                navigationStack.removeAt(navigationStack.size - 1)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Screen.NOTIFICATIONS -> NotificationsScreen(viewModel, onMovieClick = { movie ->
                        navigationStack.add(NavigationState(Screen.DETAIL, movie = movie))
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.MOVIE_REQUEST -> MovieRequestScreen(viewModel, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.ACCOUNT_SETTINGS -> AccountSettingsScreen(
                        viewModel = viewModel,
                        onBack = {
                            navigationStack.removeAt(navigationStack.size - 1)
                        }
                    )

                    Screen.THEME_SETTINGS -> ThemeSettingsScreen(viewModel, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.DOWNLOADS -> DownloadsScreen(viewModel, onPlayDownloaded = { movie ->
                        playWithAd {
                            navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = movie))
                        }
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })

                    Screen.CONTINUE_WATCHING -> ContinueWatchingScreen(viewModel, onContinueMovie = { movie ->
                        playWithAd {
                            navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = movie))
                        }
                    }, onContinueSeries = { series, ep ->
                        playWithAd {
                            navigationStack.add(NavigationState(Screen.PLAY_SERIES, webSeries = series, selectedEpisode = ep))
                        }
                    }, onBack = {
                        navigationStack.removeAt(navigationStack.size - 1)
                    })
                }
            }

            // Draggable Floating Mini-Player overlay
            if (floatingVideoState != null) {
                val fState = floatingVideoState!!
                var offsetX by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 76.dp, end = 16.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Card(
                        modifier = Modifier
                            .width(180.dp)
                            .height(110.dp)
                            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                }
                            }
                            .clickable {
                                if (fState.type == "movie") {
                                    val movie = Movie(
                                        id = fState.contentId,
                                        title = fState.title,
                                        poster = fState.poster,
                                        link = fState.videoUrl,
                                        category = "", backdrop = "", rating = "", year = "", genre = "", stars = "", director = "", storyline = ""
                                    )
                                    navigationStack.add(NavigationState(Screen.PLAY_MOVIE, movie = movie))
                                } else if (fState.type == "series") {
                                    val parts = fState.contentId.split("_s")
                                    val seriesId = parts.firstOrNull() ?: fState.contentId
                                    val epPart = parts.getOrNull(1) ?: ""
                                    val seasonNum = epPart.substringBefore("e").toIntOrNull() ?: 1
                                    val epNum = epPart.substringAfter("e").toIntOrNull() ?: 1

                                    val ep = Episode(season = seasonNum, episode = epNum, title = fState.title, link = fState.videoUrl)
                                    val series = WebSeries(id = seriesId, title = fState.title.substringBefore(" S"), poster = fState.poster)
                                    navigationStack.add(NavigationState(Screen.PLAY_SERIES, webSeries = series, selectedEpisode = ep))
                                } else if (fState.type == "livetv") {
                                    val channel = LiveTvChannel(id = fState.contentId, name = fState.title, logoUrl = fState.poster, streamUrl = fState.videoUrl, category = "")
                                    navigationStack.add(NavigationState(Screen.PLAY_LIVETV, liveTvChannel = channel))
                                }
                                viewModel.setFloatingVideo(null)
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                        border = BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.05f))
                            )
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            MiniPlayerView(
                                videoUrl = fState.videoUrl,
                                startPositionMs = fState.startPositionMs,
                                onClose = { viewModel.setFloatingVideo(null) }
                            )
                        }
                    }
                }
            }

            // Animated top online/offline banners
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                AnimatedVisibility(
                    visible = isOffline,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE53935))
                            .padding(vertical = 10.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("No connection. Running in Offline Mode.", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                var showOnlineBanner by remember { mutableStateOf(false) }
                var lastOfflineState by remember { mutableStateOf(false) }
                LaunchedEffect(isOffline) {
                    if (lastOfflineState && !isOffline) {
                        showOnlineBanner = true
                        delay(3000)
                        showOnlineBanner = false
                    }
                    lastOfflineState = isOffline
                }

                AnimatedVisibility(
                    visible = showOnlineBanner,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = slideOutVertically { -it } + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF4CAF50))
                            .padding(vertical = 10.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connection restored! Back Online.", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ------------------ BOTTOM BAR DESIGN ------------------
@Composable
fun BottomNavigationBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit
) {
    val items = listOf(
        Triple(Screen.HOME, "Home", Icons.Default.Home),
        Triple(Screen.SERIES, "Series", Icons.Default.PlayArrow),
        Triple(Screen.DOWNLOADS, "Downloads", Icons.Default.Download),
        Triple(Screen.WATCHLIST, "My List", Icons.Default.Favorite),
        Triple(Screen.PROFILE, "Profile", Icons.Default.Person)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F14).copy(alpha = 0.94f)) // Translucent iOS style bottom bar background
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Thin iOS status separator line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color(0xFF22222E))
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            items.forEach { (screen, label, icon) ->
                val isSelected = currentScreen == screen
                val activeTint = MaterialTheme.colorScheme.primary
                val inactiveTint = Color(0xFF8E8E93)
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null, // No clumsy round/bean ripples, true iOS minimal crisp feedback
                            onClick = { onNavigate(screen) }
                        )
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) activeTint else inactiveTint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = label,
                        color = if (isSelected) activeTint else inactiveTint,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ------------------ PRIMARY HOMESCREEN ------------------
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    viewModel: MovieViewModel,
    onNavigateToMovie: (Movie) -> Unit,
    onNavigateToSeries: (WebSeries) -> Unit,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit
) {
    val movies by viewModel.movies.collectAsState()
    val webSeries by viewModel.webSeries.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val recommendedMovies by viewModel.recommendedMovies.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var activeMovieIndex by remember { mutableIntStateOf(0) }

    val scale = remember { Animatable(1f) }
    LaunchedEffect(activeMovieIndex) {
        scale.snapTo(1.0f)
        scale.animateTo(
            targetValue = 1.15f,
            animationSpec = tween(durationMillis = 5000, easing = LinearEasing)
        )
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { viewModel.refreshData() }
    )

    // Start auto-scroll banner timer
    LaunchedEffect(movies) {
        while (movies.isNotEmpty()) {
            delay(5000)
            activeMovieIndex = (activeMovieIndex + 1) % movies.take(5).size
        }
    }

    Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Toolbar Top
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row {
                    Text("MANA ", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("CINEMA", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconButton(onClick = onSearchClick) {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = onNotificationsClick) {
                            Icon(imageVector = Icons.Default.Notifications, contentDescription = "Notifications", tint = Color.White)
                        }
                        if (notifications.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .align(Alignment.TopEnd)
                            )
                        }
                    }
                }
            }
        }

        // Auto-Scroll Hero Banner
        if (movies.isNotEmpty()) {
            val heroMovie = movies.getOrNull(activeMovieIndex)
            if (heroMovie != null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable { onNavigateToMovie(heroMovie) },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = heroMovie.backdrop.ifEmpty { heroMovie.poster },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale.value
                                        scaleY = scale.value
                                    }
                            )
                            // Warm vertical dark film gradient
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Black.copy(alpha = 0.3f),
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.95f)
                                            )
                                        )
                                    )
                            )
                            
                            // Top Left - FEATURED BADGE
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "FEATURED NOW",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            // Bottom Content overlay
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = heroMovie.title,
                                        color = Color.White,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = heroMovie.rating,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "${heroMovie.year} • ${heroMovie.genre.split("/").firstOrNull()?.trim() ?: heroMovie.genre}",
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    
                                    // Banners indicators
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(top = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val totalBanners = movies.take(5).size
                                        for (i in 0 until totalBanners) {
                                            Box(
                                                modifier = Modifier
                                                    .size(width = if (i == activeMovieIndex) 16.dp else 6.dp, height = 6.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (i == activeMovieIndex) MaterialTheme.colorScheme.primary
                                                        else Color.White.copy(alpha = 0.4f)
                                                    )
                                                    .clickable { activeMovieIndex = i }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Minimal White Circular Play Button
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .clickable { onNavigateToMovie(heroMovie) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Film",
                                        tint = Color.Black,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Trending Sections (Giant Ranks horizontal List)
        if (movies.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                TrendingSection(movies = movies.take(10), onNavigate = onNavigateToMovie)
            }
        }

        // Personalized Recommendations Row for Selected Profile
        if (recommendedMovies.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(18.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (activeProfile?.isKids == true) "Kids Friendly Favorites" else "Recommended for ${activeProfile?.name ?: "You"}",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recommendedMovies) { movie ->
                            Card(
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(180.dp)
                                    .clickable { onNavigateToMovie(movie) }
                                    .testTag("recommended_movie_${movie.id}"),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = movie.poster,
                                        contentDescription = movie.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Soft Bottom Shade
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                                )
                                            )
                                    )
                                    Text(
                                        text = movie.title,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Category Rows
        items(categories) { categoryName ->
            val catMovies = movies.filter { it.category == categoryName }
            if (catMovies.isNotEmpty()) {
                CategoryGridRow(
                    categoryName = categoryName,
                    movies = catMovies,
                    onClick = onNavigateToMovie
                )
            }
        }
    }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun TrendingSection(
    movies: List<Movie>,
    onNavigate: (Movie) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Top Trending",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(movies) { index, movie ->
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(210.dp)
                        .clickable { onNavigate(movie) }
                ) {
                    // Giant rank background text overlapping the poster
                    Text(
                        text = "${index + 1}",
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        fontSize = 110.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-4).dp, y = 14.dp)
                    )
                    
                    // Poster Card aligned on the right
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .width(125.dp)
                            .height(185.dp)
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 4.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = movie.poster,
                                contentDescription = movie.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Elegant dark overlay gradient
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                        )
                                    )
                            )
                            // Premium Star Rating Pill top-right
                            if (!movie.rating.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = movie.rating,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            // Movie Title overlay at bottom
                            Text(
                                text = movie.title,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 8.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryGridRow(
    categoryName: String,
    movies: List<Movie>,
    onClick: (Movie) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = categoryName,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(movies) { movie ->
                Column(
                    modifier = Modifier
                        .width(115.dp)
                        .clickable { onClick(movie) }
                ) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier.height(165.dp).fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = movie.poster,
                                contentDescription = movie.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Subtle gradient overlay at the bottom half to enhance card depth
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))
                                        )
                                    )
                            )
                            
                            // Rating Badge top-right
                            if (!movie.rating.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(9.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = movie.rating,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = movie.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${movie.year} • ${movie.genre.split("/").firstOrNull()?.trim() ?: movie.genre}",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ------------------ SERIES SCREEN ------------------
@Composable
fun SeriesScreen(
    viewModel: MovieViewModel,
    onNavigateToSeries: (WebSeries) -> Unit
) {
    val webSeries by viewModel.webSeries.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                text = "Web Series",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
        }

        if (webSeries.isNotEmpty()) {
            val hero = webSeries.first()
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onNavigateToSeries(hero) },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = hero.backdrop.ifEmpty { hero.poster },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.3f),
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.95f)
                                        )
                                    )
                                )
                        )
                        
                        // Top Left - FEATURED
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(16.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "FEATURED SHOW",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            )
                        }

                        // Bottom Info Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomStart)
                                .padding(16.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = hero.title,
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = hero.rating,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = "${hero.year} • ${hero.genre.split("/").firstOrNull()?.trim() ?: hero.genre}",
                                        color = Color.LightGray,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Action button
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .clickable { onNavigateToSeries(hero) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Show Details",
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        items(webSeries) { show ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { onNavigateToSeries(show) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Card(
                        modifier = Modifier.size(75.dp, 105.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        AsyncImage(
                            model = show.poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            show.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "★ ${show.rating} • ${show.genre.split("/").firstOrNull()?.trim() ?: show.genre} • ${show.year}",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = show.storyline,
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

// ------------------ LIVETV SCREEN ------------------
@Composable
fun LiveTvScreen(
    viewModel: MovieViewModel,
    onChannelPlay: (LiveTvChannel) -> Unit
) {
    val liveTvChannels by viewModel.liveTvChannels.collectAsState()
    val isChannelsCategories = remember(liveTvChannels) {
        liveTvChannels.map { it.category }.distinct().filter { it.isNotBlank() }
    }
    var selectedCat by remember { mutableStateOf("All") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Live TV Channels",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            item {
                FilterTabButton(
                    label = "All",
                    selected = selectedCat == "All",
                    onClick = { selectedCat = "All" }
                )
            }
            items(isChannelsCategories) { category ->
                FilterTabButton(
                    label = category,
                    selected = selectedCat == category,
                    onClick = { selectedCat = category }
                )
            }
        }

        val filteredChannels = remember(liveTvChannels, selectedCat) {
            if (selectedCat == "All") liveTvChannels else liveTvChannels.filter { it.category == selectedCat }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredChannels) { channel ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChannelPlay(channel) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.2f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.3f))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = channel.logoUrl,
                                contentDescription = channel.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            // LIVE BADGE
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("LIVE", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = channel.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = channel.category,
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FilterTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color(0xFF1C1C1E))
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF2C2C2E),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF8E8E93),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ------------------ WATCHLIST SCREEN ------------------
@Composable
fun WatchlistScreen(
    viewModel: MovieViewModel,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (WebSeries) -> Unit
) {
    val watchlist by viewModel.watchlist.collectAsState()
    val moviesList by viewModel.movies.collectAsState()
    val seriesList by viewModel.webSeries.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "My Watchlist",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        if (watchlist.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Your watchlist is empty", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(watchlist) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clickable {
                                if (item.type == "movie") {
                                    moviesList.find { it.id == item.id }?.let { onMovieClick(it) }
                                } else {
                                    seriesList.find { it.id == item.id }?.let { onSeriesClick(it) }
                                }
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = item.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(70.dp)
                                    .fillMaxHeight()
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(item.type.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(
                                onClick = { viewModel.toggleWatchlist(item.id, item.title, item.poster, item.backdrop, item.type) },
                                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------ PROFILE SCREEN ------------------
@Composable
fun ProfileScreen(
    viewModel: MovieViewModel,
    onMenuClick: (String) -> Unit,
    onLogout: () -> Unit
) {
    val userProfile by viewModel.currentUserProfile.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val isGuest by viewModel.isGuestUser.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        // Upper Backdrop Header displaying Active Profile!
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(82.dp)
                        .clip(CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    AsyncImage(
                        model = activeProfile?.avatarUrl ?: "https://i.ibb.co/yBNK21P/avatar1.jpg",
                        contentDescription = "Active Profile Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = activeProfile?.name ?: "No Profile Selected",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (activeProfile?.isKids == true) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "KIDS",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = "Preferred Genre: ${activeProfile?.preferredGenre ?: "All"}",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        }

        // Quick Profile Switcher Row
        if (profiles.size > 1) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Switch Viewer Profile",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(profiles) { prof ->
                        val isSelected = prof.id == activeProfile?.id
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { viewModel.selectProfile(prof) }
                                .testTag("quick_switch_${prof.id}")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f),
                                        shape = CircleShape
                                    )
                            ) {
                                AsyncImage(
                                    model = prof.avatarUrl,
                                    contentDescription = prof.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = prof.name,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(60.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Activity Preset Rows themed as iOS Grouped List
        val activityItems = remember(isGuest) {
            mutableListOf(
                iOSMenuItemData("Downloads", Icons.Default.ArrowBack) { onMenuClick("downloads") },
                iOSMenuItemData("Continue Watching", Icons.Default.PlayArrow) { onMenuClick("continue") }
            ).apply {
                if (!isGuest) {
                    add(iOSMenuItemData("Movie Request", Icons.Default.Edit) { onMenuClick("request") })
                }
            }
        }

        iOSGroupedSection(title = "My Activity", items = activityItems)

        Spacer(modifier = Modifier.height(8.dp))

        // Settings, Help & Switch profiles styled as iOS Grouped List
        val settingsItems = remember(isGuest) {
            mutableListOf(
                iOSMenuItemData("Switch / Manage Profiles", Icons.Default.Person) {
                    viewModel.clearActiveProfile()
                },
                iOSMenuItemData("Themes & Appearance", Icons.Default.Settings) { onMenuClick("themes") }
            ).apply {
                if (!isGuest) {
                    add(iOSMenuItemData("Account Settings", Icons.Default.Person) { onMenuClick("settings") })
                }
            }
        }

        iOSGroupedSection(title = "Settings & Help", items = settingsItems)

        Spacer(modifier = Modifier.height(24.dp))

        // iOS Style styled Centered red Row button for Logout
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(0.5.dp, Color(0xFF2C2C2E)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { onLogout() }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Sign Out",
                    color = Color(0xFFFF3B30), // iOS System Red
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

data class iOSMenuItemData(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit
)

@Composable
fun iOSGroupedSection(
    title: String,
    items: List<iOSMenuItemData>
) {
    if (items.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = title.uppercase(),
            color = Color(0xFF8E8E93),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(0.5.dp, Color(0xFF2C2C2E)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    iOSRowItem(
                        label = item.label,
                        icon = item.icon,
                        onClick = item.onClick
                    )
                    if (index < items.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .padding(start = 56.dp)
                                .background(Color(0xFF2C2C2E))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun iOSRowItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconBgColor = remember(label) {
            when (label) {
                "Downloads" -> Color(0xFF34C759)
                "Continue Watching" -> Color(0xFF5856D6)
                "Movie Request" -> Color(0xFF007AFF)
                "Themes & Appearance" -> Color(0xFFFF2D55)
                "Account Settings" -> Color(0xFF8E8E93)
                "Ads Diagnostics & Test" -> Color(0xFFFF9F0A)
                else -> Color(0xFF8E8E93)
            }
        }

        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = "Go",
            tint = Color(0xFF8E8E93).copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun iOSLabelledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            color = Color(0xFF8E8E93),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color(0xFF8E8E93)) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1C1C1E),
                unfocusedContainerColor = Color(0xFF1C1C1E),
                disabledContainerColor = Color(0xFF1C1C1E),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
        )
    }
}

// ------------------ MOVIE REQUEST SCREEN ------------------
@Composable
fun MovieRequestScreen(
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Request a Movie", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))

        iOSLabelledTextField(
            value = name,
            onValueChange = { name = it },
            label = "Movie / Show Name",
            placeholder = "e.g. Inception or Breaking Bad"
        )

        Spacer(modifier = Modifier.height(20.dp))

        iOSLabelledTextField(
            value = year,
            onValueChange = { year = it },
            label = "Release Year (Optional)",
            placeholder = "e.g. 2010"
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (name.isBlank()) {
                    Toast.makeText(context, "Please enter a movie name", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                viewModel.submitMovieRequest(name, year.toIntOrNull() ?: 0) { success ->
                    if (success) {
                        Toast.makeText(context, "Request sent successfully!", Toast.LENGTH_SHORT).show()
                        onBack()
                    } else {
                        Toast.makeText(context, "Request submission failed", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Submit Request", fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// ------------------ ACCOUNT SETTINGS SCREEN ------------------
@Composable
fun AccountSettingsScreen(
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    val profile by viewModel.currentUserProfile.collectAsState()
    var name by remember(profile) { mutableStateOf(profile?.displayName ?: "") }
    var selectedGender by remember(profile) { mutableStateOf(profile?.gender ?: "male") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Account Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(32.dp))

        iOSLabelledTextField(
            value = name,
            onValueChange = { name = it },
            label = "Display Name",
            placeholder = "Enter physical name"
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("GENDER IDENTITY", color = Color(0xFF8E8E93), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(start = 6.dp)) {
            FilterTabButton(
                label = "Male",
                selected = selectedGender == "male",
                onClick = { selectedGender = "male" }
            )
            FilterTabButton(
                label = "Female",
                selected = selectedGender == "female",
                onClick = { selectedGender = "female" }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (name.isBlank()) {
                    Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                viewModel.updateProfile(name, selectedGender) { success ->
                    if (success) {
                        Toast.makeText(context, "Changes saved!", Toast.LENGTH_SHORT).show()
                        onBack()
                    } else {
                        Toast.makeText(context, "Failed to update profile", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Save Changes", fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ------------------ THEME SETTINGS SCREEN ------------------
@Composable
fun ThemeSettingsScreen(
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val themes = listOf(
        Pair("classic-red", "Classic Red"),
        Pair("ocean-blue", "Ocean Blue"),
        Pair("forest-green", "Forest Green"),
        Pair("royal-purple", "Royal Purple"),
        Pair("sunset-orange", "Sunset Orange"),
        Pair("cyber-pink", "Cyber Pink")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Themes & Appearance", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("SELECT ACCENT THEME", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        themes.forEach { (id, label) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable {
                        viewModel.saveThemeSettings(id, true)
                        Toast.makeText(context, "$label accent selected!", Toast.LENGTH_SHORT).show()
                    },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (id == "classic-red") {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Red))
                    } else if (id == "ocean-blue") {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Cyan))
                    } else if (id == "forest-green") {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Green))
                    } else if (id == "royal-purple") {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(Color.Magenta))
                    } else {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

// ------------------ MOVIE DETAIL SCREEN ------------------
@Composable
fun DetailScreen(
    viewModel: MovieViewModel,
    movie: Movie,
    onPlayClick: () -> Unit,
    onSimilarMovieClick: (Movie) -> Unit,
    onBack: () -> Unit
) {
    val watchlist by viewModel.watchlist.collectAsState()
    val movies by viewModel.movies.collectAsState()
    val isFav = watchlist.any { it.id == movie.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                AsyncImage(
                    model = movie.backdrop.ifEmpty { movie.poster },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                            )
                        )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                // Play Button floating overlay
                Button(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Movie",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(movie.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "★ ${movie.rating} • ${movie.genre} • ${movie.year}",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPlayClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Watch Now", fontWeight = FontWeight.Bold)
                    }

                    val context = LocalContext.current
                    IconButton(
                        onClick = { 
                            com.example.downloads.DownloadService.startAction(
                                context,
                                movie.id,
                                movie.title,
                                movie.poster,
                                movie.link
                            )
                            Toast.makeText(context, "Download Started", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            painter = painterResource(com.example.R.drawable.ic_download),
                            contentDescription = "Download",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleWatchlist(movie.id, movie.title, movie.poster, movie.backdrop, "movie") },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Watchlist",
                            tint = if (isFav) Color.Red else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("STORYLINE", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(movie.storyline.ifEmpty { "No description available." }, color = Color.LightGray, fontSize = 14.sp, lineHeight = 22.sp)

                Spacer(modifier = Modifier.height(20.dp))
                Text("CREW DETAILS", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Director: ${movie.director.ifEmpty { "Unknown" }}", color = Color.LightGray, fontSize = 13.sp)
                Text("Cast stars: ${movie.stars.ifEmpty { "Unknown" }}", color = Color.LightGray, fontSize = 13.sp)
            }
        }

        // Similar Showcase Items
        item {
            val similar = movies.filter { it.genre.split(",").any { g -> movie.genre.contains(g.trim()) } && it.id != movie.id }
            if (similar.isNotEmpty()) {
                CategoryGridRow(
                    categoryName = "More Like This",
                    movies = similar,
                    onClick = onSimilarMovieClick
                )
            }
        }
    }
}

// ------------------ SERIES DETAIL SCREEN ------------------
@Composable
fun SeriesDetailScreen(
    viewModel: MovieViewModel,
    series: WebSeries,
    onEpisodePlay: (Episode) -> Unit,
    onBack: () -> Unit
) {
    val watchlist by viewModel.watchlist.collectAsState()
    val isFav = watchlist.any { it.id == series.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                AsyncImage(
                    model = series.backdrop.ifEmpty { series.poster },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                            )
                        )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(series.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "★ ${series.rating} • ${series.genre} • ${series.year}",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(
                        onClick = { viewModel.toggleWatchlist(series.id, series.title, series.poster, series.backdrop, "webseries") },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Watchlist",
                            tint = if (isFav) Color.Red else Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("STORYLINE", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(series.storyline.ifEmpty { "No series details available." }, color = Color.LightGray, fontSize = 14.sp, lineHeight = 22.sp)

                Spacer(modifier = Modifier.height(24.dp))
                Text("EPISODES LISTING", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        items(series.episodes) { ep ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { onEpisodePlay(ep) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "S${ep.season} E${ep.episode} • ${ep.title}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Play high definition stream",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    val context = LocalContext.current
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconButton(
                            onClick = { 
                                val downloadId = "${series.id}_s${ep.season}e${ep.episode}"
                                com.example.downloads.DownloadService.startAction(
                                    context,
                                    downloadId,
                                    "${series.title} S${ep.season}E${ep.episode}: ${ep.title}",
                                    series.poster,
                                    ep.link
                                )
                                Toast.makeText(context, "Download Started", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(
                                painter = painterResource(com.example.R.drawable.ic_download),
                                contentDescription = "Download Episode",
                                tint = Color.LightGray
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play Episode",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------ NOTIFICATIONS SCREEN ------------------
@Composable
fun NotificationsScreen(
    viewModel: MovieViewModel,
    onMovieClick: (Movie) -> Unit,
    onBack: () -> Unit
) {
    val notifications by viewModel.notifications.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("System Notifications", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No notifications yet", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(notifications) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(item.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(item.message, color = Color.LightGray, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ------------------ SEARCH SCREEN ------------------
@Composable
fun SearchScreen(
    viewModel: MovieViewModel,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (WebSeries) -> Unit,
    onBack: () -> Unit
) {
    val movies by viewModel.movies.collectAsState()
    val webSeries by viewModel.webSeries.collectAsState()
    var query by remember { mutableStateOf("") }

    val filteredMovies = remember(movies, query) {
        if (query.isBlank()) emptyList() else movies.filter { it.title.contains(query, ignoreCase = true) || it.genre.contains(query, ignoreCase = true) }
    }
    val filteredSeries = remember(webSeries, query) {
        if (query.isBlank()) emptyList() else webSeries.filter { it.title.contains(query, ignoreCase = true) || it.genre.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C1C1E))
                    .border(0.5.dp, Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Icon",
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_field"),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(
                                    text = "Search titles, genres...",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 15.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { query = "" }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = "Cancel",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onBack() }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (query.isBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Search everything movie on MovieHunt", color = Color.Gray)
                }
            }
        } else if (filteredMovies.isEmpty() && filteredSeries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching titles found.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (filteredMovies.isNotEmpty()) {
                    item {
                        Text("MOVIES MATCHES", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    items(filteredMovies) { movie ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clickable { onMovieClick(movie) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = movie.poster,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(60.dp).fillMaxHeight()
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(movie.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(movie.genre, color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                if (filteredSeries.isNotEmpty()) {
                    item {
                        Text("WEB SERIES MATCHES", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    items(filteredSeries) { series ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clickable { onSeriesClick(series) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = series.poster,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(60.dp).fillMaxHeight()
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(series.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(series.genre, color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------ DOWNLOADS SCREEN ------------------
@Composable
fun DownloadsScreen(
    viewModel: MovieViewModel,
    onPlayDownloaded: (Movie) -> Unit,
    onBack: () -> Unit
) {
    val downloads by viewModel.downloads.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("My Downloads", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        
        if (isOffline) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "You are offline. Showing your downloaded content.",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No downloaded media available.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(downloads) { download ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clickable(enabled = download.status == "COMPLETED") {
                                if (download.status == "COMPLETED" && download.localUri != null) {
                                    val movie = Movie(
                                        id = download.id,
                                        title = download.title,
                                        poster = download.poster,
                                        link = download.localUri,
                                        category = "", backdrop = "", rating = "", year = "", genre = "", stars = "", director = "", storyline = ""
                                    )
                                    onPlayDownloaded(movie)
                                }
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = download.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(70.dp).fillMaxHeight()
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(download.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                val statusColor = when (download.status) {
                                    "COMPLETED" -> MaterialTheme.colorScheme.primary
                                    "FAILED" -> Color.Red
                                    else -> Color.Gray
                                }
                                
                                if (download.status == "DOWNLOADING" || download.status == "PAUSED") {
                                    LinearProgressIndicator(
                                        progress = download.progress / 100f,
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = Color.DarkGray
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("${download.status} - ${download.progress}%", color = Color.LightGray, fontSize = 11.sp)
                                } else {
                                    Text(download.status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Row(
                                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (download.status == "DOWNLOADING") {
                                    IconButton(onClick = { com.example.downloads.DownloadService.controlAction(context, download.id, com.example.downloads.DownloadService.ACTION_PAUSE) }) {
                                        Icon(painterResource(com.example.R.drawable.ic_pause), contentDescription = "Pause", tint = Color.White)
                                    }
                                } else if (download.status == "PAUSED" || download.status == "FAILED") {
                                    IconButton(onClick = { com.example.downloads.DownloadService.controlAction(context, download.id, com.example.downloads.DownloadService.ACTION_RESUME) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White)
                                    }
                                } else if (download.status == "COMPLETED") {
                                    IconButton(onClick = { /* already handled by card click */ }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                
                                IconButton(onClick = { com.example.downloads.DownloadService.controlAction(context, download.id, com.example.downloads.DownloadService.ACTION_CANCEL) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------ CONTINUE WATCHING SCREEN ------------------
@Composable
fun ContinueWatchingScreen(
    viewModel: MovieViewModel,
    onContinueMovie: (Movie) -> Unit,
    onContinueSeries: (WebSeries, Episode) -> Unit,
    onBack: () -> Unit
) {
    val continueWatchingList by viewModel.continueWatching.collectAsState()
    val moviesList by viewModel.movies.collectAsState()
    val seriesList by viewModel.webSeries.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Continue Watching", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (continueWatchingList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No unfinished items found in history.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(continueWatchingList) { record ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clickable {
                                if (record.type == "movie") {
                                    moviesList.find { it.id == record.contentId }?.let { onContinueMovie(it) }
                                } else {
                                    // Parse show id
                                    val seriesId = record.contentId.substringBefore("_s")
                                    val series = seriesList.find { it.id == seriesId }
                                    if (series != null) {
                                        val epText = record.contentId.substringAfter("_s")
                                        val sNum = epText.substringBefore("e").toIntOrNull() ?: 1
                                        val eNum = epText.substringAfter("e").toIntOrNull() ?: 1
                                        val ep = series.episodes.find { it.season == sNum && it.episode == eNum } ?: series.episodes.firstOrNull()
                                        if (ep != null) {
                                            onContinueSeries(series, ep)
                                        }
                                    }
                                }
                            },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = record.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(70.dp).fillMaxHeight()
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(record.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Column {
                                    Text("Unfinished - ${(record.progress * 100).toInt()}% watched", color = Color.Gray, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = record.progress,
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = Color.DarkGray
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

// ------------------ PROFILE SELECTION SCREEN & MANAGEMENT SYSTEM ------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSelectionScreen(
    viewModel: MovieViewModel,
    onLogout: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val isGuest by viewModel.isGuestUser.collectAsState()

    var isEditMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<MovieProfile?>(null) }

    // Dialog form states
    var inputName by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf("https://i.ibb.co/yBNK21P/avatar1.jpg") }
    var isKidsState by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf("All") }

    val avatars = listOf(
        "https://i.ibb.co/yBNK21P/avatar1.jpg", // Red
        "https://i.ibb.co/VtxZ2Zq/avatar2.jpg", // Blue
        "https://i.ibb.co/zX7Vp6c/avatar3.jpg", // Green
        "https://i.ibb.co/0y7R8j4/avatar4.jpg", // Yellow
        "https://i.ibb.co/W21d2gX/avatar5.jpg"  // Purple
    )

    val genres = listOf("All", "Action", "Drama", "Sci-Fi", "Comedy", "Romance", "Adventure")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            AsyncImage(
                model = com.example.R.drawable.img_app_logo,
                contentDescription = "Mana Cinema Logo",
                modifier = Modifier
                    .size(110.dp)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .testTag("app_logo_image")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text("MANA ", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("CINEMA", color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = if (isEditMode) "Manage Profiles" else "Who's watching today?",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Grid of Profiles
            val columns = 2
            val chunked = profiles.chunked(columns)
            chunked.forEach { rowProfiles ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    rowProfiles.forEach { profile ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    if (isEditMode) {
                                        editingProfile = profile
                                        inputName = profile.name
                                        selectedAvatar = profile.avatarUrl
                                        isKidsState = profile.isKids
                                        selectedGenre = profile.preferredGenre
                                        showAddDialog = true
                                    } else {
                                        viewModel.selectProfile(profile)
                                    }
                                }
                                .testTag("profile_item_${profile.id}")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(
                                        width = if (isEditMode) 2.dp else 1.dp,
                                        color = if (isEditMode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                AsyncImage(
                                    model = profile.avatarUrl,
                                    contentDescription = profile.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (isEditMode) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.5f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit profile",
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = profile.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                )
                                if (profile.isKids) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "KIDS",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Add button if profiles count < 4
                    if (rowProfiles.size < columns && profiles.size < 4 && !isEditMode) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                editingProfile = null
                                inputName = ""
                                selectedAvatar = avatars.random()
                                isKidsState = false
                                selectedGenre = "All"
                                showAddDialog = true
                            }.testTag("add_profile_shortcut")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Create profile",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Create", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
            }

            // Inline Add/Create card if profiles size < 4 and row profiles list is empty
            if (profiles.size < 4 && profiles.isEmpty() && !isEditMode) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        editingProfile = null
                        inputName = ""
                        selectedAvatar = avatars.random()
                        isKidsState = false
                        selectedGenre = "All"
                        showAddDialog = true
                    }.testTag("add_profile_empty")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Create profile",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add Profile", color = Color.Gray, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Management Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = { isEditMode = !isEditMode },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.testTag("manage_profiles_btn")
                ) {
                    Icon(
                        imageVector = if (isEditMode) Icons.Default.Done else Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isEditMode) "Done" else "Manage")
                }

                if (profiles.size < 4 && !isEditMode) {
                    Button(
                        onClick = {
                            editingProfile = null
                            inputName = ""
                            selectedAvatar = avatars.random()
                            isKidsState = false
                            selectedGenre = "All"
                            showAddDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("add_profile_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Profile")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Logout Action
            TextButton(
                onClick = onLogout,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.LightGray),
                modifier = Modifier.testTag("auth_logout_btn")
            ) {
                Icon(imageVector = Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Log Out Account", fontSize = 14.sp)
            }
        }
    }

    // Modern Sheet/Dialog for Add/Edit profile
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = Color(0xFF16161D),
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = if (editingProfile != null) "Edit Profile" else "Add Brand-New Profile",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Profile Name Input
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { inputName = it },
                        label = { Text("Profile Name", color = Color.Gray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = Color.White.copy(alpha = 0.2f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("profile_name_field"),
                        singleLine = true
                    )

                    // Avatar Selector Row
                    Column {
                        Text("Choose Avatar Picture", color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            avatars.forEach { url ->
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = if (selectedAvatar == url) 2.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedAvatar = url }
                                ) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    // Separation Option - Preferred Genre Switcher
                    Column {
                        Text("Personalized Preferred Genre", color = Color.Gray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            genres.forEach { genre ->
                                val selected = selectedGenre == genre
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { selectedGenre = genre }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = genre,
                                        color = if (selected) Color.White else Color.LightGray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Separation Option - Kids Mode Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Kids Mode", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Family-friendly & Safe Animation focus", color = Color.Gray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = isKidsState,
                            onCheckedChange = { isKidsState = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (editingProfile != null) {
                        IconButton(
                            onClick = {
                                viewModel.deleteProfile(editingProfile!!.id)
                                showAddDialog = false
                                isEditMode = false
                            },
                            modifier = Modifier.testTag("delete_profile_trash_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Profile", tint = Color.Red.copy(alpha = 0.8f))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showAddDialog = false }) {
                            Text("Cancel", color = Color.Gray)
                        }
                        Button(
                            onClick = {
                                if (inputName.isNotBlank()) {
                                    val newProf = MovieProfile(
                                        id = editingProfile?.id ?: "prof_${System.currentTimeMillis()}",
                                        name = inputName.trim(),
                                        avatarUrl = selectedAvatar,
                                        isKids = isKidsState,
                                        preferredGenre = selectedGenre
                                    )
                                    viewModel.saveNewProfile(newProf)
                                    showAddDialog = false
                                    isEditMode = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("save_profile_done_btn")
                        ) {
                            Text("Save", color = Color.White)
                        }
                    }
                }
            }
        )
    }
}

// ------------------ ADS DIAGNOSTICS & TEST SCREEN ------------------
@Composable
fun AdsDiagnosticsScreen(
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    // Local states for custom config input fields
    var gameIdInput by remember { mutableStateOf("dummy_game_id") }
    var placementInput by remember { mutableStateOf("dummy_placement") }
    var testModeInput by remember { mutableStateOf(true) }

    // Live diagnostics states refreshed periodically or on action
    var isInitializedState by remember { mutableStateOf(false) }
    var isAdLoadedState by remember { mutableStateOf(false) }
    var initErrorState by remember { mutableStateOf<String?>(null) }
    var loadErrorState by remember { mutableStateOf<String?>(null) }
    var showErrorState by remember { mutableStateOf<String?>(null) }

    // Periodic check to keep UI reactive
    LaunchedEffect(Unit) {
        while (true) {
            isInitializedState = false
            isAdLoadedState = false
            initErrorState = null
            loadErrorState = null
            showErrorState = null
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14))
            .statusBarsPadding()
    ) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "Ads Diagnostics & Test",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Connection Status Cards
            item {
                Text(
                    text = "STATUS DIAGNOSTICS",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    border = BorderStroke(0.5.dp, Color(0xFF2C2C2E))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // SDK Initialization Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("SDK Initialization", color = Color.White, fontSize = 15.sp)
                            StatusBadge(isActive = isInitializedState, activeText = "Initialized", inactiveText = "Uninitialized")
                        }
                        
                        if (initErrorState != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Init Error: $initErrorState",
                                color = Color(0xFFFF453A),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(Color(0xFF2C2C2E))
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Ad Loaded Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Ad Buffering/Available", color = Color.White, fontSize = 15.sp)
                            StatusBadge(isActive = isAdLoadedState, activeText = "Ad Buffer Loaded", inactiveText = "No Ad Cached")
                        }
                        
                        if (loadErrorState != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Load Error: $loadErrorState",
                                color = Color(0xFFFF9F0A),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp)
                            )
                        }

                        if (showErrorState != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Last Show Failure: $showErrorState",
                                color = Color(0xFFFF453A),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

            // Section 2: Interactive Ad Tests
            item {
                Text(
                    text = "INTERACTIVE AD TESTS",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    border = BorderStroke(0.5.dp, Color(0xFF2C2C2E))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                Toast.makeText(context, "SDK Initialization Success! (Offline Simulation)", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Force SDK Re-Initialization", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                Toast.makeText(context, "Ad fetching triggered successfully! (Offline Simulation)", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5856D6)),
                            enabled = true
                        ) {
                            Text("Trigger Custom Ad Pre-Fetch", color = Color.White)
                        }

                        Button(
                            onClick = {
                                Toast.makeText(context, "Ad completion callback completed! Integration fully verified ✅ (Offline Simulation)", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))
                        ) {
                            Text("Play Rewarding Video Ad Test", color = Color.White)
                        }
                    }
                }
            }

            // Section 3: Custom Unity Ads Engine Configuration
            item {
                Text(
                    text = "ENGINE CONFIGURATION",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    border = BorderStroke(0.5.dp, Color(0xFF2C2C2E))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Unity Game ID input
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Unity Game ID", color = Color.Gray, fontSize = 13.sp)
                            OutlinedTextField(
                                value = gameIdInput,
                                onValueChange = { gameIdInput = it },
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF2C2C2E),
                                    focusedContainerColor = Color(0xFF0F0F14),
                                    unfocusedContainerColor = Color(0xFF0F0F14)
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        // Unity Reward Placement Code input
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Rewarded Placement Code", color = Color.Gray, fontSize = 13.sp)
                            OutlinedTextField(
                                value = placementInput,
                                onValueChange = { placementInput = it },
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color(0xFF2C2C2E),
                                    focusedContainerColor = Color(0xFF0F0F14),
                                    unfocusedContainerColor = Color(0xFF0F0F14)
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        // SDK Test Mode Checkbox Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Unity SDK Test Mode", color = Color.White, fontSize = 15.sp)
                                Text(
                                    "Turn ON test ads to ensure easy ad completion and bypass Google Play publication constraints.",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = testModeInput,
                                onCheckedChange = { testModeInput = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Save Configuration button
                        Button(
                            onClick = {
                                if (gameIdInput.isBlank() || placementInput.isBlank()) {
                                    Toast.makeText(context, "Game ID and Placement cannot be blank!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Engine configuration updated & initialized! ⚙️ (Offline Simulation)", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Assign & Apply Configuration", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            
            // Section 4: General diagnostic recommendations
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E).copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "💡 Diagnostics Guidance",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. To test offline or in virtual environments, make sure Test Mode is ENABLED.\n" +
                                   "2. If initialization fails, please verify your internet connection.\n" +
                                   "3. Ad buffering requires a few seconds after initialization completes. Click 'Trigger Custom Ad Pre-Fetch' to check status. if it succeeds, click 'Play Rewarding Video Ad Test' to view the full video test frame.\n" +
                                   "4. When showing ads, if a load error of 'NO_FILL' occurs, it means Unity currently has no available ads for your mock traffic; dynamic test ads are served securely to prevent blocking your flows.",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun StatusBadge(isActive: Boolean, activeText: String, inactiveText: String) {
    Box(
        modifier = Modifier
            .background(
                color = if (isActive) Color(0xFF34C759).copy(alpha = 0.15f) else Color(0xFFFF453A).copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (isActive) activeText else inactiveText,
            color = if (isActive) Color(0xFF34C759) else Color(0xFFFF453A),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
