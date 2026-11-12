# Bundled smart search (CLIP) models

[TinyCLIP](https://github.com/wkcn/TinyCLIP) by Microsoft, licensed MIT. Converted by
`tools/prepare_clip_models.py`: each model is split into an image graph and a text graph that
return L2-normalized embeddings, then quantized to int8 with ONNX Runtime dynamic quantization.
"Cosine" is the worst agreement between the int8 graph and the original PyTorch model on the
test inputs (1.0 = identical).

| Asset | Source (pinned revision) | Precision, size, cosine | SHA-256 |
|---|---|---|---|
| `clip_fast_visual.onnx` | [wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M](https://huggingface.co/wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M/tree/a2a8c6eaa2549ad66eb7c31b85022bf58273a26c) | int8, 9.4 MB, cosine 0.9964 | `3d7ab26e34ab6554f00d900da53cf41ef5f30d56134b33e53cbaae793324bc20` |
| `clip_fast_text.onnx` | [wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M](https://huggingface.co/wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M/tree/a2a8c6eaa2549ad66eb7c31b85022bf58273a26c) | int8, 15.3 MB, cosine 0.9914 | `78ce7be7940904d798c963abe965ea65a147990c63e6bdb99e68aa491e4d4cf5` |
| `clip_accurate_visual.onnx` | [wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M](https://huggingface.co/wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M/tree/95ec8197b3f2fe7f747865c61ca556cf0768b2f7) | int8, 45.3 MB, cosine 0.9933 | `5ccee9ad5b2c1b860a89d24fdb9693b625c3a561f0909420b004b1008f04be83` |
| `clip_accurate_text.onnx` | [wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M](https://huggingface.co/wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M/tree/95ec8197b3f2fe7f747865c61ca556cf0768b2f7) | int8, 48.1 MB, cosine 0.9913 | `fb2867e202483eb1f6cf25a0e82012109c776bb6fadcf3ebadfdc188f7302006` |

Original `model.safetensors` SHA-256: fast `9339ee3d736344d0ddcaa6c03edc9f89688f08caaea5401220885233da726fcc`, accurate `1b08131ac0ac54429aab216c397c550527d31f5682afb5d21d35091ad39538ae`.

`clip_vocab.txt` and `clip_merges.txt` are the CLIP BPE tokenizer (vocabulary in id order, merges in
rank order), extracted from the models' `tokenizer.json`.
