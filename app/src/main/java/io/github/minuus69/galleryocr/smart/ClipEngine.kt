package io.github.minuus69.galleryocr.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import io.github.minuus69.galleryocr.image.ImageDecoding
import io.github.minuus69.galleryocr.util.Onnx
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SIZE = 224
private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

/** Images whose long side is at least this much longer than the short side also get a whole-image view. */
private const val WHOLE_IMAGE_MIN_ASPECT = 1.2f

/**
 * Turns images into CLIP embeddings (L2-normalized, so a dot product with a text embedding of the
 * same [mode] is their cosine similarity). Not thread-safe; use one instance per worker.
 */
class ClipImageEncoder(context: Context, val mode: SmartMode) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = Onnx.session(context, mode.visualAsset)
    private val input = session.inputNames.first()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(SIZE * SIZE)
    private var staging = FloatArray(0)

    /** Decode and model time of the last [embed] call in ms, for logging. */
    var lastTiming = ""
        private set

    fun embed(resolver: ContentResolver, uri: Uri, orientation: Int): FloatArray {
        val t0 = System.nanoTime()
        val image = ImageDecoding.decodeSmall(resolver, uri, orientation, SIZE)
        val views = ArrayList<Bitmap>(2)
        try {
            views += centerCrop(image)
            val aspect = max(image.width, image.height).toFloat() / minOf(image.width, image.height).coerceAtLeast(1)
            if (mode.wholeImage && aspect >= WHOLE_IMAGE_MIN_ASPECT) views += padToSquare(image)
        } finally {
            image.recycle()
        }
        val t1 = System.nanoTime()
        val count = views.size
        if (staging.size != count * 3 * SIZE * SIZE) staging = FloatArray(count * 3 * SIZE * SIZE)
        views.forEachIndexed { i, view ->
            fill(view, i * 3 * SIZE * SIZE)
            view.recycle()
        }
        val buffer = ByteBuffer.allocateDirect(staging.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(staging).rewind()
        val out = OnnxTensor.createTensor(env, buffer, longArrayOf(count.toLong(), 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                average(result[0].value as Array<FloatArray>)
            }
        }
        lastTiming = "decode %d ms, model %d ms (%d views)".format(
            (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000, count,
        )
        return out
    }

    /** CLIP's standard view: short side scaled to 224, center crop. */
    private fun centerCrop(image: Bitmap): Bitmap {
        val s = SIZE.toFloat() / minOf(image.width, image.height)
        val w = (image.width * s).roundToInt().coerceAtLeast(SIZE)
        val h = (image.height * s).roundToInt().coerceAtLeast(SIZE)
        val left = (w - SIZE) / 2f
        val top = (h - SIZE) / 2f
        return draw { canvas -> canvas.drawBitmap(image, null, android.graphics.RectF(-left, -top, w - left, h - top), paint) }
    }

    /** The whole image, long side scaled to 224 and centered on the mean colour (zero after normalization). */
    private fun padToSquare(image: Bitmap): Bitmap {
        val s = SIZE.toFloat() / max(image.width, image.height)
        val w = (image.width * s).roundToInt().coerceAtLeast(1)
        val h = (image.height * s).roundToInt().coerceAtLeast(1)
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2
        return draw { canvas ->
            canvas.drawRGB((MEAN[0] * 255).roundToInt(), (MEAN[1] * 255).roundToInt(), (MEAN[2] * 255).roundToInt())
            canvas.drawBitmap(image, null, Rect(left, top, left + w, top + h), paint)
        }
    }

    private inline fun draw(block: (Canvas) -> Unit): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        block(Canvas(out))
        return out
    }

    /** Writes [view] as normalized CHW floats into [staging] at [offset]. */
    private fun fill(view: Bitmap, offset: Int) {
        view.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val p = pixels[i]
            staging[offset + i] = (((p shr 16) and 0xff) / 255f - MEAN[0]) / STD[0]
            staging[offset + plane + i] = (((p shr 8) and 0xff) / 255f - MEAN[1]) / STD[1]
            staging[offset + 2 * plane + i] = ((p and 0xff) / 255f - MEAN[2]) / STD[2]
        }
    }

    override fun close() = session.close()
}

/** Turns search queries into CLIP text embeddings. Thread-safe. */
class ClipTextEncoder(context: Context, val mode: SmartMode, private val tokenizer: ClipTokenizer) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    // Queries are short; two threads keep the UI responsive while a scan runs.
    private val session: OrtSession = Onnx.session(context, mode.textAsset, threads = 2)
    private val input = session.inputNames.first()

    fun embed(query: String): FloatArray {
        val ids = tokenizer.encode(query)
        return OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<FloatArray>)[0]
            }
        }
    }

    override fun close() = session.close()
}

/** Mean of normalized vectors, normalized again. */
internal fun average(vectors: Array<FloatArray>): FloatArray {
    if (vectors.size == 1) return vectors[0]
    val out = FloatArray(vectors[0].size)
    for (v in vectors) for (i in out.indices) out[i] += v[i]
    var norm = 0f
    for (x in out) norm += x * x
    norm = sqrt(norm).coerceAtLeast(1e-12f)
    for (i in out.indices) out[i] /= norm
    return out
}
