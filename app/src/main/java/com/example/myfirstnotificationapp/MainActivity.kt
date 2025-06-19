// MainActivity.kt
package com.example.myfirstnotificationapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import com.google.gson.Gson
import com.example.myfirstnotificationapp.ui.theme.MyFirstNotificationAppTheme

// WorkManager imports
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    // REMOVE: private val handler = Handler(Looper.getMainLooper())
// REMOVE: private lateinit var runnable: Runnable
    private lateinit var dataStoreManager: DataStoreManager
// REMOVE: private lateinit var nightscoutApiService: NightscoutApiService // No longer needed directly in MainActivity

// REMOVE: numberIcons array from here, move to Worker or make globally accessible if needed by Worker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dataStoreManager = DataStoreManager(this)
        // REMOVE: nightscoutApiService initialization here, it's now in the Worker

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1
            )
        }

        // Create notification channel (required for Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sgv_channel_id",
                "SGV Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // REMOVE: Definition of the repeating task (runnable)

        // Initial setup of the notification task using WorkManager
        // This will be called in onResume to ensure it reacts to settings changes
        // and also when the app first starts.
        // DO NOT call handler.post(runnable) here directly.

        setContent {
            MyFirstNotificationAppTheme {
                MainScreen()
            }
        }
    }

    // NEW: Function to manage the WorkManager task lifecycle
    private fun manageNotificationWork() {
        lifecycleScope.launch {
            val notificationsEnabled = dataStoreManager.notificationsEnabledFlow.first()
            val eventFrequency = dataStoreManager.eventFrequencyFlow.first()

            if (notificationsEnabled) {
                Log.d("MainActivity", "manageNotificationWork: Notifications enabled. Scheduling WorkManager.")

                // Minimum interval for PeriodicWorkRequest is 15 minutes
                // If eventFrequency is less than 15, WorkManager will still run it at 15 min intervals.
                val repeatInterval = eventFrequency.toLong().coerceAtLeast(15L) // Ensure minimum 15 minutes

                val notificationWorkRequest = PeriodicWorkRequestBuilder<NotificationWorker>(
                    repeatInterval, TimeUnit.MINUTES
                )
                    // Optional: Add constraints if needed (e.g., requires network)
                    // .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()

                WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                    "SGVNotificationWork", // Unique name for your work
                    ExistingPeriodicWorkPolicy.UPDATE, // Update existing work if settings change
                    notificationWorkRequest
                )
                Log.d("MainActivity", "WorkManager scheduled for every $repeatInterval minutes.")
            } else {
                Log.d("MainActivity", "manageNotificationWork: Notifications disabled. Cancelling WorkManager.")
                WorkManager.getInstance(applicationContext).cancelUniqueWork("SGVNotificationWork")
                // Optionally, cancel the notification itself if it's ongoing
                // This requires NotificationManagerCompat, which is not in the Worker
                // You can keep showNotification() in MainActivity and call it with a cancel flag
                // or move NotificationManagerCompat.from(applicationContext).cancel(1001) to Worker
                // For simplicity, let's assume the Worker handles cancelling its own notification.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // When the activity resumes (e.g., after returning from settings),
        // re-evaluate whether to start/stop the notification task.
        manageNotificationWork()
    }

    override fun onPause() {
        super.onPause()
        // No need to explicitly stop WorkManager here, it runs independently of Activity lifecycle.
        // WorkManager handles its own lifecycle.
    }

// REMOVE: showNotification from MainActivity, move it to NotificationWorker
// private fun showNotification(n: Int) { ... }

    override fun onDestroy() {
        super.onDestroy()
        // No need to remove callbacks from handler, as handler is removed.
        // WorkManager handles its own lifecycle.
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val context = LocalContext.current

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("My App") })
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = {
                        val intent = Intent(context, SettingsActivity::class.java)
                        context.startActivity(intent)
                    }
                ) {
                    Text("Go to Settings")
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        MyFirstNotificationAppTheme {
            Text("Main Screen Preview")
        }
    }
}