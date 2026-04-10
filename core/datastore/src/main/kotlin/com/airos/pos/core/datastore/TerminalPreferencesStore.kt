package com.airos.pos.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airos.pos.core.model.TerminalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.net.URI

private val Context.terminalPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "terminal_preferences")
private const val DEFAULT_EDGE_BASE_URL = "http://192.168.8.158:8000/"
private const val LEGACY_EMULATOR_HOST = "10.0.2.2"
private const val PHYSICAL_EDGE_HOST = "192.168.8.158"
private const val DEFAULT_EDGE_PORT = 8000

class TerminalPreferencesStore(
    private val context: Context,
) {
    private object Keys {
        val terminalName = stringPreferencesKey("terminal_name")
        val edgeBaseUrl = stringPreferencesKey("edge_base_url")
        val offlineMode = booleanPreferencesKey("offline_mode")
        val preferredPrinterId = stringPreferencesKey("preferred_printer_id")
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
                preferredPrinterId = preferences[Keys.preferredPrinterId],
            )
        }

    suspend fun updateTerminalName(value: String) {
        context.terminalPreferencesDataStore.edit { it[Keys.terminalName] = value }
    }

    suspend fun updateEdgeBaseUrl(value: String) {
        context.terminalPreferencesDataStore.edit { it[Keys.edgeBaseUrl] = normalizeEdgeBaseUrl(value) }
    }

    suspend fun setOfflineMode(enabled: Boolean) {
        context.terminalPreferencesDataStore.edit { it[Keys.offlineMode] = enabled }
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

    private fun normalizeEdgeBaseUrl(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return normalized
        }

        return try {
            val uri = URI(normalized)
            if (uri.host != LEGACY_EMULATOR_HOST) {
                normalized
            } else {
                URI(
                    uri.scheme ?: "http",
                    uri.userInfo,
                    PHYSICAL_EDGE_HOST,
                    if (uri.port == -1) DEFAULT_EDGE_PORT else uri.port,
                    uri.path,
                    uri.query,
                    uri.fragment,
                ).toString()
            }
        } catch (_: Exception) {
            normalized
        }
    }
}
