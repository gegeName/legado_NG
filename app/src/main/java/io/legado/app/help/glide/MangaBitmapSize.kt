package io.legado.app.help.glide

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import java.security.MessageDigest
import kotlin.math.sqrt

private const val MAX_MANGA_BITMAP_SIDE = 8192
// Leave room below Canvas's byte limit even for 8-byte-per-pixel wide-gamut bitmaps.
private const val MAX_MANGA_BITMAP_PIXELS = 8 * 1024 * 1024

internal fun RequestBuilder<Drawable>.withMangaBitmapSize(
    displayWidth: Int,
    colorTransformation: Transformation<Bitmap>? = null,
): RequestBuilder<Drawable> {
    val sized = override(displayWidth, MAX_MANGA_BITMAP_SIDE).downsample(MangaDownsampleStrategy)
    return if (colorTransformation == null) {
        sized.optionalTransform(MangaBitmapLimit)
    } else {
        sized.transform(MultiTransformation(MangaBitmapLimit, colorTransformation))
    }
}

internal object MangaDownsampleStrategy : DownsampleStrategy() {
    override fun getScaleFactor(
        sourceWidth: Int,
        sourceHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Float = minOf(
        requestedWidth.toDouble() / sourceWidth,
        requestedHeight.toDouble() / sourceHeight,
        mangaBitmapScale(sourceWidth, sourceHeight),
    ).toFloat()

    override fun getSampleSizeRounding(
        sourceWidth: Int,
        sourceHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int,
    ): SampleSizeRounding = SampleSizeRounding.QUALITY
}

private fun mangaBitmapScale(width: Int, height: Int): Double = minOf(
    1.0,
    MAX_MANGA_BITMAP_SIDE.toDouble() / maxOf(width, height),
    sqrt(MAX_MANGA_BITMAP_PIXELS.toDouble() / (width.toLong() * height)),
)

// Glide's GIF decoder uses its own sampling. Apply the same safety limit to each decoded frame.
private object MangaBitmapLimit : BitmapTransformation() {
    private val cacheKey = "io.legado.app.manga.bitmapLimit.v1".toByteArray(CHARSET)

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val scale = mangaBitmapScale(toTransform.width, toTransform.height)
        if (scale == 1.0) return toTransform
        return Bitmap.createScaledBitmap(
            toTransform,
            (toTransform.width * scale).toInt().coerceAtLeast(1),
            (toTransform.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) = messageDigest.update(cacheKey)
}
