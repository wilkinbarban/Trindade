package com.trindade.app.reports

/**
 * Turns a captured photo into the bytes that get uploaded.
 *
 * An interface for the reason D2's TokenStore is one: the work needs a device, so the part worth
 * testing has to be reachable without one. Here the testable part is [scaledDimensions], which is
 * pure, and it is the part that decides how much a photo is reduced.
 */
interface PhotoCompressor {

    /**
     * Compressed JPEG bytes for the photo at [path], or null when it cannot be read.
     *
     * Null rather than a throw, or rather than uploading the original: an uncompressed photo can
     * exceed the server's 5 MB limit, and a failure that uploads something anyway is how a limit
     * becomes a 413 the operator cannot act on.
     */
    suspend fun compressToJpeg(path: String): ByteArray?

    companion object {
        /**
         * The longest edge a photo keeps.
         *
         * A phone camera produces 4000-pixel edges and the export embeds these at thumbnail size, so
         * the resolution is spent on nothing but upload time over a yard's connection.
         */
        const val MAX_EDGE_PX = 1600

        /**
         * JPEG quality. High enough that a temperature display or a label stays legible, which is the
         * only thing a reviewer of these photos needs to read.
         */
        const val JPEG_QUALITY = 80

        /**
         * The size a photo is reduced to, preserving its aspect ratio.
         *
         * Pure, so it can be tested without a device -- and it is the half of compression that
         * decides the outcome, while the Bitmap call below only executes it.
         *
         * Never upscales: a photo already within the limit is left alone, because enlarging it would
         * add bytes without adding detail. A zero dimension returns null, because a photo reporting
         * no size is not one that can be scaled, and returning a 0-pixel image would upload an empty
         * file that the server would accept as a valid JPEG.
         */
        fun scaledDimensions(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Pair<Int, Int>? {
            if (width <= 0 || height <= 0) return null

            val longestEdge = maxOf(width, height)
            if (longestEdge <= maxEdge) return width to height

            val ratio = maxEdge.toDouble() / longestEdge
            // Rounded up and floored at 1: rounding down can reach zero on a very elongated photo,
            // and a zero-pixel edge is an image with no pixels.
            return maxOf(1, (width * ratio).toInt()) to maxOf(1, (height * ratio).toInt())
        }
    }
}
