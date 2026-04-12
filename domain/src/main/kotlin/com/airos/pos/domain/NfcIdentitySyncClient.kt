package com.airos.pos.domain

import com.airos.pos.core.common.PosResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class NfcIdentityBindingRequest(
    val canonicalUid: String,
    val staffId: String,
    val staffName: String,
    val staffRole: String,
    val enabled: Boolean = true,
    val metadata: Map<String, Any>? = null,
)

interface NfcIdentitySyncClient {
    suspend fun syncBinding(binding: NfcIdentityBindingRequest): PosResult<Unit>
}

class DefaultNfcIdentitySyncClient(
    private val backendBaseUrlProvider: () -> String,
    private val authHeaderProvider: (() -> String?)? = null,
    private val endpointPath: String = "/api/identity/nfc/bindings",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : NfcIdentitySyncClient {

    override suspend fun syncBinding(binding: NfcIdentityBindingRequest): PosResult<Unit> {
        return withContext(Dispatchers.IO) {
            val baseUrl = backendBaseUrlProvider.invoke().trim().trimEnd('/')
            if (baseUrl.isEmpty()) {
                return@withContext PosResult.Failure("AIROS identity backend URL is empty.")
            }
            val urlString = "$baseUrl/${endpointPath.trimStart('/')}"
            val connection = try {
                (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doInput = true
                    doOutput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    authHeaderProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }?.let { header ->
                        setRequestProperty("Authorization", header)
                    }
                }
            } catch (t: Throwable) {
                return@withContext PosResult.Failure("NFC binding sync failed to open connection: ${t.localizedMessage}")
            }

            try {
                val payload = binding.toJson()
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    return@withContext PosResult.Failure("NFC binding sync failed: HTTP $statusCode")
                }
                PosResult.Success(Unit)
            } catch (t: Throwable) {
                PosResult.Failure("NFC binding sync failed: ${t.localizedMessage}")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun NfcIdentityBindingRequest.toJson(): String {
        val map = linkedMapOf<String, Any?>(
            "canonical_uid" to canonicalUid,
            "staff_id" to staffId,
            "staff_name" to staffName,
            "staff_role" to staffRole,
            "enabled" to enabled,
            "metadata" to metadata,
        )
        return map.toJsonString()
    }

    private fun Map<String, Any?>.toJsonString(): String {
        return buildString {
            append("{")
            entries.forEachIndexed { index, entry ->
                append(jsonQuote(entry.key))
                append(":")
                append(jsonValue(entry.value))
                if (index < size - 1) {
                    append(",")
                }
            }
            append("}")
        }
    }

    private fun jsonValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> jsonQuote(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> buildString {
                append("{")
                value.entries.forEachIndexed { index, (key, subValue) ->
                    append(jsonQuote(key.toString()))
                    append(":")
                    append(jsonValue(subValue))
                    if (index < value.size - 1) append(",")
                }
                append("}")
            }
            else -> jsonQuote(value.toString())
        }
    }

    private fun jsonQuote(value: String): String {
        val out = StringBuilder()
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u")
                        out.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('"')
        return out.toString()
    }
}
