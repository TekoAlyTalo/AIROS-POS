package com.airos.pos.app

import android.util.Log
import com.airos.pos.core.database.dao.BackendMenuCacheDao
import com.airos.pos.core.database.entity.BackendMenuItemEntity
import com.airos.pos.core.database.entity.MenuCacheMetadataEntity
import com.airos.pos.core.model.MenuItem
import com.airos.pos.domain.MenuRepository
import com.airos.pos.domain.MenuSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * MenuRepository backed by a Room cache.
 *
 * On [refresh]:
 *  - If network succeeds → replace cache, return [MenuSyncResult.Fresh]
 *  - If network fails and cache exists → return [MenuSyncResult.FromCache]
 *  - If network fails and cache is empty → return [MenuSyncResult.NoData]
 *
 * [observeMenuItems] emits directly from Room, so the UI reacts automatically when
 * the cache is updated.
 */
class BackendMenuRepository(
    private val backendBaseUrlProvider: () -> String,
    private val menuCacheDao: BackendMenuCacheDao,
    private val restaurantKeyProvider: () -> String = { "ravintola_default" },
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) : MenuRepository {

    private val _syncState = MutableStateFlow<MenuSyncResult?>(null)
    override val syncState: StateFlow<MenuSyncResult?> = _syncState

    override fun observeMenuItems(): Flow<List<MenuItem>> =
        menuCacheDao.observeAll(restaurantKeyProvider()).map { entities ->
            entities.map { it.toMenuItem() }
        }

    override suspend fun findItemByBarcode(rawValue: String): MenuItem? =
        withContext(Dispatchers.IO) {
            val items = menuCacheDao.observeAll(restaurantKeyProvider()).first()
            items.firstOrNull { it.barcode == rawValue }?.toMenuItem()
        }

    override suspend fun refresh(): MenuSyncResult = withContext(Dispatchers.IO) {
        val restaurantKey = restaurantKeyProvider()
        val rawBaseUrl = backendBaseUrlProvider()
        val baseUrl = rawBaseUrl.trim().trimEnd('/')

        if (baseUrl.isEmpty()) {
            log("baseUrl empty")
            return@withContext fallbackOrNoData(
                restaurantKey,
                "Backend URL on tyhjä — tarkista päätelaitteen asetukset."
            )
        }

        val urlString = "$baseUrl/api/menu"
        log("refresh start url='$urlString'")

        val connection = try {
            (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doInput = true
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
        } catch (t: Throwable) {
            log("connection open failed: ${t.javaClass.simpleName}: ${t.message}")
            return@withContext fallbackOrNoData(
                restaurantKey,
                "Yhteys backendiin epäonnistui: ${t.javaClass.simpleName}: ${t.message ?: "ei viestiä"}"
            )
        }

        val result = try {
            val statusCode = connection.responseCode
            val body = readStream(
                if (statusCode in 200..299) connection.inputStream else connection.errorStream
            )
            log("response status=$statusCode body=${body.take(300)}")

            if (statusCode !in 200..299) {
                return@withContext fallbackOrNoData(
                    restaurantKey,
                    "Backend palautti HTTP $statusCode haettaessa tuotelistaa."
                )
            }

            val entities = parseMenuItemEntities(body, restaurantKey, baseUrl)
            val now = System.currentTimeMillis()
            val metadata = MenuCacheMetadataEntity(
                restaurantKey = restaurantKey,
                lastSyncedAt = now,
                itemCount = entities.size,
            )
            menuCacheDao.replaceAll(restaurantKey, entities, metadata)
            log("synced ${entities.size} items to cache")
            MenuSyncResult.Fresh
        } catch (t: Throwable) {
            log("request failed: ${t.javaClass.simpleName}: ${t.message}")
            fallbackOrNoData(
                restaurantKey,
                "Tuotelistan haku epäonnistui: ${t.javaClass.simpleName}: ${t.message ?: "ei viestiä"}"
            )
        } finally {
            connection.disconnect()
        }

        _syncState.value = result
        result
    }

    private suspend fun fallbackOrNoData(restaurantKey: String, reason: String): MenuSyncResult {
        val meta = menuCacheDao.getMetadata(restaurantKey)
        return if (meta != null && meta.itemCount > 0) {
            log("network unavailable, serving cache from ${meta.lastSyncedAt}")
            MenuSyncResult.FromCache(meta.lastSyncedAt)
        } else {
            log("network unavailable, no cache: $reason")
            MenuSyncResult.NoData(reason)
        }
    }

    private fun parseMenuItemEntities(
        json: String,
        restaurantKey: String,
        baseUrl: String,
    ): List<BackendMenuItemEntity> {
        val array = JSONArray(json.trim())
        val result = mutableListOf<BackendMenuItemEntity>()
        val now = System.currentTimeMillis()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (!obj.optBoolean("is_active", true)) continue
            result.add(
                BackendMenuItemEntity(
                    restaurantKey = restaurantKey,
                    id = obj.getInt("id").toString(),
                    sku = obj.optString("sku", ""),
                    name = obj.getString("name"),
                    category = obj.optString("category", ""),
                    priceCents = obj.getInt("price_cents"),
                    taxRatePercent = if (obj.isNull("tax_rate_percent")) 0.0
                                     else obj.getDouble("tax_rate_percent"),
                    subcategory = obj.optString("subcategory").takeIf { it.isNotEmpty() },
                    barcode = obj.optString("barcode").takeIf { it.isNotEmpty() },
                    imageUrl = resolveImageUrl(obj.optString("image_url"), baseUrl),
                    cachedAt = now,
                )
            )
        }
        return result
    }

    /**
     * Converts a backend image_url value to an absolute URL ready for Coil.
     *
     * - Already absolute (http/https) → returned as-is
     * - Relative path starting with "/" → prepend baseUrl
     * - Empty or unrecognised → null
     */
    private fun resolveImageUrl(raw: String, baseUrl: String): String? {
        if (raw.isBlank()) return null
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") && baseUrl.isNotEmpty() -> "$baseUrl$raw"
            else -> null
        }
        return url?.replace(" ", "%20")
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                buildString {
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        append(line)
                    }
                }
            }
        }
    }

    private fun log(message: String) {
        Log.d("AIROS", "[BackendMenuRepository] $message")
    }
}

private fun BackendMenuItemEntity.toMenuItem() = MenuItem(
    id = id,
    sku = sku,
    name = name,
    category = category,
    priceCents = priceCents,
    taxRatePercent = taxRatePercent,
    barcode = barcode,
    imageUrl = imageUrl,
    subcategory = subcategory,
)
