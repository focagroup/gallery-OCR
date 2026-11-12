package io.github.minuus69.galleryocr.smart

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Base64

/** Compares the Kotlin tokenizer with the reference tokenizer (fixture from tools/prepare_clip_models.py). */
class ClipTokenizerTest {
    private val tokenizer = run {
        fun lines(asset: String) = File("src/main/assets/$asset").readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        ClipTokenizer(lines(ClipTokenizer.VOCAB_ASSET), lines(ClipTokenizer.MERGES_ASSET))
    }

    @Test
    fun matchesReferenceTokenizer() {
        val fixture = javaClass.classLoader!!.getResource("clip_tokens.txt")!!.readText(Charsets.UTF_8)
        var cases = 0
        for (line in fixture.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (encoded, ids) = line.split('\t')
            val query = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            val expected = ids.split(',').map { it.toInt() }.toIntArray()
            assertArrayEquals("query \"$query\"", expected, tokenizer.tokenize(query))
            cases++
        }
        assertEquals(14, cases)
    }

    @Test
    fun encodePadsWithZeros() {
        val encoded = tokenizer.encode("a cat")
        assertEquals(ClipTokenizer.CONTEXT_LENGTH, encoded.size)
        assertEquals(ClipTokenizer.START.toLong(), encoded[0])
        assertEquals(ClipTokenizer.END.toLong(), encoded[3])
        assertEquals(0L, encoded[4])
    }
}
