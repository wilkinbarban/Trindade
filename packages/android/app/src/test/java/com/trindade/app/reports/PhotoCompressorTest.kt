package com.trindade.app.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of compression that decides the outcome, tested without a device.
 *
 * The Bitmap work below it needs Android, but *how much a photo is reduced* is arithmetic, and it is
 * where the mistakes that matter live: an upscaled photo, a zero-pixel edge, an aspect ratio quietly
 * changed.
 */
class PhotoCompressorTest {

    @Test
    fun `reduces the longest edge to the limit, preserving the aspect ratio`() {
        val scaled = PhotoCompressor.scaledDimensions(width = 4000, height = 3000)

        assertEquals(1600 to 1200, scaled)
        assertEquals(
            "the aspect ratio must survive the reduction",
            PhotoCompressor.scaledDimensions(4000, 3000)!!.first.toDouble() /
                PhotoCompressor.scaledDimensions(4000, 3000)!!.second.toDouble(),
            4000.0 / 3000.0,
            0.01,
        )
    }

    @Test
    fun `reduces by the longest edge whatever the orientation`() {
        // A portrait photo must be reduced by its height, not its width, or a narrow image stays
        // above the limit while claiming to be within it.
        assertEquals(1200 to 1600, PhotoCompressor.scaledDimensions(width = 3000, height = 4000))
    }

    @Test
    fun `leaves a photo that is already small enough alone`() {
        // Never upscales: enlarging adds bytes and no detail.
        assertEquals(800 to 600, PhotoCompressor.scaledDimensions(width = 800, height = 600))
    }

    @Test
    fun `leaves a photo exactly at the limit alone`() {
        assertEquals(1600 to 1600, PhotoCompressor.scaledDimensions(width = 1600, height = 1600))
    }

    @Test
    fun `never produces a zero-pixel edge on a very elongated photo`() {
        // 20000 by 4 scaled by width would round the height to 0, which is an image with no pixels
        // that the server would accept as a valid JPEG.
        val scaled = PhotoCompressor.scaledDimensions(width = 20000, height = 4)

        assertEquals(1600 to 1, scaled)
        assertTrue(scaled!!.second >= 1)
    }

    @Test
    fun `refuses a photo reporting no size`() {
        // A decode that failed reports zeros. Returning an image would upload an empty file.
        assertNull(PhotoCompressor.scaledDimensions(width = 0, height = 0))
        assertNull(PhotoCompressor.scaledDimensions(width = 4000, height = 0))
        assertNull(PhotoCompressor.scaledDimensions(width = 0, height = 3000))
    }

    @Test
    fun `honours a custom limit`() {
        assertEquals(500 to 250, PhotoCompressor.scaledDimensions(width = 1000, height = 500, maxEdge = 500))
    }
}
