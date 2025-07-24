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
    private val nightscoutApiService: NightscoutApiService // NEW: Inject NightscoutApiService
) : ViewModel() {

    private val _isNightscoutConnected = MutableStateFlow(false)
    val isNightscoutConnected: StateFlow<Boolean> = _isNightscoutConnected.asStateFlow()

    private val _isLoadingInitialData = MutableStateFlow(true)
    val isLoadingInitialData: StateFlow<Boolean> = _isLoadingInitialData.asStateFlow()

    private val _dexcomLoginStatus = MutableStateFlow("Not logged in to Dexcom")
    val dexcomLoginStatus: StateFlow<String> = _dexcomLoginStatus.asStateFlow()

    private val _latestEgv = MutableStateFlow<Egv?>(null)
    val latestEgv: StateFlow<Egv?> = _latestEgv.asStateFlow()

    private val _isDexcomDataLoading = MutableStateFlow(false)
    val isDexcomDataLoading: StateFlow<Boolean> = _isDexcomDataLoading.asStateFlow()

    init {
        viewModelScope.launch {
            _isLoadingInitialData.value = true

            // Observe Dexcom access token to update login status and trigger data fetch
            // This collect block will run indefinitely, updating the UI as tokens change
            dataStoreManager.dexcomAccessTokenFlow.collect { token ->
                _dexcomLoginStatus.value = if (token != null) "Logged in to Dexcom" else "Not logged in to Dexcom"
                Log.d("MainViewModel", "_dexcomLoginStatus.value $_dexcomLoginStatus.value")
                Log.d("MainViewModel", "token $token")
                if (token == null) {
                    _latestEgv.value = null
                }
                if (token != null && !_isDexcomDataLoading.value) {
                    fetchLatestDexcomEgv()
                }
            }
        }
        // Initial check for Nightscout will be done via recheckNightscoutConnection()
        // which is called from MainActivity's onResume.
    }

    // Public function to re-check Nightscout connection (e.g., called from onResume)
    fun recheckNightscoutConnection() {
        viewModelScope.launch {
            _isLoadingInitialData.value = true // Indicate loading for this re-check
            try {
                val baseUrl = dataStoreManager.baseUrlFlow.first()
                val apiKey = dataStoreManager.apiKeyFlow.first()
                // Use the NightscoutApiService to check connection
                val connected = nightscoutApiService.checkConnection(baseUrl, apiKey)
                _isNightscoutConnected.value = connected
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error during Nightscout recheck: ${e.message}", e)
                _isNightscoutConnected.value = false
            } finally {
                _isLoadingInitialData.value = false // Done with re-check
            }
        }
    }

    fun fetchLatestDexcomEgv() {
        viewModelScope.launch {
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
                _isDexcomDataLoading.value = false
            }
        }
    }

    class Factory(
        private val dataStoreManager: DataStoreManager,
        private val dexcomApiService: DexcomApiService,
        private val nightscoutApiService: NightscoutApiService // NEW: Pass NightscoutApiService
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