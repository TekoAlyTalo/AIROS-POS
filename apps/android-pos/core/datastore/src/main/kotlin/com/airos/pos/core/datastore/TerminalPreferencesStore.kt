package com.airos.pos.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airos.pos.core.model.TerminalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.net.URI
import java.util.UUID

private val Context.terminalPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "terminal_preferences")
private const val DEFAULT_EDGE_BASE_URL = "http://192.168.8.158:8000"
private const val LEGACY_EMULATOR_HOST = "10.0.2.2"
private const val PHYSICAL_EDGE_HOST = "192.168.8.158"
private const val DEFAULT_EDGE_PORT = 8000
// Default restaurant scope used by attendance sync + menu fetch when no persisted
// override is present. Kept identical to the value hardcoded in BackendMenuRepository
// before this wiring pass so current deployments continue to behave the same way.
internal const val DEFAULT_RESTAURANT_KEY = "ravintola_default"

class TerminalPreferencesStore(
    private val context: Context,
) {
    private object Keys {
        val terminalName = stringPreferencesKey("terminal_name")
        val edgeBaseUrl = stringPreferencesKey("edge_base_url")
        val offlineMode = booleanPreferencesKey("offline_mode")
        val nfcDirectLogin = booleanPreferencesKey("nfc_direct_login")
        val preferredPrinterId = stringPreferencesKey("preferred_printer_id")
        val defaultOpeningFloatCents = intPreferencesKey("default_opening_float_cents")
        val terminalInstallationId = stringPreferencesKey("terminal_installation_id")
        val restaurantKey = stringPreferencesKey("restaurant_key")
    }

    val settings: Flow<TerminalSettings> = context.terminalPreferencesDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            TerminalSettings(
                terminalName = preferences[Keys.terminalName] ?: "AIROS POS Tablet",
                edgeBaseUrl = normalizeEdgeBaseUrl(preferences[Keys.edgeBaseUrl] ?: DEFAULT_EDGE_BASE_URL),
                offlineModeEnabled = preferences[Keys.offlineMode] ?: true,
                nfcDirectLoginEnabled = preferences[Keys.nfcDirectLogin] ?: false,
                preferredPrinterId = preferences[Keys.preferredPrinterId],
                defaultOpeningFloatCents = preferences[Keys.defaultOpeningFloatCents] ?: 5000,
                restaurantKey = preferences[Keys.restaurantKey]?.trim()?.ifBlank { null } ?: DEFAULT_RESTAURANT_KEY,
            )
        }

    suspend fun updateTerminalName(value: String) {
        context.terminalPreferencesDataStore.edit { it[Keys.terminalName] = value }
    }

    suspend fun updateEdgeBaseUrl(value: String) {
        val normalized = normalizeEdgeBaseUrlOrNull(value) ?: return
        context.terminalPreferencesDataStore.edit { it[Keys.edgeBaseUrl] = normalized }
    }

    suspend fun setOfflineMode(enabled: Boolean) {
        context.terminalPreferencesDataStore.edit { it[Keys.offlineMode] = enabled }
    }

    suspend fun setNfcDirectLoginEnabled(enabled: Boolean) {
        context.terminalPreferencesDataStore.edit { it[Keys.nfcDirectLogin] = enabled }
    }

    suspend fun updateDefaultOpeningFloatCents(cents: Int) {
        context.terminalPreferencesDataStore.edit { it[Keys.defaultOpeningFloatCents] = cents }
    }

    suspend fun updatePreferredPrinter(id: String?) {
        context.terminalPreferencesDataStore.edit {
            if (id == null) {
                it.remove(Keys.preferredPrinterId)
            } else {
                it[Keys.preferredPrinterId] = id
            }
        }
    }

    // Update the persisted restaurant scope key. Attendance sync metadata is keyed
    // on this value, so changing it at runtime will start a fresh per-restaurant
    // sync lineage rather than corrupt the existing one. Blank / whitespace values
    // fall back to DEFAULT_RESTAURANT_KEY on read.
    suspend fun updateRestaurantKey(value: String) {
        val normalized = value.trim()
        context.terminalPreferencesDataStore.edit { prefs ->
            if (normalized.isBlank()) {
                prefs.remove(Keys.restaurantKey)
            } else {
                prefs[Keys.restaurantKey] = normalized
            }
        }
    }

    // Stable per-install technical terminal identifier. Generated once on first
    // access and persisted; NEVER derived from the mutable user-facing terminal
    // name. Required so attendance sync metadata and terminal-sequence numbering
    // remain consistent across terminal renames.
    suspend fun terminalInstallationId(): String {
        val existing = context.terminalPreferencesDataStore.data.first()[Keys.terminalInstallationId]
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        context.terminalPreferencesDataStore.edit { prefs ->
            val current = prefs[Keys.terminalInstallationId]
            if (current.isNullOrBlank()) {
                prefs[Keys.terminalInstallationId] = generated
            }
        }
        return context.terminalPreferencesDataStore.data.first()[Keys.terminalInstallationId] ?: generated
    }

    private fun normalizeEdgeBaseUrl(value: String): String {
        return normalizeEdgeBaseUrlOrNull(value) ?: DEFAULT_EDGE_BASE_URL
    }

    private fun normalizeEdgeBaseUrlOrNull(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        if (trimmed.any { it.isWhitespace() }) return null

        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }

        return try {
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null

            var host = uri.host?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val rawPath = uri.rawPath
            if (!rawPath.isNullOrBlank() && rawPath != "/") return null
            if (!uri.rawQuery.isNullOrBlank()) return null
            if (!uri.rawFragment.isNullOrBlank()) return null

            val port = when {
                uri.port != -1 -> uri.port
                host == LEGACY_EMULATOR_HOST -> DEFAULT_EDGE_PORT
                else -> -1
            }
            if (port != -1 && port !in 1..65535) return null

            if (host == LEGACY_EMULATOR_HOST) {
                host = PHYSICAL_EDGE_HOST
            }

            URI(
                scheme,
                null,
                host,
                port,
                null,
                null,
                null,
            ).toString()
        } catch (_: Exception) {
            null
        }
    }
}
