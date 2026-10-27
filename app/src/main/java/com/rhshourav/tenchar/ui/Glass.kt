package com.rhshourav.tenchar.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.rhshourav.tenchar.R
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Glass
// ---------------------------------------------------------------------------

private val GlassFill = Brush.linearGradient(
    listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.05f)),
)

/** Bright on the lit corner, almost gone in the middle, a little back at the far edge. */
private val GlassEdge = Brush.linearGradient(
    listOf(
        Color.White.copy(alpha = 0.34f),
        Color.White.copy(alpha = 0.05f),
        Color.White.copy(alpha = 0.16f),
    ),
)

fun tintOf(color: Color, alpha: Float): Brush = SolidColor(color.copy(alpha = alpha))

/**
 * Frosted plate: a translucent fill, an optional tint, a lit hairline edge and
 * a thin specular line along the top. The aurora behind it supplies the
 * "refraction". There is deliberately no backdrop blur: Compose has none
 * without a third-party library, and this project pins every dependency.
 */
fun Modifier.glass(shape: Shape, tint: Brush? = null): Modifier {
    var m = this.clip(shape).background(GlassFill, shape)
    if (tint != null) m = m.background(tint, shape)
    return m
        .drawBehind {
            val inset = minOf(size.height / 2f, 20.dp.toPx())
            val end = size.width - inset
            if (end > inset) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.40f), Color.Transparent),
                        startX = inset,
                        endX = end,
                    ),
                    start = Offset(inset, 0.5f),
                    end = Offset(end, 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        .border(1.dp, GlassEdge, shape)
}

/** A soft tick for taps. CONTEXT_CLICK is the gentlest standard haptic. */
@Composable
fun rememberTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) } }
}

/** Springy press feedback with a haptic tick. No ripple: the plate itself moves. */
@Composable
fun Modifier.glassClickable(enabled: Boolean, role: Role?, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val tick = rememberTick()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role) {
            tick()
            onClick()
        }
}

// ---------------------------------------------------------------------------
// Aurora background
// ---------------------------------------------------------------------------

private const val TWO_PI = 6.2831855f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.glow(
    color: Color,
    alpha: Float,
    at: Offset,
    radius: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = at,
            radius = radius,
        ),
        radius = radius,
        center = at,
    )
}

/**
 * The one ambient moment: five soft colour fields drifting very slowly behind
 * everything. It is drawn once at the app root so screen changes do not reset
 * it. It reads the animation state in the draw phase only, so it never
 * recomposes. With animations off it sits still.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val phase: State<Float> = if (reduceMotion) {
        remember { mutableFloatStateOf(0.2f) }
    } else {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 60_000, easing = LinearEasing)),
            label = "aurora-phase",
        )
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Tc.Space)
            .drawBehind {
                val w = size.width
                val h = size.height
                val a = phase.value * TWO_PI
                glow(Tc.Plum, 1f, Offset(w * 0.5f, h * 0.45f), maxOf(w, h) * 0.95f)
                glow(Tc.Indigo, 0.55f, Offset(w * (0.10f + 0.10f * sin(a)), h * (0.10f + 0.04f * cos(a))), w * 1.0f)
                glow(Tc.Lavender, 0.22f, Offset(w * (0.95f - 0.10f * cos(a)), h * (0.30f + 0.05f * sin(a))), w * 0.7f)
                glow(Tc.Peach, 0.14f, Offset(w * (0.90f + 0.06f * sin(a + 2f)), h * (0.95f - 0.04f * cos(a))), w * 0.8f)
                glow(Tc.Mint, 0.12f, Offset(w * (0.05f + 0.08f * cos(a + 1f)), h * (0.88f + 0.04f * sin(a))), w * 0.7f)
            },
    )
}

// ---------------------------------------------------------------------------
// Logo
// ---------------------------------------------------------------------------

/** The TenChar logo (res/drawable/logo_black.png). Decorative: the screen title names the app. */
@Composable
fun TcMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_black),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}

private class Orbiter(val degrees: Float, val color: Color, val radiusDp: Float)

private val Orbiters = listOf(
    Orbiter(200f, Tc.Mint, 4f),
    Orbiter(320f, Tc.Peach, 3f),
    Orbiter(85f, Tc.LavenderSoft, 5f),
)

/** The mark on a frosted disc, with the three accent dots orbiting it slowly. */
@Composable
fun LogoHero(
    modifier: Modifier = Modifier,
    diameter: Dp = 176.dp,
    halo: Color = Tc.Lavender,
) {
    val reduceMotion = LocalReduceMotion.current
    val angle: State<Float> = if (reduceMotion) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "orbit").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 40_000, easing = LinearEasing)),
            label = "orbit-angle",
        )
    }
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2f
            glow(halo, 0.34f, c, r * 1.35f)
            val orbit = r - 4.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.08f), radius = orbit, center = c, style = Stroke(1.dp.toPx()))
            rotate(angle.value, pivot = c) {
                for (o in Orbiters) {
                    val rad = Math.toRadians(o.degrees.toDouble())
                    val p = Offset(c.x + orbit * cos(rad).toFloat(), c.y + orbit * sin(rad).toFloat())
                    val dot = o.radiusDp.dp.toPx()
                    glow(o.color, 0.6f, p, dot * 3.2f)
                    drawCircle(o.color, radius = dot, center = p)
                }
            }
        }
        Box(Modifier.size(diameter * 0.66f).glass(CircleShape), contentAlignment = Alignment.Center) {
            TcMark(size = diameter * 0.40f)
        }
    }
}

/** A small glowing dot: bullets, status, progress. */
@Composable
fun GlowDot(color: Color, diameter: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(diameter)
            .drawBehind {
                val r = this.size.minDimension / 2f
                glow(color, 0.55f, center, r * 2.8f)
                drawCircle(color, radius = r)
            },
    )
}

// ---------------------------------------------------------------------------
// Icons (drawn, so no icon library is needed)
// ---------------------------------------------------------------------------

enum class TcIcon { ChevronLeft, ChevronRight, ArrowUp }

@Composable
fun TcIconView(icon: TcIcon, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shown = when {
        !rtl -> icon
        icon == TcIcon.ChevronLeft -> TcIcon.ChevronRight
        icon == TcIcon.ChevronRight -> TcIcon.ChevronLeft
        else -> icon
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val s = this.size.minDimension
        val path = Path()
        when (shown) {
            TcIcon.ChevronLeft -> {
                path.moveTo(s * 0.62f, s * 0.20f)
                path.lineTo(s * 0.34f, s * 0.50f)
                path.lineTo(s * 0.62f, s * 0.80f)
            }
            TcIcon.ChevronRight -> {
                path.moveTo(s * 0.38f, s * 0.20f)
                path.lineTo(s * 0.66f, s * 0.50f)
                path.lineTo(s * 0.38f, s * 0.80f)
            }
            TcIcon.ArrowUp -> {
                path.moveTo(s * 0.50f, s * 0.82f)
                path.lineTo(s * 0.50f, s * 0.20f)
                path.moveTo(s * 0.24f, s * 0.46f)
                path.lineTo(s * 0.50f, s * 0.20f)
                path.lineTo(s * 0.76f, s * 0.46f)
            }
        }
        drawPath(path, tint, style = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}