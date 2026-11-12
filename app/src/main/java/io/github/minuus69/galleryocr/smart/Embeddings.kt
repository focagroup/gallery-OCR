package io.github.minuus69.galleryocr.smart

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stores embeddings as little-endian float32 bytes in the database. */
object Embeddings {
    fun toBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    /** Reads [bytes] into [out] starting at [offset]; returns the number of values. */
    fun read(bytes: ByteArray, out: FloatArray, offset: Int): Int {
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = floats.remaining()
        floats.get(out, offset, n)
        return n
    }
}
