package com.example.myfirstnotificationapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import com.google.gson.Gson
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// NEW: Import for Dexcom API Service
import com.example.myfirstnotificationapp.dexcom.DexcomApiService // Assuming this path
import com.example.myfirstnotificationapp.BuildConfig
class NotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    // Initialize common HTTP client and Gson once for all services
    private val httpClient = OkHttpClient()
    private val gson = Gson()

    // Initialize DataStoreManager once
    private val dataStoreManager = DataStoreManager(appContext)

    // Initialize API services using the common httpClient, gson, and dataStoreManager
    private val nightscoutApiService = NightscoutApiService(httpClient, gson)
    private val dexcomApiService = DexcomApiService(
        httpClient,
        gson,
        dataStoreManager,
        BuildConfig.DEXCOM_CLIENT_ID,
        BuildConfig.DEXCOM_CLIENT_SECRET,
        BuildConfig.DEXCOM_TOKEN_ENDPOINT,
        BuildConfig.DEXCOM_EGV_ENDPOINT
    )

    override suspend fun doWork(): Result {
        try {
            val notificationsEnabled = dataStoreManager.notificationsEnabledFlow.first()

            if (!notificationsEnabled) {
                Log.d("NotificationWorker", "Notifications disabled. Not performing work.")
                NotificationManagerCompat.from(applicationContext).cancel(1001)
                return Result.success()
            }

            var sgvValueForNotification: Int? = null
            var source: String = "Unknown" // To log where the data came from

            // 1. Try fetching from Dexcom first
            val hasDexcomTokens = !dataStoreManager.dexcomAccessTokenFlow.first().isNullOrBlank() &&
                    !dataStoreManager.dexcomRefreshTokenFlow.first().isNullOrBlank()

            if (hasDexcomTokens) {
                Log.d("NotificationWorker", "Attempting to fetch SGV from Dexcom...")
                try {
                    val latestEgv = dexcomApiService.getLatestEgv() // This handles token refresh internally

                    if (latestEgv != null) {
                        sgvValueForNotification = latestEgv.value
                        source = "Dexcom"
                        Log.d("NotificationWorker", "Successfully fetched EGV from Dexcom: $sgvValueForNotification")
                    } else {
                        Log.w("NotificationWorker", "Dexcom API returned no EGV data or failed to refresh token. Falling back to Nightscout.")
                    }
                } catch (e: Exception) {
                    Log.e("NotificationWorker", "Error fetching from Dexcom API: ${e.message}. Falling back to Nightscout.", e)
                }
            } else {
                Log.d("NotificationWorker", "No active Dexcom connection (tokens missing). Falling back to Nightscout.")
            }

            // 2. If Dexcom failed or wasn't connected, fall back to Nightscout
            if (sgvValueForNotification == null) {
                Log.d("NotificationWorker", "Attempting to fetch SGV from Nightscout...")
                val fullUrl = dataStoreManager.fullUrlFlow.first()
                val apiKey = dataStoreManager.apiKeyFlow.first()

                if (fullUrl.isNotBlank() && apiKey.isNotBlank()) {
                    try {
                        sgvValueForNotification = nightscoutApiService.fetchSgvValue(fullUrl, apiKey)
                        source = "Nightscout"
                        Log.d("NotificationWorker", "Successfully fetched SGV from Nightscout: $sgvValueForNotification")
                    } catch (e: Exception) {
                        Log.e("NotificationWorker", "Error fetching from Nightscout API: ${e.message}", e)
                        return Result.retry()
                    }
                } else {
                    Log.w("NotificationWorker", "Nightscout URL or API Key is missing. Cannot fetch SGV.")
                    return Result.failure()
                }
            }

            // 3. Show the notification with the obtained value
            if (sgvValueForNotification != null) {
                Log.d("NotificationWorker", "Fetched SGV (mg/dL) $sgvValueForNotification");
                showNotification(sgvValueForNotification, source)
                return Result.success()
            } else {
                Log.e("NotificationWorker", "Failed to get SGV/EGV value from both Dexcom and Nightscout.")
                return Result.retry()
            }

        } catch (e: Exception) {
            Log.e("NotificationWorker", "Unhandled error in NotificationWorker: ${e.message}", e)
            return Result.retry()
        }
    }

    // Modified to include source in log/notification text
    private fun showNotification(n: Int, source: String) {
        val numberIcons = arrayOf(
            R.drawable.ic_number_0, R.drawable.ic_number_1, R.drawable.ic_number_2,
            R.drawable.ic_number_3, R.drawable.ic_number_4, R.drawable.ic_number_5,
            R.drawable.ic_number_6, R.drawable.ic_number_7, R.drawable.ic_number_8,
            R.drawable.ic_number_9, R.drawable.ic_number_10, R.drawable.ic_number_11,
            R.drawable.ic_number_12, R.drawable.ic_number_13, R.drawable.ic_number_14,
            R.drawable.ic_number_15
        )
        val safeN = n.coerceIn(0, numberIcons.size - 1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("NotificationWorker", "POST_NOTIFICATIONS permission not granted. Cannot show notification.")
                return
            }
        }

        val iconResId = numberIcons[safeN]
        val builder = NotificationCompat.Builder(applicationContext, "sgv_channel_id")
            .setSmallIcon(iconResId)
            .setContentTitle("BGL Alert ($source)") // Indicate source
            .setContentText("mmol $n")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(1001, builder.build())
        }
    }
}