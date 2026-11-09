# Bundled OCR models

Official PaddleOCR ONNX exports, licensed Apache-2.0. Recognition models are patched by
`tools/prepare_models.py` to output arg-max indices and probabilities only.

| Asset | Source (pinned revision) | SHA-256 of original |
|---|---|---|
| `det.onnx` | [PP-OCRv6_small_det_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/tree/28fe5895c24fd108c19eb3e8479f4ab385fbfc62) | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` |
| `det_fast.onnx` | [PP-OCRv6_tiny_det_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/tree/2ba1506c0380b8f0b03dd142459aac66d4421f6c) | `193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8` |
| `rec_main.onnx` | [PP-OCRv6_small_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513) | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` |
| `rec_fast.onnx` | [PP-OCRv6_tiny_rec_onnx](https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/tree/2612ab37152ae0a677521bae4e1e3d4fb4cf7c30) | `9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6` |
| `rec_cyrillic.onnx` | [cyrillic_PP-OCRv5_mobile_rec_onnx](https://huggingface.co/PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx/tree/2cef88145434beb8afa9dd82d77d799eb1ad7b29) | `5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5` |
| `rec_korean.onnx` | [korean_PP-OCRv5_mobile_rec_onnx](https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/tree/5c6f574b8e2230adf4287b33e736d71b9fabd28e) | `92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08` |
