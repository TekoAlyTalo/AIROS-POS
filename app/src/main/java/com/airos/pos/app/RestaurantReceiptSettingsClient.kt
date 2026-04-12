package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.common.PosResult
import com.airos.pos.core.model.ReceiptAlignment
import com.airos.pos.core.model.ReceiptBusiness
import com.airos.pos.core.model.ReceiptDocument
import com.airos.pos.core.model.ReceiptImageSource
import com.airos.pos.core.model.ReceiptImageSourceType
import com.airos.pos.core.model.ReceiptLogo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BackendReceiptLogoAsset(
    val dataUrl: String,
    val fileName: String,
    val normalizedWidth: Int,
    val normalizedHeight: Int,
)

data class BackendReceiptSettings(
    val business: ReceiptBusiness?,
    val currencyCode: String?,
    val logoEnabled: Boolean,
    val logoAsset: BackendReceiptLogoAsset?,
    val logoScalePercent: Int,
    val thankYouMessageEnabled: Boolean,
    val thankYouMessageText: String,
    val footerText: String,
)

interface RestaurantReceiptSettingsClient {
    suspend fun fetchCurrent(): PosResult<BackendReceiptSettings>
}

/**
 * Wraps a [RestaurantReceiptSettingsClient] with a [ReceiptSettingsDurableCache].
 *
 * - On success: persists latest settings to durable cache and returns live result.
 * - On failure: returns cached settings as Success if available, otherwise propagates original Failure.
 *
 * Source-of-truth remains the backend/Dashboard. The durable cache is a resilience-only fallback.
 */
class CachingRestaurantReceiptSettingsClient(
    private val delegate: RestaurantReceiptSettingsClient,
    private val cache: ReceiptSettingsDurableCache,
) : RestaurantReceiptSettingsClient {
    companion object {
        private const val TAG = "AIROS_RECEIPT_SETTINGS"
    }

    override suspend fun fetchCurrent(): PosResult<BackendReceiptSettings> {
        return when (val result = delegate.fetchCurrent()) {
            is PosResult.Success -> {
                Log.i(TAG, "fetchCurrent: live fetch ok logoEnabled=${result.value.logoEnabled}, persisting to durable cache")
                cache.save(result.value)
                result
            }
            is PosResult.Failure -> {
                Log.w(TAG, "fetchCurrent: live fetch failed (${result.message}), trying durable cache")
                val cached = cache.load()
                if (cached != null) {
                    Log.i(TAG, "fetchCurrent: durable cache hit logoEnabled=${cached.logoEnabled} business=${cached.business?.displayName}")
                    PosResult.Success(cached)
                } else {
                    Log.e(TAG, "fetchCurrent: durable cache miss — no cached receipt settings available")
                    result
                }
            }
        }
    }
}

class DefaultRestaurantReceiptSettingsClient(
    private val backendBaseUrlProvider: () -> String,
) : RestaurantReceiptSettingsClient {
    companion object {
        private const val TAG = "AIROS_RECEIPT_SETTINGS"
    }

    override suspend fun fetchCurrent(): PosResult<BackendReceiptSettings> = withContext(Dispatchers.IO) {
        val baseUrl = backendBaseUrlProvider().trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext PosResult.Failure("Receipt settings backend base URL is empty.")
        }
        val endpoint = "$baseUrl/api/pos/receipt-settings/current"
        return@withContext try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                doInput = true
            }

            val status = connection.responseCode
            val payloadText = try {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } finally {
                connection.disconnect()
            }

            if (status !in 200..299) {
                return@withContext PosResult.Failure("Receipt settings fetch failed: HTTP $status ${payloadText.take(160)}".trim())
            }

            val json = JSONObject(payloadText)
            PosResult.Success(json.toBackendReceiptSettings())
        } catch (t: Throwable) {
            Log.w(TAG, "fetchCurrent failed: ${t.message}", t)
            PosResult.Failure("Receipt settings fetch failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}

fun applyBackendReceiptSettings(
    document: ReceiptDocument,
    settings: BackendReceiptSettings,
): ReceiptDocument {
    val business = settings.business
    val footerText = buildString {
        if (settings.thankYouMessageEnabled && settings.thankYouMessageText.isNotBlank()) {
            append(settings.thankYouMessageText.trim())
        }
        if (settings.footerText.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append(settings.footerText.trim())
        }
    }.trim()

    val logo = if (settings.logoEnabled && settings.logoAsset != null) {
        val scalePercent = settings.logoScalePercent.coerceIn(20, 150)
        val widthPx = ((settings.logoAsset.normalizedWidth * scalePercent) / 100.0).toInt().coerceAtLeast(1)
        val heightPx = ((settings.logoAsset.normalizedHeight * scalePercent) / 100.0).toInt().coerceAtLeast(1)
        ReceiptLogo(
            source = ReceiptImageSource(
                type = ReceiptImageSourceType.DATA_URL,
                value = settings.logoAsset.dataUrl,
            ),
            widthPx = widthPx,
            heightPx = heightPx,
            align = ReceiptAlignment.CENTER,
        )
    } else {
        null
    }

    return document.copy(
        currencyCode = settings.currencyCode?.ifBlank { document.currencyCode } ?: document.currencyCode,
        business = business ?: document.business,
        logo = logo ?: document.logo,
        footerText = footerText.ifBlank { document.footerText ?: document.footer },
        footer = document.footer,
        extraTextBlocks = document.extraTextBlocks,
    )
}

private fun JSONObject.toBackendReceiptSettings(): BackendReceiptSettings {
    val businessJson = optJSONObject("business")
    val receiptJson = optJSONObject("receipt")

    return BackendReceiptSettings(
        business = businessJson?.toReceiptBusiness(),
        currencyCode = receiptJson?.optString("currencyCode")?.takeIf { it.isNotBlank() },
        logoEnabled = receiptJson?.optBoolean("logoEnabled") == true,
        logoAsset = receiptJson?.optJSONObject("logoAsset")?.toLogoAsset(),
        logoScalePercent = receiptJson?.optInt("logoScalePercent", 100) ?: 100,
        thankYouMessageEnabled = receiptJson?.optBoolean("thankYouMessageEnabled") != false,
        thankYouMessageText = receiptJson?.optString("thankYouMessageText").orEmpty(),
        footerText = receiptJson?.optString("footerText").orEmpty(),
    )
}

private fun JSONObject.toReceiptBusiness(): ReceiptBusiness {
    val addressLines = buildList {
        optString("addressLine1").trim().takeIf { it.isNotEmpty() }?.let(::add)
        optString("addressLine2").trim().takeIf { it.isNotEmpty() }?.let(::add)
        val postalCode = optString("postalCode").trim()
        val city = optString("city").trim()
        listOf(postalCode, city).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }?.let(::add)
        optString("country").trim().takeIf { it.isNotEmpty() }?.let(::add)
    }

    return ReceiptBusiness(
        displayName = optString("displayName").ifBlank { optString("legalName").ifBlank { "Restaurant" } },
        legalName = optString("legalName").ifBlank { null },
        businessId = optString("businessId").ifBlank { null },
        vatId = optString("vatId").ifBlank { null },
        addressLines = addressLines,
        phone = optString("phone").ifBlank { null },
        email = optString("email").ifBlank { null },
        website = optString("website").ifBlank { null },
    )
}

private fun JSONObject.toLogoAsset(): BackendReceiptLogoAsset {
    return BackendReceiptLogoAsset(
        dataUrl = optString("dataUrl"),
        fileName = optString("fileName"),
        normalizedWidth = optInt("normalizedWidth", 0),
        normalizedHeight = optInt("normalizedHeight", 0),
    )
}
