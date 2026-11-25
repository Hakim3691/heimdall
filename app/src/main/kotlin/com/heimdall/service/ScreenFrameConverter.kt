package com.heimdall.service

import android.graphics.PixelFormat
import android.media.Image
import com.heimdall.model.RgbImage

/**
 * Converts a `MediaProjection` frame into the pipeline's [RgbImage].
 *
 * The capture surface is RGBA_8888, so one plane and one copy is all that is needed. Downscaling
 * happens here (nearest-neighbour, area-independent) to keep both the dHash and the vision encoder
 * fed at the size they actually consume — a full-resolution screen frame is never retained.
 */
object ScreenFrameConverter {
    /**
     * @param maxEdge longest edge of the output, preserving aspect ratio
     */
    fun toRgbImage(image: Image, maxEdge: Int): RgbImage? {
        if (image.width <= 0 || image.height <= 0) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val sourceWidth = image.width
        val sourceHeight = image.height

        val scale = minOf(1f, maxEdge.toFloat() / maxOf(sourceWidth, sourceHeight))
        val outWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val outHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)

        if (image.format != PixelFormat.RGBA_8888) {
            return grayscaleFromLumaPlane(buffer, rowStride, pixelStride, sourceWidth, sourceHeight, outWidth, outHeight)
        }

        val pixels = IntArray(outWidth * outHeight)
        for (outY in 0 until outHeight) {
            val sourceY = outY * sourceHeight / outHeight
            val rowStart = sourceY * rowStride
            for (outX in 0 until outWidth) {
                val sourceX = outX * sourceWidth / outWidth
                val offset = rowStart + sourceX * pixelStride
                if (offset + 3 >= buffer.limit()) continue
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                pixels[outY * outWidth + outX] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RgbImage(outWidth, outHeight, pixels)
    }

    /** Fallback for surfaces that deliver a single luma-ish plane; treats it as greyscale. */
    private fun grayscaleFromLumaPlane(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        outWidth: Int,
        outHeight: Int,
    ): RgbImage {
        val pixels = IntArray(outWidth * outHeight)
        for (outY in 0 until outHeight) {
            val sourceY = outY * sourceHeight / outHeight
            val rowStart = sourceY * rowStride
            for (outX in 0 until outWidth) {
                val sourceX = outX * sourceWidth / outWidth
                val offset = rowStart + sourceX * pixelStride
                if (offset >= buffer.limit()) continue
                val value = buffer.get(offset).toInt() and 0xFF
                pixels[outY * outWidth + outX] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        return RgbImage(outWidth, outHeight, pixels)
    }

    /** Centre-crops/pads [source] to a square and resamples to [target] x [target] for the VLM. */
    fun toSquare(source: RgbImage, target: Int): RgbImage {
        val side = minOf(source.width, source.height)
        val offsetX = (source.width - side) / 2
        val offsetY = (source.height - side) / 2
        val out = IntArray(target * target)
        for (y in 0 until target) {
            val sy = offsetY + y * side / target
            for (x in 0 until target) {
                val sx = offsetX + x * side / target
                out[y * target + x] = source.argb[sy * source.width + sx]
            }
        }
        return RgbImage(target, target, out)
    }
}
