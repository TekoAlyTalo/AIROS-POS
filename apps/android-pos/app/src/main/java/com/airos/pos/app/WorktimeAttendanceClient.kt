package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.AttendanceEntry
import com.airos.pos.core.model.WorktimeAttendanceSnapshot
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WorktimeAttendanceClient(
    private val backendBaseUrlProvider: () -> String,
    private val pollIntervalMillis: Long = 15_000L,
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 3_000,
) {
    fun observeAttendance(): Flow<WorktimeAttendanceSnapshot> = flow {
        emit(WorktimeAttendanceSnapshot())
        while (true) {
            val snapshot = fetchAttendance()
            if (snapshot != null) {
                emit(snapshot)
            }
            delay(pollIntervalMillis)
        }
    }

    private suspend fun fetchAttendance(): WorktimeAttendanceSnapshot? = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext null
        val urlString = "$baseUrl/api/worktime/attendance"
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
                return@withContext null
            }
            parseAttendance(body)
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] fetch failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            null
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
                put("source", "android-pos")
            }
            connection.outputStream.use { os ->
                os.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode in 200..299) {
                PosResult.Success(Unit)
            } else {
                Log.d("AIROS", "[WorktimeAttendanceClient] clock-in failed status=$statusCode body=$body")
                PosResult.Failure("Clock in failed (HTTP $statusCode)")
            }
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] clock-in error: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            PosResult.Failure("Clock in failed: ${t.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun clockOut(staffId: String): PosResult<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext PosResult.Failure("No backend URL configured")
        val sessionId = findActiveSessionId(baseUrl, staffId)
            ?: return@withContext PosResult.Failure("No active session found for clock out")
        val urlString = "$baseUrl/api/worktime/$sessionId/clock-out"
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
                os.write(JSONObject().toString().toByteArray(StandardCharsets.UTF_8))
            }
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode in 200..299) {
                PosResult.Success(Unit)
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

    private fun findActiveSessionId(baseUrl: String, staffId: String): Int? {
        val urlString = "$baseUrl/api/worktime/active"
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
            if (statusCode !in 200..299) return null
            val body = readStream(connection.inputStream)
            val array = JSONArray(body)
            for (i in 0 until array.length()) {
                val session = array.optJSONObject(i) ?: continue
                if (session.optString("staff_id") == staffId) {
                    return session.optInt("id", -1).takeIf { it >= 0 }
                }
            }
            return null
        } catch (t: Throwable) {
            Log.d("AIROS", "[WorktimeAttendanceClient] fetch active sessions failed: ${t.javaClass.simpleName}")
            return null
        } finally {
            connection?.disconnect()
        }
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
