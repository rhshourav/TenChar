package com.rhshourav.tenchar.store

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.MessageLimits
import com.rhshourav.tenchar.crypto.Wire

/**
 * How one person appears in a chat: a name, an avatar and a colour. The avatar is a single emoji (or empty,
 * in which case the first letter of the name is shown), never an image: pictures from the other phone would
 * mean decoding untrusted image files, and a profile has to fit in one 64-byte frame.
 */
class Profile(val name: String, val avatar: String, val color: Int) {
    val isEmpty: Boolean get() = name.isEmpty() && avatar.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is Profile && other.name == name && other.avatar == avatar && other.color == color

    override fun hashCode(): Int = (name.hashCode() * 31 + avatar.hashCode()) * 31 + color

    companion object {
        /** Number of avatar colours; the colours themselves live in the palette. */
        const val COLORS = Constants.AVATAR_COLORS
        const val MAX_NAME_CHARS = 20
        const val MAX_NAME_BYTES = Constants.PROFILE_NAME_BYTES

        /** Room for a family emoji, which is one character but 25 bytes. */
        const val MAX_AVATAR_BYTES = Constants.PROFILE_AVATAR_BYTES

        val EMPTY = Profile("", "", 0)

        /** A name a person typed, made safe to show: no control or bidirectional characters, one space between words, at most 20 characters. */
        fun cleanName(raw: String): String {
            val shown = raw.filter { Wire.isDisplayable(it.toString()) }
            val oneSpace = shown.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            return MessageLimits.clampTo(oneSpace, MAX_NAME_CHARS, MAX_NAME_BYTES).trim()
        }

        /** The first character of [raw] if it can be an avatar, otherwise empty. */
        fun cleanAvatar(raw: String): String {
            val shown = raw.filter { Wire.isDisplayable(it.toString()) }.trim()
            val first = MessageLimits.firstGrapheme(shown)
            return if (first.toByteArray(Charsets.UTF_8).size <= MAX_AVATAR_BYTES) first else ""
        }

        fun of(name: String, avatar: String, color: Int): Profile =
            Profile(cleanName(name), cleanAvatar(avatar), color.coerceIn(0, COLORS - 1))
    }
}