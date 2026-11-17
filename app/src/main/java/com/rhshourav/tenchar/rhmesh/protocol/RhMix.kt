package com.rhshourav.tenchar.rhmesh.protocol

/** SplitMix64's finalizer: a fast, well-mixed 64-bit permutation. Used for Bloom indices and set fingerprints, never for secrets. */
internal object RhMix {
    private val C1 = 0xBF58476D1CE4E5B9uL.toLong()
    private val C2 = 0x94D049BB133111EBuL.toLong()
    val GOLDEN: Long = 0x9E3779B97F4A7C15uL.toLong()

    fun mix64(z: Long): Long {
        var x = z
        x = (x xor (x ushr 30)) * C1
        x = (x xor (x ushr 27)) * C2
        return x xor (x ushr 31)
    }
}
