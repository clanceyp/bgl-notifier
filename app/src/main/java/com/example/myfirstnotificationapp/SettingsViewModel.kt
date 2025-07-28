package com.example.myfirstnotificationapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow // Still needed for _MutableStateFlow.asStateFlow()
import kotlinx.coroutines.flow.stateIn // NEW: Import stateIn
import kotlinx.coroutines.flow.SharingStarted // NEW: Import SharingStarted
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import java.util.concurrent.TimeUnit

class SettingsViewModel(
    private val dataStoreManager: DataStoreManager,
    private val authService: AuthorizationService,
    private val dexcomClientId: String,
    private val dexcomRedirectUri: String,
    private val dexcomAuthEndpoint: String,
    private val dexcomTokenEndpoint: String,
    private val dexcomScopes: String,
    private val dexcomClientSecret: String,
    private val applicationContext: Context // Add applicationContext to constructor
) : ViewModel() {

    // --- UI State (exposed as StateFlows for Compose to observe) ---
    private val _dexcomLoginStatus = MutableStateFlow("Not connected to Dexcom")
    val dexcomLoginStatus: StateFlow<String> = _dexcomLoginStatus.asStateFlow()

    private val _dexcomAccessToken = MutableStateFlow<String?>(null)
    val dexcomAccessToken: StateFlow<String?> = _dexcomAccessToken.asStateFlow()

    private val _dexcomRefreshToken = MutableStateFlow<String?>(null)
    val dexcomRefreshToken: StateFlow<String?> = _dexcomRefreshToken.asStateFlow()

    private val _isServiceConfigReady = MutableStateFlow(false)
    val isServiceConfigReady: StateFlow<Boolean> = _isServiceConfigReady.asStateFlow()

    // CORRECTED: Nightscout Settings StateFlows (directly from DataStore using stateIn)
    val baseUrl: StateFlow<String> = dataStoreManager.baseUrlFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000), // Start collecting when subscribers appear, stop after 5s
            initialValue = "" // Initial value before DataStore provides one
        )

    val apiKey: StateFlow<String> = dataStoreManager.apiKeyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val eventFrequency: StateFlow<Int> = dataStoreManager.eventFrequencyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 10 // Default frequency
        )

    val notificationsEnabled: StateFlow<Boolean> = dataStoreManager.notificationsEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true // Default to enabled
        )

    // --- Internal State ---
    var authServiceConfig: AuthorizationServiceConfiguration? = null
        private set

    init {
        Log.d("SettingsViewModel", "ViewModel initialized.")

        // Load existing Dexcom tokens from DataStore
        viewModelScope.launch {
            dataStoreManager.dexcomAccessTokenFlow.collect { token ->
                _dexcomAccessToken.value = token
                updateLoginStatus()
            }
        }
        viewModelScope.launch {
            dataStoreManager.dexcomRefreshTokenFlow.collect { token ->
                _dexcomRefreshToken.value = token
                updateLoginStatus()
            }
        }

        // Attempt to fetch discovery document or manually configure
        AuthorizationServiceConfiguration.fetchFromUrl(
            Uri.parse(dexcomAuthEndpoint.replace("/login", "/.well-known/openid-configuration")),
            { serviceConfiguration, ex ->
                if (ex != null) {
                    Log.e("SettingsViewModel", "Failed to fetch discovery document: ${ex.message}. Attempting manual configuration.", ex)
                    // Fallback to manual configuration if discovery fails
                    authServiceConfig = AuthorizationServiceConfiguration(
                        Uri.parse(dexcomAuthEndpoint),
                        Uri.parse(dexcomTokenEndpoint),
                        null, // Registration endpoint
                        null // End session endpoint (if not explicitly provided by Dexcom sandbox)
                    )
                    _isServiceConfigReady.value = true
                    Log.d("SettingsViewModel", "AuthorizationServiceConfiguration manually configured.")
                } else {
                    authServiceConfig = serviceConfiguration
                    _isServiceConfigReady.value = true
                    Log.d("SettingsViewModel", "AuthorizationServiceConfiguration fetched successfully.")
                }
                updateLoginStatus()
            }
        )
    }

    private fun updateLoginStatus() {
        _dexcomLoginStatus.value = if (_dexcomAccessToken.value != null) {
            "Connected to Dexcom"
        } else {
            "Not connected to Dexcom"
        }
    }

    fun performDexcomLogin(): Intent? {
        val config = authServiceConfig
        if (config == null || !_isServiceConfigReady.value) {
            Log.e("SettingsViewModel", "AuthorizationServiceConfiguration is not initialized or ready.")
            _dexcomLoginStatus.value = "Error: Service configuration not ready."
            return null
        }

        val authRequest = AuthorizationRequest.Builder(
            config,
            dexcomClientId,
            ResponseTypeValues.CODE,
            Uri.parse(dexcomRedirectUri)
        )
            .setScope(dexcomScopes)
            .build()

        Log.d("SettingsViewModel", "Building authorization request: $authRequest")
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    fun handleAuthorizationResponse(intent: Intent) {
// THIS IS THE CRITICAL NEW LOG
        Log.d("AUTH_FLOW_DEBUG", "handleAuthorizationResponse called with intent: ${intent.dataString}")

        // Add a breakpoint here and inspect 'intent' object
        val response = AuthorizationResponse.fromIntent(intent)
        val ex = AuthorizationException.fromIntent(intent)

        Log.d("AUTH_FLOW_DEBUG", "AppAuth parsing result:")
        Log.d("AUTH_FLOW_DEBUG", "  Response (AuthorizationResponse): ${response?.javaClass?.simpleName ?: "null"}")
        Log.d("AUTH_FLOW_DEBUG", "  Exception (AuthorizationException): ${ex?.javaClass?.simpleName ?: "null"}")

        if (response != null) {
            Log.d("SettingsViewModel", "Authorization successful. Exchanging code for tokens.")
            Log.e("AUTH_FLOW_DEBUG", "Authorization successful. Starting token exchange.")

            // Construct the TokenRequest
            val tokenRequest = TokenRequest.Builder(
                response.request.configuration,
                response.request.clientId
            )
                .setGrantType(GrantTypeValues.AUTHORIZATION_CODE)
                .setCodeVerifier(response.request.codeVerifier)
                .setRedirectUri(response.request.redirectUri)
                .setAuthorizationCode(response.authorizationCode)
                .build()

            // Perform the token exchange request
            authService.performTokenRequest(
                tokenRequest,
                net.openid.appauth.ClientSecretPost(dexcomClientSecret)
            ) { tokenResponse, tokenEx ->
                // Log the outcome of the token request callback
                Log.e("AUTH_FLOW_DEBUG", "Token request callback received. TokenResponse: ${tokenResponse != null}, TokenException: ${tokenEx != null}")

                if (tokenResponse != null) {
                    viewModelScope.launch {
                        val accessToken = tokenResponse.accessToken
                        val refreshToken = tokenResponse.refreshToken
                        // Log.d("SettingsViewModel", "Token exchange successful. Access Token: ${accessToken?.take(10)}..., Refresh Token: ${refreshToken?.take(10)}...")
                        Log.e("AUTH_FLOW_DEBUG", "Token exchange successful. Saving tokens.")

                        // Save tokens to DataStore
                        dataStoreManager.saveDexcomAccessToken(accessToken)
                        dataStoreManager.saveDexcomRefreshToken(refreshToken)

                        // Update ViewModel's internal state
                        _dexcomAccessToken.value = accessToken
                        _dexcomRefreshToken.value = refreshToken
                        _dexcomLoginStatus.value = "Connected to Dexcom"
                        Log.e("AUTH_FLOW_DEBUG", "UI state updated to Connected.")
                    }
                } else {
                    Log.e("SettingsViewModel", "Token exchange failed: ${tokenEx?.message}", tokenEx)
                    Log.e("AUTH_FLOW_DEBUG", "Token exchange failed. Error: ${tokenEx?.message}")
                    _dexcomLoginStatus.value = "Login failed: ${tokenEx?.message}"
                    // Clear any potentially stale tokens on failure
                    viewModelScope.launch {
                        dataStoreManager.saveDexcomAccessToken(null)
                        dataStoreManager.saveDexcomRefreshToken(null)
                        _dexcomAccessToken.value = null
                        _dexcomRefreshToken.value = null
                    }
                }
            }
        } else if (ex != null) {
            Log.e("SettingsViewModel", "Authorization failed: ${ex.message}", ex)
            Log.e("AUTH_FLOW_DEBUG", "Authorization failed. Error: ${ex.message}")
            _dexcomLoginStatus.value = "Login failed: ${ex.message}"
            // Clear any potentially stale tokens on failure
            viewModelScope.launch {
                dataStoreManager.saveDexcomAccessToken(null)
                dataStoreManager.saveDexcomRefreshToken(null)
                _dexcomAccessToken.value = null
                _dexcomRefreshToken.value = null
            }
        }
    }

    fun performDexcomLogout() {
        viewModelScope.launch {
            dataStoreManager.saveDexcomAccessToken(null)
            dataStoreManager.saveDexcomRefreshToken(null)
            _dexcomAccessToken.value = null
            _dexcomRefreshToken.value = null
            _dexcomLoginStatus.value = "Not connected to Dexcom"
            Log.d("SettingsViewModel", "Dexcom tokens cleared locally.")
        }
    }

    // New: Function to save Nightscout settings
    fun saveNightscoutSettings(baseUrl: String, apiKey: String, eventFrequency: Int, notificationsEnabled: Boolean) {
        viewModelScope.launch {
            dataStoreManager.saveBaseUrl(baseUrl)
            dataStoreManager.saveApiKey(apiKey)
            dataStoreManager.saveEventFrequency(eventFrequency)
            dataStoreManager.saveNotificationsEnabled(notificationsEnabled)
            Log.d("SettingsViewModel", "Nightscout settings saved.")
        }
    }

    // New: Function to trigger NotificationWorker
    fun triggerNotificationWorker(
        context: Context,
        eventFrequencyMinutes: Int,
        notificationsEnabled: Boolean
    ) {
        val workManager = WorkManager.getInstance(applicationContext) // Use applicationContext here
        workManager.cancelUniqueWork("NotificationWorkerPeriodic")
        Log.d("SettingsViewModel", "Cancelled existing periodic work.")

        if (notificationsEnabled) {
            val oneTimeRequest = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            workManager.enqueue(oneTimeRequest)
            Log.d("SettingsViewModel", "Enqueued one-time work request.")

            val periodicRequest = PeriodicWorkRequestBuilder<NotificationWorker>(
                eventFrequencyMinutes.toLong(),
                TimeUnit.MINUTES
            )
                .setInitialDelay(eventFrequencyMinutes.toLong(), TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniquePeriodicWork(
                "NotificationWorkerPeriodic",
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )
            Log.d("SettingsViewModel", "Re-enqueued periodic work request with frequency: $eventFrequencyMinutes minutes.")
        } else {
            Log.d("SettingsViewModel", "Notifications disabled, not scheduling worker.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        authService.dispose()
        Log.d("SettingsViewModel", "AuthorizationService disposed.")
    }

    class Factory(
        private val dataStoreManager: DataStoreManager,
        private val authService: AuthorizationService,
        private val dexcomClientId: String,
        private val dexcomRedirectUri: String,
        private val dexcomAuthEndpoint: String,
        private val dexcomTokenEndpoint: String,
        private val dexcomScopes: String,
        private val applicationContext: Context, // Add applicationContext to Factory
        private val dexcomClientSecret: String
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return SettingsViewModel(
                    dataStoreManager,
                    authService,
                    dexcomClientId,
                    dexcomRedirectUri,
                    dexcomAuthEndpoint,
                    dexcomTokenEndpoint,
                    dexcomScopes,
                    dexcomClientSecret,
                    applicationContext
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}