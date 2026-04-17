package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AttendanceEntry
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class WorktimeActiveSession(
    val sessionId: Int,
    val staffId: String,
    val staffName: String,
    val restaurantKey: String? = null,
    val status: String,
    val startedAt: String,
    val startedAtEpochMillis: Long? = null,
)

data class WorktimeAttendanceSyncEvent(
    val eventId: String,
    val ownerAccountId: String?,
    val restaurantKey: String,
    val terminalId: String,
    val staffId: String,
    val staffName: String,
    val action: String,
    val occurredAtEpochMillis: Long,
    val source: String,
    val terminalSequenceNumber: Long,
)

class WorktimeAttendanceClient(
    private val backendBaseUrlProvider: () -> String,
    private val pollIntervalMillis: Long = 15_000L,
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 3_000,
) {
    fun observeAttendance(): Flow<WorktimeAttendanceSnapshot> = flow {
        emit(WorktimeAttendanceSnapshot())
        while (true) {
            when (val snapshot = fetchAttendanceSnapshot()) {
                is PosResult.Success -> emit(snapshot.value)
                is PosResult.Failure -> Unit
            }
            delay(pollIntervalMillis)
        }
    }

    suspend fun fetchAttendanceSnapshot(restaurantKey: String? = null): PosResult<WorktimeAttendanceSnapshot> = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext PosResult.Failure("No backend URL configured")
        val urlString = "$baseUrl/api/worktime/attendance${restaurantQuery(restaurantKey)}"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                Log.d("AIROS", "[WorktimeAttendanceClient] fetch failed status=$statusCode")
                return@withContext PosResult.Failure("Attendance fetch failed (HTTP $statusCode)")
            }
            PosResult.Success(parseAttendance(body))
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] fetch failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            PosResult.Failure("Attendance fetch failed: ${t.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseAttendance(body: String): WorktimeAttendanceSnapshot {
        if (body.isBlank()) return WorktimeAttendanceSnapshot()
        val root = JSONObject(body)
        return WorktimeAttendanceSnapshot(
            currentlyOnSite = parseEntries(root, "currently_on_site"),
            clockedInToday = parseEntries(root, "clocked_in_today"),
        )
    }

    private fun parseEntries(root: JSONObject, key: String): List<AttendanceEntry> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            AttendanceEntry(
                staffId = item.optString("staff_id", ""),
                staffName = item.optString("staff_name", ""),
                status = item.optString("status", ""),
                startedAt = item.optString("started_at", ""),
                durationMinutes = item.optDouble("duration_minutes", 0.0),
            )
        }
    }

    suspend fun clockIn(staffId: String, staffName: String): PosResult<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext PosResult.Failure("No backend URL configured")
        postClockIn(baseUrl, staffId, staffName, event = null)
    }

    suspend fun syncAttendanceEvent(event: WorktimeAttendanceSyncEvent): PosResult<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext PosResult.Failure("No backend URL configured")
        when (val syncResult = postAttendanceEventSync(baseUrl, event)) {
            is PosResult.Success -> {
                if (syncResult.value) {
                    PosResult.Success(Unit)
                } else {
                    postLegacyAttendanceAction(baseUrl, event)
                }
            }
            is PosResult.Failure -> syncResult
        }
    }

    private fun postAttendanceEventSync(
        baseUrl: String,
        event: WorktimeAttendanceSyncEvent,
    ): PosResult<Boolean> {
        val urlString = "$baseUrl/api/worktime/events/sync"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().apply {
                put("events", JSONArray().put(event.toJson()))
            }
            connection.outputStream.use { os ->
                os.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode in 200..299) {
                PosResult.Success(true)
            } else if (statusCode == 404 || statusCode == 405 || statusCode == 409) {
                Log.d("AIROS", "[WorktimeAttendanceClient] event sync endpoint unavailable/conflict status=$statusCode body=$body")
                PosResult.Success(false)
            } else {
                Log.d("AIROS", "[WorktimeAttendanceClient] event sync failed status=$statusCode body=$body")
                PosResult.Failure("Attendance sync failed (HTTP $statusCode)")
            }
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] event sync error: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            PosResult.Failure("Attendance sync failed: ${t.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun clockOut(staffId: String): PosResult<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext PosResult.Failure("No backend URL configured")
        postClockOut(baseUrl, staffId, event = null)
    }

    private fun postLegacyAttendanceAction(baseUrl: String, event: WorktimeAttendanceSyncEvent): PosResult<Unit> {
        return when (event.action) {
            "clock_in" -> postClockIn(baseUrl, event.staffId, event.staffName, event)
            "clock_out" -> postClockOut(baseUrl, event.staffId, event)
            else -> PosResult.Failure("Unsupported attendance action: ${event.action}")
        }
    }

    private fun postClockIn(
        baseUrl: String,
        staffId: String,
        staffName: String,
        event: WorktimeAttendanceSyncEvent?,
    ): PosResult<Unit> {
        val urlString = "$baseUrl/api/worktime/clock-in"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().apply {
                put("staff_id", staffId)
                put("staff_name", staffName)
                put("source", event?.source ?: "android-pos")
                event?.putEventFieldsInto(this)
            }
            connection.outputStream.use { os ->
                os.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode in 200..299) {
                return PosResult.Success(Unit)
            }
            if (statusCode == 409) {
                return when (val activeSession = fetchActiveSessionForStaff(baseUrl, staffId, event?.restaurantKey)) {
                    is PosResult.Success -> {
                        val session = activeSession.value
                        if (session != null) {
                            Log.d(
                                "AIROS",
                                "[WorktimeAttendanceClient] clock-in conflict resolved by active session id=${session.sessionId}",
                            )
                            PosResult.Success(Unit)
                        } else {
                            Log.d("AIROS", "[WorktimeAttendanceClient] clock-in conflict but no active session body=$body")
                            PosResult.Failure("Clock in status could not be confirmed")
                        }
                    }
                    is PosResult.Failure -> {
                        Log.d("AIROS", "[WorktimeAttendanceClient] clock-in conflict refresh failed: ${activeSession.message}")
                        PosResult.Failure("Clock in status could not be confirmed")
                    }
                }
            }
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-in failed status=$statusCode body=$body")
            return PosResult.Failure("Clock in failed (HTTP $statusCode)")
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-in error: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            return PosResult.Failure("Clock in failed: ${t.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun postClockOut(
        baseUrl: String,
        staffId: String,
        event: WorktimeAttendanceSyncEvent?,
    ): PosResult<Unit> {
        val activeSession = when (val result = fetchActiveSessionForStaff(baseUrl, staffId, event?.restaurantKey)) {
            is PosResult.Success -> result.value
            is PosResult.Failure -> return PosResult.Failure(result.message)
        } ?: return PosResult.Success(Unit)
        val sessionId = activeSession.sessionId
        val urlString = "$baseUrl/api/worktime/$sessionId/clock-out"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { os ->
                val payload = JSONObject().apply {
                    event?.putEventFieldsInto(this)
                }
                os.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode in 200..299) {
                PosResult.Success(Unit)
            } else if (statusCode == 404 || statusCode == 409) {
                when (val refreshedSession = fetchActiveSessionForStaff(baseUrl, staffId, event?.restaurantKey)) {
                    is PosResult.Success -> {
                        if (refreshedSession.value == null) {
                            Log.d(
                                "AIROS",
                                "[WorktimeAttendanceClient] clock-out conflict resolved by no active session for staffId=$staffId",
                            )
                            PosResult.Success(Unit)
                        } else {
                            Log.d("AIROS", "[WorktimeAttendanceClient] clock-out conflict still active body=$body")
                            PosResult.Failure("Clock out did not complete; active session is still open")
                        }
                    }
                    is PosResult.Failure -> {
                        Log.d("AIROS", "[WorktimeAttendanceClient] clock-out conflict refresh failed: ${refreshedSession.message}")
                        PosResult.Failure("Clock out status could not be confirmed")
                    }
                }
            } else {
                Log.d("AIROS", "[WorktimeAttendanceClient] clock-out failed status=$statusCode body=$body")
                PosResult.Failure("Clock out failed (HTTP $statusCode)")
            }
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-out error: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
                PosResult.Failure("Clock out failed: ${t.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun fetchActiveSessionForStaff(
        staffId: String,
        restaurantKey: String? = null,
    ): PosResult<WorktimeActiveSession?> = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext PosResult.Failure("No backend URL configured")
        fetchActiveSessionForStaff(baseUrl, staffId, restaurantKey)
    }

    private fun fetchActiveSessionForStaff(
        baseUrl: String,
        staffId: String,
        restaurantKey: String?,
    ): PosResult<WorktimeActiveSession?> {
        val urlString = "$baseUrl/api/worktime/active${restaurantQuery(restaurantKey)}"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                Log.d("AIROS", "[WorktimeAttendanceClient] active session fetch failed status=$statusCode body=$body")
                return PosResult.Failure("Attendance status refresh failed (HTTP $statusCode)")
            }
            return PosResult.Success(
                parseActiveSessions(body).firstOrNull { session ->
                    session.staffId == staffId &&
                        (restaurantKey == null || session.restaurantKey == null || session.restaurantKey == restaurantKey)
                },
            )
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] fetch active sessions failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            return PosResult.Failure("Attendance status refresh failed: ${t.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseActiveSessions(body: String): List<WorktimeActiveSession> {
        if (body.isBlank()) return emptyList()
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val sessionId = item.optInt("id", -1)
            val staffId = item.optString("staff_id", "")
            if (sessionId < 0 || staffId.isBlank()) return@mapNotNull null
            WorktimeActiveSession(
                sessionId = sessionId,
                staffId = staffId,
                staffName = item.optString("staff_name", ""),
                restaurantKey = item.optNullableString("restaurant_key") ?: item.optNullableString("restaurant_id"),
                status = item.optString("status", ""),
                startedAt = item.optString("started_at", ""),
                startedAtEpochMillis = parseBackendDateTimeMillis(item.optString("started_at", "")),
            )
        }
    }

    private fun WorktimeAttendanceSyncEvent.toJson(): JSONObject {
        return JSONObject().apply {
            putEventFieldsInto(this)
        }
    }

    private fun WorktimeAttendanceSyncEvent.putEventFieldsInto(target: JSONObject) {
        target.put("event_id", eventId)
        if (ownerAccountId != null) {
            target.put("owner_account_id", ownerAccountId)
        }
        target.put("restaurant_key", restaurantKey)
        target.put("terminal_id", terminalId)
        target.put("staff_id", staffId)
        target.put("staff_name", staffName)
        target.put("action", action)
        target.put("occurred_at", Instant.ofEpochMilli(occurredAtEpochMillis).toString())
        target.put("source", source)
        target.put("terminal_sequence_number", terminalSequenceNumber)
    }

    private fun parseBackendDateTimeMillis(value: String): Long? {
        if (value.isBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Throwable) {
            try {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun restaurantQuery(restaurantKey: String?): String {
        val normalized = restaurantKey?.trim()?.ifBlank { null } ?: return ""
        return "?restaurant_key=${URLEncoder.encode(normalized, StandardCharsets.UTF_8.name())}"
    }

    private fun JSONObject.optNullableString(key: String): String? {
        val value = optString(key, "").trim()
        return value.ifBlank { null }
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                buildString {
                    while (true) {
                        val line = reader.readLine() ?: break
                        append(line)
                    }
                }
            }
        }
    }
}
