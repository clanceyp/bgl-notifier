package com.example.myfirstnotificationapp // Make sure this package matches your project

import android.app.Notification
import android.app.Service
import android.content.Context // Added for updateNotification
import android.content.Intent
import android.os.IBinder
import android.util.Log
// Necessary imports for CoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first // If you use .first() on flows
// ... other necessary imports for Notification, Handler, OkHttp, Gson etc.
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.example.myfirstnotificationapp.GlucoseConstants.MG_DL_THRESHOLD_FOR_CONVERSION
import com.example.myfirstnotificationapp.GlucoseConstants.MG_DL_TO_MMOL_L_CONVERSION_FACTOR
import com.example.myfirstnotificationapp.GlucoseConstants.convertToMMOL
import com.example.myfirstnotificationapp.dexcom.DexcomApiService
import com.google.gson.Gson // If your ApiService needs it
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient // If your ApiService needs it
import java.util.concurrent.TimeUnit // For OkHttpClient config
import kotlin.math.roundToInt
import kotlin.text.first
import kotlin.text.toLong


class DataFetchService<Egv> : Service() {

    // --- ADD THESE LINES ---
    private val job = SupervisorJob() // Create a SupervisorJob for the scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + job) // IO for network, plus job
    // --- END OF ADDED LINES ---

    private lateinit var dataStoreManager: DataStoreManager
    private lateinit var nightscoutApiService: NightscoutApiService
    private lateinit var dexcomApiService: DexcomApiService // If you use it

    companion object {
        const val ACTION_START_SERVICE = "com.example.myfirstnotificationapp.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.myfirstnotificationapp.ACTION_STOP_SERVICE"
        const val ACTION_PERFORM_IMMEDIATE_FETCH = "com.example.myfirstnotificationapp.ACTION_PERFORM_IMMEDIATE_FETCH" // New Action
        private const val NOTIFICATION_CHANNEL_ID = "DataFetchServiceChannel"
        private const val NOTIFICATION_ID = 123
        val EVENT_FREQUENCY_MINUTES_KEY = intPreferencesKey("event_frequency") // Changed name for clarity
        private const val DEFAULT_EVENT_FREQUENCY_MINUTES = 5 // Default if not set
        private const val TAG = "DataFetchService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // Initialize dependencies directly:
        dataStoreManager = DataStoreManager(applicationContext)

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val gson = Gson()

        nightscoutApiService = NightscoutApiService(okHttpClient, gson)
        dexcomApiService = DexcomApiService(
            okHttpClient,
            gson,
            dataStoreManager,
            BuildConfig.DEXCOM_CLIENT_ID,
            BuildConfig.DEXCOM_CLIENT_SECRET,
            BuildConfig.DEXCOM_TOKEN_ENDPOINT,
            BuildConfig.DEXCOM_EGV_ENDPOINT
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForeground(NOTIFICATION_ID, createNotification("Starting data sync...", 0))
                Log.d(TAG, "Starting foreground service and data fetching loop.")
                // Now serviceScope is available
                serviceScope.launch {
                    while (true) { // Loop for periodic fetching
                        performDataFetch()
                        val eventFrequencyMinutes = applicationContext.dataStore.data // Use applicationContext.dataStore
                            .catch { exception -> // Optional: Handle potential IOExceptions reading DataStore
                                if (exception is IOException) {
                                    Log.e(TAG, "Error reading event frequency.", exception)
                                    emit(emptyPreferences()) // Emit empty preferences to recover with default
                                } else {
                                    throw exception
                                }
                            }
                            .map { preferences ->
                                preferences[EVENT_FREQUENCY_MINUTES_KEY] ?: DEFAULT_EVENT_FREQUENCY_MINUTES
                            }
                            .first()
                        val fetchIntervalMs = TimeUnit.MINUTES.toMillis(eventFrequencyMinutes.toLong())
                        Log.d(TAG, "Next fetch in $eventFrequencyMinutes minutes ($fetchIntervalMs ms)")
                        delay(fetchIntervalMs)
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stopping foreground service.")
                stopSelf() // This will trigger onDestroy
            }
            ACTION_PERFORM_IMMEDIATE_FETCH -> {
                Log.d(TAG, "Received request for immediate fetch.")
                if (serviceScope.isActive) { // Check if service scope is active
                    serviceScope.launch { // Launch a new coroutine for this specific fetch
                        performDataFetch()
                    }
                } else {
                    Log.w(TAG, "Cannot perform immediate fetch, service scope not active.")
                }
            }
        }
        return START_STICKY
    }

    private suspend fun performDataFetch() {
        Log.d(TAG, "Performing data fetch in foreground service...")
        try {
            val prioritiseNightscout = dataStoreManager.prioritiseNightscoutFlow.first()
            val nightscoutUrl = dataStoreManager.fullUrlFlow.first()
            val nightscoutApiKey = dataStoreManager.apiKeyFlow.first()
            val dexcomToken = dataStoreManager.dexcomAccessTokenFlow.first()

            var sgv: Int? = null
            var source: String? = null

            Log.d(TAG, "Prioritise Nightscout: $prioritiseNightscout | $nightscoutUrl.isNotBlank()")
            if (prioritiseNightscout) {
                if (nightscoutUrl.isNotBlank()) {
                    Log.d(TAG, "Attempting Nightscout fetch (Priority) with $nightscoutApiKey")
                    sgv = nightscoutApiService.fetchSgvValue(nightscoutUrl, nightscoutApiKey)
                    Log.d(TAG, "Nightscout fetch result: $sgv")
                    if (sgv != null) source = "Nightscout"
                }
                if (sgv == null && !dexcomToken.isNullOrBlank()) {
                    Log.d(TAG, "Dexcom failed or no URL, attempting Dexcom fetch (Priority Fallback)")
                    sgv = dexcomApiService.getLatestDexcomGlucoseValueAsIntConcise()// Make sure this method exists and handles token refresh
                    if (sgv != null) source = "Dexcom"
                }
            } else {
                if (!dexcomToken.isNullOrBlank()) {
                    Log.d(TAG, "Attempting Dexcom fetch (Priority)")
                    sgv = dexcomApiService.getLatestDexcomGlucoseValueAsIntConcise()
                    if (sgv != null) source = "Dexcom"
                }
                if (sgv == null && nightscoutUrl.isNotBlank()) {
                    Log.d(TAG, "Dexcom failed or no token, attempting Nightscout fetch (Priority Fallback)")
                    sgv = nightscoutApiService.fetchSgvValue(nightscoutUrl, nightscoutApiKey)
                    if (sgv != null) source = "Nightscout"
                }
            }


            if (sgv != null) {
                Log.i(TAG, "Data fetched from $source: $sgv")
                updateNotification("Latest SGV ($source): $sgv mg/dL", sgv)
            } else {
                Log.w(TAG, "No new data fetched from any source.")
                updateNotification("No new data available.", 0)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during data fetch: ${e.message}", e)
            updateNotification("Error fetching data: ${e.message?.take(30)}", 0)
        }
    }


    private fun createNotification(contentText: String, egv: Int): Notification {
        val n: Int = if (egv > MG_DL_THRESHOLD_FOR_CONVERSION) {
            convertToMMOL(egv)
        } else {
            egv
        }
        Log.d(TAG, "createNotification $egv = $n")
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)
        val numberIcons = arrayOf(
            R.drawable.ic_number_0, R.drawable.ic_number_1, R.drawable.ic_number_2,
            R.drawable.ic_number_3, R.drawable.ic_number_4, R.drawable.ic_number_5,
            R.drawable.ic_number_6, R.drawable.ic_number_7, R.drawable.ic_number_8,
            R.drawable.ic_number_9, R.drawable.ic_number_10, R.drawable.ic_number_11,
            R.drawable.ic_number_12, R.drawable.ic_number_13, R.drawable.ic_number_14,
            R.drawable.ic_number_15
        )
        val safeN = n.coerceIn(0, numberIcons.size - 1)
        Log.d(TAG, "evg $n and safeN ${safeN}");
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EBGL ($contentText)")
            .setContentText("mmol $n")
            .setSmallIcon(numberIcons[safeN])
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(contentText: String, egv: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText, egv))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Data Fetch Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Service stopping, cancelling job.")
        job.cancel() // Cancel all coroutines started in serviceScope
    }
}
