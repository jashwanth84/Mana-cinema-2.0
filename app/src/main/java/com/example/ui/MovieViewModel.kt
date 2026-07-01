package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.models.*
import com.example.repository.MovieRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MovieViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MovieRepository(application)
    private val sharedPrefs = application.getSharedPreferences("infinity_movie_prefs", Context.MODE_PRIVATE)

    // --- Floating Video Player State ---
    data class FloatingVideoState(
        val videoUrl: String,
        val title: String,
        val contentId: String,
        val poster: String,
        val type: String,
        val startPositionMs: Long
    )

    private val _floatingVideo = MutableStateFlow<FloatingVideoState?>(null)
    val floatingVideo: StateFlow<FloatingVideoState?> = _floatingVideo.asStateFlow()

    fun setFloatingVideo(state: FloatingVideoState?) {
        _floatingVideo.value = state
    }

    // ------------------ EXPOSED STATEFLOWS ------------------
    private val _movies = MutableStateFlow<List<Movie>>(emptyList())
    val movies: StateFlow<List<Movie>> = _movies.asStateFlow()

    private val _webSeries = MutableStateFlow<List<WebSeries>>(emptyList())
    val webSeries: StateFlow<List<WebSeries>> = _webSeries.asStateFlow()

    private val _liveTvChannels = MutableStateFlow<List<LiveTvChannel>>(emptyList())
    val liveTvChannels: StateFlow<List<LiveTvChannel>> = _liveTvChannels.asStateFlow()

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _notifications = MutableStateFlow<List<UserNotification>>(emptyList())
    val notifications: StateFlow<List<UserNotification>> = _notifications.asStateFlow()

    private val _watchlist = MutableStateFlow<List<WatchlistItem>>(emptyList())
    val watchlist: StateFlow<List<WatchlistItem>> = _watchlist.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<ContinueWatching>>(emptyList())
    val continueWatching: StateFlow<List<ContinueWatching>> = _continueWatching.asStateFlow()

    private val _currentUserProfile = MutableStateFlow<UserProfile?>(null)
    val currentUserProfile: StateFlow<UserProfile?> = _currentUserProfile.asStateFlow()

    // --- Profile System State ---
    private val _activeProfile = MutableStateFlow<MovieProfile?>(null)
    val activeProfile: StateFlow<MovieProfile?> = _activeProfile.asStateFlow()

    private val _profiles = MutableStateFlow<List<MovieProfile>>(emptyList())
    val profiles: StateFlow<List<MovieProfile>> = _profiles.asStateFlow()

    val recommendedMovies: StateFlow<List<Movie>> = combine(movies, _activeProfile) { movieList, profile ->
        if (profile == null) return@combine movieList
        var filtered = movieList
        if (profile.isKids) {
            filtered = filtered.filter { movie ->
                val g = movie.genre.lowercase()
                g.contains("animation") || g.contains("family") || g.contains("comedy") || g.contains("adventure")
            }
        } else if (profile.preferredGenre != "All") {
            filtered = filtered.filter { movie ->
                movie.genre.lowercase().contains(profile.preferredGenre.lowercase())
            }
        }
        filtered.take(12)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGuestUser = MutableStateFlow(false)
    val isGuestUser: StateFlow<Boolean> = _isGuestUser.asStateFlow()

    private val _isMaintenance = MutableStateFlow(false)
    val isMaintenance: StateFlow<Boolean> = _isMaintenance.asStateFlow()

    private val _maintenanceMessage = MutableStateFlow("System is currently undergoing scheduled maintenance.")
    val maintenanceMessage: StateFlow<String> = _maintenanceMessage.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    fun setOfflineState(offline: Boolean) {
        _isOffline.value = offline
    }

    val downloads: StateFlow<List<com.example.database.DownloadEntity>> = repository.getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshData() {
        viewModelScope.launch {
            _isRefreshing.value = true
            
            // Guarantee refresh state is cleared even if network fails
            launch {
                kotlinx.coroutines.delay(4000)
                _isRefreshing.value = false
            }

            launch {
                repository.getMoviesFromFirebase()
                    .catch { android.util.Log.e("MovieViewModel", "Refresh movies failed", it) }
                    .take(1)
                    .collect { list ->
                        _movies.value = list
                        updateCategories(list)
                        repository.saveMoviesToLocal(list)
                        _isRefreshing.value = false
                    }
            }

            launch {
                repository.getWebSeriesFromFirebase()
                    .catch { android.util.Log.e("MovieViewModel", "Refresh series failed", it) }
                    .take(1)
                    .collect { list ->
                        _webSeries.value = list
                        repository.saveSeriesToLocal(list)
                    }
            }
            
            launch {
                repository.getLiveTvChannelsFromFirebase()
                    .catch { android.util.Log.e("MovieViewModel", "Refresh channels failed", it) }
                    .take(1)
                    .collect { list ->
                        _liveTvChannels.value = list
                    }
            }
        }
    }

    // Removed fake data list defaultMovies

    // Removed fake data list defaultWebSeries

    // Removed fake data list defaultLiveChannels

    init {
        // Safeguard initialization of Firebase
        com.example.firebase.FirebaseInitializer.initialize(application)

        // Initialize user login state
        observeUserSession()

        // Fetch Live real-time sync from Firebase & fallback to Local DB
        viewModelScope.launch {
            _isLoading.value = true

            // Guarantees loading is cleared after 4 seconds even if Firebase hangs
            launch {
                kotlinx.coroutines.delay(4000)
                _isLoading.value = false
            }

            // Gather maintenance state securely
            launch {
                repository.getSystemMaintenance()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Error syncing maintenance", e)
                        emit(Pair(false, "System maintenance failed to sync"))
                    }
                    .collect { (enabled, message) ->
                        _isMaintenance.value = enabled
                        _maintenanceMessage.value = message
                    }
            }

            // Sync or read cached items
            launch {
                repository.localMoviesFlow.collect { entities ->
                    val (mappedMovies, mappedCategories) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        val mapped = entities.map {
                            Movie(
                                id = it.id,
                                title = it.title,
                                poster = it.poster,
                                backdrop = it.backdrop,
                                category = it.category,
                                rating = it.rating,
                                year = it.year,
                                genre = it.genre,
                                link = it.link,
                                stars = it.stars,
                                director = it.director,
                                storyline = it.storyline
                            )
                        }
                        val cats = mapped.map { it.category }.distinct().filter { it.isNotBlank() }
                        Pair(mapped, cats)
                    }
                    _movies.value = mappedMovies
                    _categories.value = mappedCategories
                }
            }

            launch {
                repository.localSeriesFlow.collect { entities ->
                    val mappedSeries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        entities.map { s ->
                            val episodesList = mutableListOf<Episode>()
                            if (!s.episodesJson.isNullOrEmpty()) {
                                try {
                                    val arr = org.json.JSONArray(s.episodesJson)
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        episodesList.add(
                                            Episode(
                                                season = obj.optInt("season", 1),
                                                episode = obj.optInt("episode", 1),
                                                title = obj.optString("title", ""),
                                                link = obj.optString("link", "")
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("MovieViewModel", "Error parsing cached series JSON", e)
                                }
                            }
                            WebSeries(
                                id = s.id,
                                title = s.title,
                                poster = s.poster,
                                backdrop = s.backdrop,
                                category = s.category,
                                rating = s.rating,
                                year = s.year,
                                genre = s.genre,
                                storyline = s.storyline,
                                episodes = episodesList
                            )
                        }
                    }
                    _webSeries.value = mappedSeries
                }
            }

            // Bind watchlist & continue watching dynamically depending on the selected active profile
            launch {
                _activeProfile.collectLatest { profile ->
                    if (profile != null) {
                        repository.syncFirestoreWatchlistAndHistoryToLocal(profile.id)
                        
                        // Collect watchlist for active profile
                        launch {
                            repository.getLocalWatchlistFlow(profile.id).collect { entities ->
                                _watchlist.value = entities.map {
                                    WatchlistItem(it.id, it.title, it.poster, it.backdrop, it.type)
                                }
                            }
                        }

                        // Collect continue watching for active profile
                        launch {
                            repository.getLocalContinueWatchingFlow(profile.id).collect { entities ->
                                _continueWatching.value = entities.map {
                                    ContinueWatching(it.contentId, it.title, it.poster, it.type, it.progress)
                                }
                            }
                        }
                    } else {
                        _watchlist.value = emptyList()
                        _continueWatching.value = emptyList()
                    }
                }
            }

            // Sync user profiles reactively based on currently authenticated Firebase/Guest account
            launch {
                _currentUserProfile.collectLatest { user ->
                    if (user != null) {
                        val uid = user.uid
                        launch {
                            repository.getProfilesFromFirestore(uid).collect { firestoreList ->
                                val localProfiles = getSavedProfilesFromPrefs(uid)
                                val merged = (firestoreList + localProfiles).distinctBy { it.id }
                                if (merged.isEmpty()) {
                                    val defaultProfile = MovieProfile(
                                        id = "prof_default_${System.currentTimeMillis()}",
                                        name = user.displayName.ifEmpty { "Main Viewer" },
                                        avatarUrl = "https://i.ibb.co/yBNK21P/avatar1.jpg",
                                        isKids = false,
                                        preferredGenre = "All"
                                    )
                                    saveNewProfile(defaultProfile)
                                } else {
                                    _profiles.value = merged
                                    if (_activeProfile.value == null) {
                                        val lastActiveId = sharedPrefs.getString("last_active_profile_$uid", "")
                                        val lastProf = merged.find { it.id == lastActiveId } ?: merged.firstOrNull()
                                        _activeProfile.value = lastProf
                                    }
                                }
                            }
                        }
                    } else {
                        _profiles.value = emptyList()
                        _activeProfile.value = null
                    }
                }
            }

            // Fetch Live Database content securely without fallback seeding if remote is empty
            launch {
                repository.getMoviesFromFirebase()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Movies Firebase fetch failed", e)
                        emit(emptyList())
                    }
                    .collect { list ->
                        _movies.value = list
                        updateCategories(list)
                        repository.saveMoviesToLocal(list)
                        _isLoading.value = false
                    }
            }

            launch {
                repository.getWebSeriesFromFirebase()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "WebSeries Firebase fetch failed", e)
                        emit(emptyList())
                    }
                    .collect { list ->
                        _webSeries.value = list
                        repository.saveSeriesToLocal(list)
                    }
            }

            launch {
                repository.getLiveTvChannelsFromFirebase()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Channels Firebase fetch failed", e)
                        emit(emptyList())
                    }
                    .collect { list ->
                        _liveTvChannels.value = list
                    }
            }

            launch {
                repository.getNotifications()
                    .catch { e ->
                        android.util.Log.e("MovieViewModel", "Notifications Firebase fetch failed", e)
                        emit(emptyList())
                    }
                    .collect { list ->
                        _notifications.value = list
                    }
            }
        }
    }

    private fun observeUserSession() {
        try {
            val user = FirebaseAuth.getInstance().currentUser
            val isGuestPersisted = sharedPrefs.getBoolean("is_guest_mode", false)
            
            if (user != null && !user.isAnonymous) {
                _isGuestUser.value = false
                val displayName = sharedPrefs.getString("user_name_${user.uid}", user.displayName ?: user.email?.substringBefore("@") ?: "Viewer") ?: "Viewer"
                val photoUrl = sharedPrefs.getString("user_photo_${user.uid}", "https://i.ibb.co/yBNK21P/avatar1.jpg") ?: "https://i.ibb.co/yBNK21P/avatar1.jpg"
                val gender = sharedPrefs.getString("user_gender_${user.uid}", "male") ?: "male"

                _currentUserProfile.value = UserProfile(
                    uid = user.uid,
                    displayName = displayName,
                    email = user.email ?: "viewer@moviehunt.com",
                    photoURL = photoUrl,
                    gender = gender,
                    createdAt = System.currentTimeMillis()
                )
            } else if (user != null && user.isAnonymous) {
                _isGuestUser.value = true
                val displayName = sharedPrefs.getString("user_name_${user.uid}", "Guest User") ?: "Guest"
                val photoUrl = sharedPrefs.getString("user_photo_${user.uid}", "https://i.ibb.co/yBNK21P/avatar1.jpg") ?: "https://i.ibb.co/yBNK21P/avatar1.jpg"
                val gender = sharedPrefs.getString("user_gender_${user.uid}", "male") ?: "male"

                _currentUserProfile.value = UserProfile(
                    uid = user.uid,
                    displayName = displayName,
                    email = "guest@moviehunt.com",
                    photoURL = photoUrl,
                    gender = gender,
                    createdAt = System.currentTimeMillis()
                )
            } else if (isGuestPersisted) {
                _isGuestUser.value = true
                val displayName = sharedPrefs.getString("user_name_guest_user", "Guest User") ?: "Guest"
                val photoUrl = sharedPrefs.getString("user_photo_guest_user", "https://i.ibb.co/yBNK21P/avatar1.jpg") ?: "https://i.ibb.co/yBNK21P/avatar1.jpg"
                val gender = sharedPrefs.getString("user_gender_guest_user", "male") ?: "male"

                _currentUserProfile.value = UserProfile(
                    uid = "guest_user",
                    displayName = displayName,
                    email = "guest@moviehunt.com",
                    photoURL = photoUrl,
                    gender = gender,
                    createdAt = System.currentTimeMillis()
                )
            } else {
                _isGuestUser.value = false
                _currentUserProfile.value = null
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieViewModel", "Error in observeUserSession - falling back to Guest default", e)
            _isGuestUser.value = true
            _currentUserProfile.value = UserProfile(
                uid = "guest_user",
                displayName = "Guest User",
                email = "guest@moviehunt.com",
                photoURL = "https://i.ibb.co/yBNK21P/avatar1.jpg",
                gender = "male",
                createdAt = System.currentTimeMillis()
            )
        }
    }

    private fun updateCategories(moviesList: List<Movie>) {
        _categories.value = moviesList.map { it.category.ifBlank { "Movies" } }.distinct()
    }

    fun handleGuestLogin() {
        sharedPrefs.edit().putBoolean("is_guest_mode", true).apply()
        observeUserSession()
    }

    fun handleLogout() {
        sharedPrefs.edit().putBoolean("is_guest_mode", false).apply()
        observeUserSession()
    }

    fun handleAuthUpdate() {
        observeUserSession()
    }

    fun deleteMovie(movieId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            repository.deleteMovieFromFirebase(movieId) { success ->
                if (success) {
                    _movies.value = _movies.value.filter { it.id != movieId }
                    updateCategories(_movies.value)
                    viewModelScope.launch {
                        repository.saveMoviesToLocal(_movies.value)
                    }
                }
                onComplete(success)
            }
        }
    }

    fun deleteWebSeries(seriesId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            repository.deleteSeriesFromFirebase(seriesId) { success ->
                if (success) {
                    _webSeries.value = _webSeries.value.filter { it.id != seriesId }
                    viewModelScope.launch {
                        repository.saveSeriesToLocal(_webSeries.value)
                    }
                }
                onComplete(success)
            }
        }
    }

    fun clearAllMovies(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            repository.clearAllMoviesFromFirebase { success ->
                if (success) {
                    _movies.value = emptyList()
                    _categories.value = emptyList()
                    viewModelScope.launch {
                        repository.saveMoviesToLocal(emptyList())
                    }
                }
                onComplete(success)
            }
        }
    }

    fun clearAllWebSeries(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            repository.clearAllSeriesFromFirebase { success ->
                if (success) {
                    _webSeries.value = emptyList()
                    viewModelScope.launch {
                        repository.saveSeriesToLocal(emptyList())
                    }
                }
                onComplete(success)
            }
        }
    }

    // ------------------ PROFILE DATA MANAGERS ------------------

    fun getSavedProfilesFromPrefs(uid: String): List<MovieProfile> {
        val count = sharedPrefs.getInt("profile_count_$uid", 0)
        val list = mutableListOf<MovieProfile>()
        for (i in 0 until count) {
            val id = sharedPrefs.getString("profile_id_${uid}_$i", "") ?: continue
            if (id.isEmpty()) continue
            val name = sharedPrefs.getString("profile_name_${uid}_$id", "") ?: ""
            val avatar = sharedPrefs.getString("profile_avatar_${uid}_$id", "") ?: ""
            val isKids = sharedPrefs.getBoolean("profile_iskids_${uid}_$id", false)
            val genre = sharedPrefs.getString("profile_genre_${uid}_$id", "All") ?: "All"
            if (name.isNotEmpty()) {
                list.add(MovieProfile(id, name, avatar, isKids, genre))
            }
        }
        return list
    }

    fun saveNewProfile(profile: MovieProfile) {
        val user = _currentUserProfile.value
        val uid = user?.uid ?: "guest_user"
        
        val currentList = getSavedProfilesFromPrefs(uid).toMutableList()
        currentList.removeAll { it.id == profile.id }
        currentList.add(profile)
        
        val editor = sharedPrefs.edit()
        editor.putInt("profile_count_$uid", currentList.size)
        currentList.forEachIndexed { index, p ->
            editor.putString("profile_id_${uid}_$index", p.id)
            editor.putString("profile_name_${uid}_${p.id}", p.name)
            editor.putString("profile_avatar_${uid}_${p.id}", p.avatarUrl)
            editor.putBoolean("profile_iskids_${uid}_${p.id}", p.isKids)
            editor.putString("profile_genre_${uid}_${p.id}", p.preferredGenre)
        }
        editor.apply()

        if (uid != "guest_user") {
            repository.saveProfileToFirestore(uid, profile)
        }
        
        _profiles.value = currentList
        if (_activeProfile.value == null || _activeProfile.value?.id == profile.id) {
            selectProfile(profile)
        }
    }

    fun selectProfile(profile: MovieProfile) {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"
        sharedPrefs.edit().putString("last_active_profile_$uid", profile.id).apply()
        _activeProfile.value = profile
    }

    fun clearActiveProfile() {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"
        sharedPrefs.edit().remove("last_active_profile_$uid").apply()
        _activeProfile.value = null
    }

    fun deleteProfile(profileId: String) {
        val uid = _currentUserProfile.value?.uid ?: "guest_user"
        val currentList = getSavedProfilesFromPrefs(uid).toMutableList()
        currentList.removeAll { it.id == profileId }
        
        val editor = sharedPrefs.edit()
        editor.putInt("profile_count_$uid", currentList.size)
        currentList.forEachIndexed { index, p ->
            editor.putString("profile_id_${uid}_$index", p.id)
            editor.putString("profile_name_${uid}_${p.id}", p.name)
            editor.putString("profile_avatar_${uid}_${p.id}", p.avatarUrl)
            editor.putBoolean("profile_iskids_${uid}_${p.id}", p.isKids)
            editor.putString("profile_genre_${uid}_${p.id}", p.preferredGenre)
        }
        editor.apply()

        if (uid != "guest_user") {
            repository.deleteProfileFromFirestore(uid, profileId)
        }
        
        _profiles.value = currentList
        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = currentList.firstOrNull()
        }
    }

    // ------------------ BUSINESS ACTIONS ------------------

    fun saveWatchProgress(contentId: String, title: String, poster: String, type: String, progress: Float) {
        viewModelScope.launch {
            val profId = _activeProfile.value?.id ?: "prof_default"
            if (progress >= 0.95f) {
                repository.deleteProgress(profId, contentId)
            } else {
                repository.saveProgress(profId, contentId, title, poster, type, progress)
            }
        }
    }

    fun toggleWatchlist(id: String, title: String, poster: String, backdrop: String, type: String) {
        viewModelScope.launch {
            val profId = _activeProfile.value?.id ?: "prof_default"
            repository.toggleWatchlist(profId, id, title, poster, backdrop, type)
        }
    }

    fun submitMovieRequest(name: String, year: Int, onComplete: (Boolean) -> Unit) {
        val userMail = _currentUserProfile.value?.email ?: "guest_user"
        repository.requestMovie(name, year, userMail, onComplete)
    }

    fun updateProfile(name: String, gender: String, onComplete: (Boolean) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid ?: "guest_user"

        sharedPrefs.edit()
            .putString("user_name_$uid", name)
            .putString("user_gender_$uid", gender)
            .apply()

        _currentUserProfile.value = _currentUserProfile.value?.copy(
            displayName = name,
            gender = gender
        )
        onComplete(true)
    }

    fun updateAvatar(avatarUrl: String, onComplete: (Boolean) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid ?: "guest_user"

        sharedPrefs.edit()
            .putString("user_photo_$uid", avatarUrl)
            .apply()

        _currentUserProfile.value = _currentUserProfile.value?.copy(
            photoURL = avatarUrl
        )
        onComplete(true)
    }

    fun saveThemeSettings(themeName: String, isDark: Boolean) {
        sharedPrefs.edit()
            .putString("active_theme", themeName)
            .putBoolean("is_theme_dark", isDark)
            .apply()
    }
}
