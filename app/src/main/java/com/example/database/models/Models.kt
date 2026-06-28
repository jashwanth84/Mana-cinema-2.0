package com.example.models

data class Movie(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val category: String = "",
    val rating: String = "",
    val year: String = "",
    val genre: String = "",
    val link: String = "",
    val stars: String = "",
    val director: String = "",
    val storyline: String = ""
)

data class Episode(
    val season: Int = 1,
    val episode: Int = 1,
    val title: String = "",
    val link: String = ""
)

data class WebSeries(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val category: String = "",
    val rating: String = "",
    val year: String = "",
    val genre: String = "",
    val storyline: String = "",
    val episodes: List<Episode> = emptyList()
)

data class LiveTvChannel(
    val id: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val streamUrl: String = "",
    val category: String = ""
)

data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoURL: String = "",
    val gender: String = "male",
    val createdAt: Long = 0L
)

data class WatchlistItem(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val backdrop: String = "",
    val type: String = "movie"
)

data class ContinueWatching(
    val contentId: String = "",
    val title: String = "",
    val poster: String = "",
    val type: String = "",
    val progress: Float = 0f
)

data class MovieRequest(
    val id: String = "",
    val title: String = "",
    val year: Int = 0,
    val userEmail: String = "",
    val timestamp: Long = 0L
)

data class UserNotification(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val timestamp: Long = 0L
)

data class MovieProfile(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val isKids: Boolean = false,
    val preferredGenre: String = "All"
)
