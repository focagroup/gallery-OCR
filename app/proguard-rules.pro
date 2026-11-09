# ONNX Runtime is called from JNI; keep its Java API intact.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
