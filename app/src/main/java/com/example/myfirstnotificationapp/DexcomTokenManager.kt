package com.example.myfirstnotificationapp.dexcom

import android.content.Context
import android.util.Log
import com.example.myfirstnotificationapp.DataStoreManager
import kotlinx.coroutines.flow.first
import net.openid.appauth.AuthorizationService
import net.openid.appauth.TokenRequest
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.AuthorizationServiceConfiguration
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DexcomTokenManager(
    private val context: Context, // Application context is usually best here
    private val dexcomClientId: String,
    private val dexcomAuthEndpoint: String, // Added for consistency, though not directly used here for serviceConfig
    private val dexcomTokenEndpoint: String,
    private val dexcomRedirectUri: String // Added for consistency, though not directly used here
) {
    private val dataStoreManager = DataStoreManager(context)
    private val authService = AuthorizationService(context)

// Now using constructor parameters for configuration
// private val DEXCOM_CLIENT_ID = "r9TqywzRpj0gbruswIXH2wkxt9bvrCno" // REMOVED
// private val DEXCOM_TOKEN_ENDPOINT = "https://sandbox-api.dexcom.com/v2/oauth2/token" // REMOVED

    private val managerScope = CoroutineScope(Dispatchers.IO)

    // Use the injected token endpoint for serviceConfig
    private val serviceConfig = AuthorizationServiceConfiguration(
        // The auth endpoint here should match what you use for discovery in SettingsViewModel
        // For refresh, the serviceConfig only strictly needs the token endpoint.
        // However, for consistency, it's good to use the discovered config if possible,
        // or ensure this matches the one used for initial auth.
        // If you are *only* using this for token refresh, the auth endpoint here might not matter
        // as much as the token endpoint.
        Uri.parse(dexcomAuthEndpoint.replace("/login", "/authorize")), // Assuming this is the direct authorize endpoint
        Uri.parse(dexcomTokenEndpoint),
        Uri.parse(dexcomTokenEndpoint) // JWKS URI (often same as token endpoint for Dexcom)
    )

    // This method will try to return a valid access token, refreshing if necessary
    suspend fun getValidAccessToken(): String? {
        var accessToken = dataStoreManager.dexcomAccessTokenFlow.first()
        val refreshToken = dataStoreManager.dexcomRefreshTokenFlow.first()

        if (accessToken.isNullOrBlank() && refreshToken.isNullOrBlank()) {
            Log.d("DexcomTokenManager", "No tokens available.")
            return null
        }

        // TODO: Implement actual token expiry check here.
        // You would need to store the accessTokenExpiresAt timestamp in DataStore
        // and compare it with System.currentTimeMillis().
        // For now, we'll assume if access token is null, it needs refresh.
        if (accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
            Log.d("DexcomTokenManager", "Access token missing, attempting to refresh...")
            accessToken = refreshAccessToken(refreshToken)
        }

        return accessToken
    }

    private suspend fun refreshAccessToken(refreshToken: String): String? {
        return suspendCancellableCoroutine { continuation ->
            val tokenRequest = TokenRequest.Builder(
                serviceConfig,
                dexcomClientId // Use the injected client ID
            )
                .setGrantType("refresh_token")
                .setRefreshToken(refreshToken)
                .build()

            val clientAuthentication = ClientSecretPost(dexcomClientId) // Use the injected client ID

            authService.performTokenRequest(tokenRequest, clientAuthentication) { tokenResponse, authException ->
                if (tokenResponse != null) {
                    val newAccessToken = tokenResponse.accessToken
                    val newRefreshToken = tokenResponse.refreshToken ?: refreshToken

                    val expiresInSeconds = tokenResponse.additionalParameters["expires_in"]?.toLongOrNull()
                    val accessTokenExpiresAt: Long? = expiresInSeconds?.let {
                        System.currentTimeMillis() + it * 1000
                    }

                    Log.d("DexcomTokenManager", "Token refresh successful.")

                    managerScope.launch {
                        dataStoreManager.saveDexcomAccessToken(newAccessToken)
                        dataStoreManager.saveDexcomRefreshToken(newRefreshToken)
                        // dataStoreManager.saveAccessTokenExpiry(accessTokenExpiresAt) // Save expiry if you implement it
                        Log.d("DexcomTokenManager", "Tokens saved to DataStore.")
                        continuation.resume(newAccessToken)
                    }
                } else {
                    Log.e("DexcomTokenManager", "Token refresh failed: ${authException?.message}", authException)
                    managerScope.launch {
                        dataStoreManager.saveDexcomAccessToken(null)
                        dataStoreManager.saveDexcomRefreshToken(null)
                        Log.d("DexcomTokenManager", "Tokens cleared from DataStore.")
                        continuation.resumeWithException(authException ?: Exception("Unknown token refresh error"))
                    }
                }
            }
        }
    }

    // Don't forget to dispose the authService when the manager is no longer needed
    fun dispose() {
        authService.dispose()
    }
}