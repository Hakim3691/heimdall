package com.heimdall.testing

import com.heimdall.model.RgbImage

/** Synthetic screen frames for dHash and stabilité scenarios. */
object SyntheticFrames {
    /**
     * A frame with a horizontal brightness ramp — the dHash of this is a stable known vector, so
     * scenarios can assert exact hash behaviour.
     */
    fun ramp(width: Int = 32, height: Int = 32, invert: Boolean = false): RgbImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = (x * 255) / (width - 1)
                val v = if (invert) 255 - value else value
                pixels[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return RgbImage(width, height, pixels)
    }

    /** Uniform frame of one grey value; its dHash is all zeroes. */
    fun flat(value: Int = 128, width: Int = 32, height: Int = 32): RgbImage =
        RgbImage.uniform(width, height, (0xFF shl 24) or (value shl 16) or (value shl 8) or value)

    /**
     * [flat] with a small bright patch, i.e. a frame that differs from [flat] in a handful of
     * dHash bits — used to test the stability threshold from both sides.
     */
    fun flatWithPatch(
        patchSize: Int = 4,
        value: Int = 128,
        patchValue: Int = 240,
        width: Int = 32,
        height: Int = 32,
    ): RgbImage {
        val image = flat(value, width, height)
        val pixels = image.argb.copyOf()
        for (y in 0 until patchSize) {
            for (x in 0 until patchSize) {
                pixels[y * width + x] = (0xFF shl 24) or (patchValue shl 16) or (patchValue shl 8) or patchValue
            }
        }
        return RgbImage(width, height, pixels)
    }
}
