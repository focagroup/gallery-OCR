package io.github.minuus69.galleryocr.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import java.io.Closeable
import java.io.FileNotFoundException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A decoded image that can be read in pieces, so very long screenshots never sit in memory at once. */
interface TileSource : Closeable {
    val width: Int
    val height: Int

    /** Returns [rect] (in this source's coordinates) as a new bitmap owned by the caller. */
    fun tile(rect: Rect): Bitmap
}

/** An image prepared for display; tall images are split into strips that fit into GPU textures. */
class ViewerBitmap(val width: Int, val height: Int, val strips: List<Strip>) {
    class Strip(val top: Int, val bitmap: Bitmap)
}

object ImageDecoding {
    /** Above this length (after scaling) the OCR reads the image in tiles. */
    const val SINGLE_PASS_MAX = 2560

    private const val OCR_MAX_PIXELS = 8_000_000
    private const val VIEW_MAX_SIDE = 4096
    private const val VIEW_MAX_PIXELS = 16_000_000
    private const val STRIP_HEIGHT = 2048

    private fun rawBounds(resolver: ContentResolver, uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        // With inJustDecodeBounds the decoder returns null and only fills in the size.
        stream.use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth to options.outHeight
    }

    private fun isQuarterTurn(orientation: Int) = orientation == 90 || orientation == 270

    /**
     * Opens an image for OCR, scaled so that its shorter side is at most [minSideTarget].
     * [orientation] is the MediaStore rotation in degrees.
     */
    fun openForOcr(resolver: ContentResolver, uri: Uri, orientation: Int, minSideTarget: Int): TileSource {
        val (rw, rh) = rawBounds(resolver, uri)
        if (rw > 0 && rh > 0 && orientation == 0) {
            val s = min(1f, minSideTarget.toFloat() / min(rw, rh))
            val sw = max(1, (rw * s).roundToInt())
            val sh = max(1, (rh * s).roundToInt())
            if (max(sw, sh) > SINGLE_PASS_MAX) {
                regionDecoder(resolver, uri)?.let { return RegionTileSource(it, s, sw, sh) }
            }
        }
        val bitmap = decode(resolver, uri, orientation, rw, rh, software = true) { w, h ->
            var s = min(1f, minSideTarget.toFloat() / min(w, h))
            if (w * s * h * s > OCR_MAX_PIXELS) s = sqrt(OCR_MAX_PIXELS / (w.toFloat() * h))
            s
        }
        return FullTileSource(bitmap)
    }

    /** Decodes an image for full-screen viewing. */
    fun decodeForViewer(resolver: ContentResolver, uri: Uri, orientation: Int): ViewerBitmap {
        val (rw, rh) = rawBounds(resolver, uri)
        if (rw > 0 && rh > 0 && orientation == 0 && rh > VIEW_MAX_SIDE && rh >= rw) {
            regionDecoder(resolver, uri)?.let { decoder ->
                try {
                    var sample = 1
                    while (rw / sample > VIEW_MAX_SIDE || (rw / sample).toLong() * (rh / sample) > VIEW_MAX_PIXELS) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val strips = ArrayList<ViewerBitmap.Strip>()
                    var top = 0
                    var y = 0
                    val step = STRIP_HEIGHT * sample
                    while (y < rh) {
                        val bmp = decoder.decodeRegion(Rect(0, y, rw, min(rh, y + step)), options) ?: break
                        strips += ViewerBitmap.Strip(top, bmp)
                        top += bmp.height
                        y += step
                    }
                    if (strips.isNotEmpty()) return ViewerBitmap(strips[0].bitmap.width, top, strips)
                } finally {
                    decoder.recycle()
                }
            }
        }
        val bitmap = decode(resolver, uri, orientation, rw, rh, software = false) { w, h ->
            var s = min(1f, VIEW_MAX_SIDE.toFloat() / max(w, h))
            if (w * s * h * s > VIEW_MAX_PIXELS) s = sqrt(VIEW_MAX_PIXELS / (w.toFloat() * h))
            s
        }
        return ViewerBitmap(bitmap.width, bitmap.height, listOf(ViewerBitmap.Strip(0, bitmap)))
    }

    private fun regionDecoder(resolver: ContentResolver, uri: Uri): BitmapRegionDecoder? = try {
        resolver.openInputStream(uri)?.use { stream ->
            if (Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Decodes the whole image. [scaleFor] gets the (oriented) size and returns the scale factor.
     * EXIF/MediaStore rotation is applied.
     */
    private fun decode(
        resolver: ContentResolver,
        uri: Uri,
        orientation: Int,
        rawWidth: Int,
        rawHeight: Int,
        software: Boolean,
        scaleFor: (Int, Int) -> Float,
    ): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder handles more formats (HEIF, AVIF on newer versions) and applies EXIF rotation itself.
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = scaleFor(info.size.width, info.size.height)
                if (s < 1f) {
                    decoder.setTargetSize(
                        max(1, (info.size.width * s).roundToInt()),
                        max(1, (info.size.height * s).roundToInt()),
                    )
                }
            }
        }
        if (rawWidth <= 0 || rawHeight <= 0) throw IllegalArgumentException("Unsupported image format")
        val turned = isQuarterTurn(orientation)
        val ow = if (turned) rawHeight else rawWidth
        val oh = if (turned) rawWidth else rawHeight
        val s = scaleFor(ow, oh)
        var sample = 1
        while (sample * 2 <= 1f / s) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Could not decode image")
        val tw = max(1, (rawWidth * s).roundToInt())
        val th = max(1, (rawHeight * s).roundToInt())
        if (bmp.width != tw || bmp.height != th) {
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return rotate(bmp, orientation)
    }

    /** Rotates [bitmap] by [degrees] (recycling the input), used where the decoder doesn't. */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    private class FullTileSource(private val bitmap: Bitmap) : TileSource {
        override val width get() = bitmap.width
        override val height get() = bitmap.height

        // For the full rect the source bitmap itself is handed out; recycle() is idempotent,
        // so the caller recycling it and close() recycling it again is fine.
        override fun tile(rect: Rect): Bitmap =
            Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())

        override fun close() = bitmap.recycle()
    }

    private class RegionTileSource(
        private val decoder: BitmapRegionDecoder,
        private val scale: Float,
        override val width: Int,
        override val height: Int,
    ) : TileSource {
        private val sample: Int = run {
            var s = 1
            while (s * 2 <= 1f / scale) s *= 2
            s
        }

        override fun tile(rect: Rect): Bitmap {
            val src = Rect(
                floor(rect.left / scale).toInt().coerceIn(0, decoder.width - 1),
                floor(rect.top / scale).toInt().coerceIn(0, decoder.height - 1),
                ceil(rect.right / scale).toInt().coerceIn(1, decoder.width),
                ceil(rect.bottom / scale).toInt().coerceIn(1, decoder.height),
            )
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = decoder.decodeRegion(src, options) ?: throw IllegalStateException("decodeRegion failed")
            if (bmp.width == rect.width() && bmp.height == rect.height()) return bmp
            val scaled = Bitmap.createScaledBitmap(bmp, rect.width(), rect.height(), true)
            if (scaled !== bmp) bmp.recycle()
            return scaled
        }

        override fun close() = decoder.recycle()
    }
}
