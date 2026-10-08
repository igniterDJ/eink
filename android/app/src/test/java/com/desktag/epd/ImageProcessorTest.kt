package com.desktag.epd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageProcessorTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun crc32_knownVector() {
        val bytes = "123456789".toByteArray(Charsets.US_ASCII)
        val crc = ImageProcessor.crc32(bytes)
        assertEquals(0xCBF43926L, crc)
    }

    @Test
    fun allWhite_producesZeroPlanes() {
        val img = IntArray(ImageProcessor.PREVIEW_W * ImageProcessor.PREVIEW_H) { argb(255, 255, 255) }
        val res = ImageProcessor.process(img, ImageProcessor.PREVIEW_W, ImageProcessor.PREVIEW_H)
        assertEquals(8000, res.frame.size)
        for (b in res.frame) {
            assertEquals(0, b.toInt() and 0xFF)
        }
    }

    @Test
    fun allBlack_setsK_bits_R_zero_and_padding_zero() {
        val img = IntArray(ImageProcessor.PREVIEW_W * ImageProcessor.PREVIEW_H) { argb(0, 0, 0) }
        val res = ImageProcessor.process(img, ImageProcessor.PREVIEW_W, ImageProcessor.PREVIEW_H)

        val planeSize = 4000
        val rowBytes = 16
        // K plane
        for (y in 0 until ImageProcessor.H) {
            val rowOff = y * rowBytes
            // first 15 bytes are full pixels => all bits set
            for (i in 0 until 15) {
                assertEquals(0xFF, res.frame[rowOff + i].toInt() and 0xFF)
            }
            // last byte: first 2 bits correspond to pixels 120,121 => set; pad bits must be 0
            assertEquals(0xC0, res.frame[rowOff + 15].toInt() and 0xFF)
        }
        // R plane must be all 0
        for (i in 0 until planeSize) {
            assertEquals(0, res.frame[planeSize + i].toInt() and 0xFF)
        }
    }

    @Test
    fun allRed_setsR_bits_K_zero_and_padding_zero() {
        val img = IntArray(ImageProcessor.PREVIEW_W * ImageProcessor.PREVIEW_H) { argb(255, 0, 0) }
        val res = ImageProcessor.process(img, ImageProcessor.PREVIEW_W, ImageProcessor.PREVIEW_H)

        val planeSize = 4000
        val rowBytes = 16
        // K plane must be all 0
        for (i in 0 until planeSize) {
            assertEquals(0, res.frame[i].toInt() and 0xFF)
        }
        // R plane
        for (y in 0 until ImageProcessor.H) {
            val rowOff = planeSize + y * rowBytes
            for (i in 0 until 15) {
                assertEquals(0xFF, res.frame[rowOff + i].toInt() and 0xFF)
            }
            assertEquals(0xC0, res.frame[rowOff + 15].toInt() and 0xFF)
        }
    }

    @Test
    fun markerPixel_landscape00_mapsToNative1210_expectedBit() {
        val white = argb(255, 255, 255)
        val black = argb(0, 0, 0)
        val img = IntArray(ImageProcessor.PREVIEW_W * ImageProcessor.PREVIEW_H) { white }
        img[0] = black // landscape (0,0)

        val res = ImageProcessor.process(img, ImageProcessor.PREVIEW_W, ImageProcessor.PREVIEW_H)

        // native (nx,ny) = (121 - y, x) => (121,0)
        val rowBytes = 16
        val ny = 0
        val nx = 121
        val byteIndex = nx / 8 // 15
        val bitIndex = nx % 8  // 1
        val mask = 0x80 ushr bitIndex

        val kPlaneOffset = ny * rowBytes + byteIndex
        assertEquals(mask, res.frame[kPlaneOffset].toInt() and 0xFF)

        // Ensure R plane remains 0 at that location
        val planeSize = 4000
        assertEquals(0, res.frame[planeSize + kPlaneOffset].toInt() and 0xFF)
    }

    @Test
    fun chunk_offsetsContiguous_and_reassemblePayload242() {
        val frame = ByteArray(8000) { (it and 0xFF).toByte() }
        val payload = 242
        val chunks = ImageProcessor.chunk(frame, payload)

        var expectedOffset = 0
        val reassembled = ByteArray(8000)
        for (chunk in chunks) {
            assertTrue(chunk.size >= 2)
            val off = (chunk[0].toInt() and 0xFF) or ((chunk[1].toInt() and 0xFF) shl 8)
            assertEquals(expectedOffset, off)
            val len = chunk.size - 2
            System.arraycopy(chunk, 2, reassembled, off, len)
            expectedOffset += len
        }
        assertEquals(8000, expectedOffset)
        assertArrayEquals(frame, reassembled)
    }

    @Test
    fun previewLandscape_roundTrip_preservesFrame() {
        val img = IntArray(ImageProcessor.PREVIEW_W * ImageProcessor.PREVIEW_H) { idx ->
            val x = idx % ImageProcessor.PREVIEW_W
            // simple 3-color vertical stripes
            when {
                x < 80 -> argb(0, 0, 0)
                x < 160 -> argb(255, 255, 255)
                else -> argb(255, 0, 0)
            }
        }
        val res1 = ImageProcessor.process(img, ImageProcessor.PREVIEW_W, ImageProcessor.PREVIEW_H)
        val preview = ImageProcessor.previewLandscape(res1)
        val res2 = ImageProcessor.process(preview, ImageProcessor.PREVIEW_W, ImageProcessor.PREVIEW_H)
        assertArrayEquals(res1.frame, res2.frame)
    }

    @Test
    fun goldenFrame_writtenAndHasExpectedSize() {
        val W = ImageProcessor.PREVIEW_W
        val H = ImageProcessor.PREVIEW_H
        val white = argb(255, 255, 255)
        val black = argb(0, 0, 0)
        val red = argb(255, 0, 0)

        val img = IntArray(W * H) { idx ->
            val x = idx % W
            when {
                x < (W / 3) -> black
                x < (2 * W / 3) -> white
                else -> red
            }
        }
        // 10x10 black square at landscape (130,5) (in the white third)
        for (dy in 0 until 10) {
            for (dx in 0 until 10) {
                val x = 130 + dx
                val y = 5 + dy
                img[y * W + x] = black
            }
        }

        val res = ImageProcessor.process(img, W, H)
        assertEquals(8000, res.frame.size)

        val userDir = System.getProperty("user.dir") ?: throw AssertionError("user.dir is null")
        val base = File(userDir)
        // user.dir is android/app when tests run
        val outDir = File(base, "../../tools/e2e")
        assertTrue(outDir.exists() || outDir.mkdirs())
        val outFile = File(outDir, "golden_frame.bin")
        outFile.writeBytes(res.frame)
        assertEquals(8000L, outFile.length())
    }
}
