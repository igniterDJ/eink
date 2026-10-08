package com.keychain.epd

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** Decodes [uri] downsampled to at most [maxSide] on its longest side, oriented per EXIF. */
fun decodeDownsampledAndOrientedBitmap(contentResolver: ContentResolver, uri: Uri, maxSide: Int = 1600): Bitmap {
    val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, optsBounds)
    }

    val (w, h) = optsBounds.outWidth to optsBounds.outHeight
    if (w <= 0 || h <= 0) {
        throw IllegalArgumentException("Unable to decode image bounds")
    }

    var sample = 1
    var tmpW = w
    var tmpH = h
    while (tmpW > maxSide || tmpH > maxSide) {
        sample *= 2
        tmpW /= 2
        tmpH /= 2
    }

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val decoded = contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    } ?: throw IllegalArgumentException("Unable to decode image")

    val orientation = contentResolver.openInputStream(uri)?.use { input ->
        ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } ?: ExifInterface.ORIENTATION_NORMAL

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }

    if (!matrix.isIdentity) {
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    return decoded
}
