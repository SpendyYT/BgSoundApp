package com.bgsounds.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes an asset PNG at a reduced resolution so the sound list doesn't hold
 * ~20 full-size cover images in memory at once.
 */
fun loadSampledBitmapFromAsset(context: Context, assetPath: String, reqSize: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqSize, reqSize)
        }
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
