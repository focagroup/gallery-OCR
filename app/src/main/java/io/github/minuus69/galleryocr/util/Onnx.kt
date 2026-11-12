package io.github.minuus69.galleryocr.util

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context

object Onnx {
    /**
     * 4 threads measured fastest on a Pixel 8 (1 big + 4 mid + 4 little cores); more threads
     * spill onto little cores and slow every step down. (XNNPACK crashed under GrapheneOS's
     * hardened allocator, so the default CPU provider is used.)
     */
    val threads: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Loads a bundled model from the APK assets. */
    fun session(context: Context, asset: String, threads: Int = Onnx.threads): OrtSession {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            OrtEnvironment.getEnvironment().createSession(bytes, options)
        }
    }
}
