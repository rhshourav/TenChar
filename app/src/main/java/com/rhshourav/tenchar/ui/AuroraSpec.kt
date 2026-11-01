package com.rhshourav.tenchar.ui

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Where the aurora's five glows are, for a window of [w] by [h] at a point in the 60-second loop.
 * AuroraBackground draws exactly these, and ContrastTest measures exactly these, so the test sees
 * what the screen shows.
 */
object AuroraSpec {
    const val LOOP_MS = 60_000
    private const val TWO_PI = 6.2831855f

    /** A radial glow: [alpha] at ([x], [y]), fading to nothing at [radius]. */
    class Glow(val color: Long, val alpha: Float, val x: Float, val y: Float, val radius: Float)

    /** [phase] is the position in the loop, 0 to 1. */
    fun glows(w: Float, h: Float, phase: Float): List<Glow> {
        val a = phase * TWO_PI
        return listOf(
            Glow(Palette.PLUM, Palette.GLOW_PLUM, w * 0.5f, h * 0.45f, max(w, h) * 0.95f),
            Glow(Palette.INDIGO, Palette.GLOW_INDIGO, w * (0.10f + 0.10f * sin(a)), h * (0.10f + 0.04f * cos(a)), w * 1.0f),
            Glow(Palette.LAVENDER, Palette.GLOW_LAVENDER, w * (0.95f - 0.10f * cos(a)), h * (0.30f + 0.05f * sin(a)), w * 0.7f),
            Glow(Palette.PEACH, Palette.GLOW_PEACH, w * (0.90f + 0.06f * sin(a + 2f)), h * (0.95f - 0.04f * cos(a)), w * 0.8f),
            Glow(Palette.MINT, Palette.GLOW_MINT, w * (0.05f + 0.08f * cos(a + 1f)), h * (0.88f + 0.04f * sin(a)), w * 0.7f),
        )
    }
}