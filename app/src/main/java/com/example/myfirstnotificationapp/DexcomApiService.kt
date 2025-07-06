package com.example.myfirstnotificationapp.dexcom

import android.util.Log
import com.example.myfirstnotificationapp.DataStoreManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant // Requires Java 8+ or Android API 26+
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Data classes for parsing Dexcom API responses
data class DexcomTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("scope") val scope: String
)

data class DexcomEgvResponse(
    @SerializedName("egvs") val egvs: List<Egv>
)

data class Egv(
    @SerializedName("systemTime") val systemTime: String,
    @SerializedName("displayTime") val displayTime: String,
    @SerializedName("value") val value: Int,
    @SerializedName("status") val status: String?,
    @SerializedName("trend") val trend: String?,
    @SerializedName("trendRate") val trendRate: Double?
)

class DexcomApiService(
    private val httpClient: OkHttpClient,
    private val gson: Gson,
    private val dataStoreManager: DataStoreManager // To save/retrieve tokens
) {
    // Dexcom OAuth Configuration (should match what's in SettingsActivity)
    private val DEXCOM_CLIENT_ID = "r9TqywzRpj0gbruswIXH2wkxt9bvrCno"
    private val DEXCOM_CLIENT_SECRET = "AGwRRfKdwjDEQRnE" // <--- IMPORTANT: Replace with your actual Client Secret
// In a real app, you might want to fetch this from a secure build config or server.
// NEVER hardcode in a production app if it's client-side only.

    // OAuth endpoints for Sandbox (adjust for production if needed)
    private val DEXCOM_TOKEN_ENDPOINT = "https://sandbox-api.dexcom.com/v2/oauth2/token"
    private val DEXCOM_EGV_ENDPOINT = "https://sandbox-api.dexcom.com/v2/users/self/egvs"

    // Function to refresh the access token
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = dataStoreManager.dexcomRefreshTokenFlow.first()
        if (refreshToken == null) {
            Log.w("DexcomApiService", "No refresh token available.")
            return@withContext false
        }

        val formBody = FormBody.Builder()
            .add("client_id", DEXCOM_CLIENT_ID)
            .add("client_secret", DEXCOM_CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(DEXCOM_TOKEN_ENDPOINT)
            .post(formBody)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val tokenResponse = gson.fromJson(responseBody, DexcomTokenResponse::class.java)

                dataStoreManager.saveDexcomAccessToken(tokenResponse.accessToken)
                dataStoreManager.saveDexcomRefreshToken(tokenResponse.refreshToken) // Save new refresh token if provided
                Log.d("DexcomApiService", "Access token refreshed successfully.")
                return@withContext true
            } else {
                Log.e("DexcomApiService", "Failed to refresh token: ${response.code} - ${response.message}")
                // If refresh fails, it means the refresh token is invalid/expired.
                // Clear all tokens to force re-login.
                dataStoreManager.saveDexcomAccessToken(null)
                dataStoreManager.saveDexcomRefreshToken(null)
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("DexcomApiService", "Exception during token refresh: ${e.message}", e)
            return@withContext false
        }
    }

    // Function to get the latest EGV (Estimated Glucose Value)
    suspend fun getLatestEgv(): Egv? = withContext(Dispatchers.IO) {
        var accessToken = dataStoreManager.dexcomAccessTokenFlow.first()

        // Check if access token is null or potentially expired (simple check, not robust)
        // A more robust check would involve storing token expiry time.
        // For now, we'll just try to refresh if it's null.
        if (accessToken == null) {
            Log.d("DexcomApiService", "Access token is null, attempting to refresh.")
            if (!refreshAccessToken()) {
                Log.e("DexcomApiService", "Failed to refresh token, cannot get EGV.")
                return@withContext null
            }
            accessToken = dataStoreManager.dexcomAccessTokenFlow.first() // Get the new token
            if (accessToken == null) {
                Log.e("DexcomApiService", "Access token still null after refresh attempt.")
                return@withContext null
            }
        }

        // Dexcom API requires a date range. Fetching a small range around now.
        val now = Instant.now()
        val endDate = now.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
        val startDate = now.minusSeconds(300).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT) // Last 5 minutes

        val url = "$DEXCOM_EGV_ENDPOINT?startDate=$startDate&endDate=$endDate"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val egvResponse = gson.fromJson(responseBody, DexcomEgvResponse::class.java)
                Log.d("DexcomApiService", "EGV data received: ${egvResponse.egvs.size} entries.")
                return@withContext egvResponse.egvs.maxByOrNull { Instant.parse(it.systemTime) } // Get the latest by systemTime
            } else if (response.code == 401) {
                Log.w("DexcomApiService", "EGV request failed with 401. Attempting token refresh.")
                // Token might be expired. Try to refresh and retry the request.
                if (refreshAccessToken()) {
                    Log.d("DexcomApiService", "Token refreshed, retrying EGV request.")
                    // Recursive call (be careful with recursion depth, but for 1 retry it's fine)
                    return@withContext getLatestEgv()
                } else {
                    Log.e("DexcomApiService", "Failed to refresh token after 401, cannot get EGV.")
                    return@withContext null
                }
            } else {
                Log.e("DexcomApiService", "Failed to get EGV: ${response.code} - ${response.message}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("DexcomApiService", "Exception during EGV request: ${e.message}", e)
            return@withContext null
        }
    }
}