package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackendReservation(
    val id: Int,
    val tableId: Int?,
    val customerName: String,
    val customerPhone: String?,
    val customerProfileId: Int?,
    val startTime: String,
    val endTime: String,
    val persons: Int,
    val notes: String?,
    val createdAt: String,
)

data class BackendReservationWrite(
    val tableId: Int?,
    val customerName: String,
    val customerPhone: String?,
    val customerProfileId: Int? = null,
    val startTime: String,
    val endTime: String,
    val persons: Int,
    val notes: String?,
)

class BackendReservationsRepository(
    private val backendBaseUrlProvider: () -> String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) {
    suspend fun listReservations(): PosResult<List<BackendReservation>> = withContext(Dispatchers.IO) {
        request(
            method = "GET",
            path = "/reservations",
            parse = { body -> parseReservationArray(body) },
        )
    }

    suspend fun createReservation(payload: BackendReservationWrite): PosResult<BackendReservation> =
        withContext(Dispatchers.IO) {
            request(
                method = "POST",
                path = "/reservations",
                payload = payload.toJson(),
                parse = { body -> JSONObject(body).toBackendReservation() },
            )
        }

    suspend fun updateReservation(id: Int, payload: BackendReservationWrite): PosResult<BackendReservation> =
        withContext(Dispatchers.IO) {
            request(
                method = "PUT",
                path = "/reservations/$id",
                payload = payload.toJson(),
                parse = { body -> JSONObject(body).toBackendReservation() },
            )
        }

    suspend fun deleteReservation(id: Int): PosResult<Unit> = withContext(Dispatchers.IO) {
        request(
            method = "DELETE",
            path = "/reservations/$id",
            parse = { Unit },
        )
    }

    private fun <T> request(
        method: String,
        path: String,
        payload: JSONObject? = null,
        parse: (String) -> T,
    ): PosResult<T> {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return PosResult.Failure("Backend URL is empty. Check terminal settings.")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    doOutput = true
                    val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Content-Length", bytes.size.toString())
                    outputStream.use { it.write(bytes) }
                }
            }

            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                return reservationFailure(statusCode, body)
            }
            PosResult.Success(parse(body))
        } catch (t: Throwable) {
            Log.d("AIROS", "[BackendReservationsRepository] $method $path failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            PosResult.Failure("Reservation request failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun reservationFailure(statusCode: Int, body: String): PosResult.Failure {
        val detail = extractDetail(body)
        val message = if (statusCode == 400 && detail.contains("Overlapping reservation", ignoreCase = true)) {
            "This table already has a reservation in that time range."
        } else {
            "Reservations backend rejected the request (HTTP $statusCode): ${detail.ifBlank { body.take(180) }}"
        }
        return PosResult.Failure(message.trim())
    }

    private fun parseReservationArray(body: String): List<BackendReservation> {
        if (body.isBlank()) return emptyList()
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.toBackendReservation()
        }
    }
}

private fun BackendReservationWrite.toJson(): JSONObject = JSONObject().apply {
    if (tableId == null) put("table_id", JSONObject.NULL) else put("table_id", tableId)
    put("customer_name", customerName)
    if (customerPhone.isNullOrBlank()) put("customer_phone", JSONObject.NULL) else put("customer_phone", customerPhone)
    if (customerProfileId == null) put("customer_profile_id", JSONObject.NULL) else put("customer_profile_id", customerProfileId)
    put("start_time", startTime)
    put("end_time", endTime)
    put("persons", persons)
    if (notes.isNullOrBlank()) put("notes", JSONObject.NULL) else put("notes", notes)
}

private fun JSONObject.toBackendReservation(): BackendReservation {
    return BackendReservation(
        id = optInt("id"),
        tableId = optIntOrNull("table_id"),
        customerName = optString("customer_name"),
        customerPhone = optStringOrNull("customer_phone"),
        customerProfileId = optIntOrNull("customer_profile_id"),
        startTime = optString("start_time"),
        endTime = optString("end_time"),
        persons = optInt("persons"),
        notes = optStringOrNull("notes"),
        createdAt = optString("created_at"),
    )
}

private fun extractDetail(body: String): String {
    if (body.isBlank()) return ""
    return try {
        JSONObject(body).optString("detail").ifBlank { body.take(180) }
    } catch (_: Throwable) {
        body.take(180)
    }
}

private fun readStream(stream: InputStream?): String {
    if (stream == null) return ""
    return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).trim().takeIf { it.isNotBlank() }
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}
