// NightscoutModels.kt
package com.example.myfirstnotificationapp

import com.google.gson.annotations.SerializedName

// Represents a single entry from the Nightscout API
data class NightscoutEntry(
    @SerializedName("sgv") val sgv: Int?,
    @SerializedName("date") val dateTimestamp: Long?, // Epoch ms
    @SerializedName("dateString") val dateString: String?, // ISO Date String
    @SerializedName("direction") val direction: String?, // Direction
    @SerializedName("trend") val trend: Int?, // Trend
)

// The top-level response is an array of NightscoutEntry objects
typealias NightscoutResponse = List<NightscoutEntry>