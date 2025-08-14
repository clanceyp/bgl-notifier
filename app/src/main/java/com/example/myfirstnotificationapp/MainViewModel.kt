package com.example.myfirstnotificationapp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myfirstnotificationapp.dexcom.DexcomApiService
import com.example.myfirstnotificationapp.dexcom.Egv
import com.example.myfirstnotificationapp.NightscoutApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import com.google.gson.Gson

class MainViewModel(
    private val dataStoreManager: DataStoreManager,
    private val dexcomApiService: DexcomApiService,
    private val nightscoutApiService: NightscoutApiService
) : ViewModel() {

    private val _isNightscoutConnected = MutableStateFlow(false)
    val isNightscoutConnected: StateFlow<Boolean> = _isNightscoutConnected.asStateFlow()

    private val _isLoadingInitialData = MutableStateFlow(true)
    val isLoadingInitialData: StateFlow<Boolean> = _isLoadingInitialData.asStateFlow()

    private val _nightscoutSgvValue = MutableStateFlow<Int?>(null)
    val nightscoutSgvValue: StateFlow<Int?> = _nightscoutSgvValue.asStateFlow()

    private val _nightscoutEgvValue = MutableStateFlow<NightscoutEntry?>(null)
    val nightscoutEgvValue: StateFlow<NightscoutEntry?> = _nightscoutEgvValue.asStateFlow()

    private val _dexcomLoginStatus = MutableStateFlow("Not logged in to Dexcom")
    val dexcomLoginStatus: StateFlow<String> = _dexcomLoginStatus.asStateFlow()

    private val _latestEgv = MutableStateFlow<Egv?>(null)
    val latestEgv: StateFlow<Egv?> = _latestEgv.asStateFlow()

    private val _isDexcomDataLoading = MutableStateFlow(false)
    val isDexcomDataLoading: StateFlow<Boolean> = _isDexcomDataLoading.asStateFlow()

    init {
        viewModelScope.launch {
            _isLoadingInitialData.value = true // Start loading indicator

            // --- Nightscout Initial Check ---
            // This should run immediately when the ViewModel is created
            try {
                val baseUrl = dataStoreManager.baseUrlFlow.first()
                val apiKey = dataStoreManager.apiKeyFlow.first()
                val fullUrl = dataStoreManager.fullUrlFlow.first()
                Log.d("MainViewModel", "Initial Nightscout check: URL=$baseUrl, APIKey=${apiKey.length} chars")
                val connected = nightscoutApiService.checkConnection(baseUrl, apiKey)
                val sgvValueForNotification = nightscoutApiService.fetchSgvValue(fullUrl, apiKey)
                val egvValueForNotification = nightscoutApiService.fetchEgvAsMap(fullUrl, apiKey)
                Log.d("MainViewModel", "_isNightscoutConnected: ${connected}")
                _isNightscoutConnected.value = connected
                _nightscoutSgvValue.value = sgvValueForNotification
                _nightscoutEgvValue.value = egvValueForNotification
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error during initial Nightscout check: ${e.message}", e)
                _isNightscoutConnected.value = false
            }

            // --- Dexcom Token Observation ---
            // This collect block will run indefinitely, updating the UI as tokens change
            dataStoreManager.dexcomAccessTokenFlow.collect { token ->
                _dexcomLoginStatus.value = if (token != null) "Logged in to Dexcom" else "Not logged in to Dexcom"
                Log.d("MainViewModel", "Dexcom token status updated: ${if (token != null) "present" else "null"}")
                if (token == null) {
                    _latestEgv.value = null
                }
                // Only fetch Dexcom data if logged in and not already loading
                if (token != null && !_isDexcomDataLoading.value) {
                    fetchLatestDexcomEgv()
                }
            }

            // After initial checks, set loading to false
            _isLoadingInitialData.value = false
        }
    }

    // Public function to re-check Nightscout connection (e.g., called from onResume)
// This function is still useful if settings are changed and you want to force a re-check
    fun recheckNightscoutConnection() {
        viewModelScope.launch {
            _isLoadingInitialData.value = true // Indicate loading for this re-check
            try {
                val baseUrl = dataStoreManager.baseUrlFlow.first()
                val apiKey = dataStoreManager.apiKeyFlow.first()
                Log.d("MainViewModel", "Re-checking Nightscout: URL=$baseUrl, APIKey=${apiKey.length} chars")
                val connected = nightscoutApiService.checkConnection(baseUrl, apiKey)
                Log.d("MainViewModel", "_isNightscoutConnected $connected")
                _isNightscoutConnected.value = connected
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error during Nightscout recheck: ${e.message}", e)
                _isNightscoutConnected.value = false
            } finally {
                Log.d("MainViewModel", "_isNightscoutConnected finally setting to false")
                _isLoadingInitialData.value = false // Done with re-check
            }
        }
    }

    fun fetchLatestDexcomEgv() {
        viewModelScope.launch {
            Log.d("MainViewModel", "loading data")
            _isDexcomDataLoading.value = true
            try {
                val egv = dexcomApiService.getLatestEgv()
                _latestEgv.value = egv
                if (egv == null) {
                    Log.w("MainViewModel", "Failed to fetch latest EGV from Dexcom.")
                } else {
                    Log.d("MainViewModel", "Latest EGV: ${egv.value} at ${egv.displayTime}")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching Dexcom EGV: ${e.message}", e)
                _latestEgv.value = null
            } finally {
                Log.d("MainViewModel", "loading finally")
                _isDexcomDataLoading.value = false
            }
        }
    }

    class Factory(
        private val dataStoreManager: DataStoreManager,
        private val dexcomApiService: DexcomApiService,
        private val nightscoutApiService: NightscoutApiService
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(dataStoreManager, dexcomApiService, nightscoutApiService) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}