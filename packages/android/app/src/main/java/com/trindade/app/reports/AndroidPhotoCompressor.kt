package com.trindade.app.reports

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compresses a captured photo to JPEG before it is uploaded.
 *
 * The decision of how much to reduce lives in [PhotoCompressor.scaledDimensions], which is pure and
 * tested. This class only executes it: decode the dimensions first, decode the pixels at a sampled
 * size, rotate, and re-encode.
 *
 * Rotation is not decoration. A camera writes the sensor's orientation into EXIF rather than rotating
 * the pixels, so a photo taken in portrait arrives landscape unless something reads that tag, and an
 * upside-down photo of a temperature display is worse than no photo.
 */
@Singleton
class AndroidPhotoCompressor @Inject constructor() : PhotoCompressor {

    override suspend fun compressToJpeg(path: String): ByteArray? = withContext(Dispatchers.IO) {
        // Decoded twice on purpose: bounds only first, because decoding the pixels of a 4000-pixel
        // photo to then discard most of them is the memory this compresses away.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        val target = PhotoCompressor.scaledDimensions(bounds.outWidth, bounds.outHeight)
            ?: return@withContext null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        val decoded = BitmapFactory.decodeFile(path, decodeOptions) ?: return@withContext null

        val scaled = scaleToTarget(decoded, target)
        val oriented = applyExifRotation(path, scaled)

        try {
            ByteArrayOutputStream().use { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, PhotoCompressor.JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            // Recycled explicitly: several photos can be queued, and the backing memory is held by
            // native code that the garbage collector will not feel pressure from.
            if (oriented !== scaled) oriented.recycle()
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /**
     * A power-of-two sampling factor that decodes at or above the target.
     *
     * Powers of two because that is the only thing `inSampleSize` honours; the exact size is reached
     * afterwards by [scaleToTarget]. Sampling down to below the target first would lose the detail the
     * final scale is meant to keep.
     */
    private fun sampleSize(width: Int, height: Int, target: Pair<Int, Int>): Int {
        var sample = 1
        while (width / (sample * 2) >= target.first && height / (sample * 2) >= target.second) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToTarget(bitmap: Bitmap, target: Pair<Int, Int>): Bitmap {
        if (bitmap.width == target.first && bitmap.height == target.second) return bitmap
        return Bitmap.createScaledBitmap(bitmap, target.first, target.second, true)
    }

    private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            // Anything else, including a mirrored orientation, is left as decoded: rotating a
            // mirrored photo would produce a plausible image that is not the one that was taken.
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
