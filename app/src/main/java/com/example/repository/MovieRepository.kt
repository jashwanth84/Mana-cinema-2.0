package com.example.repository

import android.content.Context
import com.example.database.*
import com.example.models.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MovieRepository(context: Context) {

    private val dbInstance: FirebaseDatabase? by lazy {
        try {
            FirebaseDatabase.getInstance("https://infinity-earning-app-default-rtdb.asia-southeast1.firebasedatabase.app")
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "FirebaseDatabase initialization failed", e)
            null
        }
    }
    private val appDao = AppDatabase.getDatabase(context).appDao()

    // ------------------ LOCAL CACHE SYNC ------------------
    val localMoviesFlow: Flow<List<CachedMovieEntity>> = appDao.getAllMovies()
    val localSeriesFlow: Flow<List<CachedSeriesEntity>> = appDao.getAllSeries()

    fun getLocalWatchlistFlow(profileId: String): Flow<List<LocalWatchlistEntity>> {
        return appDao.getWatchlistForProfile(profileId)
    }

    fun getLocalContinueWatchingFlow(profileId: String): Flow<List<ContinueWatchingEntity>> {
        return appDao.getContinueWatchingForProfile(profileId)
    }

    fun getAllDownloads(): Flow<List<DownloadEntity>> {
        return appDao.getAllDownloads()
    }

    // Firestore instance initialized safely
    private val firestoreInstance: com.google.firebase.firestore.FirebaseFirestore? by lazy {
        try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Firestore initialization failed", e)
            null
        }
    }

    suspend fun saveProgress(profileId: String, contentId: String, title: String, poster: String, type: String, progress: Float) {
        val composite = "${profileId}_${contentId}"
        appDao.saveProgress(
            ContinueWatchingEntity(
                compositeId = composite,
                profileId = profileId,
                contentId = contentId,
                title = title,
                poster = poster,
                type = type,
                progress = progress,
                timestamp = System.currentTimeMillis()
            )
        )
        syncHistoryToFirestore(profileId, contentId, title, poster, type, progress)
    }

    suspend fun deleteProgress(profileId: String, contentId: String) {
        val composite = "${profileId}_${contentId}"
        appDao.deleteProgressByCompositeId(composite)
        deleteHistoryFromFirestore(profileId, contentId)
    }

    suspend fun toggleWatchlist(profileId: String, id: String, title: String, poster: String, backdrop: String, type: String) {
        val composite = "${profileId}_${id}"
        val isAlreadyWatchlisted = appDao.isWatchlisted(composite)
        if (isAlreadyWatchlisted) {
            appDao.removeFromWatchlistByCompositeId(composite)
            deleteWatchlistFromFirestore(profileId, id)
        } else {
            appDao.addToWatchlist(
                LocalWatchlistEntity(
                    compositeId = composite,
                    profileId = profileId,
                    id = id,
                    title = title,
                    poster = poster,
                    backdrop = backdrop,
                    type = type
                )
            )
            saveWatchlistToFirestore(profileId, id, title, poster, backdrop, type)
        }
    }

    suspend fun isWatchlisted(profileId: String, id: String): Boolean {
        val composite = "${profileId}_${id}"
        return appDao.isWatchlisted(composite)
    }

    // ------------------ FIRESTORE PROFILE SYSTEMS ------------------

    fun getProfilesFromFirestore(uid: String): Flow<List<MovieProfile>> = callbackFlow {
        val fs = firestoreInstance
        if (fs == null || uid.isEmpty() || uid == "guest_user") {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = fs.collection("users").document(uid).collection("profiles")
        val registration = ref.addSnapshotListener { snapshot, error ->
            if (error != null) {
                android.util.Log.e("MovieRepository", "Error getting profiles", error)
                trySend(emptyList())
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val list = snapshot.documents.mapNotNull { doc ->
                    try {
                        MovieProfile(
                            id = doc.getString("id") ?: doc.id,
                            name = doc.getString("name") ?: "",
                            avatarUrl = doc.getString("avatarUrl") ?: "",
                            isKids = doc.getBoolean("isKids") ?: false,
                            preferredGenre = doc.getString("preferredGenre") ?: "All"
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                trySend(list)
            } else {
                trySend(emptyList())
            }
        }
        awaitClose { registration.remove() }
    }

    fun saveProfileToFirestore(uid: String, profile: MovieProfile) {
        val fs = firestoreInstance ?: return
        if (uid.isEmpty() || uid == "guest_user") return
        val map = mapOf(
            "id" to profile.id,
            "name" to profile.name,
            "avatarUrl" to profile.avatarUrl,
            "isKids" to profile.isKids,
            "preferredGenre" to profile.preferredGenre
        )
        fs.collection("users").document(uid).collection("profiles").document(profile.id)
            .set(map)
            .addOnFailureListener { e ->
                android.util.Log.e("MovieRepository", "Firestore save profile failed", e)
            }
    }

    fun deleteProfileFromFirestore(uid: String, profileId: String) {
        val fs = firestoreInstance ?: return
        if (uid.isEmpty() || uid == "guest_user") return
        fs.collection("users").document(uid).collection("profiles").document(profileId)
            .delete()
            .addOnFailureListener { e ->
                android.util.Log.e("MovieRepository", "Firestore delete profile failed", e)
            }
    }

    private fun getCurrentUserUid(): String? {
        return try {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Failed to resolve Firebase UID safely", e)
            null
        }
    }

    private fun saveWatchlistToFirestore(profileId: String, id: String, title: String, poster: String, backdrop: String, type: String) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        val map = mapOf(
            "id" to id,
            "title" to title,
            "poster" to poster,
            "backdrop" to backdrop,
            "type" to type,
            "timestamp" to System.currentTimeMillis()
        )
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("watchlist").document(id)
            .set(map)
    }

    private fun deleteWatchlistFromFirestore(profileId: String, id: String) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("watchlist").document(id)
            .delete()
    }

    private fun syncHistoryToFirestore(profileId: String, contentId: String, title: String, poster: String, type: String, progress: Float) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        val map = mapOf(
            "contentId" to contentId,
            "title" to title,
            "poster" to poster,
            "type" to type,
            "progress" to progress,
            "timestamp" to System.currentTimeMillis()
        )
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("history").document(contentId)
            .set(map)
    }

    private fun deleteHistoryFromFirestore(profileId: String, contentId: String) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("history").document(contentId)
            .delete()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    suspend fun syncFirestoreWatchlistAndHistoryToLocal(profileId: String) {
        val fs = firestoreInstance ?: return
        val uid = getCurrentUserUid() ?: return
        
        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("watchlist")
            .get()
            .addOnSuccessListener { querySnapshot ->
                kotlinx.coroutines.GlobalScope.launch {
                    for (doc in querySnapshot.documents) {
                        val id = doc.getString("id") ?: doc.id
                        val title = doc.getString("title") ?: ""
                        val poster = doc.getString("poster") ?: ""
                        val backdrop = doc.getString("backdrop") ?: ""
                        val type = doc.getString("type") ?: "movie"
                        
                        val composite = "${profileId}_${id}"
                        appDao.addToWatchlist(LocalWatchlistEntity(composite, profileId, id, title, poster, backdrop, type))
                    }
                }
            }

        fs.collection("users").document(uid)
            .collection("profiles").document(profileId)
            .collection("history")
            .get()
            .addOnSuccessListener { querySnapshot ->
                kotlinx.coroutines.GlobalScope.launch {
                    for (doc in querySnapshot.documents) {
                        val contentId = doc.getString("contentId") ?: doc.id
                        val title = doc.getString("title") ?: ""
                        val poster = doc.getString("poster") ?: ""
                        val type = doc.getString("type") ?: ""
                        val progress = doc.getDouble("progress")?.toFloat() ?: 0f
                        val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                        
                        val composite = "${profileId}_${contentId}"
                        appDao.saveProgress(ContinueWatchingEntity(composite, profileId, contentId, title, poster, type, progress, timestamp))
                    }
                }
            }
    }

    // Cache sync helpers
    suspend fun saveMoviesToLocal(movies: List<Movie>) {
        val entities = movies.map {
            CachedMovieEntity(
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
        appDao.clearMovies()
        appDao.insertMovies(entities)
    }

    suspend fun saveSeriesToLocal(series: List<WebSeries>) {
        val entities = series.map { s ->
            val arr = JSONArray()
            s.episodes.forEach { ep ->
                val epObj = JSONObject()
                epObj.put("season", ep.season)
                epObj.put("episode", ep.episode)
                epObj.put("title", ep.title)
                epObj.put("link", ep.link)
                arr.put(epObj)
            }
            CachedSeriesEntity(
                id = s.id,
                title = s.title,
                poster = s.poster,
                backdrop = s.backdrop,
                category = s.category,
                rating = s.rating,
                year = s.year,
                genre = s.genre,
                storyline = s.storyline,
                episodesJson = arr.toString()
            )
        }
        appDao.clearSeries()
        appDao.insertSeries(entities)
    }

    // ------------------ FIREBASE REALTIME DATABASE ------------------

    fun getMoviesFromFirebase(): Flow<List<Movie>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = db.getReference("movies")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    val list = mutableListOf<Movie>()
                    for (child in snapshot.children) {
                        try {
                            val movie = child.getValue(Movie::class.java)
                            if (movie != null) {
                                // Ensure id is populated if empty from RTDB key
                                val finalMovie = if (movie.id.isEmpty()) movie.copy(id = child.key ?: "") else movie
                                list.add(finalMovie)
                            }
                        } catch(e: Exception) {
                            android.util.Log.e("MovieRepository", "Error parsing movie", e)
                        }
                    }
                    trySend(list)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getWebSeriesFromFirebase(): Flow<List<WebSeries>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = db.getReference("webseries")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    val list = mutableListOf<WebSeries>()
                    for (child in snapshot.children) {
                        try {
                            // Manual parsing to handle Episode objects safely inside nested list
                            val id = child.child("id").getValue(String::class.java) ?: child.key ?: ""
                            val title = child.child("title").getValue(String::class.java) ?: ""
                            val poster = child.child("poster").getValue(String::class.java) ?: ""
                            val backdrop = child.child("backdrop").getValue(String::class.java) ?: ""
                            val category = child.child("category").getValue(String::class.java) ?: ""
                            val rating = child.child("rating").getValue(String::class.java) ?: ""
                            val year = child.child("year").getValue(String::class.java) ?: ""
                            val genre = child.child("genre").getValue(String::class.java) ?: ""
                            val storyline = child.child("storyline").getValue(String::class.java) ?: ""

                            val episodes = mutableListOf<Episode>()
                            val epSnapshot = child.child("episodes")
                            if (epSnapshot.exists()) {
                                for (epChild in epSnapshot.children) {
                                    try {
                                        val sVal = epChild.child("season").value
                                        val season = when (sVal) {
                                            is Number -> sVal.toInt()
                                            is String -> sVal.toIntOrNull() ?: 1
                                            else -> 1
                                        }
                                        val eVal = epChild.child("episode").value
                                        val episodeNum = when (eVal) {
                                            is Number -> eVal.toInt()
                                            is String -> eVal.toIntOrNull() ?: 1
                                            else -> 1
                                        }
                                        val epTitle = epChild.child("title").getValue(String::class.java) ?: ""
                                        val epLink = epChild.child("link").getValue(String::class.java) ?: ""

                                        episodes.add(
                                            Episode(
                                                season = season,
                                                episode = episodeNum,
                                                title = epTitle,
                                                link = epLink
                                            )
                                        )
                                    } catch (e: Exception) {
                                        android.util.Log.e("MovieRepository", "Error parsing individual episode safely", e)
                                    }
                                }
                            }

                            list.add(
                                WebSeries(
                                    id = id,
                                    title = title,
                                    poster = poster,
                                    backdrop = backdrop,
                                    category = category,
                                    rating = rating,
                                    year = year,
                                    genre = genre,
                                    storyline = storyline,
                                    episodes = episodes
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("MovieRepository", "Error parsing individual webseries safely", e)
                        }
                    }
                    trySend(list)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getLiveTvChannelsFromFirebase(): Flow<List<LiveTvChannel>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = db.getReference("channels")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                    val list = mutableListOf<LiveTvChannel>()
                    for (child in snapshot.children) {
                        try {
                            val id = child.child("id").getValue(String::class.java) ?: child.key ?: ""
                            val name = child.child("name").getValue(String::class.java) ?: ""
                            val logoUrl = child.child("logoUrl").getValue(String::class.java) ?: ""
                            val streamUrl = child.child("streamUrl").getValue(String::class.java) ?: ""
                            val category = child.child("category").getValue(String::class.java) ?: ""
                            list.add(
                                LiveTvChannel(
                                    id = id,
                                    name = name,
                                    logoUrl = logoUrl,
                                    streamUrl = streamUrl,
                                    category = category
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("MovieRepository", "Error parsing TV channel safely", e)
                        }
                    }
                    trySend(list)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getSystemMaintenance(): Flow<Pair<Boolean, String>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            trySend(Pair(false, "System maintenance offline - Firebase not initialized"))
            close()
            return@callbackFlow
        }
        val ref = db.getReference("maintenance")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                val message = snapshot.child("message").getValue(String::class.java) ?: "Scheduled system maintenance under progress."
                trySend(Pair(enabled, message))
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(Pair(false, "Maintenance cancelled"))
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun getNotifications(): Flow<List<UserNotification>> = callbackFlow {
        val db = dbInstance
        if (db == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val ref = db.getReference("notifications")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<UserNotification>()
                for (child in snapshot.children) {
                    val notif = child.getValue(UserNotification::class.java)
                    if (notif != null) {
                        list.add(if (notif.id.isEmpty()) notif.copy(id = child.key ?: "") else notif)
                    }
                }
                trySend(list)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun requestMovie(movieName: String, year: Int, userEmail: String, onComplete: (Boolean) -> Unit) {
        val db = dbInstance
        if (db == null) {
            onComplete(false)
            return
        }
        try {
            val ref = db.getReference("requests").push()
            val key = ref.key ?: ""
            val req = MovieRequest(
                id = key,
                title = movieName,
                year = year,
                userEmail = userEmail,
                timestamp = System.currentTimeMillis()
            )
            ref.setValue(req).addOnCompleteListener { task ->
                onComplete(task.isSuccessful)
            }
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error requesting movie via Firebase", e)
            onComplete(false)
        }
    }

    fun deleteMovieFromFirebase(movieId: String, onComplete: (Boolean) -> Unit) {
        val db = dbInstance ?: run { onComplete(false); return }
        db.getReference("movies").child(movieId).removeValue()
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun deleteSeriesFromFirebase(seriesId: String, onComplete: (Boolean) -> Unit) {
        val db = dbInstance ?: run { onComplete(false); return }
        db.getReference("webseries").child(seriesId).removeValue()
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun clearAllMoviesFromFirebase(onComplete: (Boolean) -> Unit) {
        val db = dbInstance ?: run { onComplete(false); return }
        db.getReference("movies").removeValue()
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun clearAllSeriesFromFirebase(onComplete: (Boolean) -> Unit) {
        val db = dbInstance ?: run { onComplete(false); return }
        db.getReference("webseries").removeValue()
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }
}
