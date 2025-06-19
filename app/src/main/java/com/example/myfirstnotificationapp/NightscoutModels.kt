// NightscoutModels.kt
package com.example.myfirstnotificationapp

import com.google.gson.annotations.SerializedName

// Represents a single entry from the Nightscout API
data class NightscoutEntry(
    @SerializedName("sgv") val sgv: Int?, // sgv can be null if not available
// Add other fields if you need them, e.g.,
// @SerializedName("date") val date: Long?,
// @SerializedName("direction") val direction: String?
)

// The top-level response is an array of NightscoutEntry objects
typealias NightscoutResponse = List<NightscoutEntry>