package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed FCM token: $token")
        // Persist token in shared preferences if needed
        val sharedPrefs = getSharedPreferences("sportzfy_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("fcm_token", token).apply()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // Extract values from data payload
        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            Log.d(TAG, "Message data payload: $data")
            val matchId = data["matchId"]
            val title = data["title"] ?: "Match Went Live!"
            val sport = data["sport"] ?: "Sports"
            val status = data["status"] ?: "Live"
            val body = data["body"] ?: "Tap to start watching the live stream immediately!"

            // If the status is live, trigger notification
            if (status.equals("Live", ignoreCase = true)) {
                sendLiveMatchNotification(title, body, sport, matchId)
            }
        }

        // Check if message contains a notification payload
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            val title = it.title ?: "Sportzfy Live"
            val body = it.body ?: "A match you subscribed to is now live!"
            sendLiveMatchNotification(title, body, "Live", null)
        }
    }

    private fun sendLiveMatchNotification(
        title: String,
        body: String,
        sport: String,
        matchId: String?
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sportzfy_live_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Sportzfy Match Alerts"
            val channelDescription = "Alerts when your subscribed matches go live"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
                enableLights(true)
                lightColor = 0xFF00E5FF.toInt()
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Open MainActivity when notification is clicked
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("matchId", matchId)
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            matchId?.hashCode() ?: 0,
            intent,
            pendingIntentFlags
        )

        // Build elegant Material 3 style notification
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm) // System icon fallback or custom drawable if present
            .setContentTitle("🔴 LIVE NOW: $title")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\nSport: $sport"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(0xFF00E5FF.toInt()) // Modern Sportzfy cyan accent color

        val notificationId = matchId?.toIntOrNull() ?: System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    companion object {
        private const val TAG = "SportzfyFCM"
    }
}
