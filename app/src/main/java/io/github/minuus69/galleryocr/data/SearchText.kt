package io.github.minuus69.galleryocr.data

import io.github.minuus69.galleryocr.ocr.OcrBox
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

/**
 * Normalization shared by indexing and searching, so that search is case-insensitive,
 * accent-insensitive for Latin/Greek/Cyrillic ("muller" finds "Müller") and robust against
 * OCR spacing mistakes (whitespace is removed entirely; terms are matched as substrings).
 */
object SearchText {
    fun normalize(input: CharSequence): String {
        val decomposed = Normalizer.normalize(input.toString().lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        var lastBase = 0.toChar()
        for (ch in decomposed) {
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) continue
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt()) {
                // Drop accents on European scripts only; keep marks that matter elsewhere.
                if (lastBase.code < 0x0530) continue
            } else {
                lastBase = ch
            }
            sb.append(ch)
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC).replace("ß", "ss")
    }

    /** Splits a query into normalized terms. Quoted parts ("credit card") stay one term. */
    fun terms(query: String): List<String> {
        val terms = ArrayList<String>()
        Regex("\"([^\"]*)\"|(\\S+)").findAll(query).forEach { m ->
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val n = normalize(raw.trim('"'))
            if (n.isNotEmpty()) terms += n
        }
        return terms.distinct()
    }

    /**
     * Horizontal parts (fractions 0..1 of the box width) of [box] that match [terms].
     * Recognized boxes are whole text lines, so the position of the match inside the line is
     * estimated from its character offsets. Empty if the box does not match.
     */
    fun matchSpans(box: OcrBox, terms: List<String>): List<ClosedFloatingPointRange<Float>> {
        if (!boxMatches(box, terms)) return emptyList()
        val text = box.text
        if (text.isEmpty()) return emptyList()
        // Normalize character by character, remembering where each normalized char came from.
        val normalized = StringBuilder()
        val origin = ArrayList<Int>()
        for ((i, ch) in text.withIndex()) {
            for (n in normalize(ch.toString())) {
                normalized.append(n)
                origin += i
            }
        }
        val spans = ArrayList<ClosedFloatingPointRange<Float>>()
        for (term in terms) {
            var from = normalized.indexOf(term)
            while (from >= 0) {
                val start = origin[from]
                val end = origin[from + term.length - 1] + 1
                spans += (start.toFloat() / text.length)..(end.toFloat() / text.length)
                from = normalized.indexOf(term, from + term.length)
            }
        }
        // Term spread over several boxes: highlight the whole box.
        return spans.ifEmpty { listOf(0f..1f) }
    }

    /** Whether a recognized box should be highlighted for the given terms. */
    fun boxMatches(box: OcrBox, terms: List<String>): Boolean {
        val text = normalize(box.text)
        if (text.isEmpty()) return false
        return terms.any { term ->
            text.contains(term) ||
                (text.length >= 2 && term.contains(text)) ||
                // a term that continues on the next box ("credit" + "card")
                (term.length >= 3 && overlapsEdge(text, term))
        }
    }

    private fun overlapsEdge(text: String, term: String): Boolean {
        val min = minOf(3, term.length)
        for (k in term.length - 1 downTo min) {
            if (text.endsWith(term.substring(0, k)) || text.startsWith(term.substring(term.length - k))) return true
        }
        return false
    }
}

/** Compact JSON encoding of recognized boxes: [[left, top, right, bottom, "text"], …]. */
object BoxCodec {
    fun encode(boxes: List<OcrBox>): String {
        val arr = JSONArray()
        for (b in boxes) {
            arr.put(
                JSONArray()
                    .put(round(b.left)).put(round(b.top)).put(round(b.right)).put(round(b.bottom))
                    .put(b.text),
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<OcrBox> = try {
        val arr = JSONArray(json)
        List(arr.length()) { i ->
            val b = arr.getJSONArray(i)
            OcrBox(
                b.getDouble(0).toFloat(), b.getDouble(1).toFloat(),
                b.getDouble(2).toFloat(), b.getDouble(3).toFloat(), b.getString(4),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun round(v: Float): Double = Math.round(v * 10000.0) / 10000.0
}
