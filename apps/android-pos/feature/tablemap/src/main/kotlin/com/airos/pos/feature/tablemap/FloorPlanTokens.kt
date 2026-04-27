package com.airos.pos.feature.tablemap

import androidx.compose.ui.graphics.Color

internal enum class TableMapViewMode {
    GRID,
    FLOOR_PLAN,
}

internal enum class FloorPlanVisualStyle {
    SIMPLE,
    RICH,
}

internal data class TableMapPalette(
    val shellColor: Color,
    val panelColor: Color,
    val panelAltColor: Color,
    val panelAccentColor: Color,
    val borderColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentText: Color,
    val accentContrastText: Color,
    val availableColor: Color,
    val occupiedColor: Color,
    val dirtyColor: Color,
    val reservedColor: Color,
)

internal object TableMapVisualTokens {
    val Current = TableMapPalette(
        shellColor = Color(0xFF0D151E),
        panelColor = Color(0xFF131E29),
        panelAltColor = Color(0xFF182633),
        panelAccentColor = Color(0xFF153847),
        borderColor = Color(0x14FFFFFF),
        textPrimary = Color(0xFFFBFEFF),
        textSecondary = Color(0xFFE8F0F6),
        textMuted = Color(0xFFC0CCD6),
        accentText = Color(0xFF85F5E0),
        accentContrastText = Color(0xFF072127),
        availableColor = Color(0xFF51DBD4),
        occupiedColor = Color(0xFFFFB055),
        dirtyColor = Color(0xFFFF6D4A),
        reservedColor = Color(0xFF86A6FF),
    )

    val ShellColor = Current.shellColor
    val PanelColor = Current.panelColor
    val PanelAltColor = Current.panelAltColor
    val PanelAccentColor = Current.panelAccentColor
    val BorderColor = Current.borderColor
    val TextPrimary = Current.textPrimary
    val TextSecondary = Current.textSecondary
    val TextMuted = Current.textMuted
    val AccentText = Current.accentText
    val AccentContrastText = Current.accentContrastText
    val AvailableColor = Current.availableColor
    val OccupiedColor = Current.occupiedColor
    val DirtyColor = Current.dirtyColor
    val ReservedColor = Current.reservedColor
}

internal data class FloorPlanViewpoint(
    val stationId: String,
    val defaultCenterX: Float,
    val defaultCenterY: Float,
    val defaultRotationDeg: Float = 0f,
)

internal val DefaultFloorPlanViewpoint = FloorPlanViewpoint(
    stationId = "main-pos",
    defaultCenterX = 760f,
    defaultCenterY = 452f,
    defaultRotationDeg = 0f,
)