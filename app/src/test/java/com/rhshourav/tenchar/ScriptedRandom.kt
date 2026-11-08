package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.RandomSource

/**
 * A [RandomSource] that hands out prepared bytes in order. A request of a different size than the
 * next chunk, or a request after the last chunk, is a test failure: it means the code drew
 * randomness in a different order or amount than the specification says.
 */
class ScriptedRandom(chunks: List<ByteArray>, private val name: String = "script") : RandomSource {
    private val queue = ArrayDeque(chunks.map { it.copyOf() })
    private var drawn = 0

    override fun nextBytes(out: ByteArray) {
        if (queue.isEmpty()) throw AssertionError("$name: draw #${drawn + 1} of ${out.size} bytes, but the script is empty")
        val next = queue.removeFirst()
        if (next.size != out.size) {
            throw AssertionError("$name: draw #${drawn + 1} asked for ${out.size} bytes, script has ${next.size}")
        }
        next.copyInto(out)
        drawn++
    }

    /** Fails unless every prepared chunk was used. */
    fun assertDrained() {
        if (queue.isNotEmpty()) throw AssertionError("$name: ${queue.size} unused chunk(s) left after $drawn draw(s)")
    }
}