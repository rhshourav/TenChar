package com.rhshourav.tenchar

/**
 * Shows a pairing code as XXXX-XXXX-XXXX-XXXX while it is kept as plain letters with no hyphens.
 *
 * The text field holds the plain letters and only draws the hyphens, so typing never rewrites what the
 * field holds. (The earlier version put the hyphens into the field's own text, which left the cursor one place
 * behind after the 5th, 9th and 13th letter, in front of the letter just typed.) The two functions say where the
 * cursor goes when the shown text and the held text have different lengths.
 */
object CodeFormat {
    /** "ABCDE" becomes "ABCD-E". A group is only closed with a hyphen once a letter follows it. */
    fun display(raw: String): String = raw.chunked(Constants.CODE_GROUP_LEN).joinToString("-")

    /** Cursor position in the held text (number of letters before it) to the position in the shown text. */
    fun originalToTransformed(offset: Int): Int =
        if (offset <= 0) 0 else offset + (offset - 1) / Constants.CODE_GROUP_LEN

    /** The reverse, for a tap or selection in the shown text. A position just after a hyphen means "before the next letter". */
    fun transformedToOriginal(offset: Int, rawLength: Int): Int =
        (offset - offset / (Constants.CODE_GROUP_LEN + 1)).coerceIn(0, rawLength)
}