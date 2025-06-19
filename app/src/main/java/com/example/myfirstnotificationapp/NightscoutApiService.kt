// NightscoutApiService.kt
package com.example.myfirstnotificationapp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken // Import TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NightscoutApiService(private val client: OkHttpClient, private val gson: Gson) {

    suspend fun fetchSgvValue(fullUrl: String, apiKey: String): Int {
        val requestUrl = "$fullUrl?token=$apiKey"

        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Accept", "application/json")
            .build()

        return try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                Log.d("NightscoutApiService", "Raw JSON Response: $responseBody")

                if (responseBody.isNullOrEmpty()) {
                    Log.e("NightscoutApiService", "Empty response body")
                    return 0
                }

                // --- FIX IS HERE ---
                // Define the type token for List<NightscoutEntry>
                val listType = object : TypeToken<List<NightscoutEntry>>() {}.type
                val entries: List<NightscoutEntry> = gson.fromJson(responseBody, listType)
                // --- END FIX ---

                if (entries.isNotEmpty()) {
                    val firstEntry = entries[0]
                    val sgv = firstEntry.sgv

                    if (sgv != null) {
                        val processedSgv = sgv / 18
                        if (processedSgv >= 16) {
                            15
                        } else {
                            processedSgv
                        }
                    } else {
                        Log.e("NightscoutApiService", "SGV value is null in the first entry.")
                        0
                    }
                } else {
                    Log.e("NightscoutApiService", "No entries found in the response.")
                    0
                }
            } else {
                Log.e("NightscoutApiService", "HTTP Error: ${response.code} - ${response.message}")
                0
            }
        } catch (e: IOException) {
            Log.e("NightscoutApiService", "Network error: ${e.message}", e)
            0
        } catch (e: Exception) {
            Log.e("NightscoutApiService", "JSON parsing or unexpected error: ${e.message}", e)
            0
        }
    }
}