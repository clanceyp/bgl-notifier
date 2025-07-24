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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ViewModel related imports
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider

// WorkManager imports
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.myfirstnotificationapp.NightscoutApiService
import com.example.myfirstnotificationapp.dexcom.DexcomApiService
import com.example.myfirstnotificationapp.dexcom.Egv
import com.example.myfirstnotificationapp.ui.theme.MyFirstNotificationAppTheme
import java.util.concurrent.TimeUnit

// OkHttp and Gson imports for MainViewModelFactory
import okhttp3.OkHttpClient
import com.google.gson.Gson

class MainActivity : ComponentActivity() {

    private lateinit var dataStoreManager: DataStoreManager
    private lateinit var dexcomApiService: DexcomApiService // Declare DexcomApiService
    private lateinit var nightscoutApiService: NightscoutApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dataStoreManager = DataStoreManager(this)
        // Initialize DexcomApiService
        dexcomApiService = DexcomApiService(OkHttpClient(), Gson(), dataStoreManager)
        nightscoutApiService = NightscoutApiService(OkHttpClient(), Gson())

        // No need to call checkDexcomConnection() directly here anymore,
        // as the ViewModel will handle it in its init block.

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

        setContent {
            MyFirstNotificationAppTheme {
                // Provide the ViewModel to the Composable hierarchy
                val mainViewModel: MainViewModel = viewModel(
                    factory = MainViewModel.Factory(
                        dataStoreManager, dexcomApiService,
                        nightscoutApiService
                    )
                )
                MainScreen(mainViewModel = mainViewModel)
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

                val repeatInterval = eventFrequency.toLong().coerceAtLeast(15L) // Ensure minimum 15 minutes

                val notificationWorkRequest = PeriodicWorkRequestBuilder<NotificationWorker>(
                    repeatInterval, TimeUnit.MINUTES
                ).build()

                WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                    "NotificationWorkerPeriodic",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    notificationWorkRequest
                )
                Log.d("MainActivity", "WorkManager scheduled for every $repeatInterval minutes.")
            } else {
                Log.d("MainActivity", "manageNotificationWork: Notifications disabled. Cancelling WorkManager.")
                WorkManager.getInstance(applicationContext).cancelUniqueWork("NotificationWorkerPeriodic")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        manageNotificationWork()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(mainViewModel: MainViewModel = viewModel()) {
        val context = LocalContext.current

        val isNightscoutConnected = mainViewModel.isNightscoutConnected.collectAsState().value
        val isLoadingInitialData = mainViewModel.isLoadingInitialData.collectAsState().value // Use this for overall loading

        val dexcomLoginStatus = mainViewModel.dexcomLoginStatus.collectAsState().value
        val latestEgv = mainViewModel.latestEgv.collectAsState().value
        val isDexcomDataLoading = mainViewModel.isDexcomDataLoading.collectAsState().value

        Log.d("MainActivity", "dexcomLoginStatus $dexcomLoginStatus")
        Log.d("MainActivity", "latestEgv $latestEgv")
        Log.d("MainActivity", "isNightscoutConnected $isNightscoutConnected")

        // Call recheckNightscoutConnection when the activity resumes
        DisposableEffect(Unit) {
            val activity = context as? ComponentActivity
            activity?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    mainViewModel.recheckNightscoutConnection()
                }
            })
            onDispose {
                // Clean up observer if needed, though for onResume it's less critical
            }
        }

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
                // Use isLoadingInitialData for the primary loading indicator
                if (isLoadingInitialData) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Checking connections...")
                } else {
                    // Nightscout Status
                    Text("Nightscout Status:", style = MaterialTheme.typography.titleMedium)
                    if (isNightscoutConnected) {
                        Text("Nightscout connection: OK", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
                    } else {
                        Text("Nightscout connection: NOT SET UP", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                        Text("Please go to settings to configure your Nightscout URL and API Key.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    // Dexcom Status
                    Text("Dexcom Status:", style = MaterialTheme.typography.titleMedium)
                    Text(dexcomLoginStatus, style = MaterialTheme.typography.bodyLarge)

                    if (isDexcomDataLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text("Fetching Dexcom data...")
                    } else {
                        latestEgv?.let { egv ->
                            Text("Latest EGV: ${egv.value} mg/dL", style = MaterialTheme.typography.headlineMedium)
                            Text("Time: ${egv.displayTime}", style = MaterialTheme.typography.bodyMedium)
                            egv.trend?.let { trend ->
                                Text("Trend: $trend", style = MaterialTheme.typography.bodyMedium)
                            }
                        } ?: run {
                            if (dexcomLoginStatus == "Logged in to Dexcom") {
                                Text("No recent Dexcom data available.", style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text("Please log in to Dexcom in settings.", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            val intent = Intent(context, SettingsActivity::class.java)
                            context.startActivity(intent)
                        }
                    ) {
                        Text("Go to Settings")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            mainViewModel.fetchLatestDexcomEgv() // Manually refresh Dexcom data
                        },
                        enabled = dexcomLoginStatus == "Logged in to Dexcom" && !isDexcomDataLoading
                    ) {
                        Text("Refresh Dexcom Data")
                    }
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