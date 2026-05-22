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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * MenuRepository backed by a Room cache.
 *
 * On [refresh]:
 *  - If network succeeds â†’ replace cache, return [MenuSyncResult.Fresh]
 *  - If network fails and cache exists â†’ return [MenuSyncResult.FromCache]
 *  - If network fails and cache is empty â†’ return [MenuSyncResult.NoData]
 *
 * [observeMenuItems] emits directly from Room, so the UI reacts automatically when
 * the cache is updated.
 */
class BackendMenuRepository(
    private val backendBaseUrlProvider: () -> String,
    private val menuCacheDao: BackendMenuCacheDao,
    private val imageCache: ProductImageCache? = null,
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
                "Backend URL on tyhjÃ¤ â€” tarkista pÃ¤Ã¤telaitteen asetukset."
            )
        }

        val restaurantQuery = URLEncoder.encode(restaurantKey, StandardCharsets.UTF_8.name())
        val urlString = "$baseUrl/api/menu?restaurant_key=$restaurantQuery"
        val visualsUrlString = "$baseUrl/api/menu/subcategory-visuals?restaurant_key=$restaurantQuery"
        log("refresh start url='$urlString' visualsUrl='$visualsUrlString'")

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
                "Yhteys backendiin epÃ¤onnistui: ${t.javaClass.simpleName}: ${t.message ?: "ei viestiÃ¤"}"
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

            val subcategoryVisuals = fetchSubcategoryVisualMap(visualsUrlString, baseUrl)
            var entities = parseMenuItemEntities(body, restaurantKey, baseUrl, subcategoryVisuals)

            // Download product and subcategory images to durable local cache.
            // Image caching is best-effort: a failed image download must not turn a
            // successful live menu refresh into a misleading FromCache state.
            if (imageCache != null) {
                entities = cacheImages(entities)
            }

            val now = System.currentTimeMillis()
            val metadata = MenuCacheMetadataEntity(
                restaurantKey = restaurantKey,
                lastSyncedAt = now,
                itemCount = entities.size,
            )
            menuCacheDao.replaceAll(restaurantKey, entities, metadata)

            // Prune images that are no longer referenced by any menu item.
            // Pruning is also best-effort for the same reason: product truth is live
            // even if a local image-cache maintenance task fails.
            if (imageCache != null) {
                val activeUrls = entities.flatMap { listOfNotNull(it.imageUrl, it.subcategoryImageUrl) }.toSet()
                runCatching { imageCache.pruneUnused(activeUrls) }
                    .onFailure { t ->
                        log("image cache prune failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
            }

            log("synced ${entities.size} items to cache")
            MenuSyncResult.Fresh
        } catch (t: Throwable) {
            log("request failed: ${t.javaClass.simpleName}: ${t.message}")
            fallbackOrNoData(
                restaurantKey,
                "Tuotelistan haku epÃ¤onnistui: ${t.javaClass.simpleName}: ${t.message ?: "ei viestiÃ¤"}"
            )
        } finally {
            connection.disconnect()
        }

        _syncState.value = result
        result
    }

    private suspend fun cacheImages(
        entities: List<BackendMenuItemEntity>,
    ): List<BackendMenuItemEntity> {
        val cache = imageCache ?: return entities
        return entities.map { entity ->
            val cachedProduct = entity.imageUrl?.let { url ->
                cacheImageBestEffort(cache, url, entity.id, "product")
            }
            val cachedSubcategory = entity.subcategoryImageUrl?.let { url ->
                cacheImageBestEffort(cache, url, entity.id, "subcategory")
            }
            entity.copy(
                cachedImagePath = cachedProduct,
                cachedSubcategoryImagePath = cachedSubcategory,
            )
        }
    }

    private suspend fun cacheImageBestEffort(
        cache: ProductImageCache,
        imageUrl: String,
        itemId: String,
        imageKind: String,
    ): String? = runCatching {
        cache.ensureCached(imageUrl)
    }.onFailure { t ->
        log("image cache failed kind=$imageKind item=$itemId url='$imageUrl': ${t.javaClass.simpleName}: ${t.message}")
    }.getOrNull()

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
        subcategoryVisuals: Map<String, String>,
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
                    subcategoryImageUrl = subcategoryVisuals[
                        subcategoryVisualKey(
                            obj.optString("category", ""),
                            obj.optString("subcategory"),
                        )
                    ],
                    cachedAt = now,
                )
            )
        }
        return result
    }

    private fun fetchSubcategoryVisualMap(
        urlString: String,
        baseUrl: String,
    ): Map<String, String> {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val statusCode = connection.responseCode
            val body = readStream(if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            if (statusCode !in 200..299) {
                log("subcategory visuals skipped: status=$statusCode body=${body.take(200)}")
                emptyMap()
            } else {
                parseSubcategoryVisualMap(body, baseUrl)
            }
        } catch (t: Throwable) {
            log("subcategory visuals request failed: ${t.javaClass.simpleName}: ${t.message}")
            emptyMap()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSubcategoryVisualMap(
        json: String,
        baseUrl: String,
    ): Map<String, String> {
        val array = JSONArray(json.trim())
        val result = linkedMapOf<String, String>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val category = obj.optString("category", "")
            val subcategory = obj.optString("subcategory", "")
            val imageUrl = resolveImageUrl(obj.optString("image_url"), baseUrl) ?: continue
            val key = subcategoryVisualKey(category, subcategory)
            if (key.isNotEmpty()) {
                result[key] = imageUrl
            }
        }
        return result
    }

    /**
     * Converts a backend image_url value to an absolute URL ready for Coil.
     *
     * - Already absolute (http/https) â†’ returned as-is
     * - Relative path starting with "/" â†’ prepend baseUrl
     * - Empty or unrecognised â†’ null
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
    imageUrl = cachedImagePath?.let { "file://$it" } ?: imageUrl,
    subcategory = subcategory,
    subcategoryImageUrl = cachedSubcategoryImagePath?.let { "file://$it" } ?: subcategoryImageUrl,
)

private fun subcategoryVisualKey(category: String?, subcategory: String?): String {
    val normalizedCategory = category.orEmpty().trim().lowercase()
    val normalizedSubcategory = subcategory.orEmpty().trim().lowercase()
    if (normalizedCategory.isEmpty() || normalizedSubcategory.isEmpty()) {
        return ""
    }
    return "$normalizedCategory|$normalizedSubcategory"
}
