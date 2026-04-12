package com.airos.pos.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airos.pos.core.model.StaffUiPreferences
import com.airos.pos.core.model.StaffTableMapViewPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.staffUiPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "staff_ui_preferences")

class StaffUiPreferencesStore(
    private val context: Context,
) {
    fun observeStaffUiPreferences(staffId: String): Flow<StaffUiPreferences> {
        return context.staffUiPreferencesDataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                StaffUiPreferences(
                    tableMapViewMode = preferences[tableMapViewModeKey(staffId)]
                        ?.toStaffTableMapViewPreference()
                        ?: StaffTableMapViewPreference.FLOOR_PLAN,
                )
            }
    }

    fun observeTableMapViewMode(staffId: String): Flow<StaffTableMapViewPreference> {
        return observeStaffUiPreferences(staffId).map { it.tableMapViewMode }
    }

    suspend fun setTableMapViewMode(
        staffId: String,
        mode: StaffTableMapViewPreference,
    ) {
        context.staffUiPreferencesDataStore.edit { preferences ->
            preferences[tableMapViewModeKey(staffId)] = mode.name
        }
    }

    private fun tableMapViewModeKey(staffId: String): Preferences.Key<String> {
        return stringPreferencesKey("table_map_view_mode_${staffId.trim()}")
    }

    private fun String.toStaffTableMapViewPreference(): StaffTableMapViewPreference {
        return runCatching { StaffTableMapViewPreference.valueOf(this) }
            .getOrDefault(StaffTableMapViewPreference.FLOOR_PLAN)
    }
}
