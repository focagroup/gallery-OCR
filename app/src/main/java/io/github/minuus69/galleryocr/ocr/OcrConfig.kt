package io.github.minuus69.galleryocr.ocr

/**
 * Speed/accuracy trade-off. [id] is stored in the settings; [configId] goes into the per-image
 * config key, so images can be re-scanned when a mode's pipeline changes.
 */
enum class ScanMode(
    val id: String,
    val configId: String,
    val detAsset: String,
    val detMinSide: Int,
    val boxThresh: Float,
    val cascade: Boolean,
) {
    /** PP-OCRv6 small detector at a short side of 960 px; every line read by the main model. */
    ACCURATE("accurate", "accurate", "models/det.onnx", 960, 0.45f, false),

    /**
     * PP-OCRv6 tiny detector at 736 px; lines are read by the PP-OCRv6 tiny recognizer and
     * only unclear ones by the main model.
     */
    FAST("fast", "fast2", "models/det_fast.onnx", 736, 0.40f, true),
}

/** Bundled recognition models. Every model reads Latin digits and punctuation. */
enum class RecModel(val id: String, val modelAsset: String, val dictAsset: String) {
    /** PP-OCRv6 small: Latin script (50 languages), Chinese, Japanese, Greek. */
    MAIN("main", "models/rec_main.onnx", "models/rec_main.txt"),

    /** PP-OCRv5 Cyrillic: Russian, Ukrainian, Belarusian, Bulgarian, Serbian, … */
    CYRILLIC("cyrillic", "models/rec_cyrillic.onnx", "models/rec_cyrillic.txt"),

    /** PP-OCRv5 Korean. */
    KOREAN("korean", "models/rec_korean.onnx", "models/rec_korean.txt"),
}

data class OcrConfig(val mode: ScanMode, val models: List<RecModel>) {
    init {
        require(models.isNotEmpty()) { "at least one recognition model is required" }
    }

    /** Stored with every scanned image so outdated results can be found after settings change. */
    val key: String get() = "v1;${mode.configId};${models.joinToString(",") { it.id }}"
}
