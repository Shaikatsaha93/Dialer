package com.example.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Colors for the frosted-glass look ("liquid glass"), one set per light/dark theme. */
@Immutable
data class GlassColors(
    val fill: Color,
    val sheen: Color,
    val edgeTop: Color,
    val edgeBottom: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val orbs: List<Color>
)

val LightGlassColors = GlassColors(
    fill = Color.White.copy(alpha = 0.48f),
    sheen = Color.White.copy(alpha = 0.38f),
    edgeTop = Color.White.copy(alpha = 0.95f),
    edgeBottom = Color.White.copy(alpha = 0.30f),
    backgroundTop = Color(0xFFF2F2F7),
    backgroundBottom = Color(0xFFE8EDF5),
    // Faint iOS system-blue glows on the neutral iOS grouped-background gray
    orbs = listOf(Color(0x80007AFF), Color(0x665AC8FA), Color(0x4D007AFF))
)

val DarkGlassColors = GlassColors(
    fill = Color.White.copy(alpha = 0.07f),
    sheen = Color.White.copy(alpha = 0.07f),
    edgeTop = Color.White.copy(alpha = 0.30f),
    edgeBottom = Color.White.copy(alpha = 0.06f),
    backgroundTop = Color(0xFF000000),
    backgroundBottom = Color(0xFF0A0D14),
    orbs = listOf(Color(0x990A84FF), Color(0x4D64D2FF), Color(0x660A84FF))
)

val LocalGlassColors = staticCompositionLocalOf { LightGlassColors }

/**
 * Frosted glass panel: translucent fill, a soft top sheen and a bright hairline edge that
 * fades toward the bottom, like light catching the rim of a glass pane.
 */
fun Modifier.glass(
    shape: Shape,
    colors: GlassColors,
    fill: Color = colors.fill,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .background(Brush.verticalGradient(listOf(colors.sheen, Color.Transparent)), shape)
    .border(
        borderWidth,
        Brush.linearGradient(
            colors = listOf(colors.edgeTop, colors.edgeBottom),
            start = Offset.Zero,
            end = Offset(0f, Float.POSITIVE_INFINITY)
        ),
        shape
    )

/** Gradient edge for components that take a BorderStroke (Card, Surface). */
fun glassEdgeBrush(colors: GlassColors): Brush = Brush.verticalGradient(listOf(colors.edgeTop, colors.edgeBottom))

/** App-wide backdrop: a soft gradient with blurred color orbs that the glass panels sit on. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalGlassColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val spots = listOf(
                Triple(Offset(w * 0.08f, h * 0.10f), w * 0.75f, 0.55f),
                Triple(Offset(w * 1.00f, h * 0.45f), w * 0.70f, 0.45f),
                Triple(Offset(w * 0.15f, h * 0.92f), w * 0.80f, 0.40f)
            )
            spots.forEachIndexed { i, (center, radius, alpha) ->
                val color = colors.orbs[i % colors.orbs.size]
                drawCircle(
                    brush = Brush.radialGradient(
                        // Orb colors carry their own intensity in alpha
                        colors = listOf(color.copy(alpha = alpha * color.alpha), color.copy(alpha = 0f)),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }
        content()
    }
}
