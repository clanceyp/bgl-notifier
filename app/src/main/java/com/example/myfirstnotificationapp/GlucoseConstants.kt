package com.example.myfirstnotificationapp

import android.util.Log
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.text.format

object GlucoseConstants { // Using an 'object' for namespacing
    const val MG_DL_TO_MMOL_L_CONVERSION_FACTOR = 18.018f
    const val MG_DL_THRESHOLD_FOR_CONVERSION = 30
    fun convertToMMOLIfOverThreshHold(n: Int): Int {
        if (n > MG_DL_THRESHOLD_FOR_CONVERSION) {
            return convertToMMOL(n)
        } else {
            return n
        }
    }
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
    fun parseIsoTimestampToMillis(isoTimestamp: String?): Long? {
        if (isoTimestamp.isNullOrBlank()) {
            return null
        }
        return try {
            java.time.Instant.parse(isoTimestamp).toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            // Log the error or handle it as appropriate for your app
            kotlin.io.println("Error parsing timestamp: '$isoTimestamp' - ${e.message}")
            null
        }
    }
    fun formatTimeAgo(isoTimestamp: String): String {
        val egvTimestampMillis = parseIsoTimestampToMillis(isoTimestamp)

        if (egvTimestampMillis == null) {
            return "Invalid time" // Or "Unknown time", or handle as an error
        }
        val nowMillis = java.lang.System.currentTimeMillis()
        val diffMillis = kotlin.math.abs(nowMillis - egvTimestampMillis) // Use abs in case of slight clock differences

        if (diffMillis < 0) { // Should not happen if egvTimestampMillis is in the past
            return "just now"
        }

        val seconds = TimeUnit.MILLISECONDS.toSeconds(diffMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMillis)
        val days = TimeUnit.MILLISECONDS.toDays(diffMillis)

        return when {
            seconds < 60 -> "just now"
            minutes == 1L -> "1 minute ago"
            minutes < 60 -> "$minutes minutes ago"
            hours == 1L -> "1 hour ago"
            hours < 24 -> "$hours hours ago"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            else -> {
                // For older dates, you might want to show the actual date
                // This is a simple fallback, you can format it more nicely
                val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                "on ${sdf.format(java.util.Date(egvTimestampMillis))}"
            }
        }
    }
}
