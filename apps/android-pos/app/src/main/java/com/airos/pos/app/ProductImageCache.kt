package com.airos.pos.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Durable on-device image cache stored in app-internal storage.
 *
 * Downloaded images survive app restarts, process death, and backend outages.
 * The cache is keyed by URL — when the backend returns a different image_url
 * for an item, the old file is no longer referenced and will be cleaned up on
 * the next [pruneUnused] call.
 *
 * Directory layout:
 *     filesDir/product_images/<sha256-of-url>.{ext}
 *
 * Thread-safety: all public methods are suspend and run on [Dispatchers.IO].
 */
class ProductImageCache(context: Context) {

    private val cacheDir: File = File(context.applicationContext.filesDir, CACHE_DIR_NAME).also {
        it.mkdirs()
    }

    /**
     * Ensure the image at [imageUrl] is cached locally.
     *
     * @return absolute file path to the cached image, or null if download failed.
     */
    suspend fun ensureCached(imageUrl: String): String? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null

        val file = fileForUrl(imageUrl)
        if (file.exists() && file.length() > 0) {
            return@withContext file.absolutePath
        }

        downloadToFile(imageUrl, file)
    }

    /**
     * Return the local file path for a previously cached image, or null if not cached.
     * Does NOT download — purely a cache lookup.
     */
    fun getCachedPath(imageUrl: String): String? {
        if (imageUrl.isBlank()) return null
        val file = fileForUrl(imageUrl)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /**
     * Download all images in [urls] that are not already cached.
     * Returns the number of newly downloaded images.
     */
    suspend fun ensureAllCached(urls: Collection<String>): Int = withContext(Dispatchers.IO) {
        var downloaded = 0
        for (url in urls) {
            if (url.isBlank()) continue
            val file = fileForUrl(url)
            if (file.exists() && file.length() > 0) continue
            if (downloadToFile(url, file) != null) {
                downloaded++
            }
        }
        downloaded
    }

    /**
     * Remove cached files whose URLs are not in [activeUrls].
     * Call this after a successful menu sync to reclaim disk space.
     */
    suspend fun pruneUnused(activeUrls: Set<String>): Int = withContext(Dispatchers.IO) {
        val activeFileNames = activeUrls
            .filter { it.isNotBlank() }
            .map { fileForUrl(it).name }
            .toSet()

        var removed = 0
        val files = cacheDir.listFiles() ?: return@withContext 0
        for (file in files) {
            if (file.name !in activeFileNames) {
                if (file.delete()) removed++
            }
        }
        if (removed > 0) {
            log("pruneUnused: removed $removed stale cached images")
        }
        removed
    }

    private fun fileForUrl(url: String): File {
        val hash = sha256(url)
        val ext = extensionFromUrl(url)
        return File(cacheDir, "$hash.$ext")
    }

    private fun downloadToFile(imageUrl: String, target: File): String? {
        val connection = try {
            (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                useCaches = false
            }
        } catch (t: Throwable) {
            log("download connection failed: ${t.javaClass.simpleName}: ${t.message} url=$imageUrl")
            return null
        }

        return try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                log("download HTTP $statusCode for $imageUrl")
                return null
            }

            val tmpFile = File(cacheDir, "${target.name}.tmp")
            connection.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            if (tmpFile.length() == 0L) {
                tmpFile.delete()
                log("download empty body for $imageUrl")
                return null
            }

            // Atomic rename so readers never see a partial file.
            if (tmpFile.renameTo(target)) {
                target.absolutePath
            } else {
                // renameTo can fail on some Android filesystems — fall back to copy.
                tmpFile.copyTo(target, overwrite = true)
                tmpFile.delete()
                target.absolutePath
            }
        } catch (t: Throwable) {
            log("download failed: ${t.javaClass.simpleName}: ${t.message} url=$imageUrl")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun extensionFromUrl(url: String): String {
        val pathPart = url.substringBefore('?').substringAfterLast('/')
        val ext = pathPart.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "jpg"
            "png" -> "png"
            "webp" -> "webp"
            else -> "img"
        }
    }

    private fun log(message: String) {
        Log.d(TAG, "[ProductImageCache] $message")
    }

    companion object {
        private const val TAG = "AIROS"
        private const val CACHE_DIR_NAME = "product_images"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
