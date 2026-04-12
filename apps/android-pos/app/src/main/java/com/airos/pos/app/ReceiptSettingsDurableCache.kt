package com.airos.pos.app

import android.content.Context
import android.util.Log
import com.airos.pos.core.model.ReceiptBusiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable local cache for [BackendReceiptSettings].
 *
 * Serializes the last known backend receipt settings to a private JSON file in the app's
 * internal files directory. This allows the app to restore settings after a restart even
 * when the backend is unavailable.
 *
 * Source-of-truth remains the backend/Dashboard. This cache is a resilience-only fallback.
 *
 * Both [save] and [load] are suspend functions that dispatch to [Dispatchers.IO], ensuring
 * file I/O never executes on the main thread regardless of the caller's dispatcher.
 */
class ReceiptSettingsDurableCache(context: Context) {
    companion object {
        private const val TAG = "AIROS_RECEIPT_CACHE"
        private const val FILE_NAME = "receipt_settings_cache.json"
    }

    private val cacheFile = File(context.filesDir, FILE_NAME)

    /** Persist [settings] to disk on [Dispatchers.IO]. */
    suspend fun save(settings: BackendReceiptSettings) = withContext(Dispatchers.IO) {
        Log.i(TAG, "save attempt file=${cacheFile.absolutePath} logoEnabled=${settings.logoEnabled} business=${settings.business?.displayName}")
        try {
            val json = settings.toJson().toString()
            cacheFile.writeText(json, Charsets.UTF_8)
            Log.i(TAG, "save ok bytes=${json.length} logoEnabled=${settings.logoEnabled} business=${settings.business?.displayName}")
        } catch (t: Throwable) {
            Log.e(TAG, "save failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    /**
     * Return the last persisted [BackendReceiptSettings] on [Dispatchers.IO], or null if
     * no cache file exists or the file is unreadable/corrupt.
     */
    suspend fun load(): BackendReceiptSettings? = withContext(Dispatchers.IO) {
        Log.i(TAG, "load attempt file=${cacheFile.absolutePath} exists=${cacheFile.exists()}")
        try {
            if (!cacheFile.exists()) {
                Log.i(TAG, "load miss: no cache file at ${cacheFile.absolutePath}")
                return@withContext null
            }
            val text = cacheFile.readText(Charsets.UTF_8)
            val result = JSONObject(text).toCachedBackendReceiptSettings()
            Log.i(TAG, "load ok logoEnabled=${result.logoEnabled} business=${result.business?.displayName}")
            result
        } catch (t: Throwable) {
            Log.e(TAG, "load failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }
}

// ── Serialization ─────────────────────────────────────────────────────────────────────────────────

private fun BackendReceiptSettings.toJson(): JSONObject = JSONObject().apply {
    business?.let { b ->
        put("business", JSONObject().apply {
            put("displayName", b.displayName)
            b.legalName?.let { put("legalName", it) }
            b.businessId?.let { put("businessId", it) }
            b.vatId?.let { put("vatId", it) }
            val addressArray = JSONArray()
            b.addressLines.forEach { addressArray.put(it) }
            put("addressLines", addressArray)
            b.phone?.let { put("phone", it) }
            b.email?.let { put("email", it) }
            b.website?.let { put("website", it) }
        })
    }
    currencyCode?.let { put("currencyCode", it) }
    put("logoEnabled", logoEnabled)
    logoAsset?.let { asset ->
        put("logoAsset", JSONObject().apply {
            put("dataUrl", asset.dataUrl)
            put("fileName", asset.fileName)
            put("normalizedWidth", asset.normalizedWidth)
            put("normalizedHeight", asset.normalizedHeight)
        })
    }
    put("logoScalePercent", logoScalePercent)
    put("thankYouMessageEnabled", thankYouMessageEnabled)
    put("thankYouMessageText", thankYouMessageText)
    put("footerText", footerText)
}

private fun JSONObject.toCachedBackendReceiptSettings(): BackendReceiptSettings {
    val businessJson = optJSONObject("business")
    return BackendReceiptSettings(
        business = businessJson?.let { b ->
            val arr = b.optJSONArray("addressLines")
            val addressLines = buildList {
                if (arr != null) for (i in 0 until arr.length()) add(arr.getString(i))
            }
            ReceiptBusiness(
                displayName = b.optString("displayName").ifBlank { "Restaurant" },
                legalName = b.optString("legalName").ifBlank { null },
                businessId = b.optString("businessId").ifBlank { null },
                vatId = b.optString("vatId").ifBlank { null },
                addressLines = addressLines,
                phone = b.optString("phone").ifBlank { null },
                email = b.optString("email").ifBlank { null },
                website = b.optString("website").ifBlank { null },
            )
        },
        currencyCode = optString("currencyCode").ifBlank { null },
        logoEnabled = optBoolean("logoEnabled", false),
        logoAsset = optJSONObject("logoAsset")?.let { asset ->
            BackendReceiptLogoAsset(
                dataUrl = asset.optString("dataUrl"),
                fileName = asset.optString("fileName"),
                normalizedWidth = asset.optInt("normalizedWidth", 0),
                normalizedHeight = asset.optInt("normalizedHeight", 0),
            )
        },
        logoScalePercent = optInt("logoScalePercent", 100),
        thankYouMessageEnabled = optBoolean("thankYouMessageEnabled", true),
        thankYouMessageText = optString("thankYouMessageText").orEmpty(),
        footerText = optString("footerText").orEmpty(),
    )
}
