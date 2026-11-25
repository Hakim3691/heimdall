package com.heimdall.engine.trigger

/**
 * 64-bit difference hash of a screen frame (dHash, TRD §6.3).
 *
 * Each bit records "is this pixel brighter than its right neighbour" for an 8x8 cell grid derived
 * from a 9x8 luma thumbnail, so the hash is invariant to brightness shifts and cheap to compare.
 */
@JvmInline
value class FrameSignature(val bits: Long) {
    /** Number of differing bits, 0..64. */
    fun hammingTo(other: FrameSignature): Int = java.lang.Long.bitCount(bits xor other.bits)

    /** True when the two frames are within [maxDistance] bits of each other (TRD gate: <= 2). */
    fun isWithin(other: FrameSignature, maxDistance: Int): Boolean = hammingTo(other) <= maxDistance
}
