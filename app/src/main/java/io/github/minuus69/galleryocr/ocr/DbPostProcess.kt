package io.github.minuus69.galleryocr.ocr

import android.graphics.RectF
import kotlin.math.min

/**
 * Turns the probability map of the DB text detector into text boxes.
 *
 * Simplified version of PaddleOCR's DBPostProcess for (mostly horizontal) screen text:
 * connected regions of the thresholded map become axis-aligned boxes, which are scored by
 * their mean probability and then expanded ("unclipped") back to the full text size.
 */
internal class DbPostProcess {
    private var visited = BooleanArray(0)
    private var stack = IntArray(4096)

    fun boxes(
        prob: FloatArray,
        width: Int,
        height: Int,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        maxCandidates: Int = 3000,
    ): List<RectF> {
        val n = width * height
        if (visited.size < n) visited = BooleanArray(n) else visited.fill(false, 0, n)
        val out = ArrayList<RectF>()
        for (start in 0 until n) {
            if (visited[start] || prob[start] <= thresh) continue
            // Flood fill (8-connectivity) and track the bounding box.
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var minX = start % width
            var maxX = minX
            var minY = start / width
            var maxY = minY
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % width
                val y = p / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    val row = ny * width
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val q = row + nx
                        if (!visited[q] && prob[q] > thresh) {
                            visited[q] = true
                            if (sp == stack.size) stack = stack.copyOf(sp * 2)
                            stack[sp++] = q
                        }
                    }
                }
            }
            val bw = (maxX - minX).toFloat()
            val bh = (maxY - minY).toFloat()
            if (min(bw, bh) < MIN_SIZE) continue
            var sum = 0.0
            for (y in minY..maxY) {
                val row = y * width
                for (x in minX..maxX) sum += prob[row + x]
            }
            val score = sum / ((maxX - minX + 1) * (maxY - minY + 1))
            if (score < boxThresh) continue
            val d = bw * bh * unclipRatio / (2f * (bw + bh))
            val box = RectF(minX - d, minY - d, maxX + d, maxY + d)
            if (min(box.width(), box.height()) < MIN_SIZE + 2) continue
            out += box
            if (out.size >= maxCandidates) break
        }
        return out
    }

    private companion object {
        const val MIN_SIZE = 3f
    }
}
