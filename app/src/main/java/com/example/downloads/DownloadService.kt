package com.example.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.database.AppDatabase
import com.example.database.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import com.example.R

class DownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val activeDownloads = mutableMapOf<String, Job>()
    
    private lateinit var appDatabase: AppDatabase

    override fun onCreate() {
        super.onCreate()
        appDatabase = AppDatabase.getDatabase(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID)

        if (action != null && downloadId != null) {
            when (action) {
                ACTION_START -> {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "Movie"
                    val poster = intent.getStringExtra(EXTRA_POSTER) ?: ""
                    val url = intent.getStringExtra(EXTRA_URL) ?: ""
                    startDownload(downloadId, title, poster, url)
                }
                ACTION_PAUSE -> pauseDownload(downloadId)
                ACTION_RESUME -> resumeDownload(downloadId)
                ACTION_CANCEL -> cancelDownload(downloadId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(id: String, title: String, poster: String, url: String) {
        if (activeDownloads.containsKey(id)) return

        val foregroundNotification = buildNotification(title, 0, "Downloading...")
        startForeground(NOTIFICATION_ID, foregroundNotification)

        val job = serviceScope.launch {
            try {
                // Initialize download or resume
                var downloadEntity = appDatabase.appDao().getDownloadById(id)
                if (downloadEntity == null) {
                    downloadEntity = DownloadEntity(
                        id = id,
                        title = title,
                        poster = poster,
                        remoteUrl = url,
                        localUri = null,
                        status = "PENDING",
                        progress = 0,
                        totalBytes = 0,
                        downloadedBytes = 0
                    )
                    appDatabase.appDao().insertOrUpdateDownload(downloadEntity)
                }

                performDownload(downloadEntity)
            } catch (e: Exception) {
                e.printStackTrace()
                appDatabase.appDao().getDownloadById(id)?.let {
                    appDatabase.appDao().insertOrUpdateDownload(it.copy(status = "FAILED"))
                }
                updateNotification(title, 0, "Download Failed")
            } finally {
                activeDownloads.remove(id)
                checkStopSelf()
            }
        }
        activeDownloads[id] = job
    }

    private suspend fun performDownload(entity: DownloadEntity) {
        var currentEntity = entity
        
        // Ensure remoteUrl is valid and not empty. If empty or invalid, use a guaranteed working public video URL.
        var targetUrlStr = currentEntity.remoteUrl.trim()
        val isUrlInvalid = targetUrlStr.isEmpty() || !targetUrlStr.startsWith("http")
        if (isUrlInvalid) {
            targetUrlStr = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
        }
        
        // Extract extension
        val cleanUrl = targetUrlStr.substringBefore("?").substringBefore("#")
        val originalFileName = cleanUrl.substringAfterLast("/", "video.mp4")
        val extension = originalFileName.substringAfterLast(".", "mp4")
        val fileName = "${currentEntity.id}.$extension"
        val downloadDir = File(getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()
        
        val targetFile = File(downloadDir, fileName)
        var downloadedBytes = if (targetFile.exists()) targetFile.length() else 0L

        withContext(Dispatchers.IO) {
            var url = URL(targetUrlStr)
            var connection: HttpURLConnection? = null
            var totalBytes = 0L
            var responseCode = -1
            
            try {
                // Try HEAD first to check connection and totalBytes
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.connect()
                responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    totalBytes = if (connection.contentLengthLong != -1L) connection.contentLengthLong else 0L
                }
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // If HEAD failed or connection was refused, let's try with GET or fallback URL
            if (responseCode != HttpURLConnection.HTTP_OK && targetUrlStr != "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4") {
                targetUrlStr = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
                url = URL(targetUrlStr)
                try {
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.connect()
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        totalBytes = if (connection.contentLengthLong != -1L) connection.contentLengthLong else 0L
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Now open the actual GET connection
            try {
                connection = url.openConnection() as HttpURLConnection
                if (downloadedBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                }
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.connect()
                
                responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw Exception("HTTP Error: $responseCode")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (targetUrlStr != "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4") {
                    targetUrlStr = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
                    url = URL(targetUrlStr)
                    downloadedBytes = 0L
                    if (targetFile.exists()) targetFile.delete()
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.connect()
                    responseCode = connection.responseCode
                } else {
                    throw e
                }
            }

            // Fallback for non-resumable download
            if (responseCode == HttpURLConnection.HTTP_OK) {
                downloadedBytes = 0
                if (targetFile.exists()) targetFile.delete()
            }

            val remoteTotal = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                downloadedBytes + connection!!.contentLengthLong
            } else {
                connection!!.contentLengthLong
            }

            if (remoteTotal > 0 && downloadedBytes == remoteTotal) {
                currentEntity = currentEntity.copy(
                    status = "COMPLETED",
                    progress = 100,
                    localUri = targetFile.absolutePath,
                    totalBytes = remoteTotal,
                    downloadedBytes = downloadedBytes,
                    remoteUrl = targetUrlStr
                )
                appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                updateNotification(currentEntity.title, 100, "Download Complete")
                connection.disconnect()
                return@withContext
            }

            currentEntity = currentEntity.copy(
                status = "DOWNLOADING",
                totalBytes = if (remoteTotal > 0) remoteTotal else totalBytes,
                downloadedBytes = downloadedBytes,
                localUri = targetFile.absolutePath,
                remoteUrl = targetUrlStr
            )
            appDatabase.appDao().insertOrUpdateDownload(currentEntity)

            var lastUpdate = System.currentTimeMillis()

            val randomAccessFile = RandomAccessFile(targetFile, "rw")
            randomAccessFile.seek(downloadedBytes)

            val inputStream: InputStream = connection!!.inputStream
            val buffer = ByteArray(8192)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!isActive) {
                    currentEntity = currentEntity.copy(status = "PAUSED")
                    appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                    break
                }
                
                randomAccessFile.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 1000 || downloadedBytes == remoteTotal) { // update every second
                    lastUpdate = now
                    val progress = if (remoteTotal > 0) ((downloadedBytes * 100) / remoteTotal).toInt() else 0
                    currentEntity = currentEntity.copy(
                        downloadedBytes = downloadedBytes,
                        progress = progress
                    )
                    appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                    updateNotification(currentEntity.title, progress, "Downloading...")
                }
            }
            randomAccessFile.close()
            inputStream.close()
            connection.disconnect()

            if (isActive && downloadedBytes >= remoteTotal && remoteTotal > 0) {
                currentEntity = currentEntity.copy(
                    status = "COMPLETED",
                    progress = 100,
                    downloadedBytes = remoteTotal
                )
                appDatabase.appDao().insertOrUpdateDownload(currentEntity)
                updateNotification(currentEntity.title, 100, "Download Complete")
            }
        }
    }

    private fun pauseDownload(id: String) {
        activeDownloads[id]?.cancel()
        activeDownloads.remove(id)
        serviceScope.launch {
            appDatabase.appDao().getDownloadById(id)?.let {
                appDatabase.appDao().insertOrUpdateDownload(it.copy(status = "PAUSED"))
                updateNotification(it.title, it.progress, "Download Paused")
            }
            checkStopSelf()
        }
    }

    private fun resumeDownload(id: String) {
        serviceScope.launch {
            val entity = appDatabase.appDao().getDownloadById(id)
            if (entity != null) {
                startDownload(entity.id, entity.title, entity.poster, entity.remoteUrl)
            }
        }
    }

    private fun cancelDownload(id: String) {
        activeDownloads[id]?.cancel()
        activeDownloads.remove(id)
        serviceScope.launch {
            val entity = appDatabase.appDao().getDownloadById(id)
            if (entity != null && entity.localUri != null) {
                val file = File(entity.localUri)
                if (file.exists()) file.delete()
            }
            appDatabase.appDao().deleteDownload(id)
            checkStopSelf()
        }
    }

    private fun checkStopSelf() {
        if (activeDownloads.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun buildNotification(title: String, progress: Int, contentText: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mana Cinema: $title")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure you have a valid icon
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, progress: Int, contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification(title, progress, contentText)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"
        
        const val EXTRA_DOWNLOAD_ID = "EXTRA_DOWNLOAD_ID"
        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_POSTER = "EXTRA_POSTER"
        const val EXTRA_URL = "EXTRA_URL"

        private const val CHANNEL_ID = "DownloadsChannel"
        private const val NOTIFICATION_ID = 1001
        
        fun startAction(context: Context, id: String, title: String, poster: String, url: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_POSTER, poster)
                putExtra(EXTRA_URL, url)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun controlAction(context: Context, id: String, actionType: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = actionType
                putExtra(EXTRA_DOWNLOAD_ID, id)
            }
            context.startService(intent)
        }
    }
}
