package com.airos.pos.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val StartupVisibleDurationMs = 1700L
private const val StartupExitDurationMs = 160L
private const val StartupEdgeSweepDelayMs = 90L
private const val StartupEdgeSweepDurationMs = 1140
private const val StartupPulseDelayMs = 1000L
private const val StartupPulseDurationMs = 260
private const val StartupLogoRevealDelayMs = 1120L
private const val StartupLogoRevealDurationMs = 300

private val StartupBackdropBase = Color(0xFF07100C)

private val GoldBlackBronze = Color(0xFF1B0E07)
private val GoldDarkBronze = Color(0xFF2D170A)
private val GoldBronze = Color(0xFF3D1D0B)
private val GoldAntique = Color(0xFF4A2610)
private val GoldMetal = Color(0xFF5A3622)
private val GoldHotMetal = Color(0xFF6F5820)
private val GoldEdge = Color(0xFF8A6D29)

private val PulseCore = Color(0xCCF1D38A)
private val PulseOuter = Color(0x66A1722A)

@Composable
fun AirosPosStartupHost(
    appContainer: AppContainer,
) {
    var showStartupOverlay by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        AirosPosApp(appContainer = appContainer)

        if (showStartupOverlay) {
            AirosStartupOverlay(
                onFinished = { showStartupOverlay = false },
            )
        }
    }
}

@Composable
private fun AirosStartupOverlay(
    onFinished: () -> Unit,
) {
    val sweepProgress = remember { Animatable(0f) }
    val pulseProgress = remember { Animatable(0f) }
    var showLogo by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (isExiting) 0f else 1f,
        animationSpec = tween(durationMillis = StartupExitDurationMs.toInt(), easing = FastOutSlowInEasing),
        label = "startupOverlayAlpha",
    )

    val logoAlpha by animateFloatAsState(
        targetValue = if (showLogo) 1f else 0f,
        animationSpec = tween(durationMillis = StartupLogoRevealDurationMs, easing = LinearOutSlowInEasing),
        label = "startupLogoAlpha",
    )

    val logoScale by animateFloatAsState(
        targetValue = if (showLogo) 1f else 0.965f,
        animationSpec = tween(durationMillis = StartupLogoRevealDurationMs, easing = FastOutSlowInEasing),
        label = "startupLogoScale",
    )

    LaunchedEffect(Unit) {
        launch {
            delay(StartupEdgeSweepDelayMs)
            sweepProgress.snapTo(0f)
            sweepProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = StartupEdgeSweepDurationMs, easing = FastOutSlowInEasing),
            )
        }

        launch {
            delay(StartupPulseDelayMs)
            pulseProgress.snapTo(0f)
            pulseProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = StartupPulseDurationMs, easing = FastOutSlowInEasing),
            )
        }

        launch {
            delay(StartupLogoRevealDelayMs)
            showLogo = true
        }

        delay(StartupVisibleDurationMs)
        isExiting = true
        delay(StartupExitDurationMs)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = overlayAlpha),
    ) {
        StartupBackdrop()
        StartupEdgeLines(progress = sweepProgress.value)
        StartupCenterPulse(progress = pulseProgress.value)
        StartupLogo(
            alpha = logoAlpha,
            scale = logoScale,
        )
    }
}

@Composable
private fun StartupBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StartupBackdropBase),
    ) {
        Image(
            painter = painterResource(id = R.drawable.airos_pos_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun StartupLogo(
    alpha: Float,
    scale: Float,
) {
    if (alpha <= 0f) return

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.airos_logo),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(
                    alpha = alpha,
                    scaleX = scale,
                    scaleY = scale,
                ),
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
        )
    }
}

@Composable
private fun StartupCenterPulse(progress: Float) {
    if (progress <= 0f || progress >= 1f) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width * 0.5f
        val y = size.height * 0.58125f

        val coreRadius = size.minDimension * (0.004f + (0.011f * progress))
        val outerRadius = size.minDimension * (0.012f + (0.028f * progress))

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    PulseCore,
                    Color(0x88D5A24B),
                    Color(0x00D5A24B),
                ),
                center = Offset(centerX, y),
                radius = outerRadius,
            ),
            radius = outerRadius,
            center = Offset(centerX, y),
        )

        drawCircle(
            color = PulseOuter,
            radius = coreRadius,
            center = Offset(centerX, y),
        )
    }
}

@Composable
private fun StartupEdgeLines(progress: Float) {
    if (progress <= 0f || progress >= 1f) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val y = size.height * 0.58125f
        val centerX = size.width * 0.5f
        val reach = centerX * progress
        val lineHeight = size.height * 0.0054f

        val leftStart = 0f
        val leftEnd = reach.coerceAtMost(centerX)

        val rightStart = size.width
        val rightEnd = (size.width - reach).coerceAtLeast(centerX)

        if (leftEnd > leftStart) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        GoldBlackBronze,
                        GoldDarkBronze,
                        GoldBronze,
                        GoldAntique,
                        GoldMetal,
                        GoldHotMetal,
                        GoldEdge,
                    ),
                    startX = leftStart,
                    endX = leftEnd,
                ),
                topLeft = Offset(leftStart, y - lineHeight / 2f),
                size = Size(leftEnd - leftStart, lineHeight),
            )
        }

        if (rightStart > rightEnd) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        GoldEdge,
                        GoldHotMetal,
                        GoldMetal,
                        GoldAntique,
                        GoldBronze,
                        GoldDarkBronze,
                        GoldBlackBronze,
                    ),
                    startX = rightEnd,
                    endX = rightStart,
                ),
                topLeft = Offset(rightEnd, y - lineHeight / 2f),
                size = Size(rightStart - rightEnd, lineHeight),
            )
        }
    }
}
