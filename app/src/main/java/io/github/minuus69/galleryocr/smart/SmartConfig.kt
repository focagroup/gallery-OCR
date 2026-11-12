package io.github.minuus69.galleryocr.smart

/**
 * Bundled CLIP models for smart search. Each model has its own embedding space, so an image
 * embedding can only be compared with a text embedding from the same model.
 *
 * [scoreLo] and [scoreHi] are the cosine-similarity cutoffs at the loosest and strictest
 * search setting.
 */
enum class SmartMode(
    val id: String,
    val visualAsset: String,
    val textAsset: String,
    /** Also embed the whole image padded to a square and average it with the center crop. */
    val wholeImage: Boolean,
    val scoreLo: Float,
    val scoreHi: Float,
) {
    /** TinyCLIP ViT-8M/16, text 3M (YFCC-15M). */
    FAST("fast", "models/clip_fast_visual.onnx", "models/clip_fast_text.onnx", false, 0.26f, 0.38f),

    /** TinyCLIP ViT-40M/32, text 19M (LAION-400M). */
    ACCURATE("accurate", "models/clip_accurate_visual.onnx", "models/clip_accurate_text.onnx", true, 0.14f, 0.26f),
    ;

    /** Stored with every analyzed image so outdated embeddings can be found after a change. */
    val key: String get() = "v1;$id"

    /** Similarity an image needs to show up, for a strictness between 0 and 1. */
    fun cutoff(strictness: Float): Float = scoreLo + (scoreHi - scoreLo) * strictness.coerceIn(0f, 1f)

    companion object {
        fun fromKey(key: String): SmartMode? = entries.firstOrNull { it.key == key }
    }
}
