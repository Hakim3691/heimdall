package com.heimdall.model

/**
 * A captured screen frame in 32-bit ARGB, never backed by a `Bitmap` so the whole pipeline below
 * the platform adapter is JVM-testable.
 *
 * @property argb row-major pixels, `width * height` entries, alpha ignored by the pipeline.
 */
class RgbImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(argb.size == width * height) {
            "pixel buffer holds ${argb.size} pixels but ${width}x$height needs ${width * height}"
        }
    }

    /** Luma (Rec. 601) at (x, y) in 0..255. */
    fun lumaAt(x: Int, y: Int): Int {
        val pixel = argb[y * width + x]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    companion object {
        /** Uniform grey frame; used by tests and as a warm-up frame. */
        fun uniform(width: Int, height: Int, argbColour: Int = 0xFF000000.toInt()): RgbImage =
            RgbImage(width, height, IntArray(width * height) { argbColour })
    }
}
