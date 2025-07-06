package com.example.myfirstnotificationapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.myfirstnotificationapp.ui.theme.MyFirstNotificationAppTheme
import kotlinx.coroutines.launch
import kotlin.system.exitProcess
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Divider // Import Divider

// NEW IMPORTS FOR DEXCOM LOGIN
import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.AuthorizationException
import net.openid.appauth.EndSessionRequest
import android.util.Log
import android.content.Intent // Import Intent for onNewIntent
import android.content.Context // Import Context
import android.widget.Toast // For Toast messages

// WorkManager imports
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider

// ViewModel imports
import androidx.lifecycle.viewmodel.compose.viewModel

class SettingsActivity : ComponentActivity() {

    // Dexcom OAuth Configuration (NOW USING SANDBOX ENDPOINTS AND YOUR CLIENT ID)
    private val DEXCOM_CLIENT_ID = "r9TqywzRpj0gbruswIXH2wkxt9bvrCno"
    private val DEXCOM_REDIRECT_URI = "myfirstnotificationapp://callback" // Must match your AndroidManifest and Dexcom Dev Portal
    // OAuth endpoints for Sandbox
    private val DEXCOM_AUTH_ENDPOINT = "https://sandbox-api.dexcom.com/v2/oauth2/login"
    private val DEXCOM_TOKEN_ENDPOINT = "https://sandbox-api.dexcom.com/v2/oauth2/token"
    private val DEXCOM_END_SESSION_ENDPOINT = "https://sandbox-api.dexcom.com/v2/oauth2/logout" // Optional, for logout
    private val DEXCOM_SCOPES = "offline_access egv" // Space-separated list of scopes

    // This is for AppAuth's AuthorizationService
    private lateinit var authService: AuthorizationService
    private lateinit var dataStoreManager: DataStoreManager // Declare here

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize AppAuth AuthorizationService
        authService = AuthorizationService(this)
        dataStoreManager = DataStoreManager(this) // Initialize DataStoreManager

        setContent {
            MyFirstNotificationAppTheme {
                // Get the ViewModel instance using the factory
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        dataStoreManager = dataStoreManager,
                        authService = authService,
                        dexcomClientId = DEXCOM_CLIENT_ID,
                        dexcomRedirectUri = DEXCOM_REDIRECT_URI,
                        dexcomAuthEndpoint = DEXCOM_AUTH_ENDPOINT,
                        dexcomTokenEndpoint = DEXCOM_TOKEN_ENDPOINT,
                        dexcomScopes = DEXCOM_SCOPES
                    )
                )
                SettingsScreen(
                    settingsViewModel = settingsViewModel, // Pass the ViewModel
                    dexcomEndSessionEndpoint = DEXCOM_END_SESSION_ENDPOINT // Still needed for logout button logic
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // authService.dispose() is now handled in ViewModel's onCleared()
    }

    // Handle the redirect from Dexcom (when the user grants/denies permission)
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        Log.d("SettingsActivity", "onNewIntent called. Intent data: ${intent?.data}")

        if (intent?.data != null && intent.data.toString().startsWith(DEXCOM_REDIRECT_URI)) {
            Log.d("SettingsActivity", "Redirect URI matched in onNewIntent. Passing to ViewModel.")

            // Instantiate the ViewModel using the same factory as in onCreate
            // This ensures you get the correct ViewModel instance.
            val settingsViewModel = ViewModelProvider(
                this,
                SettingsViewModel.Factory(
                    dataStoreManager = dataStoreManager, // Use the initialized dataStoreManager
                    authService = authService,
                    dexcomClientId = DEXCOM_CLIENT_ID,
                    dexcomRedirectUri = DEXCOM_REDIRECT_URI,
                    dexcomAuthEndpoint = DEXCOM_AUTH_ENDPOINT,
                    dexcomTokenEndpoint = DEXCOM_TOKEN_ENDPOINT,
                    dexcomScopes = DEXCOM_SCOPES
                )
            ).get(SettingsViewModel::class.java)

            settingsViewModel.handleAuthorizationResponse(intent)
            // Clear the intent data to prevent re-processing on configuration changes
            this.intent = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel, // Receive the ViewModel
    dexcomEndSessionEndpoint: String // Still pass this if needed for logout button logic
) {
    val context = LocalContext.current
    val dataStoreManager = remember { DataStoreManager(context) } // Still needed for Nightscout settings

// Observe states from ViewModel
    val dexcomLoginStatus by settingsViewModel.dexcomLoginStatus.collectAsState()
    val dexcomAccessToken by settingsViewModel.dexcomAccessToken.collectAsState()
    val dexcomRefreshToken by settingsViewModel.dexcomRefreshToken.collectAsState()

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var eventFrequency by remember { mutableStateOf(10) }
    var notificationsEnabled by remember { mutableStateOf(true) }

    var expanded by remember { mutableStateOf(false) }
    val frequencyOptions = listOf(1, 3, 5, 10, 20, 30)

// Collect Nightscout settings from DataStore (still in Composable)
    LaunchedEffect(key1 = Unit) {
        dataStoreManager.baseUrlFlow.collect { baseUrl = it }
    }
    LaunchedEffect(key1 = Unit) {
        dataStoreManager.apiKeyFlow.collect { apiKey = it }
    }
    LaunchedEffect(key1 = Unit) {
        dataStoreManager.eventFrequencyFlow.collect { eventFrequency = it }
    }
    LaunchedEffect(key1 = Unit) {
        dataStoreManager.notificationsEnabledFlow.collect { notificationsEnabled = it }
    }

// No need for a LaunchedEffect to handle the intent here anymore,
// as the ViewModel handles it via onNewIntent.

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ... (Nightscout settings UI remains the same) ...
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Your Nightscout URL") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Nightscout API Key") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = notificationsEnabled,
                        onValueChange = { notificationsEnabled = it },
                        role = Role.Switch
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enable Notifications", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = eventFrequency.toString(),
                    onValueChange = { /* Read-only */ },
                    readOnly = true,
                    label = { Text("Event Frequency (minutes)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    enabled = notificationsEnabled
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    frequencyOptions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption.toString()) },
                            onClick = {
                                eventFrequency = selectionOption
                                expanded = false
                            },
                            enabled = notificationsEnabled
                        )
                    }
                }
            }

            // NEW: Dexcom Login Section
            Divider(modifier = Modifier.fillMaxWidth().height(1.dp).padding(vertical = 8.dp))
            Text(text = "Dexcom Connection", style = MaterialTheme.typography.titleMedium)
            Text(text = dexcomLoginStatus, style = MaterialTheme.typography.bodyMedium)

            if (dexcomAccessToken == null) { // Show login button if not logged in
                Button(
                    onClick = {
                        // Initiate Dexcom OAuth Login
                        val authRequestIntent = settingsViewModel.authService.getAuthorizationRequestIntent(
                            AuthorizationRequest.Builder(
                                settingsViewModel.authServiceConfig,
                                settingsViewModel.dexcomClientId,
                                ResponseTypeValues.CODE,
                                Uri.parse(settingsViewModel.dexcomRedirectUri)
                            )
                                .setScope(settingsViewModel.dexcomScopes)
                                .build()
                        )
                        context.startActivity(authRequestIntent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Login to Dexcom")
                }
            } else { // Show logout button if logged in
                Button(
                    onClick = {
                        settingsViewModel.performDexcomLogout()
                        // Optionally, if Dexcom has an end session endpoint, you can redirect there
                        if (dexcomEndSessionEndpoint.isNotBlank()) {
                            // This part is tricky. You'd need to construct the EndSessionRequest
                            // and get its intent from the authService.
                            // For simplicity, we're just clearing local tokens for now.
                            // If you need a full IdP logout, you'd need to pass authService
                            // and authServiceConfig to a helper function.
                            // Example:
                            // val endSessionRequest = EndSessionRequest.Builder(settingsViewModel.authServiceConfig)
                            //     .setPostLogoutRedirectUri(Uri.parse("myfirstnotificationapp://logout_complete"))
                            //     .build()
                            // val endSessionIntent = settingsViewModel.authService.getEndSessionRequestIntent(endSessionRequest)
                            // context.startActivity(endSessionIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Logout from Dexcom")
                }
            }

            // Save Button
            Button(
                onClick = {
                    (context as? SettingsActivity)?.lifecycleScope?.launch {
                        dataStoreManager.saveBaseUrl(baseUrl)
                        dataStoreManager.saveApiKey(apiKey)
                        dataStoreManager.saveEventFrequency(eventFrequency)
                        dataStoreManager.saveNotificationsEnabled(notificationsEnabled)
                        Toast.makeText(context, "Settings saved!", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }

            // NEW: Check Now Button
            Button(
                onClick = {
                    val workManager = WorkManager.getInstance(context)
                    // 1. Cancel any existing periodic work
                    workManager.cancelUniqueWork("NotificationWorkerPeriodic")
                    Log.d("SettingsScreen", "Cancelled existing periodic work.")

                    // 2. Enqueue a one-time immediate work request
                    val oneTimeRequest = OneTimeWorkRequestBuilder<NotificationWorker>()
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                    workManager.enqueue(oneTimeRequest)
                    Log.d("SettingsScreen", "Enqueued one-time work request.")

                    // 3. Re-enqueue the periodic work request to reset its timer
                    val periodicRequest = PeriodicWorkRequestBuilder<NotificationWorker>(
                        eventFrequency.toLong(),
                        TimeUnit.MINUTES
                    )
                        .setInitialDelay(eventFrequency.toLong(), TimeUnit.MINUTES)
                        .build()
                    workManager.enqueueUniquePeriodicWork(
                        "NotificationWorkerPeriodic",
                        ExistingPeriodicWorkPolicy.UPDATE,
                        periodicRequest
                    )
                    Log.d("SettingsScreen", "Re-enqueued periodic work request with frequency: $eventFrequency minutes.")

                    Toast.makeText(context, "Checking for new glucose value...", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = notificationsEnabled
            ) {
                Text("Check Now")
            }

            // Close App Button
            Button(
                onClick = {
                    (context as? ComponentActivity)?.finishAffinity()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Close App")
            }
        }
    }
}

// No longer needed as these functions are now in SettingsViewModel
// private fun performDexcomLogin(...) { ... }
// private fun performDexcomLogout(...) { ... }

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MyFirstNotificationAppTheme {
        // For preview, you'll need to provide a mock ViewModel
        // Example:
        // val mockDataStoreManager = DataStoreManager(LocalContext.current) // Mock or real for preview
        // val mockAuthService = AuthorizationService(LocalContext.current) // Mock or real for preview
        // val mockViewModel = SettingsViewModel(
        //     mockDataStoreManager, mockAuthService, "id", "uri", "auth", "token", "scopes"
        // )
        // SettingsScreen(mockViewModel, "logout_endpoint")
        Text("Settings Screen Preview")
    }
}