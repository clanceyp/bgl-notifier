package com.example.myfirstnotificationapp

import java.util.Locale
import kotlin.math.roundToInt

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
}
