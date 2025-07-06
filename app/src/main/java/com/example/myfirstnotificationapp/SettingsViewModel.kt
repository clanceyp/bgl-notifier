package com.example.myfirstnotificationapp

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest

class SettingsViewModel(
    private val dataStoreManager: DataStoreManager,
    val authService: AuthorizationService, // Inject AuthorizationService
    val dexcomClientId: String,
    val dexcomRedirectUri: String,
    private val dexcomAuthEndpoint: String,
    private val dexcomTokenEndpoint: String,
    val dexcomScopes: String
) : ViewModel() {

    // UI State for Dexcom Login
    private val _dexcomLoginStatus = MutableStateFlow("Checking login status...")
    val dexcomLoginStatus: StateFlow<String> = _dexcomLoginStatus.asStateFlow()

    private val _dexcomAccessToken = MutableStateFlow<String?>(null)
    val dexcomAccessToken: StateFlow<String?> = _dexcomAccessToken.asStateFlow()

    private val _dexcomRefreshToken = MutableStateFlow<String?>(null)
    val dexcomRefreshToken: StateFlow<String?> = _dexcomRefreshToken.asStateFlow()

    // Configuration for AppAuth
    val authServiceConfig: AuthorizationServiceConfiguration = AuthorizationServiceConfiguration(
        Uri.parse(dexcomAuthEndpoint),
        Uri.parse(dexcomTokenEndpoint),
        Uri.parse(dexcomTokenEndpoint) // Dexcom uses the same endpoint for token and jwks_uri
    )

    init {
        // Collect tokens from DataStore immediately when ViewModel is created
        viewModelScope.launch {
            dataStoreManager.dexcomAccessTokenFlow.collect { token ->
                _dexcomAccessToken.value = token
                updateLoginStatus(token)
            }
        }
        viewModelScope.launch {
            dataStoreManager.dexcomRefreshTokenFlow.collect { token ->
                _dexcomRefreshToken.value = token
            }
        }
    }

    private fun updateLoginStatus(token: String?) {
        _dexcomLoginStatus.value = if (token != null) "Logged in to Dexcom" else "Not logged in to Dexcom"
    }

    // Function to initiate Dexcom login
    fun performDexcomLogin() {
        val authRequest = AuthorizationRequest.Builder(
            authServiceConfig,
            dexcomClientId,
            ResponseTypeValues.CODE,
            Uri.parse(dexcomRedirectUri)
        )
            .setScope(dexcomScopes)
            .build()

        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
        // This intent needs to be started by the Activity, so we'll return it
        // or use a mechanism like ActivityResultLauncher.
        // For now, we'll assume the Activity will call startActivity(intent)
        // when this function is called.
        // This function will primarily build the request.
        // The actual starting of the activity will be in the Composable.
    }

    // Function to handle the redirect intent
    fun handleAuthorizationResponse(intent: Intent) {
        Log.d("SettingsViewModel", "handleAuthorizationResponse called. Intent data: ${intent.data}")
        val resp = AuthorizationResponse.fromIntent(intent)
        val ex = AuthorizationException.fromIntent(intent)

        if (resp != null) {
            Log.d("SettingsViewModel", "Authorization response received. Code: ${resp.authorizationCode}")
            _dexcomLoginStatus.value = "Exchanging code..."

            val tokenRequest = resp.createTokenExchangeRequest()
            authService.performTokenRequest(tokenRequest) { tokenResponse, authException ->
                viewModelScope.launch { // Use viewModelScope for DataStore operations
                    if (tokenResponse != null) {
                        Log.d("SettingsViewModel", "Token exchange successful.")
                        val newAccessToken = tokenResponse.accessToken
                        val newRefreshToken = tokenResponse.refreshToken

                        if (newAccessToken != null && newRefreshToken != null) {
                            dataStoreManager.saveDexcomAccessToken(newAccessToken)
                            dataStoreManager.saveDexcomRefreshToken(newRefreshToken)
                            // The collect block in init will update _dexcomLoginStatus
                            Log.d("SettingsViewModel", "Tokens saved to DataStore.")
                        } else {
                            _dexcomLoginStatus.value = "Login failed: Missing access or refresh token."
                            Log.e("SettingsViewModel", "Token response missing access or refresh token.")
                        }
                    } else {
                        _dexcomLoginStatus.value = "Login failed: ${authException?.message}"
                        Log.e("SettingsViewModel", "Token exchange failed: ${authException?.message}", authException)
                    }
                }
            }
        } else if (ex != null) {
            _dexcomLoginStatus.value = "Login failed: ${ex.message}"
            Log.e("SettingsViewModel", "Authorization failed: ${ex.message}", ex)
        } else {
            Log.w("SettingsViewModel", "No authorization response or exception found in intent.")
        }
    }

    // Function to perform Dexcom logout
    fun performDexcomLogout() {
        viewModelScope.launch {
            dataStoreManager.saveDexcomAccessToken(null)
            dataStoreManager.saveDexcomRefreshToken(null)
            // The collect block in init will update _dexcomLoginStatus
            Log.d("SettingsViewModel", "Dexcom tokens cleared from DataStore.")
        }
        // If you need to redirect to Dexcom's end session endpoint,
        // you'd typically return an Intent here for the Activity to start.
        // For now, we'll just clear local tokens.
    }

    override fun onCleared() {
        super.onCleared()
        // Dispose of the AuthorizationService when the ViewModel is cleared
        authService.dispose()
    }

    // ViewModel Factory
    class Factory(
        private val dataStoreManager: DataStoreManager,
        private val authService: AuthorizationService,
        private val dexcomClientId: String,
        private val dexcomRedirectUri: String,
        private val dexcomAuthEndpoint: String,
        private val dexcomTokenEndpoint: String,
        private val dexcomScopes: String
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
                    dexcomScopes
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}