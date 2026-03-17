package com.airos.pos.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class CentsFormatterTest {
    @Test
    fun format_formatsGrossAmountFromCents() {
        assertThat(CentsFormatter.format(1234, Locale.US)).isEqualTo("$12.34")
    }
}
