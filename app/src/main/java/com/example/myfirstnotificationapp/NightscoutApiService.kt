package com.example.myfirstnotificationapp

import android.util.Log
import com.example.myfirstnotificationapp.GlucoseConstants.convertToMMOL
import com.example.myfirstnotificationapp.NightscoutEntry
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant // Assuming you need this for parsing Nightscout data
import java.util.concurrent.TimeUnit


class NightscoutApiService(
    private val httpClient: OkHttpClient,
    private val gson: Gson
) {


    suspend fun fetchEgvAsMap(fullUrl: String, apiKey: String): NightscoutEntry = withContext(Dispatchers.IO) {
        val emptyMap = NightscoutEntry(
            sgv = null,
            dateTimestamp = null,
            dateString = null,
            direction = null,
            trend = null
        )
        if (fullUrl.isBlank() || apiKey.isBlank()) {
            Log.w("NightscoutApiService", "URL or API Key is blank for SGV fetch.")
            return@withContext emptyMap
        }

        val requestUrl = "$fullUrl.json?count=1&sort$-date&token=$apiKey"
        Log.d("NightscoutApiService", "URL: $requestUrl")

        val request = Request.Builder()
            .url(requestUrl)
            .build()

        try {
            Log.d("NightscoutApiService", "URL: newCall(request).execute")
            val response = httpClient.newCall(request).execute()
            Log.d("NightscoutApiService", "await response")
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val entries = gson.fromJson(responseBody, Array<NightscoutEntry>::class.java).toList()

                Log.d("NightscoutApiService", "Nightscout Raw Response: $responseBody")
                // Log.d("NightscoutApiService", "entries: $responseBody")

                if (entries.isNotEmpty()) {
                    val latestSgv = entries.first().sgv
                    val latestSgvMmol = latestSgv?.let { convertToMMOL(it) }
                    Log.d("NightscoutApiService", "Fetched SGV (mg/dL): $latestSgv, (mmol/L): $latestSgvMmol")

                    return@withContext NightscoutEntry(
                        sgv = entries.first().sgv,
                        dateTimestamp = entries.first().dateTimestamp,
                        dateString = entries.first().dateString,
                        direction = entries.first().direction,
                        trend = entries.first().trend
                    )
                } else {
                    Log.w("NightscoutApiService", "Nightscout API returned no entries.")
                    return@withContext emptyMap
                }
            } else {
                Log.e("NightscoutApiService", "Failed to fetch SGV: ${response.code} - ${response.message}")
                return@withContext emptyMap
            }
            // Log.d("NightscoutApiService", "await response failed")
        } catch (e: Exception) {
            Log.e("NightscoutApiService", "Exception during SGV fetch: ${e.message}", e)
            return@withContext emptyMap
        }
    }

    // Function to fetch the latest SGV value
    suspend fun fetchSgvValue(fullUrl: String, apiKey: String): Int? = withContext(Dispatchers.IO) {
        Log.d("NightscoutApiService", "fetchSgvValue")
        if (fullUrl.isBlank() || apiKey.isBlank()) {
            Log.w("NightscoutApiService", "URL or API Key is blank for SGV fetch.")
            return@withContext null
        }

        val latestSgv = fetchEgvAsMap(fullUrl, apiKey).sgv
        Log.d("NightscoutApiService", latestSgv.toString())
        return@withContext latestSgv;
    }

    suspend fun fetchSgvMap(fullUrl: String, apiKey: String): NightscoutEntry? = withContext(Dispatchers.IO) {
        Log.d("NightscoutApiService", "fetchSgvValue")
        if (fullUrl.isBlank() || apiKey.isBlank()) {
            Log.w("NightscoutApiService", "URL or API Key is blank for SGV fetch.")
            return@withContext null
        }
        val map = fetchEgvAsMap(fullUrl, apiKey);
        Log.d("NightscoutApiService", map.toString())
        return@withContext NightscoutEntry(
            sgv = map.sgv,
            dateTimestamp = map.dateTimestamp,
            dateString = map.dateString,
            direction = map.direction,
            trend = map.trend
        );
    }

    // Function to check if the Nightscout connection is valid
// This will attempt to fetch a small amount of data, which is more reliable than /status.json
    suspend fun checkConnection(baseUrl: String, apiKey: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            Log.d("NightscoutApiService", "checkConnection settings not found")
            return@withContext false
        }
        Log.d("NightscoutApiService", "checkConnection")

        // Assuming your DataStoreManager provides fullUrlFlow which includes /api/v1/entries
        // If not, you'll need to construct it here.
        val fullUrl = "$baseUrl/api/v1/entries" // Assuming /api/v1/entries is the base path for data

        try {
            // Try to fetch just one entry to confirm connection
            val sgv = fetchSgvValue(fullUrl, apiKey)
            Log.d("NightscoutApiService", "Nightscout connection found")
            return@withContext sgv != null // If we got an SGV, connection is good
        } catch (e: Exception) {
            Log.e("NightscoutApiService", "Nightscout connection check failed: ${e.message}", e)
            return@withContext false
        }
    }
}