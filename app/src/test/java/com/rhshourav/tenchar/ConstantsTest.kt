package com.rhshourav.tenchar

import com.rhshourav.tenchar.ui.AuroraSpec
import com.rhshourav.tenchar.ui.Palette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Measures the colour claims instead of asserting them. Colours are alpha-composited in sRGB and
 * compared with the WCAG 2.x contrast ratio. Text must reach 4.5 : 1; symbols (a focus ring,
 * a chevron) 3 : 1.
 *
 * The surfaces come from the app itself: [AuroraSpec] gives the glows exactly as AuroraBackground
 * draws them, and [Palette] gives every alpha. The test samples three window shapes (phone, wide
 * desktop, near-square), 12 points of the 60-second loop and a 21 by 41 grid, so a pass means
 * "everywhere on screen, at every moment", not "at one chosen point".
 *
 * Plates are measured at their lightest (white 13 %). Peach text on a peach-tinted plate cannot
 * reach 4.5 : 1 anywhere on screen (about 3 : 1), so no text uses that pairing: the caution
 * button's text is ink. See [specifiedWorstCaseStackForTheRecord] for the harsher stack in the task.
 */
class ContrastTest {
    private class Rgb(val r: Double, val g: Double, val b: Double)

    private fun rgb(argb: Long) = Rgb(
        ((argb shr 16) and 0xFF) / 255.0, ((argb shr 8) and 0xFF) / 255.0, (argb and 0xFF) / 255.0,
    )

    private fun over(bg: Rgb, fg: Rgb, alpha: Float): Rgb {
        val a = alpha.toDouble()
        return Rgb(fg.r * a + bg.r * (1 - a), fg.g * a + bg.g * (1 - a), fg.b * a + bg.b * (1 - a))
    }

    private fun luminance(c: Rgb): Double {
        fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    }

    private fun ratio(a: Rgb, b: Rgb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val space = rgb(Palette.SPACE)
    private val white = Rgb(1.0, 1.0, 1.0)
    private val ink = rgb(Palette.INK)
    private val peach = rgb(Palette.PEACH)
    private val peachText = rgb(Palette.PEACH_TEXT)
    private val mint = rgb(Palette.MINT)
    private val lavender = rgb(Palette.LAVENDER)
    private val indigo = rgb(Palette.INDIGO)

    private class Spot(val color: Rgb, val where: String)

    /** The aurora as it is drawn, at every sampled point. */
    private val backgrounds: List<Spot> by lazy {
        val spots = ArrayList<Spot>()
        for ((w, h) in listOf(1080f to 2400f, 1200f to 800f, 800f to 1000f)) {
            for (step in 0 until 12) {
                val glows = AuroraSpec.glows(w, h, step / 12f)
                for (gx in 0..20) for (gy in 0..40) {
                    val x = w * gx / 20f
                    val y = h * gy / 40f
                    var c = space
                    for (g in glows) {
                        val d = Math.hypot((x - g.x).toDouble(), (y - g.y).toDouble()).toFloat()
                        c = over(c, rgb(g.color), g.alpha * maxOf(0f, 1f - d / g.radius))
                    }
                    spots += Spot(c, "${w.toInt()}x${h.toInt()} at ${gx * 5}% x ${gy * 100 / 40}% y, loop step $step/12")
                }
            }
        }
        spots
    }

    private fun plate(bg: Rgb) = over(bg, white, Palette.GLASS_FILL_LIGHT)

    /** Fails if the worst surface is under [min]; always prints the worst value, for the report. */
    private fun worst(name: String, min: Double, surfaces: (Rgb) -> List<Rgb>, foreground: (Rgb) -> Rgb) {
        var low = Double.MAX_VALUE
        var at = ""
        for (spot in backgrounds) for (s in surfaces(spot.color)) {
            val r = ratio(foreground(s), s)
            if (r < low) { low = r; at = spot.where }
        }
        println("%-52s worst %5.2f : 1  (needs %.1f)  %s".format(name, low, min, if (low >= min) "ok" else "FAIL at $at"))
        assertTrue("$name: $low : 1 at $at is below $min : 1", low >= min)
    }

    private fun inkAt(alpha: Float): (Rgb) -> Rgb = { s -> over(s, ink, alpha) }
    private val bare = { bg: Rgb -> listOf(bg) }
    private val plain = { bg: Rgb -> listOf(plate(bg)) }

    @Test
    fun inkIsReadableEverywhere() {
        worst("Ink on the bare background", 4.5, bare, inkAt(1f))
        worst("Ink on a plate", 4.5, plain, inkAt(1f))
        worst("Ink on a caution-tinted plate", 4.5, { bg -> listOf(over(plate(bg), peach, Palette.TINT_CAUTION)) }, inkAt(1f))
        worst("Ink on an info-tinted plate", 4.5, { bg -> listOf(over(plate(bg), lavender, Palette.TINT_INFO)) }, inkAt(1f))
        worst("Ink on my bubble, indigo end", 4.5, { bg -> listOf(over(plate(bg), indigo, Palette.BUBBLE_MINE_INDIGO)) }, inkAt(1f))
        worst("Ink on my bubble, lavender end", 4.5, { bg -> listOf(over(plate(bg), lavender, Palette.BUBBLE_MINE_LAVENDER)) }, inkAt(1f))
        worst("Ink on their bubble", 4.5, { bg -> listOf(over(plate(bg), peach, Palette.BUBBLE_THEIRS)) }, inkAt(1f))
    }

    @Test
    fun mutedTextIsReadableOnTheBackgroundAndOnPlates() {
        worst("Muted on the bare background", 4.5, bare, inkAt(Palette.INK_MUTED_ALPHA))
        // Also what field placeholders use.
        worst("Muted on a plate (and placeholders)", 4.5, plain, inkAt(Palette.INK_MUTED_ALPHA))
    }

    @Test
    fun faintTextIsReadableOnTheBackground() {
        // Faint is for 13 sp notes on the bare background. On a plate it is only a symbol (below).
        worst("Faint on the bare background (notes)", 4.5, bare, inkAt(Palette.INK_FAINT_ALPHA))
    }

    @Test
    fun peachTextIsReadableWhereItIsUsed() {
        worst("Peach on the bare background", 4.5, bare) { peach }
        worst("Peach text colour on the bare background", 4.5, bare) { peachText }
        worst("Peach text colour on a plate", 4.5, plain) { peachText }
    }

    @Test
    fun darkTextOnTheBrandGradientIsReadable() {
        val ends = listOf(mint, lavender)
        val low = ends.minOf { ratio(space, it) }
        println("%-52s worst %5.2f : 1  (needs 4.5)".format("Space text on the Brand gradient, both ends", low))
        assertTrue("Space on Brand: $low", low >= 4.5)
    }

    @Test
    fun symbolsReachThreeToOne() {
        worst("Mint focus ring / cursor on the bare background", 3.0, bare) { mint }
        worst("Mint focus ring / cursor on a plate", 3.0, plain) { mint }
        worst("Faint chevron on a plate", 3.0, plain, inkAt(Palette.INK_FAINT_ALPHA))
        worst("Peach chevron on a plate", 3.0, plain) { peach }
    }

    /**
     * The task's own stack: Space, then each glow at its peak over its own centre (and the Plum + Indigo
     * + Lavender overlap, which can never occur on screen because Indigo and Lavender sit in opposite
     * corners), then the lightest plate, then each tint. Printed for the record, not asserted: on this stack
     * even ink at full strength only reaches 4.3 : 1 where the tint and overlap coincide, so no token change
     * can make it pass, and it does not describe anything a person can see.
     */
    @Test
    fun specifiedWorstCaseStackForTheRecord() {
        val glowSets = listOf(
            "Space" to emptyList(), "Plum" to listOf("P"), "Indigo" to listOf("I"), "Lavender" to listOf("L"),
            "Peach" to listOf("E"), "Mint" to listOf("M"), "Plum+Indigo+Lavender" to listOf("P", "I", "L"),
        )
        fun glow(code: String): Pair<Rgb, Float> = when (code) {
            "P" -> rgb(Palette.PLUM) to Palette.GLOW_PLUM
            "I" -> indigo to Palette.GLOW_INDIGO
            "L" -> lavender to Palette.GLOW_LAVENDER
            "E" -> peach to Palette.GLOW_PEACH
            else -> mint to Palette.GLOW_MINT
        }
        var worstInk = Double.MAX_VALUE
        var worstMuted = Double.MAX_VALUE
        var worstFaint = Double.MAX_VALUE
        var worstPeachOnTint = Double.MAX_VALUE
        for ((_, codes) in glowSets) {
            var base = space
            for (c in codes) { val (col, a) = glow(c); base = over(base, col, a) }
            val surfaces = listOf(base, plate(base), over(plate(base), peach, Palette.TINT_CAUTION), over(plate(base), lavender, Palette.TINT_INFO))
            for (s in surfaces) {
                worstInk = minOf(worstInk, ratio(over(s, ink, 1f), s))
                worstMuted = minOf(worstMuted, ratio(over(s, ink, Palette.INK_MUTED_ALPHA), s))
                worstFaint = minOf(worstFaint, ratio(over(s, ink, Palette.INK_FAINT_ALPHA), s))
            }
            worstPeachOnTint = minOf(worstPeachOnTint, ratio(peach, over(plate(base), peach, Palette.TINT_CAUTION)))
        }
        println("Specified stack (for the record): Ink %.2f  Muted %.2f  Faint %.2f  Peach on peach-tinted plate %.2f"
            .format(worstInk, worstMuted, worstFaint, worstPeachOnTint))
        assertTrue("sanity: ratios are computed", worstInk > 1.0 && worstPeachOnTint > 1.0)
    }
}