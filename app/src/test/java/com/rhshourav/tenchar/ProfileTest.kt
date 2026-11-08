package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.store.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names and avatars arrive from the other phone, so what reaches the screen is cleaned the same way as typed text. */
class ProfileTest {
    @Test
    fun aNameIsTrimmedAndKeptToOneLineWithSingleSpaces() {
        assertEquals("Rafi", Profile.cleanName("  Rafi  "))
        assertEquals("Rafi Khan", Profile.cleanName("Rafi \t  \n Khan"))
        assertEquals("", Profile.cleanName("   "))
        assertEquals("আমি রাফি", Profile.cleanName(" আমি   রাফি "))
    }

    @Test
    fun controlAndBidirectionalCharactersAreRemovedFromANameWithoutWreckingTheRest() {
        assertEquals("Rafi", Profile.cleanName("Ra\u0000f\u0007i"))
        // A right-to-left override can make a name read backwards; it is dropped, the letters stay.
        assertEquals("Rafi", Profile.cleanName("\u202ERafi\u202C"))
        assertEquals("Rafi", Profile.cleanName("R\u200Fa\u2066f\u2069i"))
    }

    @Test
    fun aNameIsAtMostTwentyCharactersAndThirtyTwoBytes() {
        assertEquals("a".repeat(20), Profile.cleanName("a".repeat(50)))
        // 20 characters of 4-byte emoji would be 80 bytes: the byte limit binds first, at 8 emoji.
        val emoji = Profile.cleanName("😀".repeat(20))
        assertEquals(8, emoji.codePointCount(0, emoji.length))
        assertTrue(emoji.toByteArray().size <= 32)
        // Bangla letters are 3 bytes each: 10 fit.
        val bangla = Profile.cleanName("অ".repeat(30))
        assertEquals(10, bangla.length)
    }

    @Test
    fun anAvatarIsOneCharacterOrNothing() {
        assertEquals("🦊", Profile.cleanAvatar("🦊"))
        assertEquals("🦊", Profile.cleanAvatar("🦊😀🐱")) // only the first
        assertEquals("", Profile.cleanAvatar(""))
        assertEquals("", Profile.cleanAvatar("   "))
        assertEquals("A", Profile.cleanAvatar("ABC"))
        // A family emoji is one character of 25 bytes and fits; a character with a huge pile of marks does not.
        assertEquals("👨‍👩‍👧‍👦", Profile.cleanAvatar("👨‍👩‍👧‍👦"))
        assertEquals("", Profile.cleanAvatar("e" + "\u0301".repeat(20)))
        assertEquals("", Profile.cleanAvatar("\u202E"))
    }

    @Test
    fun aColourIsKeptInRange() {
        assertEquals(0, Profile.of("x", "", -5).color)
        assertEquals(7, Profile.of("x", "", 99).color)
        assertEquals(3, Profile.of("x", "", 3).color)
    }

    @Test
    fun aHostileProfileFrameIsCleanedBeforeItIsShown() {
        // What the receiving phone does: decode strictly, then clean. A frame that is well formed but unpleasant
        // (spaces, a family emoji followed by more) still ends up as something safe.
        val frame = Wire.encodeProfile("  Nadia   Rahman ", "👨‍👩‍👧‍👦", 5)
        assertNotNull(frame)
        val d = Wire.decodeFrame(frame!!) as Wire.Decoded.ProfileUpdate
        val shown = Profile.of(d.name, d.avatar, d.color)
        assertEquals("Nadia Rahman", shown.name)
        assertEquals("👨‍👩‍👧‍👦", shown.avatar)
        assertEquals(5, shown.color)
    }
}