// NotificationWorker.kt
package com.example.myfirstnotificationapp

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import com.google.gson.Gson
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat // Import for cancelling notifications

class NotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dataStoreManager = DataStoreManager(applicationContext)
        val nightscoutApiService = NightscoutApiService(OkHttpClient(), Gson()) // Re-initialize services

        try {
            val notificationsEnabled = dataStoreManager.notificationsEnabledFlow.first()

            if (notificationsEnabled) {
                val fullUrl = dataStoreManager.fullUrlFlow.first()
                val apiKey = dataStoreManager.apiKeyFlow.first()

                Log.d("NotificationWorker", "Fetching SGV from: $fullUrl with API Key: $apiKey")

                val sgvValueForNotification = nightscoutApiService.fetchSgvValue(fullUrl, apiKey)

                // Show the notification (you'll need to pass the showNotification logic here)
                showNotification(sgvValueForNotification)

                return Result.success()
            } else {
                Log.d("NotificationWorker", "Notifications disabled. Not performing work.")
                // If notifications are disabled, cancel any existing notification
                NotificationManagerCompat.from(applicationContext).cancel(1001)
                return Result.success() // Still return success, as the work was to check and act accordingly
            }
        } catch (e: Exception) {
            Log.e("NotificationWorker", "Error fetching SGV or showing notification: ${e.message}", e)
            return Result.retry() // Retry if there was an error
        }
    }

    // This function needs to be moved from MainActivity or made accessible
    private fun showNotification(n: Int) {
        // You'll need access to the numberIcons array.
        // The best way is to make it a static/companion object property or pass it via inputData.
        // For simplicity, let's assume you make it accessible or pass it.
        // For now, I'll hardcode a default icon for demonstration.
        val numberIcons = arrayOf(
            R.drawable.ic_number_0, R.drawable.ic_number_1, R.drawable.ic_number_2,
            R.drawable.ic_number_3, R.drawable.ic_number_4, R.drawable.ic_number_5,
            R.drawable.ic_number_6, R.drawable.ic_number_7, R.drawable.ic_number_8,
            R.drawable.ic_number_9, R.drawable.ic_number_10, R.drawable.ic_number_11,
            R.drawable.ic_number_12, R.drawable.ic_number_13, R.drawable.ic_number_14,
            R.drawable.ic_number_15
        )
        val safeN = n.coerceIn(0, numberIcons.size - 1)

        // Permission check is handled by the app's overall permission request
        // and the system will prevent notification if permission is not granted.
        // No need for explicit check here, but good to be aware.

        val iconResId = numberIcons[safeN]
        // --- NEW PERMISSION CHECK ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Permission not granted. Log an error and return.
                // You cannot request permission from a Worker, only from an Activity.
                Log.e("NotificationWorker", "POST_NOTIFICATIONS permission not granted. Cannot show notification.")
                return
            }
        }
        // --- END NEW PERMISSION CHECK ---
        val builder = NotificationCompat.Builder(applicationContext, "sgv_channel_id")
            .setSmallIcon(iconResId)
            .setContentTitle("SGV Alert")
            .setContentText("SGV Value: $n")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(1001, builder.build())
        }
    }
}