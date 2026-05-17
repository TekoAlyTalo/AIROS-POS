package com.airos.pos.app

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

class BackendShiftScheduleRepositoryTest {
    private val repository = BackendShiftScheduleRepository(
        backendBaseUrlProvider = { "http://localhost:8000" },
        restaurantKeyProvider = { "ravintola_default" },
    )

    @Test
    fun parseOperationalDayFields_preservesMissingTruthWithoutFallback() {
        val operationalDay = repository.parseOperationalDayFields(
            truthAvailable = false,
            opensRaw = null,
            closesRaw = null,
            isClosed = false,
            missingReason = "restaurant_hours_profile_missing",
            source = null,
        )

        assertThat(operationalDay.truthAvailable).isFalse()
        assertThat(operationalDay.opensAt).isNull()
        assertThat(operationalDay.closesAt).isNull()
        assertThat(operationalDay.missingReason).isEqualTo("restaurant_hours_profile_missing")
    }

    @Test
    fun parseOperationalDayFields_parsesWindow() {
        val operationalDay = repository.parseOperationalDayFields(
            truthAvailable = true,
            opensRaw = "2026-05-20T09:00:00",
            closesRaw = "2026-05-20T23:00:00",
            isClosed = false,
            missingReason = null,
            source = "truth:restaurant_profile:test",
        )

        assertThat(operationalDay.truthAvailable).isTrue()
        assertThat(operationalDay.opensAt).isEqualTo(LocalDateTime.of(2026, 5, 20, 9, 0))
        assertThat(operationalDay.closesAt).isEqualTo(LocalDateTime.of(2026, 5, 20, 23, 0))
        assertThat(operationalDay.isClosed).isFalse()
        assertThat(operationalDay.source).isEqualTo("truth:restaurant_profile:test")
    }

    @Test
    fun parseOperationalDayFields_marksMalformedWindowMissing() {
        val operationalDay = repository.parseOperationalDayFields(
            truthAvailable = true,
            opensRaw = "",
            closesRaw = "2026-05-20T23:00:00",
            isClosed = false,
            missingReason = null,
            source = null,
        )

        assertThat(operationalDay.truthAvailable).isFalse()
        assertThat(operationalDay.opensAt).isNull()
        assertThat(operationalDay.closesAt).isNull()
        assertThat(operationalDay.missingReason).isEqualTo("operating_hours_window_invalid")
    }
}
