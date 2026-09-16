package com.github.tvbox.osc.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.navbar.InteractiveHighlight
import com.github.tvbox.osc.ui.theme.LiquidGlassState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.min

internal val LocalTopBarGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

internal const val GLASS_BACKDROP_BAND_MARGIN_DP = 64

@Composable
fun Modifier.glassTopBarSurface(
    shape: Shape,
    fallbackColor: Color,
): Modifier = glassSurface(LocalTopBarGlassBackdrop.current, shape, fallbackColor)

@Composable
fun Modifier.glassSurface(
    shape: Shape,
    fallbackColor: Color,
): Modifier = glassSurface(emptyBackdrop(), shape, fallbackColor)

@Composable
private fun Modifier.glassSurface(
    backdrop: Backdrop?,
    shape: Shape,
    fallbackColor: Color,
): Modifier {
    val config = LiquidGlassState.config
    val glassEnabled = backdrop != null &&
        config.controlsEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (!glassEnabled) {
        return this.clip(shape).background(fallbackColor)
    }

    val density = LocalDensity.current
    val blurPx = with(density) { config.blurDp.dp.toPx() }
    val distortionPx = with(density) { config.distortionDp.dp.toPx() }
    val supportsLens = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.45f)
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }

    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurPx)
            if (supportsLens) {
                val refraction = min(distortionPx, size.minDimension / 2f)
                lens(refraction, refraction)
            }
        },
        highlight = { Highlight.Default },
        shadow = {
            Shadow(
                radius = 8.dp,
                color = Color.Black.copy(if (isLightTheme) 0.08f else 0.16f),
            )
        },
        innerShadow = { InnerShadow(radius = 4.dp, alpha = 0.1f) },
        layerBlock = {
            val progress = interactiveHighlight.pressProgress
            if (progress > 0f && size.height > 0f && size.width > 0f) {
                val growthPx = 4.dp.toPx() * progress
                scaleY = 1f + growthPx / size.height
                scaleX = 1f + growthPx / size.maxDimension
            }
        },
        onDrawSurface = { drawRect(containerColor) },
    )
        .then(interactiveHighlight.modifier)
        .then(interactiveHighlight.gestureModifier)
}