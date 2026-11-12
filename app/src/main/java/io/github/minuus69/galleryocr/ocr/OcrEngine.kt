package io.github.minuus69.galleryocr.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import io.github.minuus69.galleryocr.image.ImageDecoding
import io.github.minuus69.galleryocr.util.Onnx
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One recognized piece of text; coordinates are relative to the image (0..1). */
data class OcrBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
)

data class OcrResult(val text: String, val boxes: List<OcrBox>)

/**
 * On-device OCR with PaddleOCR models running on ONNX Runtime (CPU only, no network).
 *
 * Pipeline: decode (tiled for long images) -> DB text detection -> crop text lines ->
 * CTC recognition with the main model -> optional extra-script models for lines the main
 * model could not read -> assemble lines in reading order.
 *
 * Not thread-safe; use one instance per worker.
 */
class OcrEngine(context: Context, val config: OcrConfig) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = ArrayList<OrtSession>()
    private val det: OrtSession
    private val detInput: String
    private val recognizers: List<Recognizer>
    private val quick: Recognizer?
    private val post = DbPostProcess()
    private var inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var probBuffer = FloatArray(0)
    private var stagingArray = FloatArray(0)
    private val cropPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Per-stage timings of the last [recognize] call, for logging. */
    private val stage = LongArray(7) // decode, detPre, detRun, detPost, recPre, recRun, quickRun
    private var recCrops = IntArray(0)
    private var quickCrops = 0
    val lastTiming: String
        get() = "decode %d, det pre %d run %d post %d, rec pre %d run %d (crops %s)".format(
            stage[0] / 1_000_000, stage[1] / 1_000_000, stage[2] / 1_000_000, stage[3] / 1_000_000,
            stage[4] / 1_000_000, stage[5] / 1_000_000,
            (if (quick != null) "quick $quickCrops in ${stage[6] / 1_000_000} ms, " else "") + recCrops.joinToString("/"),
        )

    private inline fun <T> timed(index: Int, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            stage[index] += System.nanoTime() - t0
        }
    }

    init {
        fun load(asset: String): OrtSession = Onnx.session(context, asset).also { sessions += it }
        fun chars(asset: String): Array<String> {
            val dict = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { r ->
                r.readLines().filter { it.isNotEmpty() }
            }
            // CTC classes: 0 = blank, 1..n = dictionary, n + 1 = space.
            return (listOf("") + dict + listOf(" ")).toTypedArray()
        }
        try {
            det = load(config.mode.detAsset)
            detInput = det.inputNames.first()
            recognizers = config.models.map { model -> Recognizer(load(model.modelAsset), chars(model.dictAsset)) }
            // Fast mode: a small model reads every line first; only unclear lines go to the main model.
            quick = if (config.mode.cascade && config.models.firstOrNull() == RecModel.MAIN) {
                Recognizer(load(QUICK_MODEL), chars(QUICK_DICT))
            } else {
                null
            }
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    override fun close() {
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    fun recognize(resolver: ContentResolver, uri: Uri, orientation: Int): OcrResult {
        val found = ArrayList<OcrBox>()
        stage.fill(0)
        recCrops = IntArray(recognizers.size)
        quickCrops = 0
        timed(0) { ImageDecoding.openForOcr(resolver, uri, orientation, config.mode.detMinSide) }.use { source ->
            val w = source.width
            val h = source.height
            val vertical = h >= w
            val length = max(w, h)
            val tiles = planTiles(length)
            for ((index, range) in tiles.withIndex()) {
                val rect = if (vertical) Rect(0, range.first, w, range.last) else Rect(range.first, 0, range.last, h)
                val tile = timed(0) { source.tile(rect) }
                try {
                    // Boxes near a tile edge are taken from the neighbouring tile, which sees them whole.
                    val lo = if (index > 0) range.first + OVERLAP / 2f else Float.NEGATIVE_INFINITY
                    val hi = if (index < tiles.lastIndex) range.last - OVERLAP / 2f else Float.POSITIVE_INFINITY
                    val boxes = detect(tile).filter { b ->
                        val c = if (vertical) rect.top + b.centerY() else rect.left + b.centerX()
                        c >= lo && c < hi
                    }
                    if (boxes.isEmpty()) continue
                    val texts = recognizeBoxes(tile, boxes)
                    for ((b, text) in boxes.zip(texts)) {
                        if (text == null) continue
                        found += OcrBox(
                            left = (rect.left + b.left) / w,
                            top = (rect.top + b.top) / h,
                            right = (rect.left + b.right) / w,
                            bottom = (rect.top + b.bottom) / h,
                            text = text,
                        )
                    }
                } finally {
                    tile.recycle()
                }
            }
        }
        return assemble(found)
    }

    // ---------------------------------------------------------------- detection

    private fun detect(bitmap: Bitmap): List<RectF> {
        val w = bitmap.width
        val h = bitmap.height
        val tw = max(32, ((w + 16) / 32) * 32)
        val th = max(32, ((h + 16) / 32) * 32)
        val t0 = System.nanoTime()
        val input = if (tw == w && th == h) bitmap else Bitmap.createScaledBitmap(bitmap, tw, th, true)
        val n = tw * th
        val pixels = IntArray(n)
        input.getPixels(pixels, 0, tw, 0, 0, tw, th)
        if (input !== bitmap) input.recycle()

        // Fill a plain array and copy it into the native buffer in one go: single-element
        // puts into a direct buffer cost seconds per image on phones.
        val data = staging(3 * n)
        val g = n
        val r = 2 * n
        for (i in 0 until n) {
            val p = pixels[i]
            // BGR channel order, ImageNet normalization (as in PaddleOCR).
            data[i] = DET_B[p and 0xFF]
            data[g + i] = DET_G[(p shr 8) and 0xFF]
            data[r + i] = DET_R[(p shr 16) and 0xFF]
        }
        val buffer = floatBuffer(3 * n)
        buffer.put(data, 0, 3 * n)
        buffer.rewind()
        if (probBuffer.size < n) probBuffer = FloatArray(n)
        stage[1] += System.nanoTime() - t0
        timed(2) {
            OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, th.toLong(), tw.toLong())).use { tensor ->
                det.run(mapOf(detInput to tensor)).use { result ->
                    (result.get(0) as OnnxTensor).floatBuffer.get(probBuffer, 0, n)
                }
            }
        }
        val boxes = timed(3) { post.boxes(probBuffer, tw, th, DET_THRESH, config.mode.boxThresh, UNCLIP_RATIO) }
        val sx = w.toFloat() / tw
        val sy = h.toFloat() / th
        return boxes.map {
            RectF(
                max(0f, it.left * sx),
                max(0f, it.top * sy),
                min(w.toFloat(), it.right * sx),
                min(h.toFloat(), it.bottom * sy),
            )
        }.filter { it.width() >= 2f && it.height() >= 2f }
    }

    // -------------------------------------------------------------- recognition

    private class Crop(val bitmap: Bitmap, val width: Int)

    private class RecResult(val text: String, val confidence: Float, val minProb: Float) {
        val evidence: Float get() = text.count { !it.isWhitespace() } * confidence
    }

    /** Returns the recognized text per box, or null if nothing reliable was read. */
    private fun recognizeBoxes(tile: Bitmap, boxes: List<RectF>): List<String?> {
        val crops = timed(4) { boxes.map { r ->
            val rect = Rect(
                floor(r.left).toInt().coerceIn(0, tile.width - 1),
                floor(r.top).toInt().coerceIn(0, tile.height - 1),
                ceil(r.right).toInt().coerceIn(1, tile.width),
                ceil(r.bottom).toInt().coerceIn(1, tile.height),
            )
            val cw = max(1, rect.width())
            val ch = max(1, rect.height())
            val rw = ceil(REC_HEIGHT.toFloat() * cw / ch).toInt().coerceIn(1, REC_MAX_WIDTH)
            val out = Bitmap.createBitmap(rw, REC_HEIGHT, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(tile, rect, Rect(0, 0, rw, REC_HEIGHT), cropPaint)
            Crop(out, rw)
        } }
        try {
            val results = arrayOfNulls<RecResult>(crops.size)
            var pending: List<Int> = crops.indices.toList()
            for ((m, recognizer) in recognizers.withIndex()) {
                if (pending.isEmpty()) break
                val read = if (m == 0 && quick != null) {
                    quickCrops += pending.size
                    val before = stage[5]
                    val fast = quick.run(crops, pending)
                    stage[6] += stage[5] - before
                    val unclear = pending.filter { i -> fast[i]?.let(::needsMainModel) ?: true }
                    recCrops[m] += unclear.size
                    fast + recognizer.run(crops, unclear)
                } else {
                    recCrops[m] += pending.size
                    recognizer.run(crops, pending)
                }
                val next = ArrayList<Int>()
                for (i in pending) {
                    val res = read[i] ?: continue
                    if (m == 0) {
                        results[i] = res
                        if (needsOtherScripts(res, crops[i].width)) next += i
                    } else {
                        val old = results[i]
                        if (old == null || res.evidence > old.evidence * 1.3f) results[i] = res
                        next += i
                    }
                }
                pending = next
            }
            return results.map { r ->
                val text = r?.text?.trim()
                if (r == null || text.isNullOrEmpty() || r.confidence < MIN_CONFIDENCE) null else text
            }
        } finally {
            crops.forEach { it.bitmap.recycle() }
        }
    }

    /**
     * Cascade rule for Fast mode: the quick model's reading is kept only if it is clearly
     * sure about every character. It does not know Cyrillic and turns it into confident Latin
     * look-alikes ("pnBet" for "Привет"), so odd capitals inside a word or digits between
     * letters also send the line to the main model. Tuned on test screenshots: recognition
     * ~1.7x faster with ~2 % of words read differently.
     */
    private fun needsMainModel(res: RecResult): Boolean =
        res.confidence < 0.95f || res.minProb < 0.5f || MISREAD_PATTERN.containsMatchIn(res.text)

    /**
     * A model that does not know a script drops its letters (CTC blanks) while staying
     * confident about the rest, so lines with too few characters for their width are
     * re-read by the other enabled models. Tiny boxes (icons, single characters) and lines
     * already read as Chinese/Japanese/Korean are not worth it.
     */
    private fun needsOtherScripts(res: RecResult, cropWidth: Int): Boolean {
        val frames = ceil(cropWidth / 8f)
        if (frames < 12f) return false
        val text = res.text.trim()
        val cjk = text.any { c ->
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
                else -> false
            }
        }
        val density = text.length / frames
        return density < (if (cjk) 0.10f else 0.25f) || res.confidence < 0.7f
    }

    private inner class Recognizer(private val session: OrtSession, private val chars: Array<String>) {
        private val input = session.inputNames.first()
        private val pixels = IntArray(REC_HEIGHT * REC_MAX_WIDTH)

        fun run(crops: List<Crop>, indices: List<Int>): Map<Int, RecResult> {
            val out = HashMap<Int, RecResult>(indices.size)
            val order = indices.sortedBy { crops[it].width }
            for (batch in order.chunked(REC_BATCH)) {
                val tFill = System.nanoTime()
                val width = max(REC_MIN_WIDTH, batch.maxOf { crops[it].width })
                val plane = REC_HEIGHT * width
                val length = batch.size * 3 * plane
                val data = staging(length)
                // Zero padding on the right, as in PaddleOCR.
                data.fill(0f, 0, length)
                for ((k, idx) in batch.withIndex()) {
                    val crop = crops[idx]
                    val cw = crop.width
                    crop.bitmap.getPixels(pixels, 0, cw, 0, 0, cw, REC_HEIGHT)
                    val base = k * 3 * plane
                    for (y in 0 until REC_HEIGHT) {
                        for (x in 0 until cw) {
                            val p = pixels[y * cw + x]
                            val o = base + y * width + x
                            data[o] = REC_NORM[p and 0xFF]
                            data[o + plane] = REC_NORM[(p shr 8) and 0xFF]
                            data[o + 2 * plane] = REC_NORM[(p shr 16) and 0xFF]
                        }
                    }
                }
                val buffer = floatBuffer(length)
                buffer.put(data, 0, length)
                buffer.rewind()
                val shape = longArrayOf(batch.size.toLong(), 3, REC_HEIGHT.toLong(), width.toLong())
                stage[4] += System.nanoTime() - tFill
                val tRun = System.nanoTime()
                OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
                    session.run(mapOf(input to tensor)).also { stage[5] += System.nanoTime() - tRun }.use { result ->
                        val indexTensor = result.get(0) as OnnxTensor
                        val probTensor = result.get(1) as OnnxTensor
                        val steps = (indexTensor.info as TensorInfo).shape[1].toInt()
                        val classes = LongArray(batch.size * steps).also { indexTensor.longBuffer.get(it) }
                        val probs = FloatArray(batch.size * steps).also { probTensor.floatBuffer.get(it) }
                        for ((k, idx) in batch.withIndex()) {
                            val sb = StringBuilder()
                            var conf = 0f
                            var minProb = 1f
                            var count = 0
                            var prev = -1
                            for (t in 0 until steps) {
                                val c = classes[k * steps + t].toInt()
                                if (c != prev && c > 0 && c < chars.size) {
                                    sb.append(chars[c])
                                    val p = probs[k * steps + t]
                                    conf += p
                                    if (p < minProb) minProb = p
                                    count++
                                }
                                prev = c
                            }
                            out[idx] = if (count > 0) RecResult(sb.toString(), conf / count, minProb) else RecResult("", 0f, 0f)
                        }
                    }
                }
            }
            return out
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Reusable plain float array of at least [count] elements. */
    private fun staging(count: Int): FloatArray {
        if (stagingArray.size < count) stagingArray = FloatArray(count)
        return stagingArray
    }

    /** Direct, native-order float buffer of exactly [count] elements (reused between calls). */
    private fun floatBuffer(count: Int): FloatBuffer {
        val bytes = count * 4
        if (inputBuffer.capacity() < bytes) {
            inputBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
        inputBuffer.clear()
        inputBuffer.limit(bytes)
        return inputBuffer.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    private fun planTiles(length: Int): List<IntRange> {
        if (length <= ImageDecoding.SINGLE_PASS_MAX) return listOf(0..length)
        val tiles = ArrayList<IntRange>()
        var start = 0
        while (true) {
            val end = min(length, start + TILE)
            tiles += start..end
            if (end >= length) break
            start = end - OVERLAP
        }
        return tiles
    }

    /** Groups boxes into lines (top to bottom, left to right) and builds the full text. */
    private fun assemble(boxes: List<OcrBox>): OcrResult {
        val lines = ArrayList<MutableList<OcrBox>>()
        for (b in boxes.sortedBy { (it.top + it.bottom) / 2 }) {
            val line = lines.lastOrNull()
            val cy = (b.top + b.bottom) / 2
            val bh = b.bottom - b.top
            if (line != null) {
                val lcy = line.map { (it.top + it.bottom) / 2 }.average().toFloat()
                val lh = line.map { it.bottom - it.top }.average().toFloat()
                if (abs(cy - lcy) < 0.5f * min(bh, lh)) {
                    line += b
                    continue
                }
            }
            lines += mutableListOf(b)
        }
        val ordered = lines.map { line -> line.sortedBy { it.left } }
        val text = ordered.joinToString("\n") { line -> line.joinToString(" ") { it.text } }
        return OcrResult(text, ordered.flatten())
    }

    private companion object {
        const val DET_THRESH = 0.2f
        const val UNCLIP_RATIO = 1.4f
        const val TILE = 2048
        const val OVERLAP = 160
        const val REC_HEIGHT = 48
        // PaddleOCR pads short lines to 320 px; 160 gave equal or better results on test
        // screenshots with ~20 % less recognition work.
        const val REC_MIN_WIDTH = 160
        const val REC_MAX_WIDTH = 3200
        const val REC_BATCH = 8
        const val MIN_CONFIDENCE = 0.5f
        const val QUICK_MODEL = "models/rec_fast.onnx"
        const val QUICK_DICT = "models/rec_fast.txt"
        val MISREAD_PATTERN = Regex("\\p{Ll}\\p{Lu}|\\p{L}\\p{Nd}\\p{L}")

        private fun lut(mean: Float, std: Float) = FloatArray(256) { (it / 255f - mean) / std }
        val DET_B = lut(0.485f, 0.229f)
        val DET_G = lut(0.456f, 0.224f)
        val DET_R = lut(0.406f, 0.225f)
        val REC_NORM = lut(0.5f, 0.5f)
    }
}
