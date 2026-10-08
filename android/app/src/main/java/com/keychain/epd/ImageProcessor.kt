package com.keychain.epd

import java.util.zip.CRC32

/**
 * Pure Kotlin/JVM image processor for EPD Keychain.
 * No android.* imports. Operates on IntArray ARGB pixels.
 *
 * Processing pipeline:
 *   1. Input: ARGB image 250x122 landscape (center-cropped, EXIF already applied by caller)
 *   2. Rotate 90° clockwise to 122x250 native orientation
 *   3. Floyd-Steinberg dither to 3-color palette {white, black, red}
 *   4. Pack K (black) and R (red) bit-planes per the wire format
 *   5. Output: 8000-byte frame (4000 K + 4000 R)
 *
 * Wire format:
 *   - Native W=122, H=250, row-major top→bottom
 *   - Each row = 16 bytes (ceil(122/8)), MSB first = leftmost pixel
 *   - 2 pad bits at row end = 0
 *   - Plane K bit 1 = black; Plane R bit 1 = red
 *   - If both set, red wins
 */
object ImageProcessor {
    const val W = 122
    const val H = 250

    /** Landscape preview dimensions: 250x122 */
    const val PREVIEW_W = 250
    const val PREVIEW_H = 122

    private val BLACK = Triple(0, 0, 0)
    private val WHITE = Triple(255, 255, 255)
    private val RED = Triple(255, 0, 0)

    /**
     * Process a 250x122 landscape ARGB image into an 8000-byte frame suitable
     * for sending over BLE to the EPD device.
     *
     * @param argb  IntArray of ARGB pixels, 250×122 landscape, row-major
     * @param width  Must be 250
     * @param height Must be 122
     * @return Result containing the 8000-byte frame and the dithered native ARGB array
     */
    fun process(argb: IntArray, width: Int, height: Int): Result {
        require(width == PREVIEW_W && height == PREVIEW_H) {
            "Expected ${PREVIEW_W}x${PREVIEW_H} input, got ${width}x${height}"
        }

        // Step 1: Rotate landscape 250x122 → native 122x250
        // Contract (ARCHITECTURE.md):
        //   landscape(x,y) in 250x122 -> native(nx,ny) = (121 - y, x)
        val native = IntArray(W * H)
        for (y in 0 until PREVIEW_H) {
            for (x in 0 until PREVIEW_W) {
                val srcIdx = y * PREVIEW_W + x
                val dstX = (W - 1) - y
                val dstY = x
                native[dstY * W + dstX] = argb[srcIdx]
            }
        }

        // Step 2: Floyd-Steinberg dither to palette {white, black, red}
        val dithered = IntArray(W * H)
        val errorsR = FloatArray(W * H)
        val errorsG = FloatArray(W * H)
        val errorsB = FloatArray(W * H)

        for (idx in 0 until W * H) {
            val pixel = native[idx]
            val r = ((pixel shr 16) and 0xFF).toFloat() + errorsR[idx]
            val g = ((pixel shr 8) and 0xFF).toFloat() + errorsG[idx]
            val b = (pixel and 0xFF).toFloat() + errorsB[idx]

            // Find nearest palette color by squared RGB distance
            val nearest = nearestColor(r, g, b)
            val qR = nearest.first.toFloat()
            val qG = nearest.second.toFloat()
            val qB = nearest.third.toFloat()

            dithered[idx] = (0xFF shl 24) or
                (nearest.first shl 16) or
                (nearest.second shl 8) or
                nearest.third

            val errR = r - qR
            val errG = g - qG
            val errB = b - qB

            val x = idx % W
            val y = idx / W

            // Distribute error with Floyd-Steinberg kernel:
            // 7/16 right, 3/16 down-left, 5/16 down, 1/16 down-right
            distributeError(errorsR, errR, x, y, W, H)
            distributeError(errorsG, errG, x, y, W, H)
            distributeError(errorsB, errB, x, y, W, H)
        }

        // Step 3: Pack bit-planes
        val rowBytes = 16 // ceil(122/8)
        val planeSize = H * rowBytes // 4000
        val frame = ByteArray(planeSize * 2) // 8000

        for (y in 0 until H) {
            for (byteIdx in 0 until rowBytes) {
                var kByte = 0
                var rByte = 0
                for (bit in 0 until 8) {
                    val px = byteIdx * 8 + bit
                    if (px >= W) break

                    val idx = y * W + px
                    val color = dithered[idx]
                    val cr = (color shr 16) and 0xFF
                    val cg = (color shr 8) and 0xFF
                    val cb = color and 0xFF

                    val isBlack = cr == 0 && cg == 0 && cb == 0
                    val isRed = cr == 255 && cg == 0 && cb == 0

                    // K plane: bit 1 = black (MSB first, bit 7 = leftmost)
                    if (isBlack && !isRed) {
                        kByte = kByte or (1 shl (7 - bit))
                    }
                    // R plane: bit 1 = red
                    if (isRed) {
                        rByte = rByte or (1 shl (7 - bit))
                    }
                    // Red wins over black: if red, K bit stays 0
                }
                val planeOffset = y * rowBytes + byteIdx
                frame[planeOffset] = kByte.toByte()
                frame[planeOffset + planeSize] = rByte.toByte()
            }
        }

        return Result(frame, dithered)
    }

    private fun nearestColor(r: Float, g: Float, b: Float): Triple<Int, Int, Int> {
        val colors = listOf(WHITE, BLACK, RED)
        var best = colors[0]
        var bestDist = Float.MAX_VALUE

        for (c in colors) {
            val dr = r - c.first
            val dg = g - c.second
            val db = b - c.third
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = c
            }
        }

        return best
    }

    private fun distributeError(errors: FloatArray, error: Float, x: Int, y: Int, w: Int, h: Int) {
        val f7_16 = 7f / 16f
        val f3_16 = 3f / 16f
        val f5_16 = 5f / 16f
        val f1_16 = 1f / 16f

        // Right neighbor
        if (x + 1 < w) {
            errors[y * w + (x + 1)] += error * f7_16
        }
        // Bottom-left neighbor
        if (x - 1 >= 0 && y + 1 < h) {
            errors[(y + 1) * w + (x - 1)] += error * f3_16
        }
        // Bottom neighbor
        if (y + 1 < h) {
            errors[(y + 1) * w + x] += error * f5_16
        }
        // Bottom-right neighbor
        if (x + 1 < w && y + 1 < h) {
            errors[(y + 1) * w + (x + 1)] += error * f1_16
        }
    }

    /**
     * Compute CRC-32 (IEEE 802.3 / zlib) of a byte array.
     */
    fun crc32(frame: ByteArray): Long {
        val crc = CRC32()
        crc.update(frame)
        return crc.value
    }

    /**
     * Chunk an 8000-byte frame into a list of DATA characteristic writes.
     * Each chunk = u16 LE offset (2 bytes) + payload bytes.
     *
     * @param frame       The 8000-byte frame to chunk
     * @param payloadSize Maximum payload bytes per chunk (MTU - 3 - 2)
     * @return List of ByteArrays, each starting with u16 LE offset
     */
    fun chunk(frame: ByteArray, payloadSize: Int): List<ByteArray> {
        require(frame.size == 8000) { "Frame must be 8000 bytes, got ${frame.size}" }

        val chunks = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < frame.size) {
            val remaining = frame.size - offset
            val chunkPayload = minOf(payloadSize, remaining)
            // 2 bytes offset + payload
            val chunk = ByteArray(2 + chunkPayload)
            // u16 LE offset
            chunk[0] = (offset and 0xFF).toByte()
            chunk[1] = ((offset shr 8) and 0xFF).toByte()
            // Copy payload
            System.arraycopy(frame, offset, chunk, 2, chunkPayload)
            chunks.add(chunk)
            offset += chunkPayload
        }

        return chunks
    }

    /**
     * Create a 250x122 landscape ARGB preview from the processing result.
     * Rotates the native 122x250 back to landscape orientation.
     *
     * @param result The processing result (contains 122x250 native ARGB)
     * @return IntArray of 250x122 ARGB pixels in landscape orientation
     */
    fun previewLandscape(result: Result): IntArray {
        val native = result.native
        val preview = IntArray(PREVIEW_W * PREVIEW_H)

        for (y in 0 until PREVIEW_H) {
            for (x in 0 until PREVIEW_W) {
                // Forward mapping (process(), step 1) is native(W-1-y, x) = landscape(x,y),
                // so the inverse is landscape(x,y) = native(W-1-y, x).
                val srcX = (W - 1) - y
                val srcY = x
                val srcIdx = srcY * W + srcX
                val dstIdx = y * PREVIEW_W + x
                preview[dstIdx] = native[srcIdx]
            }
        }

        return preview
    }
}

/**
 * Result of image processing.
 *
 * @param frame  8000-byte frame: first 4000 bytes = K plane, last 4000 bytes = R plane
 * @param native 122x250 ARGB IntArray of the dithered image in native display orientation
 */
data class Result(
    val frame: ByteArray,   // 8000 bytes
    val native: IntArray    // 122x250 ARGB of dithered colors
)
