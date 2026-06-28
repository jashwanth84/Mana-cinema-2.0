package com.example.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_movies")
data class CachedMovieEntity(
    @PrimaryKey val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val category: String,
    val rating: String,
    val year: String,
    val genre: String,
    val link: String,
    val stars: String,
    val director: String,
    val storyline: String
)

@Entity(tableName = "cached_series")
data class CachedSeriesEntity(
    @PrimaryKey val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val category: String,
    val rating: String,
    val year: String,
    val genre: String,
    val storyline: String,
    val episodesJson: String // Serialized list of episodes as JSON for simple local database storage
)

@Entity(tableName = "local_watchlist")
data class LocalWatchlistEntity(
    @PrimaryKey val compositeId: String, // format: "${profileId}_${id}"
    val profileId: String,
    val id: String,
    val title: String,
    val poster: String,
    val backdrop: String,
    val type: String
)

@Entity(tableName = "continue_watching")
data class ContinueWatchingEntity(
    @PrimaryKey val compositeId: String, // format: "${profileId}_${contentId}"
    val profileId: String,
    val contentId: String,
    val title: String,
    val poster: String,
    val type: String,
    val progress: Float,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val poster: String,
    val remoteUrl: String,
    val localUri: String?, // file path representation
    val status: String,    // "PENDING", "DOWNLOADING", "PAUSED", "COMPLETED", "FAILED"
    val progress: Int,     // 0 to 100
    val totalBytes: Long,
    val downloadedBytes: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface AppDao {
    @Query("SELECT * FROM cached_movies")
    fun getAllMovies(): Flow<List<CachedMovieEntity>>

    @Query("DELETE FROM cached_movies")
    suspend fun clearMovies()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<CachedMovieEntity>)

    @Query("SELECT * FROM cached_series")
    fun getAllSeries(): Flow<List<CachedSeriesEntity>>

    @Query("DELETE FROM cached_series")
    suspend fun clearSeries()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: List<CachedSeriesEntity>)

    @Query("SELECT * FROM local_watchlist WHERE profileId = :profileId")
    fun getWatchlistForProfile(profileId: String): Flow<List<LocalWatchlistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToWatchlist(item: LocalWatchlistEntity)

    @Query("DELETE FROM local_watchlist WHERE compositeId = :compositeId")
    suspend fun removeFromWatchlistByCompositeId(compositeId: String)

    @Query("SELECT EXISTS(SELECT * FROM local_watchlist WHERE compositeId = :compositeId)")
    suspend fun isWatchlisted(compositeId: String): Boolean

    @Query("SELECT * FROM continue_watching WHERE profileId = :profileId ORDER BY timestamp DESC")
    fun getContinueWatchingForProfile(profileId: String): Flow<List<ContinueWatchingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(entity: ContinueWatchingEntity)

    @Query("DELETE FROM continue_watching WHERE compositeId = :compositeId")
    suspend fun deleteProgressByCompositeId(compositeId: String)

    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING' OR status = 'PENDING'")
    suspend fun getActiveDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDownload(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownload(id: String)
}
