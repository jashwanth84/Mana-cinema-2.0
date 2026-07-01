package com.example.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseInitializer {
    fun initialize(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyAOVmYYxvvxGKl-koWzx7ldYJDaIPM3TR4")
                    .setApplicationId("1:313100918753:web:2c60c2ca8abb63e6cebca0")
                    .setDatabaseUrl("https://infinity-earning-app-default-rtdb.asia-southeast1.firebasedatabase.app")
                    .setProjectId("infinity-earning-app")
                    .setStorageBucket("infinity-earning-app.firebasestorage.app")
                    .setGcmSenderId("313100918753")
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
        } catch (e: Exception) {
            android.util.Log.e("FirebaseInitializer", "FirebaseApp initialization failed", e)
        }
    }
}
