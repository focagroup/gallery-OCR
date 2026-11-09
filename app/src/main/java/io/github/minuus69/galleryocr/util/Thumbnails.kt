package io.github.minuus69.galleryocr.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.minuus69.galleryocr.data.MediaStoreSource
import io.github.minuus69.galleryocr.image.ImageDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Grid thumbnails from MediaStore's own thumbnail cache, kept in a memory LRU cache. */
object Thumbnails {
    private val cache = object : LruCache<Long, ImageBitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)

    fun cached(id: Long): ImageBitmap? = cache.get(id)

    fun evict(id: Long) {
        cache.remove(id)
    }

    suspend fun load(context: Context, id: Long, orientation: Int, sizePx: Int): ImageBitmap? = withContext(dispatcher) {
        cache.get(id)?.let { return@withContext it }
        val resolver = context.contentResolver
        var bitmap = try {
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.loadThumbnail(MediaStoreSource.imageUri(id), Size(sizePx, sizePx), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    ?.let { ImageDecoding.rotate(it, orientation) }
            }
        } catch (e: Exception) {
            null
        }
        // System thumbnails of long screenshots are tiny and blurry; decode just their top instead.
        if (orientation == 0 && (bitmap == null || bitmap.height > bitmap.width * 2.2f)) {
            topOfImage(context, id, sizePx)?.let {
                bitmap?.recycle()
                bitmap = it
            }
        }
        bitmap?.asImageBitmap()?.also { cache.put(id, it) }
    }

    private fun topOfImage(context: Context, id: Long, sizePx: Int): Bitmap? = try {
        context.contentResolver.openInputStream(MediaStoreSource.imageUri(id))?.use { stream ->
            val decoder = (
                if (Build.VERSION.SDK_INT >= 31) {
                    BitmapRegionDecoder.newInstance(stream)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(stream, false)
                }
                ) ?: return@use null
            try {
                val w = decoder.width
                val h = min(decoder.height, (w * 1.8f).toInt())
                var sample = 1
                while (w / (sample * 2) >= sizePx) sample *= 2
                decoder.decodeRegion(Rect(0, 0, w, h), BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                decoder.recycle()
            }
        }
    } catch (e: Exception) {
        null
    }
}
