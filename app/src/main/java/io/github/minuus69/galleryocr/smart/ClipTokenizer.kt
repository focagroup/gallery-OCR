package io.github.minuus69.galleryocr.smart

import java.text.Normalizer

/**
 * CLIP's byte-level BPE tokenizer, matching the Hugging Face `CLIPTokenizerFast` used to train
 * and export the models (NFC, collapse whitespace, lowercase, split, byte-level BPE with `</w>`).
 *
 * [vocab] holds the tokens in id order, [merges] the BPE merges ("a b") in rank order.
 */
class ClipTokenizer(vocab: List<String>, merges: List<String>) {
    private val encoder = HashMap<String, Int>(vocab.size * 2).apply {
        vocab.forEachIndexed { id, token -> put(token, id) }
    }
    private val ranks = HashMap<String, Int>(merges.size * 2).apply {
        merges.forEachIndexed { rank, merge -> put(merge, rank) }
    }
    private val cache = object : LinkedHashMap<String, IntArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 4096
    }

    /** Token ids including start and end token, truncated to [CONTEXT_LENGTH]. */
    fun tokenize(text: String): IntArray {
        val normalized = WHITESPACE.replace(Normalizer.normalize(text, Normalizer.Form.NFC), " ").lowercase()
        val ids = ArrayList<Int>()
        ids += START
        for (match in SPLIT.findAll(normalized)) {
            val word = match.value.toByteArray(Charsets.UTF_8)
                .joinToString("") { BYTE_TO_CHAR[it.toInt() and 0xff].toString() }
            for (id in bpe(word)) {
                if (ids.size >= CONTEXT_LENGTH - 1) break
                ids += id
            }
            if (ids.size >= CONTEXT_LENGTH - 1) break
        }
        ids += END
        return ids.toIntArray()
    }

    /** [tokenize] padded with zeros to [CONTEXT_LENGTH], as the text model expects. */
    fun encode(text: String): LongArray {
        val out = LongArray(CONTEXT_LENGTH)
        tokenize(text).forEachIndexed { i, id -> out[i] = id.toLong() }
        return out
    }

    private fun bpe(word: String): IntArray = synchronized(cache) { cache[word] } ?: run {
        var parts = word.map { it.toString() }.toMutableList()
        parts[parts.lastIndex] = parts.last() + "</w>"
        while (parts.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val rank = ranks[parts[i] + " " + parts[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = i
                }
            }
            if (best < 0) break
            val first = parts[best]
            val second = parts[best + 1]
            val merged = ArrayList<String>(parts.size)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == first && parts[i + 1] == second) {
                    merged += first + second
                    i += 2
                } else {
                    merged += parts[i]
                    i++
                }
            }
            parts = merged
        }
        // Every single byte symbol is in the vocabulary, so the fallback is never needed in practice.
        val ids = parts.map { encoder[it] ?: END }.toIntArray()
        synchronized(cache) { cache[word] = ids }
        ids
    }

    companion object {
        const val CONTEXT_LENGTH = 77
        const val START = 49406
        const val END = 49407

        /**
         * Unicode White_Space, spelled out: Android's ICU regex rejects the (?U) flag that plain
         * Java needs to make \s Unicode-aware, and the reference tokenizer uses Unicode \s.
         */
        private const val WS = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"
        private val WHITESPACE = Regex("[$WS]+")
        private val SPLIT = Regex("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^$WS\\p{L}\\p{N}]+")

        /** GPT-2's reversible byte -> printable character map. */
        private val BYTE_TO_CHAR: CharArray = run {
            val printable = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toSet()
            val map = CharArray(256)
            var n = 0
            for (b in 0 until 256) {
                map[b] = if (b in printable) b.toChar() else (256 + n++).toChar()
            }
            map
        }

        fun fromAssets(context: android.content.Context): ClipTokenizer {
            fun lines(asset: String) = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { r ->
                r.readLines().filter { it.isNotEmpty() }
            }
            return ClipTokenizer(lines(VOCAB_ASSET), lines(MERGES_ASSET))
        }

        const val VOCAB_ASSET = "models/clip_vocab.txt"
        const val MERGES_ASSET = "models/clip_merges.txt"
    }
}
