package com.airos.pos.feature.tablemap

import androidx.compose.ui.graphics.Color

internal enum class TableMapViewMode {
    GRID,
    FLOOR_PLAN,
    PULSE,
}

internal enum class FloorPlanVisualStyle {
    SIMPLE,
    RICH,
}

internal enum class FloorPlanRenderMode {
    FLOOR_PLAN,
    PULSE,
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

// Floor / table / bar-counter visual surface seam.
//
// Why this exists: the POS renderer paints the floor, table tops and bar counter
// from preset color tokens. Each surface is exposed as a small, named token group
// so future settings UI can:
//   1. swap the active preset with a single key (theme select), and
//   2. override individual surface tokens (custom material per object).
//
// Keep this file boring: data classes + a registry. No theme engine.

internal enum class FloorPlanSurfacePresetKey {
    CLASSIC_PREMIUM_PUB,
}

internal data class FloorPlanFloorSurfaceTokens(
    val baseTop: Color,
    val baseMiddle: Color,
    val baseBottom: Color,
    val plankHighlight: Color,
    val plankSeam: Color,
    val plankShadow: Color,
    val grain: Color,
    val vignette: Color,
)

internal data class FloorPlanTableSurfaceTokens(
    val top: Color,
    val middle: Color,
    val bottom: Color,
    val highlight: Color,
    val grainLight: Color,
    val grainDark: Color,
    val edgeDark: Color,
    val edgeWarm: Color,
)

internal data class FloorPlanBarCounterSurfaceTokens(
    val top: Color,
    val middle: Color,
    val bottom: Color,
    val highlight: Color,
    val railLight: Color,
    val railDark: Color,
    val edge: Color,
    val grainLight: Color,
    val grainDark: Color,
    val frontPanelTop: Color,
    val frontPanelBottom: Color,
)

internal data class FloorPlanSurfaceTheme(
    val key: FloorPlanSurfacePresetKey,
    val viewportBackground: List<Color>,
    val viewportGrid: Color,
    val floor: FloorPlanFloorSurfaceTokens,
    val table: FloorPlanTableSurfaceTokens,
    val barCounter: FloorPlanBarCounterSurfaceTokens,
)

internal data class FloorPlanSurfaceCustomOverrides(
    val viewportBackground: List<Color>? = null,
    val viewportGrid: Color? = null,
    val floor: FloorPlanFloorSurfaceTokens? = null,
    val table: FloorPlanTableSurfaceTokens? = null,
    val barCounter: FloorPlanBarCounterSurfaceTokens? = null,
)

internal object FloorPlanSurfaceThemeRegistry {
    fun preset(key: FloorPlanSurfacePresetKey): FloorPlanSurfaceTheme = when (key) {
        FloorPlanSurfacePresetKey.CLASSIC_PREMIUM_PUB -> classicPremiumPub()
    }

    fun default(): FloorPlanSurfaceTheme = preset(FloorPlanSurfacePresetKey.CLASSIC_PREMIUM_PUB)

    fun resolve(
        presetKey: FloorPlanSurfacePresetKey = FloorPlanSurfacePresetKey.CLASSIC_PREMIUM_PUB,
        overrides: FloorPlanSurfaceCustomOverrides = FloorPlanSurfaceCustomOverrides(),
    ): FloorPlanSurfaceTheme {
        val base = preset(presetKey)
        return base.copy(
            viewportBackground = overrides.viewportBackground ?: base.viewportBackground,
            viewportGrid = overrides.viewportGrid ?: base.viewportGrid,
            floor = overrides.floor ?: base.floor,
            table = overrides.table ?: base.table,
            barCounter = overrides.barCounter ?: base.barCounter,
        )
    }

    private fun classicPremiumPub(): FloorPlanSurfaceTheme = FloorPlanSurfaceTheme(
        key = FloorPlanSurfacePresetKey.CLASSIC_PREMIUM_PUB,
        viewportBackground = listOf(
            Color(0xFF061015),
            Color(0xFF0A141B),
            Color(0xFF0D1820),
        ),
        viewportGrid = Color(0x1FB8CBD8),
        floor = FloorPlanFloorSurfaceTokens(
            baseTop = Color(0xFF263126),
            baseMiddle = Color(0xFF1E281F),
            baseBottom = Color(0xFF151C16),
            plankHighlight = Color(0xFFE4D1A2).copy(alpha = 0.075f),
            plankSeam = Color(0xFF070B08).copy(alpha = 0.36f),
            plankShadow = Color(0xFF050705).copy(alpha = 0.18f),
            grain = Color(0xFFC7B789).copy(alpha = 0.045f),
            vignette = Color(0xFF020403).copy(alpha = 0.18f),
        ),
        table = FloorPlanTableSurfaceTokens(
            top = Color(0xFF8A5428),
            middle = Color(0xFF5A2E13),
            bottom = Color(0xFF241006),
            highlight = Color(0xFFFFC47A).copy(alpha = 0.22f),
            grainLight = Color(0xFFD99A57),
            grainDark = Color(0xFF160804),
            edgeDark = Color(0xFF100602),
            edgeWarm = Color(0xFFC4863C),
        ),
        barCounter = FloorPlanBarCounterSurfaceTokens(
            top = Color(0xFF5C3216),
            middle = Color(0xFF2F1608),
            bottom = Color(0xFF0E0502),
            highlight = Color(0xFFE9B26A).copy(alpha = 0.24f),
            railLight = Color(0xFFFFD489).copy(alpha = 0.34f),
            railDark = Color(0xFF000000).copy(alpha = 0.55f),
            edge = Color(0xFFC8923E).copy(alpha = 0.78f),
            grainLight = Color(0xFFFFB870),
            grainDark = Color(0xFF0C0502),
            frontPanelTop = Color(0xFF3D1E0D),
            frontPanelBottom = Color(0xFF0A0401),
        ),
    )
}

internal object FloorPlanSurfaceThemeHolder {
    @Volatile
    var current: FloorPlanSurfaceTheme = FloorPlanSurfaceThemeRegistry.default()
}
