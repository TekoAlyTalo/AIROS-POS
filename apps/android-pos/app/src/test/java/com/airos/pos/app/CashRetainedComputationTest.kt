package com.airos.pos.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CashRetainedComputationTest {

    @Test
    fun overpaidCashSubtractsChangeOnceFromTendered() {
        // 9,00 EUR sale, 10,00 EUR tendered, 1,00 EUR change -> drawer +9,00 EUR.
        val result = computeRetainedCashCents(cashTenderedCents = 1000, changeCents = 100)
        assertThat(result).isEqualTo(900)
    }

    @Test
    fun exactCashKeepsFullTenderedAmount() {
        // 15,00 EUR sale, 15,00 EUR tendered, 0 change -> drawer +15,00 EUR.
        val result = computeRetainedCashCents(cashTenderedCents = 1500, changeCents = 0)
        assertThat(result).isEqualTo(1500)
    }

    @Test
    fun nullTenderedMeansNoCashIntoDrawer() {
        // Card / voucher only payments must never claim a cash drawer increase.
        val result = computeRetainedCashCents(cashTenderedCents = null, changeCents = 0)
        assertThat(result).isEqualTo(0)
    }

    @Test
    fun negativeChangeIsClampedToZero() {
        val result = computeRetainedCashCents(cashTenderedCents = 1000, changeCents = -200)
        assertThat(result).isEqualTo(1000)
    }

    @Test
    fun changeExceedingTenderedClampsToZero() {
        // Defensive: never produce a negative drawer delta from a cash sale.
        val result = computeRetainedCashCents(cashTenderedCents = 500, changeCents = 1000)
        assertThat(result).isEqualTo(0)
    }
}

