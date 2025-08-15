package com.example.myfirstnotificationapp.dexcom

import android.util.Log
import com.example.myfirstnotificationapp.BuildConfig
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
import java.time.temporal.ChronoUnit

// Data classes for parsing Dexcom API responses
data class DexcomTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("scope") val scope: String
)

data class DexcomEgvResponse(
    @SerializedName("records") val egvs: List<Egv>
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
    private val dataStoreManager: DataStoreManager,
    private val dexcomClientId: String,
    private val dexcomClientSecret: String,
    private val dexcomTokenEndpoint: String,
    private val dexcomEgvEndpoint: String
) {
    // Function to refresh the access token
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = dataStoreManager.dexcomRefreshTokenFlow.first()
        if (refreshToken == null) {
            Log.w("DexcomApiService", "No refresh token available.")
            return@withContext false
        }

        val formBody = FormBody.Builder()
            .add("client_id", dexcomClientId)
            .add("client_secret", dexcomClientSecret)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(dexcomTokenEndpoint)
            .post(formBody)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val tokenResponse = gson.fromJson(responseBody, DexcomTokenResponse::class.java)

                dataStoreManager.saveDexcomAccessToken(tokenResponse.accessToken)
                dataStoreManager.saveDexcomRefreshToken(tokenResponse.refreshToken) // Save new refresh token if provided
                Log.d("DexcomApiService", "Access token refreshed: ${tokenResponse.accessToken.takeLast(24)}")
                Log.d("DexcomApiService", "Refresh token the same? ${tokenResponse.refreshToken == refreshToken}")
                // Log.d("DexcomApiService", "Refresh token; ${tokenResponse.refreshToken}")
                // Log.d("DexcomApiService", "DEBUG: Client ID used for refresh: '$dexcomClientId'")
                // Log.d("DexcomApiService", "DEBUG: Client Secret used for refresh: '$dexcomClientSecret'")
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
                Log.d("DexcomApiService", "Failed to refresh token.")
                Log.e("DexcomApiService", "Failed to refresh token, cannot get EGV.")
                return@withContext null
            }
            accessToken = dataStoreManager.dexcomAccessTokenFlow.first() // Get the new token
            if (accessToken == null) {
                Log.d("DexcomApiService", "Failed to refresh access token.")
                Log.e("DexcomApiService", "Access token still null after refresh attempt.")
                return@withContext null
            }
        }

        // Dexcom API requires a date range. Fetching a small range around now.
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
            .withZone(ZoneOffset.UTC)
        val now = Instant.now()
        val endDate = formatter.format(now)
        val startDate = now.minus(6, ChronoUnit.HOURS).let {
            formatter.format(it.atOffset(ZoneOffset.UTC))
        }
        val url = "$dexcomEgvEndpoint?maxCount=1&startDate=$startDate&endDate=$endDate"

        Log.d("DexcomApiService", url)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                try {
                    val egvResponse = gson.fromJson(responseBody, DexcomEgvResponse::class.java)
                    Log.d("DexcomApiService", "EGV data received: ${egvResponse.egvs.size} entries.")
                    return@withContext egvResponse.egvs.maxByOrNull { Instant.parse(it.systemTime) } // Get the latest by systemTime
                } catch (e: Exception) {
                    Log.e("DexcomApiService", "Failed to parse EGV response: $responseBody")
                    return@withContext null
                }
            } else if (response.code == 401) {
                Log.w("DexcomApiService", "EGV request failed with ${response.code}. Attempting token refresh.")
                // Token might be expired. Try to refresh and retry the request.
                if (refreshAccessToken()) {
                    Log.d("DexcomApiService", "Token refreshed, retrying EGV request.")
                    // Recursive call (be careful with recursion depth, but for 1 retry it's fine)
                    return@withContext getLatestEgv()
                } else {
                    Log.e("DexcomApiService", "Failed to refresh token after ${response.code}, cannot get EGV.")
                    return@withContext null
                }
            } else {
                Log.e("DexcomApiService", "Failed to get EGV: ${response.code} - ${response.message} - ${response.body?.string()}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("DexcomApiService", "Exception during EGV request: ${e.message}", e)
            return@withContext null
        }
    }

    suspend fun getLatestDexcomGlucoseValueAsIntConcise(): Int? {
        val latestEgvObject: Egv? = getLatestEgv()
        return latestEgvObject?.value // Uses the safe call operator ?.
        // If latestEgvObject is null, this expression evaluates to null.
        // Otherwise, it returns latestEgvObject.value (which is an Int).
    }
}