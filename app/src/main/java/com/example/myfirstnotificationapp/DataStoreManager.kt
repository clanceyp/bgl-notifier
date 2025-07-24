// DataStoreManager.kt
package com.example.myfirstnotificationapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey // Import for booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// At the top level of your file, define the DataStore instance
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class DataStoreManager(private val context: Context) {

    // Keys for your preferences
    private object PreferencesKeys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val EVENT_FREQUENCY = intPreferencesKey("event_frequency")
        // NEW: Key for notifications enabled
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")

        // NEW: Keys for Dexcom Access and Refresh Tokens
        val DEXCOM_ACCESS_TOKEN = stringPreferencesKey("dexcom_access_token")
        val DEXCOM_REFRESH_TOKEN = stringPreferencesKey("dexcom_refresh_token")
    }

    // Define your fixed API path
    private val API_PATH = "/api/v1/entries"

    // Flow to observe BASE URL changes
    val baseUrlFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.BASE_URL] ?: ""
        }

    // Flow that provides the complete, constructed URL for API calls
    val fullUrlFlow: Flow<String> = baseUrlFlow
        .map { baseUrl ->
            val cleanBaseUrl = baseUrl.trimEnd('/')
            "$cleanBaseUrl$API_PATH"
        }

    // Flow to observe API Key changes
    val apiKeyFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.API_KEY] ?: ""
        }

    // Flow to observe Event Frequency changes
    val eventFrequencyFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.EVENT_FREQUENCY] ?: 10
        }

    // NEW: Flow to observe Notifications Enabled state
    val notificationsEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            // Default to true if the preference is not set (e.g., for first-time users or app updates)
            preferences[PreferencesKeys.NOTIFICATIONS_ENABLED] ?: true
        }

    // NEW: Flow to observe Dexcom Access Token
    val dexcomAccessTokenFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.DEXCOM_ACCESS_TOKEN]
        }

    // NEW: Flow to observe Dexcom Refresh Token
    val dexcomRefreshTokenFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.DEXCOM_REFRESH_TOKEN]
        }

    // Function to save BASE URL
    suspend fun saveBaseUrl(baseUrl: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BASE_URL] = baseUrl.trim()
        }
    }

    // Function to save API Key
    suspend fun saveApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.API_KEY] = apiKey.trim()
        }
    }

    // Function to save Event Frequency
    suspend fun saveEventFrequency(frequency: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.EVENT_FREQUENCY] = frequency
        }
    }

    // NEW: Function to save Notifications Enabled state
    suspend fun saveNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.NOTIFICATIONS_ENABLED] = enabled
        }
    }

    // NEW: Function to save Dexcom Access Token
    suspend fun saveDexcomAccessToken(token: String?) {
        context.dataStore.edit { preferences ->
            if (token != null) {
                preferences[PreferencesKeys.DEXCOM_ACCESS_TOKEN] = token
            } else {
                preferences.remove(PreferencesKeys.DEXCOM_ACCESS_TOKEN)
            }
        }
    }

    // NEW: Function to save Dexcom Refresh Token
    suspend fun saveDexcomRefreshToken(token: String?) {
        context.dataStore.edit { preferences ->
            if (token != null) {
                preferences[PreferencesKeys.DEXCOM_REFRESH_TOKEN] = token
            } else {
                preferences.remove(PreferencesKeys.DEXCOM_REFRESH_TOKEN)
            }
        }
    }
}