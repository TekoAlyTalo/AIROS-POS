package com.airos.pos.core.common

import java.text.NumberFormat
import java.util.Locale

object CentsFormatter {
    fun format(cents: Int, locale: Locale = Locale("fi", "FI")): String {
        val formatter = NumberFormat.getCurrencyInstance(locale)
        return formatter.format(cents / 100.0)
    }
}
