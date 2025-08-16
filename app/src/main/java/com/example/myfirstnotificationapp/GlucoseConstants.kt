package com.example.myfirstnotificationapp

import android.util.Log
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.text.format

object GlucoseConstants { // Using an 'object' for namespacing
    const val MG_DL_TO_MMOL_L_CONVERSION_FACTOR = 18.018f
    const val MG_DL_THRESHOLD_FOR_CONVERSION = 30
    fun convertToMMOL(n: Int): Int {
       return  (n / MG_DL_TO_MMOL_L_CONVERSION_FACTOR).roundToInt()
    }
    fun convertToMMOLString(n: Int): String {
        val result = n / MG_DL_TO_MMOL_L_CONVERSION_FACTOR
        return String.format(Locale.UK ,"%.1f", result) // "%.1f" means format as float with 1 decimal place
    }
    fun formatTimestampToHourMinuteLocalDateTimeOrDefault(timestampMillis: Long?): String {
        if (timestampMillis == null) {
            return "00:00"
        }
        try {
            val localDateTime = java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneId.systemDefault())
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            return localDateTime.format(formatter)
        } catch (e: Exception) {
            Log.d("GlucoseConstants", "Error formatting timestamp $timestampMillis (LocalDateTime): ${e.message}")
            return "00:00"
        }
    }
}
