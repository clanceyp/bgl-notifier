package com.example.myfirstnotificationapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstnotificationapp.ui.theme.MyFirstNotificationAppTheme
import net.openid.appauth.AuthorizationService
import com.example.myfirstnotificationapp.R
import kotlin.system.exitProcess // For finishAffinity alternative
import androidx.core.net.toUri

class SettingsActivity : ComponentActivity() {

    private lateinit var authService: AuthorizationService
    private lateinit var dataStoreManager: DataStoreManager
    private lateinit var settingsViewModel: SettingsViewModel

    private val authActivityResultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("AUTH_FLOW_DEBUG", "ActivityResultLauncher callback received.")
            Log.d("AUTH_FLOW_DEBUG", "Result Code: ${result.resultCode}")
            Log.d("AUTH_FLOW_DEBUG", "Result Data: ${result.data?.dataString}") // Log the URI data

            if (result.data != null) {
                Log.d("AUTH_FLOW_DEBUG", "Calling settingsViewModel.handleAuthorizationResponse().")
                settingsViewModel.handleAuthorizationResponse(result.data!!)
            } else {
                Log.e("AUTH_FLOW_DEBUG", "ActivityResultLauncher received null data.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("SettingsActivity", "onCreate: Activity created.")

        Log.d("AUTH_FLOW_DEBUG", "SettingsActivity onCreate called.")

// Check for process death
        if (savedInstanceState != null) {
            Log.d("AUTH_FLOW_DEBUG", "SettingsActivity recreated from savedInstanceState. This indicates process death or configuration change.")
        } else {
            Log.d("AUTH_FLOW_DEBUG", "SettingsActivity created for the first time or resumed from background.")
        }

        authService = AuthorizationService(this)
        dataStoreManager = DataStoreManager(this)
        Log.d("SettingsActivity", "onCreate: AuthorizationService and DataStoreManager initialized.")

        setContent {
            MyFirstNotificationAppTheme {
                settingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        application,
                        dataStoreManager = dataStoreManager,
                        authService = authService,
                        dexcomClientId = BuildConfig.DEXCOM_CLIENT_ID,
                        dexcomRedirectUri = BuildConfig.DEXCOM_REDIRECT_URI,
                        dexcomAuthEndpoint = BuildConfig.DEXCOM_AUTH_ENDPOINT,
                        dexcomTokenEndpoint = BuildConfig.DEXCOM_TOKEN_ENDPOINT,
                        dexcomScopes = BuildConfig.DEXCOM_SCOPES,
                        dexcomClientSecret = BuildConfig.DEXCOM_CLIENT_SECRET,
                    )
                )
                SettingsScreen(
                    settingsViewModel = settingsViewModel,
                    onLoginClick = { intent ->
                        // This callback is where the Activity launches the Intent
                        // startActivity(intent)
                        authActivityResultLauncher.launch(intent)
                    },
                    onCloseApp = {
                        // This callback is where the Activity handles closing the app
                        finishAffinity() // Closes all activities in the task
                        // If you want to completely exit the process (less common for Android apps)
                        // exitProcess(0)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("AUTH_FLOW_DEBUG", "SettingsActivity onNewIntent called.")
        Log.d("AUTH_FLOW_DEBUG", "onNewIntent Intent Action: ${intent?.action}")
        Log.d("AUTH_FLOW_DEBUG", "onNewIntent Intent Data: ${intent?.dataString}")

        // Check if this intent is the redirect from the OAuth flow
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val redirectUri = BuildConfig.DEXCOM_REDIRECT_URI.toUri()
            if (intent.data?.scheme == redirectUri.scheme && intent.data?.host == redirectUri.host) {
                Log.d("AUTH_FLOW_DEBUG", "Redirect URI matched in onNewIntent. Passing to ViewModel.")
                settingsViewModel.handleAuthorizationResponse(intent)
            } else {
                Log.w("AUTH_FLOW_DEBUG", "onNewIntent received non-matching redirect URI: ${intent.dataString}")
            }
        } else {
            Log.d("AUTH_FLOW_DEBUG", "onNewIntent received non-redirect intent.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SettingsActivity", "onDestroy: Activity destroyed.")
    }
}