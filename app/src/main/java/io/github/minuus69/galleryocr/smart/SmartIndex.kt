package io.github.minuus69.galleryocr.smart

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.minuus69.galleryocr.data.ImageDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/** A search hit: image id and cosine similarity. */
data class SmartHit(val id: Long, val score: Float)

/**
 * In-memory smart search over all stored image embeddings. Images analyzed with different
 * models (e.g. while a mode switch is being applied) are each compared with the query encoded
 * by their own model, so search keeps working during re-analysis.
 */
class SmartIndex(private val context: Context, private val dao: ImageDao) : Closeable {
    private class Group(val ids: LongArray, val vectors: FloatArray, val dim: Int)

    private val mutex = Mutex()
    private var groups: Map<SmartMode, Group> = emptyMap()
    private var loadedVersion: String? = null
    private var tokenizer: ClipTokenizer? = null
    private val encoders = HashMap<SmartMode, ClipTextEncoder>()

    @Volatile
    private var closed = false

    /**
     * Returns images scoring at least the cutoff for [strictness], best first. [version] is the
     * current embeddings version; the index reloads when it changed.
     */
    suspend fun search(query: String, strictness: Float, version: String): List<SmartHit> = mutex.withLock {
        try {
            searchLocked(query, strictness, version)
        } finally {
            if (closed) closeEncoders()
        }
    }

    private suspend fun searchLocked(query: String, strictness: Float, version: String): List<SmartHit> {
        if (closed) return emptyList()
        if (version != loadedVersion) load(version)
        val hits = ArrayList<SmartHit>()
        for ((mode, group) in groups) {
            val t0 = SystemClock.elapsedRealtime()
            val q = encoder(mode).embed(query)
            if (q.size != group.dim) continue
            val cutoff = mode.cutoff(strictness)
            var best = -1f
            for (row in group.ids.indices) {
                var dot = 0f
                val base = row * group.dim
                for (k in 0 until group.dim) dot += q[k] * group.vectors[base + k]
                if (dot > best) best = dot
                if (dot >= cutoff) hits += SmartHit(group.ids[row], dot)
            }
            Log.d(TAG, "\"$query\" (${mode.id}): ${group.ids.size} images, best %.3f, cutoff %.3f, %d hits in %d ms"
                .format(best, cutoff, hits.size, SystemClock.elapsedRealtime() - t0))
        }
        hits.sortByDescending { it.score }
        if (hits.size > MAX_RESULTS) hits.subList(MAX_RESULTS, hits.size).clear()
        return hits
    }

    private suspend fun load(version: String) {
        val t0 = SystemClock.elapsedRealtime()
        val byModel = dao.allEmbeddings().groupBy { it.model }
        val loaded = HashMap<SmartMode, Group>()
        for ((model, rows) in byModel) {
            val mode = SmartMode.entries.firstOrNull { it.id == model } ?: continue
            val dim = rows.first().vector.size / 4
            val ids = LongArray(rows.size)
            val vectors = FloatArray(rows.size * dim)
            var n = 0
            for (row in rows) {
                if (row.vector.size != dim * 4) continue
                ids[n] = row.imageId
                Embeddings.read(row.vector, vectors, n * dim)
                n++
            }
            loaded[mode] = Group(ids.copyOf(n), vectors.copyOf(n * dim), dim)
        }
        groups = loaded
        loadedVersion = version
        // Free text models that are no longer needed.
        encoders.keys.filter { it !in loaded }.forEach { encoders.remove(it)?.close() }
        Log.i(TAG, "loaded ${loaded.values.sumOf { it.ids.size }} embeddings in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    private fun encoder(mode: SmartMode): ClipTextEncoder = encoders.getOrPut(mode) {
        val tok = tokenizer ?: ClipTokenizer.fromAssets(context).also { tokenizer = it }
        ClipTextEncoder(context, mode, tok)
    }

    /** Frees the text models; a search still running closes them when it is done. */
    override fun close() {
        closed = true
        if (mutex.tryLock()) {
            try {
                closeEncoders()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun closeEncoders() {
        encoders.values.forEach { runCatching { it.close() } }
        encoders.clear()
    }

    private companion object {
        const val TAG = "SmartIndex"
        const val MAX_RESULTS = 2000
    }
}
