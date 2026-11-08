package com.rhshourav.tenchar.ui

/**
 * Every number that decides how readable the app is, as plain values with no Compose types, so the
 * unit tests can measure the real thing instead of a copy. Theme.kt, Glass.kt and the screens read
 * these; ContrastTest checks them against the 4.5 : 1 (text) and 3 : 1 (symbols) rules.
 *
 * Colours are ARGB. Alphas are 0 to 1.
 */
object Palette {
    const val SPACE: Long = 0xFF13141C
    const val PLUM: Long = 0xFF1E1B38
    const val MINT: Long = 0xFF84F5B7
    const val LAVENDER: Long = 0xFFB491FE
    const val LAVENDER_SOFT: Long = 0xFFD2B8FF
    const val PEACH: Long = 0xFFFFB385
    const val INDIGO: Long = 0xFF5A33D3
    const val INK: Long = 0xFFF4F2FF

    /**
     * Peach for text that sits on a glass plate. Plain Peach reaches only about 3.7 : 1 there, because the
     * plate lightens what is behind it; this lighter peach clears 4.5 : 1. Peach stays for dots, glows and
     * text directly on the dark background, where it is above 5 : 1.
     */
    const val PEACH_TEXT: Long = 0xFFFFD1B6

    /** Secondary and tertiary text. Were 0.72 and 0.56, which measured 3.3 : 1 and 2.6 : 1 on the brightest plates. */
    const val INK_MUTED_ALPHA = 0.84f
    const val INK_FAINT_ALPHA = 0.72f

    // The glass plate: a white sheen over whatever is behind it, lightest at the lit corner.
    const val GLASS_FILL_LIGHT = 0.13f
    const val GLASS_FILL_DARK = 0.05f
    const val TINT_CAUTION = 0.14f
    const val TINT_INFO = 0.14f

    // Message bubbles, laid over the plate.
    const val BUBBLE_MINE_INDIGO = 0.70f

    /** Was 0.34, which left ink at 4.1 : 1 at the lavender end of my bubble. */
    const val BUBBLE_MINE_LAVENDER = 0.22f
    const val BUBBLE_THEIRS = 0.07f

    /**
     * Avatar backgrounds, by colour number: pairs of light colours so the dark letter on top stays readable
     * (ContrastTest checks the dark text against both ends of every pair).
     */
    val AVATARS: List<Pair<Long, Long>> = listOf(
        MINT to LAVENDER,
        PEACH to LAVENDER,
        MINT to LAVENDER_SOFT,
        PEACH to MINT,
        LAVENDER_SOFT to PEACH,
        LAVENDER to PEACH_TEXT,
        MINT to PEACH_TEXT,
        LAVENDER_SOFT to MINT,
    )

    // The aurora: five soft glows at the peak alpha shown, fading to nothing at their radius.
    const val GLOW_PLUM = 1.0f
    const val GLOW_INDIGO = 0.55f
    const val GLOW_LAVENDER = 0.22f
    const val GLOW_PEACH = 0.14f
    const val GLOW_MINT = 0.12f
}