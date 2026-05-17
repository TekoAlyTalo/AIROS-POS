package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.PlannedStaffShift
import com.airos.pos.core.model.ShiftScheduleDay
import com.airos.pos.core.model.ShiftScheduleOperationalDay
import com.airos.pos.core.model.ShiftSchedulePublicationStatus
import com.airos.pos.core.model.ShiftScheduleSnapshot
import com.airos.pos.domain.ShiftScheduleRepository
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BackendShiftScheduleRepository(
    private val backendBaseUrlProvider: () -> String,
    private val restaurantKeyProvider: () -> String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : ShiftScheduleRepository {
    override suspend fun fetchPosSchedule(
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): PosResult<ShiftScheduleSnapshot> = withContext(Dispatchers.IO) {
        request(
            path = "/api/shift-schedule/pos",
            dateFrom = dateFrom,
            dateTo = dateTo,
        )
    }

    override suspend fun fetchOwnShifts(
        staffId: String,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): PosResult<ShiftScheduleSnapshot> = withContext(Dispatchers.IO) {
        val normalizedStaffId = staffId.trim()
        if (normalizedStaffId.isBlank()) {
            return@withContext PosResult.Failure("No active staff id available for own shifts")
        }
        request(
            path = "/api/shift-schedule/own/${urlEncode(normalizedStaffId)}",
            dateFrom = dateFrom,
            dateTo = dateTo,
        )
    }

    private fun request(
        path: String,
        dateFrom: LocalDate,
        dateTo: LocalDate,
    ): PosResult<ShiftScheduleSnapshot> {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return PosResult.Failure("Backend URL is empty. Check terminal settings.")
        }

        val restaurantKey = restaurantKeyProvider().trim().ifBlank { "ravintola_default" }
        val query = "restaurant_key=${urlEncode(restaurantKey)}" +
            "&date_from=${urlEncode(dateFrom.toString())}" +
            "&date_to=${urlEncode(dateTo.toString())}"

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$baseUrl$path?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }

            val statusCode = connection.responseCode
            val body = readScheduleStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                val detail = extractScheduleDetail(body)
                return PosResult.Failure(
                    "Shift schedule backend rejected the request (HTTP $statusCode): " +
                        detail.ifBlank { body.take(180) },
                )
            }
            PosResult.Success(parseSnapshot(body))
        } catch (t: Throwable) {
            Log.d(
                "AIROS",
                "[BackendShiftScheduleRepository] GET $path failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}",
            )
            PosResult.Failure("Shift schedule request failed: ${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseSnapshot(body: String): ShiftScheduleSnapshot {
        if (body.isBlank()) {
            val today = LocalDate.now()
            return ShiftScheduleSnapshot(
                restaurantKey = restaurantKeyProvider().trim().ifBlank { "ravintola_default" },
                dateFrom = today,
                dateTo = today,
            )
        }
        val root = JSONObject(body)
        return ShiftScheduleSnapshot(
            restaurantKey = root.optString("restaurant_key", restaurantKeyProvider().trim().ifBlank { "ravintola_default" }),
            dateFrom = parseLocalDate(root.requiredString("date_from")),
            dateTo = parseLocalDate(root.requiredString("date_to")),
            days = parseDays(root.optJSONArray("days")),
        )
    }

    private fun parseDays(array: JSONArray?): List<ShiftScheduleDay> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            item.acceptOptionalTimestamp("published_at")
            item.acceptOptionalTimestamp("closed_at")
            ShiftScheduleDay(
                date = parseLocalDate(item.requiredString("business_date", "date")),
                publicationStatus = parsePublicationStatus(
                    item.optString("publication_status").ifBlank { item.optString("status") },
                ),
                operationalDay = parseOperationalDay(item.optJSONObject("operational_day")),
                plannedShifts = parseShifts(
                    item.optJSONArray("planned_shifts") ?: item.optJSONArray("shifts"),
                ),
            )
        }
    }

    private fun parseOperationalDay(item: JSONObject?): ShiftScheduleOperationalDay {
        if (item == null) {
            return missingOperationalDay("operational_day_missing")
        }
        return parseOperationalDayFields(
            truthAvailable = item.optBoolean("truth_available", false),
            opensRaw = item.optNullableString("opens_at"),
            closesRaw = item.optNullableString("closes_at"),
            isClosed = item.optBoolean("is_closed", false),
            missingReason = item.optNullableString("missing_reason"),
            source = item.optNullableString("source"),
        )
    }

    internal fun parseOperationalDayFields(
        truthAvailable: Boolean,
        opensRaw: String?,
        closesRaw: String?,
        isClosed: Boolean,
        missingReason: String?,
        source: String?,
    ): ShiftScheduleOperationalDay {
        if (!truthAvailable) {
            return missingOperationalDay(
                reason = missingReason ?: "operating_hours_unavailable",
                source = source,
            )
        }
        if (isClosed) {
            return ShiftScheduleOperationalDay(
                truthAvailable = true,
                isClosed = true,
                source = source,
            )
        }

        if (opensRaw == null || closesRaw == null) {
            return missingOperationalDay("operating_hours_window_missing", source)
        }

        return runCatching {
            val opensAt = parseLocalDateTime(opensRaw)
            val closesAt = parseLocalDateTime(closesRaw)
            if (!closesAt.isAfter(opensAt)) {
                missingOperationalDay("operating_hours_window_invalid", source)
            } else {
                ShiftScheduleOperationalDay(
                    truthAvailable = true,
                    opensAt = opensAt,
                    closesAt = closesAt,
                    isClosed = false,
                    source = source,
                )
            }
        }.getOrElse {
            missingOperationalDay("operating_hours_window_invalid", source)
        }
    }

    private fun missingOperationalDay(reason: String, source: String? = null): ShiftScheduleOperationalDay {
        return ShiftScheduleOperationalDay(
            truthAvailable = false,
            missingReason = reason,
            source = source,
        )
    }

    private fun parseShifts(array: JSONArray?): List<PlannedStaffShift> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            PlannedStaffShift(
                id = item.optInt("id", 0),
                staffId = item.optString("staff_id"),
                staffName = item.optString("staff_name"),
                startsAt = parseLocalDateTime(item.requiredString("starts_at")),
                endsAt = parseLocalDateTime(item.requiredString("ends_at")),
                role = item.optNullableString("role"),
                status = item.optNullableString("status"),
                source = item.optNullableString("source")
                    ?: item.optNullableString("source_tag")
                    ?: item.optNullableString("created_by_source"),
            )
        }
    }

    private fun parsePublicationStatus(raw: String): ShiftSchedulePublicationStatus {
        return runCatching {
            ShiftSchedulePublicationStatus.valueOf(raw.trim().uppercase())
        }.getOrDefault(ShiftSchedulePublicationStatus.UNPUBLISHED)
    }

    private fun parseLocalDate(raw: String): LocalDate {
        return LocalDate.parse(raw.trim())
    }

    private fun parseOptionalLocalDateTime(raw: String?): LocalDateTime? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return parseLocalDateTime(value)
    }

    private fun parseLocalDateTime(raw: String): LocalDateTime {
        val value = raw.trim()
        return runCatching {
            LocalDateTime.parse(value)
        }.getOrElse {
            OffsetDateTime.parse(value).toLocalDateTime()
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun JSONObject.requiredString(vararg names: String): String {
        names.forEach { name ->
            optNullableString(name)?.let { return it }
        }
        throw IllegalArgumentException("Missing required shift schedule field: ${names.joinToString(" or ")}")
    }

    private fun JSONObject.acceptOptionalTimestamp(name: String) {
        parseOptionalLocalDateTime(optNullableString(name))
    }
}

private fun extractScheduleDetail(body: String): String {
    if (body.isBlank()) return ""
    return try {
        JSONObject(body).optString("detail").ifBlank { body.take(180) }
    } catch (_: Throwable) {
        body.take(180)
    }
}

private fun readScheduleStream(stream: InputStream?): String {
    if (stream == null) return ""
    return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotBlank() }
}
