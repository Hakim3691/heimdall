package com.heimdall.engine.trigger

import com.heimdall.model.RgbImage

/**
 * Difference-hashing for frame-stability gating (TRD §6.3, FR-02 stage 3).
 *
 * Deliberately allocation-light and free of `android.graphics` so it runs on the capture thread of
 * an ambient service without GC churn: a 9x8 luma thumbnail is box-filtered straight from the ARGB
 * buffer and hashed in one pass.
 */
object FrameDiffEngine {
    /** dHash compares 8 neighbours per row, so the thumbnail is 9 columns wide. */
    const val HASH_COLUMNS: Int = 9

    /** ...and 8 rows tall: 8x8 comparisons == 64 bits. */
    const val HASH_ROWS: Int = 8

    /**
     * Box-filters [image] to a [columns] x [rows] luma grid.
     *
     * Each output cell averages the source rectangle mapped onto it; this keeps the hash stable
     * under the ~2-3x resolution differences between devices.
     */
    fun lumaGrid(
        image: RgbImage,
        columns: Int = HASH_COLUMNS,
        rows: Int = HASH_ROWS,
    ): IntArray {
        require(columns > 0 && rows > 0) { "grid must be non-empty" }
        val grid = IntArray(columns * rows)
        val scratch = IntArray(columns * rows)
        for (row in 0 until rows) {
            val yStart = row * image.height / rows
            val yEnd = maxOf(yStart + 1, (row + 1) * image.height / rows)
            for (col in 0 until columns) {
                val xStart = col * image.width / columns
                val xEnd = maxOf(xStart + 1, (col + 1) * image.width / columns)
                var sum = 0
                var count = 0
                for (y in yStart until yEnd) {
                    for (x in xStart until xEnd) {
                        sum += image.lumaAt(x, y)
                        count++
                    }
                }
                grid[row * columns + col] = if (count == 0) 0 else sum / count
                scratch[row * columns + col] = grid[row * columns + col]
            }
        }
        return grid
    }

    /** Computes the 64-bit dHash of a luma grid produced by [lumaGrid]. */
    fun dHash(
        grid: IntArray,
        columns: Int = HASH_COLUMNS,
        rows: Int = HASH_ROWS,
    ): FrameSignature {
        require(columns >= 2) { "dHash needs at least two columns" }
        require(grid.size == columns * rows) { "grid size does not match $columns x $rows" }
        var hash = 0L
        for (row in 0 until rows) {
            val rowOffset = row * columns
            for (col in 0 until columns - 1) {
                if (grid[rowOffset + col] > grid[rowOffset + col + 1]) {
                    hash = hash or (1L shl (row * (columns - 1) + col))
                }
            }
        }
        return FrameSignature(hash)
    }

    /** Convenience: signature of an image in one call. */
    fun signature(image: RgbImage): FrameSignature = dHash(lumaGrid(image))

    /** True when two consecutive frames are static enough to warrant inference. */
    fun isStatic(
        previous: FrameSignature,
        current: FrameSignature,
        maxHammingDistance: Int,
    ): Boolean = previous.isWithin(current, maxHammingDistance)
}
