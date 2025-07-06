package com.example.myfirstnotificationapp

import android.util.Log
import com.example.myfirstnotificationapp.NightscoutEntry
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant // Assuming you need this for parsing Nightscout data


class NightscoutApiService(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {
    // Function to fetch the latest SGV value
    suspend fun fetchSgvValue(fullUrl: String, apiKey: String): Int? = withContext(Dispatchers.IO) {
        if (fullUrl.isBlank() || apiKey.isBlank()) {
            Log.w("NightscoutApiService", "URL or API Key is blank for SGV fetch.")
            return@withContext null
        }

        // Nightscout API path for entries (usually /api/v1/entries)
        // Ensure fullUrl already includes the base path, e.g., "https://your.nightscout.site/api/v1/entries"
        // The count=1 and sort$-date are to get the latest entry
        val requestUrl = "$fullUrl.json?count=1&sort$-date&token=$apiKey"
        Log.d("NightscoutApiService", "URL: $requestUrl")

        val request = Request.Builder()
            .url(requestUrl)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val entries = gson.fromJson(responseBody, Array<NightscoutEntry>::class.java).toList()

                Log.d("NightscoutApiService", "Nightscout Raw Response: $responseBody")
                // Log.d("NightscoutApiService", "entries: $responseBody")

                if (entries.isNotEmpty()) {
                    val latestSgv = entries.first().sgv
                    val latestSgvMmol = latestSgv?.div(18) // Calculate mmol/L
                    Log.d("NightscoutApiService", "Fetched SGV (mg/dL): $latestSgv, (mmol/L): $latestSgvMmol")

                    return@withContext latestSgvMmol
                } else {
                    Log.w("NightscoutApiService", "Nightscout API returned no entries.")
                    return@withContext null
                }
            } else {
                Log.e("NightscoutApiService", "Failed to fetch SGV: ${response.code} - ${response.message}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("NightscoutApiService", "Exception during SGV fetch: ${e.message}", e)
            return@withContext null
        }
    }

    // Function to check if the Nightscout connection is valid
// This will attempt to fetch a small amount of data, which is more reliable than /status.json
    suspend fun checkConnection(baseUrl: String, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return@withContext false
        }

        // Assuming your DataStoreManager provides fullUrlFlow which includes /api/v1/entries
        // If not, you'll need to construct it here.
        val fullUrl = "$baseUrl/api/v1/entries" // Assuming /api/v1/entries is the base path for data

        try {
            // Try to fetch just one entry to confirm connection
            val sgv = fetchSgvValue(fullUrl, apiKey)
            return@withContext sgv != null // If we got an SGV, connection is good
        } catch (e: Exception) {
            Log.e("NightscoutApiService", "Nightscout connection check failed: ${e.message}", e)
            return@withContext false
        }
    }
}