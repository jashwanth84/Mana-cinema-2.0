package com.example.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseInitializer {
    fun initialize(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyBPZpmk0nI7yerXVjwA9y0JoTTMuOzjGqs")
                    .setApplicationId("1:1078257806666:web:82bac133b65ffc9e3625b3")
                    .setDatabaseUrl("https://new-moviehunt-default-rtdb.firebaseio.com")
                    .setProjectId("new-moviehunt")
                    .setStorageBucket("new-moviehunt.firebasestorage.app")
                    .setGcmSenderId("1078257806666")
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
        } catch (e: Exception) {
            android.util.Log.e("FirebaseInitializer", "FirebaseApp initialization failed", e)
        }
    }
}
