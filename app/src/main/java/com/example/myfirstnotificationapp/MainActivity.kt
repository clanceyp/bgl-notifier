package com.example.myfirstnotificationapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

// WorkManager imports
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.myfirstnotificationapp.dexcom.DexcomApiService
import com.example.myfirstnotificationapp.ui.theme.MyFirstNotificationAppTheme
import com.example.myfirstnotificationapp.GlucoseConstants.convertToMMOLString
import com.example.myfirstnotificationapp.GlucoseConstants.formatTimeAgo
import java.util.concurrent.TimeUnit
import android.content.Context


// OkHttp and Gson imports for MainViewModelFactory
import okhttp3.OkHttpClient
import com.google.gson.Gson
import kotlin.jvm.java

class MainActivity : ComponentActivity() {

    private lateinit var dataStoreManager: DataStoreManager
    private lateinit var dexcomApiService: DexcomApiService
    private lateinit var nightscoutApiService: NightscoutApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dataStoreManager = DataStoreManager(this)

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS) // Connection timeout
            .readTimeout(5, TimeUnit.SECONDS)    // Read timeout
            .writeTimeout(5, TimeUnit.SECONDS)   // Write timeout
            .build()

        // Initialize DexcomApiService
        dexcomApiService = DexcomApiService(
            okHttpClient,
            Gson(),
            dataStoreManager,
            BuildConfig.DEXCOM_CLIENT_ID,
            BuildConfig.DEXCOM_CLIENT_SECRET,
            BuildConfig.DEXCOM_TOKEN_ENDPOINT,
            BuildConfig.DEXCOM_EGV_ENDPOINT
        )
        nightscoutApiService = NightscoutApiService(okHttpClient, Gson())

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
                        dataStoreManager,
                        dexcomApiService,
                        nightscoutApiService
                    )
                )
                MainScreen(mainViewModel = mainViewModel)
            }
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            if (!isBatteryOptimizationIgnored()) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                // Check if there's an Activity to handle this Intent
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    // Handle case where the settings screen might not be available
                    // Maybe show a general battery optimization settings screen
                    // Or inform the user manually.
                    Log.w("BatteryOpt", "No activity found to handle ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                    try {
                        // Fallback: Open general battery optimization settings
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(fallbackIntent)
                    } catch (e: Exception) {
                        Log.e("BatteryOpt", "Could not open any battery optimization settings", e)
                        // Inform user to do it manually
                    }
                }
            }
        }
    }

    private fun manageNotificationWork() {
        lifecycleScope.launch {
            val useForegroundService = dataStoreManager.useForegroundServiceFlow.first() // <<< ADD THIS
            val notificationsEnabled = dataStoreManager.notificationsEnabledFlow.first()
            val eventFrequencyMinutes = dataStoreManager.eventFrequencyFlow.first()

            Log.d("MainActivity",
                "manageNotificationWork: useForegroundService=$useForegroundService, notificationsEnabled=$notificationsEnabled"
            )

            if (useForegroundService) {
                // If foreground service is active, it's responsible for updates.
                // We should ensure NotificationWorker is cancelled.
                Log.d("MainActivity", "manageNotificationWork: Foreground service is selected. Cancelling any existing NotificationWorker.")
                WorkManager.getInstance(applicationContext).cancelUniqueWork("NotificationWorkerPeriodic")
            } else {
                // Foreground service is NOT selected. Use NotificationWorker based on notificationsEnabled.
                if (notificationsEnabled) {
                    Log.d("MainActivity", "manageNotificationWork: Foreground service NOT selected & notifications ON. Scheduling NotificationWorker.")

                    val repeatInterval = eventFrequencyMinutes.toLong().coerceAtLeast(15L) // Ensure minimum 15 minutes

                    val notificationWorkRequest = PeriodicWorkRequestBuilder<NotificationWorker>(
                        repeatInterval, TimeUnit.MINUTES
                    ).build()

                    WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                        "NotificationWorkerPeriodic",
                        ExistingPeriodicWorkPolicy.REPLACE, // Using REPLACE ensures the latest settings are applied
                        notificationWorkRequest
                    )
                    Log.d("MainActivity", "NotificationWorker (WorkManager) scheduled for every $repeatInterval minutes.")
                } else {
                    Log.d("MainActivity", "manageNotificationWork: Foreground service NOT selected & notifications OFF. Cancelling NotificationWorker.")
                    WorkManager.getInstance(applicationContext).cancelUniqueWork("NotificationWorkerPeriodic")
                }
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
        val isLoadingInitialData = mainViewModel.isLoadingInitialData.collectAsState().value

        val nightscoutEgvValue = mainViewModel.nightscoutEgvValue.collectAsState().value
        val dexcomLoginStatus = mainViewModel.dexcomLoginStatus.collectAsState().value
        val latestEgv = mainViewModel.latestEgv.collectAsState().value
        val isDexcomDataLoading = mainViewModel.isDexcomDataLoading.collectAsState().value

        Log.d("MainActivity", "dexcomLoginStatus $dexcomLoginStatus")
        Log.d("MainActivity", "latestEgv $latestEgv")
        Log.d("MainActivity", "isNightscoutConnected $isNightscoutConnected")
        Log.d("MainActivity", "nightscoutEgvValue $nightscoutEgvValue")

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
                TopAppBar(title = { Text("") })
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
                    Text("Nightscout Status",
                        style = MaterialTheme.typography.headlineSmall)
                    if (isNightscoutConnected) {
                        Text("Nightscout connection: OK",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp, top = 8.dp))
                    } else {
                        Text("Nightscout connection: NOT SET UP",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error)
                        Text("Please go to settings to configure Nightscout details",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center)
                    }
                    if (nightscoutEgvValue?.sgv != null) {
                        Text("Latest EGV: ${convertToMMOLString(nightscoutEgvValue.sgv)} mmol/L",
                            style = MaterialTheme.typography.bodyLarge)
                    }
                    if (nightscoutEgvValue?.dateString != null) {
                        Text("${formatTimeAgo(nightscoutEgvValue.dateString)}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(24.dp))

                    // Dexcom Status
                    Text("Dexcom Status:",
                        style = MaterialTheme.typography.headlineSmall)
                    Text(dexcomLoginStatus,
                        style = MaterialTheme.typography.bodyLarge)

                    if (isDexcomDataLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text("Fetching Dexcom data...")
                    } else {
                        latestEgv?.let { egv ->
                            Text("Latest EGV: ${convertToMMOLString(egv.value)} mmoL/L",
                                style = MaterialTheme.typography.bodyLarge)
                            Text("${ formatTimeAgo(egv.displayTime) }",
                                 style = MaterialTheme.typography.bodyMedium)
                            egv.trend?.let { trend ->
                                Text("Trend: $trend",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        } ?: run {
                            if (dexcomLoginStatus == "Logged in to Dexcom") {
                                Text("No recent Dexcom data available.",
                                    style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text("Please log in to Dexcom in settings.",
                                    style = MaterialTheme.typography.bodyLarge)
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
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            finishAffinity()
                        }
                    ) {
                        Text("Close")
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
