package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Reassembler
import com.rhshourav.tenchar.crypto.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ReassemblerTest {
    private fun fragments(text: String, group: Int): List<Wire.Decoded.Fragment> =
        Wire.encodeMessage(text, group)!!.map { Wire.decodeFrame(it) as Wire.Decoded.Fragment }

    private fun feed(r: Reassembler, parts: List<Wire.Decoded.Fragment>): List<String> =
        parts.mapNotNull { r.accept(it) }

    @Test
    fun piecesInOrderGiveTheMessageOnTheLastOne() {
        val text = "hello ".repeat(80).trim()
        val parts = fragments(text, 1)
        val r = Reassembler()
        for (p in parts.dropLast(1)) assertNull(r.accept(p))
        assertEquals(text, r.accept(parts.last()))
        assertTrue(r.pending.isEmpty())
    }

    @Test
    fun piecesInAnyOrderGiveTheSameMessage() {
        val text = ("a" + "😀").repeat(120) // 5 bytes per unit, so cuts fall in the middle of characters
        assertEquals(600, text.toByteArray().size)
        val parts = fragments(text, 9)
        val rnd = Random(42)
        repeat(20) {
            val shuffled = parts.shuffled(rnd)
            assertEquals(listOf(text), feed(Reassembler(), shuffled))
        }
        assertEquals(listOf(text), feed(Reassembler(), parts.reversed()))
    }

    @Test
    fun aRepeatedPieceDoesNothing() {
        val parts = fragments("x".repeat(200), 3)
        val r = Reassembler()
        assertNull(r.accept(parts[0]))
        assertNull(r.accept(parts[0]))
        assertEquals(1, r.pending.size)
        assertEquals("x".repeat(200), feed(r, parts.drop(1)).single())
    }

    @Test
    fun twoMessagesInTheirOwnGroupsDoNotMix() {
        val a = "a".repeat(250)
        val b = "b".repeat(250)
        val pa = fragments(a, 1)
        val pb = fragments(b, 2)
        val mixed = pa.zip(pb).flatMap { listOf(it.first, it.second) }
        assertEquals(setOf(a, b), feed(Reassembler(), mixed).toSet())
    }

    @Test
    fun aPieceThatDisagreesAboutTheCountIsIgnored() {
        val parts = fragments("x".repeat(200), 4) // 4 pieces
        val other = fragments("y".repeat(130), 4) // 3 pieces, same group number
        val r = Reassembler()
        assertNull(r.accept(parts[0]))
        assertNull(r.accept(other[1]))
        assertEquals("x".repeat(200), feed(r, parts.drop(1)).single())
    }

    @Test
    fun anIncompleteMessageIsEventuallyPushedOut() {
        val r = Reassembler()
        val first = fragments("x".repeat(200), 100)
        assertNull(r.accept(first[0]))
        // Sixteen newer messages, each started but not finished, push the first out.
        for (g in 1..16) assertNull(r.accept(fragments("y".repeat(200), 100 + g)[0]))
        assertEquals(16, r.pending.size)
        assertTrue(r.pending.none { it.id == 100 })
        // The rest of the first message now starts a new, incomplete group instead of finishing it.
        assertEquals(emptyList<String>(), feed(r, first.drop(1)))
    }

    @Test
    fun anOversizeOrMalformedMessageIsDropped() {
        // 35 full pieces is 2,100 bytes, over the 2,048 limit.
        val big = (0 until 35).map { Wire.Decoded.Fragment(1, it, 35, ByteArray(60) { 'a'.code.toByte() }) }
        assertEquals(emptyList<String>(), feed(Reassembler(), big))
        // Bytes that are not UTF-8 once joined.
        val bad = listOf(
            Wire.Decoded.Fragment(2, 0, 2, ByteArray(60) { 'a'.code.toByte() }),
            Wire.Decoded.Fragment(2, 1, 2, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
        )
        assertEquals(emptyList<String>(), feed(Reassembler(), bad))
        // Blank and control characters are refused like in a single frame.
        val blank = listOf(
            Wire.Decoded.Fragment(3, 0, 2, ByteArray(60) { ' '.code.toByte() }),
            Wire.Decoded.Fragment(3, 1, 2, ByteArray(3) { ' '.code.toByte() }),
        )
        assertEquals(emptyList<String>(), feed(Reassembler(), blank))
    }

    @Test
    fun savedPiecesCanBeRestored() {
        val text = "z".repeat(300)
        val parts = fragments(text, 8)
        val first = Reassembler()
        for (p in parts.take(3)) assertNull(first.accept(p))
        val second = Reassembler()
        second.restore(first.pending)
        assertEquals(text, feed(second, parts.drop(3)).single())
    }

    @Test
    fun wipeClearsEverything() {
        val r = Reassembler()
        r.accept(fragments("q".repeat(200), 1)[0])
        val held = r.pending.single().parts[0]!!
        r.wipe()
        assertTrue(r.pending.isEmpty())
        assertTrue(held.all { it.toInt() == 0 })
    }
}