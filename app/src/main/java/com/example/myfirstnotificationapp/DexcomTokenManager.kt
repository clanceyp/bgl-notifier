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
import kotlinx.coroutines.CoroutineScope // Import CoroutineScope
import kotlinx.coroutines.Dispatchers // Import Dispatchers
import kotlinx.coroutines.launch // Import launch

class DexcomTokenManager(private val context: Context) {
    private val dataStoreManager = DataStoreManager(context)
    private val authService = AuthorizationService(context) // Re-use the AppAuth service

    // Dexcom OAuth Configuration (should match what you use in SettingsActivity)
    private val DEXCOM_CLIENT_ID = "r9TqywzRpj0gbruswIXH2wkxt9bvrCno"
    private val DEXCOM_TOKEN_ENDPOINT = "https://sandbox-api.dexcom.com/v2/oauth2/token"
    private val managerScope = CoroutineScope(Dispatchers.IO) // Or Dispatchers.Default

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://sandbox-api.dexcom.com/v2/oauth2/login"), // Auth endpoint
        Uri.parse(DEXCOM_TOKEN_ENDPOINT), // Token endpoint
        Uri.parse(DEXCOM_TOKEN_ENDPOINT) // JWKS URI (often same as token endpoint for Dexcom)
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
                DEXCOM_CLIENT_ID
            )
                .setGrantType("refresh_token")
                .setRefreshToken(refreshToken)
                .build()

            val clientAuthentication = ClientSecretPost(DEXCOM_CLIENT_ID)

            authService.performTokenRequest(tokenRequest, clientAuthentication) { tokenResponse, authException ->
                if (tokenResponse != null) {
                    val newAccessToken = tokenResponse.accessToken
                    val newRefreshToken = tokenResponse.refreshToken ?: refreshToken

                    val expiresInSeconds = tokenResponse.additionalParameters["expires_in"]?.toLongOrNull()
                    val accessTokenExpiresAt: Long? = expiresInSeconds?.let {
                        System.currentTimeMillis() + it * 1000
                    }

                    Log.d("DexcomTokenManager", "Token refresh successful.")

                    // FIX: Launch a coroutine to call suspend functions
                    managerScope.launch {
                        dataStoreManager.saveDexcomAccessToken(newAccessToken)
                        dataStoreManager.saveDexcomRefreshToken(newRefreshToken)
                        // dataStoreManager.saveAccessTokenExpiry(accessTokenExpiresAt) // Save expiry if you implement it
                        Log.d("DexcomTokenManager", "Tokens saved to DataStore.")
                        // Resume the continuation AFTER saving, to ensure data is persisted
                        continuation.resume(newAccessToken)
                    }
                } else {
                    Log.e("DexcomTokenManager", "Token refresh failed: ${authException?.message}", authException)
                    // FIX: Launch a coroutine to call suspend functions
                    managerScope.launch {
                        dataStoreManager.saveDexcomAccessToken(null)
                        dataStoreManager.saveDexcomRefreshToken(null)
                        Log.d("DexcomTokenManager", "Tokens cleared from DataStore.")
                        // Resume the continuation AFTER clearing
                        continuation.resumeWithException(authException ?: Exception("Unknown token refresh error"))
                    }
                }
            }
        }
    }
}