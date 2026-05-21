package com.airos.pos.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airos.pos.core.model.StaffFloorPlanViewportPreference
import com.airos.pos.core.model.StaffUiPreferences
import com.airos.pos.core.model.StaffUiLanguage
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
                    floorPlanViewport = StaffFloorPlanViewportPreference(
                        zoomScale = preferences[floorPlanZoomScaleKey(staffId)],
                        panX = preferences[floorPlanPanXKey(staffId)],
                        panY = preferences[floorPlanPanYKey(staffId)],
                    ),
                    uiLanguage = preferences[uiLanguageKey(staffId)]
                        ?.toStaffUiLanguage()
                        ?: StaffUiLanguage.FI,
                )
            }
    }

    fun observeTableMapViewMode(staffId: String): Flow<StaffTableMapViewPreference> {
        return observeStaffUiPreferences(staffId).map { it.tableMapViewMode }
    }

    fun observeUiLanguage(staffId: String): Flow<StaffUiLanguage> {
        return observeStaffUiPreferences(staffId).map { it.uiLanguage }
    }

    suspend fun setTableMapViewMode(
        staffId: String,
        mode: StaffTableMapViewPreference,
    ) {
        context.staffUiPreferencesDataStore.edit { preferences ->
            preferences[tableMapViewModeKey(staffId)] = mode.name
        }
    }

    suspend fun setFloorPlanViewport(
        staffId: String,
        viewport: StaffFloorPlanViewportPreference,
    ) {
        context.staffUiPreferencesDataStore.edit { preferences ->
            viewport.zoomScale?.let { preferences[floorPlanZoomScaleKey(staffId)] = it }
                ?: preferences.remove(floorPlanZoomScaleKey(staffId))
            viewport.panX?.let { preferences[floorPlanPanXKey(staffId)] = it }
                ?: preferences.remove(floorPlanPanXKey(staffId))
            viewport.panY?.let { preferences[floorPlanPanYKey(staffId)] = it }
                ?: preferences.remove(floorPlanPanYKey(staffId))
        }
    }

    suspend fun setUiLanguage(
        staffId: String,
        language: StaffUiLanguage,
    ) {
        context.staffUiPreferencesDataStore.edit { preferences ->
            preferences[uiLanguageKey(staffId)] = language.name
        }
    }

    private fun tableMapViewModeKey(staffId: String): Preferences.Key<String> {
        return stringPreferencesKey("table_map_view_mode_${staffId.trim()}")
    }

    private fun uiLanguageKey(staffId: String): Preferences.Key<String> {
        return stringPreferencesKey("ui_language_${staffId.trim()}")
    }

    private fun floorPlanZoomScaleKey(staffId: String): Preferences.Key<Float> {
        return floatPreferencesKey("table_map_floor_plan_zoom_scale_${staffId.trim()}")
    }

    private fun floorPlanPanXKey(staffId: String): Preferences.Key<Float> {
        return floatPreferencesKey("table_map_floor_plan_pan_x_${staffId.trim()}")
    }

    private fun floorPlanPanYKey(staffId: String): Preferences.Key<Float> {
        return floatPreferencesKey("table_map_floor_plan_pan_y_${staffId.trim()}")
    }

    private fun String.toStaffTableMapViewPreference(): StaffTableMapViewPreference {
        return runCatching { StaffTableMapViewPreference.valueOf(this) }
            .getOrDefault(StaffTableMapViewPreference.FLOOR_PLAN)
    }

    private fun String.toStaffUiLanguage(): StaffUiLanguage {
        return runCatching { StaffUiLanguage.valueOf(this) }
            .getOrDefault(StaffUiLanguage.FI)
    }
}
