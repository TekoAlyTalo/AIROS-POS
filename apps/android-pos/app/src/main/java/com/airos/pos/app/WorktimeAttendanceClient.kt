package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AttendanceEntry
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import java.io.BufferedReader
import java.io.IOException
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

// Classified outcome of attempting to deliver a single attendance event to the backend.
// - Delivered: server accepted the event (new authoritative state will be available on next refresh).
// - ConflictReconciled: server had already recorded this effective state (idempotent — treat as success).
// - SequenceRecoverable: server rejected the event because the terminal_sequence_number conflicts with
//   an existing accepted sequence (duplicate_terminal_sequence) or is behind the backend's last_seen
//   counter (stale_terminal_sequence). The carried backendLastSeenTerminalSequence is the authoritative
//   counter the caller MUST advance past before resubmitting under a fresh event_id.
// - TransientFailure: network/offline condition; safe to retry soon.
// - RetriableServerFailure: backend 5xx / indeterminate state; retry later.
// - NonRetriableFailure: contract / schema / business rejection that will not self-heal on retry.
sealed class AttendanceSyncOutcome {
    object Delivered : AttendanceSyncOutcome()
    object ConflictReconciled : AttendanceSyncOutcome()
    data class SequenceRecoverable(
        val backendLastSeenTerminalSequence: Long,
        val message: String,
    ) : AttendanceSyncOutcome()
    data class TransientFailure(val message: String) : AttendanceSyncOutcome()
    data class RetriableServerFailure(val message: String) : AttendanceSyncOutcome()
    data class NonRetriableFailure(val message: String) : AttendanceSyncOutcome()
}

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
            requiresReview = parseEntries(root, "requires_review"),
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
                endedAt = item.optNullableString("ended_at"),
                requiresReview = item.optBoolean("requires_review", false),
                sessionId = item.optInt("session_id", -1),
            )
        }
    }

    suspend fun acknowledgeSessionReview(sessionId: Int): PosResult<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext PosResult.Failure("Taustajärjestelmän osoite puuttuu.")
        val urlString = "$baseUrl/api/worktime/sessions/$sessionId/review"
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
            connection.outputStream.use { os ->
                os.write("{}".toByteArray(StandardCharsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            return@withContext when {
                statusCode in 200..299 -> PosResult.Success(Unit)
                statusCode == 404 -> PosResult.Failure("Työaikaa ei löydy (session $sessionId).")
                statusCode == 409 -> {
                    val detail = runCatching { JSONObject(body).optString("detail", "") }.getOrDefault("")
                    PosResult.Failure("Tarkistusta ei voi tehdä: $detail".trimEnd(':').trimEnd())
                }
                else -> PosResult.Failure("Tarkistuksen tallennus epäonnistui (HTTP $statusCode).")
            }
        } catch (t: IOException) {
            Log.d("AIROS", "[WorktimeAttendanceClient] review acknowledge transient: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            PosResult.Failure("Yhteys palvelimeen epäonnistui. Yritä uudelleen.")
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] review acknowledge error: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            PosResult.Failure("Tarkistuksen tallennus epäonnistui: ${t.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun syncAttendanceEvent(event: WorktimeAttendanceSyncEvent): AttendanceSyncOutcome = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext AttendanceSyncOutcome.NonRetriableFailure("Työaikatapahtumaa ei voi vahvistaa: backend-osoitetta ei ole määritetty.")
        }
        when (val primary = postAttendanceEventSync(baseUrl, event)) {
            is PrimaryEndpointResult.Final -> primary.outcome
        }
    }

    private sealed class PrimaryEndpointResult {
        data class Final(val outcome: AttendanceSyncOutcome) : PrimaryEndpointResult()
    }

    private fun postAttendanceEventSync(
        baseUrl: String,
        event: WorktimeAttendanceSyncEvent,
    ): PrimaryEndpointResult {
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
            when {
                statusCode in 200..299 -> PrimaryEndpointResult.Final(
                    classifyEventsSyncBody(body, event.terminalId, statusCode),
                )
                statusCode == 404 || statusCode == 405 || statusCode == 409 -> {
                    Log.d("AIROS", "[WorktimeAttendanceClient] event sync endpoint unavailable/conflict status=$statusCode body=$body")
                    PrimaryEndpointResult.Final(
                        AttendanceSyncOutcome.NonRetriableFailure(
                            "Työaikatapahtumaa ei voitu vahvistaa palvelimella (HTTP $statusCode). Toiminto estetty.",
                        ),
                    )
                }
                statusCode in 500..599 -> {
                    Log.d("AIROS", "[WorktimeAttendanceClient] event sync server error status=$statusCode body=$body")
                    PrimaryEndpointResult.Final(AttendanceSyncOutcome.RetriableServerFailure("Työaikatapahtumaa ei voitu vahvistaa palvelimella (HTTP $statusCode). Toiminto estetty."))
                }
                else -> {
                    Log.d("AIROS", "[WorktimeAttendanceClient] event sync non-retriable status=$statusCode body=$body")
                    PrimaryEndpointResult.Final(AttendanceSyncOutcome.NonRetriableFailure("Työaikatapahtuma hylättiin palvelimella (HTTP $statusCode). Toiminto estetty."))
                }
            }
        } catch (t: IOException) {
            Log.d("AIROS", "[WorktimeAttendanceClient] event sync transient: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            PrimaryEndpointResult.Final(AttendanceSyncOutcome.TransientFailure("Työaikatapahtumaa ei voitu vahvistaa: yhteys palvelimeen epäonnistui. Toiminto estetty."))
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] event sync error: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            PrimaryEndpointResult.Final(AttendanceSyncOutcome.NonRetriableFailure("Työaikatapahtumaa ei voitu vahvistaa: ${t.message.orEmpty()}"))
        } finally {
            connection?.disconnect()
        }
    }

    // Inspect the /api/worktime/events/sync envelope and classify the single submitted event's
    // outcome. The envelope returns HTTP 200 even when every event was rejected (per-event status
    // lives in results[].processing_status/result_status), so the body must be inspected here.
    // terminal_states[]/results[].last_seen_terminal_sequence are the backend's authoritative
    // sequence counter — required to recover from terminal sequence drift.
    private fun classifyEventsSyncBody(
        body: String,
        terminalId: String,
        statusCode: Int,
    ): AttendanceSyncOutcome {
        if (body.isBlank()) {
            Log.d("AIROS", "[WorktimeAttendanceClient] event sync 2xx with empty body status=$statusCode — falling back to Delivered")
            return AttendanceSyncOutcome.Delivered
        }
        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] event sync body parse failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()} — falling back to Delivered")
            return AttendanceSyncOutcome.Delivered
        }
        val results = root.optJSONArray("results")
        val result = results?.optJSONObject(0)
        if (result == null) {
            Log.d("AIROS", "[WorktimeAttendanceClient] event sync body has no results[] — falling back to Delivered")
            return AttendanceSyncOutcome.Delivered
        }
        val processingStatus = result.optString("processing_status", "").trim().lowercase()
        val resultStatus = result.optString("result_status", "").trim().lowercase()
        val resultMessage = result.optNullableString("result_message")
        val lastSeenFromResult = result.optLong("last_seen_terminal_sequence", -1L)
        val lastSeenFromTerminalStates = root.optJSONArray("terminal_states")?.let { array ->
            (0 until array.length()).asSequence()
                .mapNotNull { idx -> array.optJSONObject(idx) }
                .firstOrNull { state -> state.optString("terminal_id") == terminalId }
                ?.optLong("last_seen_terminal_sequence", -1L)
        } ?: -1L
        val backendLastSeen = maxOf(lastSeenFromResult, lastSeenFromTerminalStates).coerceAtLeast(0L)
        return when {
            resultStatus == "duplicate_terminal_sequence" || resultStatus == "stale_terminal_sequence" -> {
                Log.d("AIROS", "[WorktimeAttendanceClient] event sync rejected result_status=$resultStatus backendLastSeen=$backendLastSeen message=$resultMessage")
                AttendanceSyncOutcome.SequenceRecoverable(
                    backendLastSeenTerminalSequence = backendLastSeen,
                    message = resultMessage
                        ?: "Työaikatapahtuma hylättiin: terminaalin järjestysnumero on jo käytössä palvelimella.",
                )
            }
            resultStatus == "stale_requires_review" -> {
                AttendanceSyncOutcome.NonRetriableFailure(
                    resultMessage
                        ?: "Työaikatapahtumaa ei voi viedä loppuun: edellinen työaika vaatii tarkistuksen.",
                )
            }
            resultStatus == "unsupported_action" -> {
                AttendanceSyncOutcome.NonRetriableFailure(
                    resultMessage ?: "Työaikatapahtumaa ei tueta palvelimella.",
                )
            }
            resultStatus in setOf("clocked_in", "clocked_out") -> AttendanceSyncOutcome.Delivered
            resultStatus in setOf("already_active", "already_closed") -> AttendanceSyncOutcome.ConflictReconciled
            processingStatus == "duplicate" -> {
                // Re-submission of an event_id the server has seen before. If the original
                // outcome was a normal success / reconciled state, treat as idempotent
                // success; otherwise the original was a rejection being re-presented and
                // must NOT be silently treated as success.
                if (resultStatus in setOf("clocked_in", "clocked_out", "already_active", "already_closed")) {
                    AttendanceSyncOutcome.ConflictReconciled
                } else {
                    AttendanceSyncOutcome.NonRetriableFailure(
                        resultMessage
                            ?: "Työaikatapahtumaa ei voitu vahvistaa palvelimella (toistuva tapahtuma, alkuperäinen hylätty).",
                    )
                }
            }
            processingStatus == "rejected" -> {
                AttendanceSyncOutcome.NonRetriableFailure(
                    resultMessage ?: "Työaikatapahtuma hylättiin palvelimella.",
                )
            }
            processingStatus == "processed" -> AttendanceSyncOutcome.Delivered
            else -> {
                Log.d("AIROS", "[WorktimeAttendanceClient] event sync unrecognised processingStatus=$processingStatus resultStatus=$resultStatus body=$body")
                AttendanceSyncOutcome.Delivered
            }
        }
    }

    private fun postLegacyAttendanceAction(baseUrl: String, event: WorktimeAttendanceSyncEvent): AttendanceSyncOutcome {
        return when (event.action) {
            "clock_in" -> postClockIn(baseUrl, event.staffId, event.staffName, event)
            "clock_out" -> postClockOut(baseUrl, event.staffId, event)
            else -> AttendanceSyncOutcome.NonRetriableFailure("Unsupported attendance action: ${event.action}")
        }
    }

    private fun postClockIn(
        baseUrl: String,
        staffId: String,
        staffName: String,
        event: WorktimeAttendanceSyncEvent?,
    ): AttendanceSyncOutcome {
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
                return AttendanceSyncOutcome.Delivered
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
                            AttendanceSyncOutcome.ConflictReconciled
                        } else {
                            Log.d("AIROS", "[WorktimeAttendanceClient] clock-in conflict but no active session body=$body")
                            AttendanceSyncOutcome.RetriableServerFailure("Clock in status could not be confirmed")
                        }
                    }
                    is PosResult.Failure -> {
                        Log.d("AIROS", "[WorktimeAttendanceClient] clock-in conflict refresh failed: ${activeSession.message}")
                        AttendanceSyncOutcome.TransientFailure("Clock in status could not be confirmed")
                    }
                }
            }
            if (statusCode in 500..599) {
                Log.d("AIROS", "[WorktimeAttendanceClient] clock-in server error status=$statusCode body=$body")
                return AttendanceSyncOutcome.RetriableServerFailure("Clock in server error (HTTP $statusCode)")
            }
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-in rejected status=$statusCode body=$body")
            return AttendanceSyncOutcome.NonRetriableFailure("Clock in rejected (HTTP $statusCode)")
        } catch (t: IOException) {
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-in transient: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            return AttendanceSyncOutcome.TransientFailure("Clock in offline: ${t.message.orEmpty()}")
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-in error: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            return AttendanceSyncOutcome.NonRetriableFailure("Clock in failed: ${t.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun postClockOut(
        baseUrl: String,
        staffId: String,
        event: WorktimeAttendanceSyncEvent?,
    ): AttendanceSyncOutcome {
        val activeSession = when (val result = fetchActiveSessionForStaff(baseUrl, staffId, event?.restaurantKey)) {
            is PosResult.Success -> result.value
            is PosResult.Failure -> return AttendanceSyncOutcome.TransientFailure(result.message)
        } ?: return AttendanceSyncOutcome.ConflictReconciled
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
            when {
                statusCode in 200..299 -> AttendanceSyncOutcome.Delivered
                statusCode == 404 || statusCode == 409 -> {
                    when (val refreshedSession = fetchActiveSessionForStaff(baseUrl, staffId, event?.restaurantKey)) {
                        is PosResult.Success -> {
                            if (refreshedSession.value == null) {
                                Log.d(
                                    "AIROS",
                                    "[WorktimeAttendanceClient] clock-out conflict resolved by no active session for staffId=$staffId",
                                )
                                AttendanceSyncOutcome.ConflictReconciled
                            } else {
                                Log.d("AIROS", "[WorktimeAttendanceClient] clock-out conflict still active body=$body")
                                AttendanceSyncOutcome.RetriableServerFailure("Clock out did not complete; active session is still open")
                            }
                        }
                        is PosResult.Failure -> {
                            Log.d("AIROS", "[WorktimeAttendanceClient] clock-out conflict refresh failed: ${refreshedSession.message}")
                            AttendanceSyncOutcome.TransientFailure("Clock out status could not be confirmed")
                        }
                    }
                }
                statusCode in 500..599 -> {
                    Log.d("AIROS", "[WorktimeAttendanceClient] clock-out server error status=$statusCode body=$body")
                    AttendanceSyncOutcome.RetriableServerFailure("Clock out server error (HTTP $statusCode)")
                }
                else -> {
                    Log.d("AIROS", "[WorktimeAttendanceClient] clock-out rejected status=$statusCode body=$body")
                    AttendanceSyncOutcome.NonRetriableFailure("Clock out rejected (HTTP $statusCode)")
                }
            }
        } catch (t: IOException) {
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-out transient: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            AttendanceSyncOutcome.TransientFailure("Clock out offline: ${t.message.orEmpty()}")
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-out error: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            AttendanceSyncOutcome.NonRetriableFailure("Clock out failed: ${t.message.orEmpty()}")
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
