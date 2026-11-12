# Optindex

Search the text in your screenshots and photos, or describe what is in them, without internet at all.

Optindex reads the text in the images of the folders you choose (for example
`Pictures/Screenshots`), stores it in a local index and lets you find any image by the
words in it. With **smart search** you can also find images by describing them ("dog on a
beach", "receipt", "map"). New screenshots are picked up automatically. It is a spiritual successor to
Mozilla's discontinued *ScreenshotGo*, whose OCR model download no longer works.

## Privacy

* The app has **no `INTERNET` permission**. Android then refuses to open *any* network
  socket for it. So no internet, local network and even `localhost`.
* Network permissions that libraries might add are explicitly removed in
  `AndroidManifest.xml`, and the build **fails** if one ever ends up in the final manifest
  (`verify<Variant>NoNetwork` Gradle task).
* The OCR and smart search models are **built into the APK**. No need to download anything.
* Android backup and device-to-device transfer of the index are disabled.
* Sharing or opening an image hands it to another app through Android's share sheet, only
  when you tap the button. What that app does is up to that app.

## Features

* **Choose folders** to scan, separately for text search and smart search (a folder can be in
  both), or *All folders* (including folders created later).
* **Fast search** across thousands of images: substring search that ignores case, accents
  (`muller` finds `Müller`) and OCR spacing mistakes. Several words must all match;
  `"quoted words"` must appear together.
* **Smart search**: switch the search field from *Text* to *Smart* and describe the image in
  English. Results are sorted by how well they match; a *strictness* slider in the settings
  decides how close a match must be.
* **Background scanning** with a progress notification and a pause button. The first
  scan continues when you leave the app; afterwards only new or changed images are scanned
  each time the app opens (and while it is open).
* **Viewer** with pinch/double-tap zoom, swiping between results and optional
  **highlighting of the matched words** (can be turned off in settings).
* **Recognized text** of every image: select, copy or share it.
* **Share / open / delete** images (delete moves to the system trash on Android 11+).
* **Collections**: put images into your own groups and filter or search inside them.
* Long screenshots are supported (read in tiles, shown in full resolution).
* Light and dark theme, Material You colours on Android 12+.

## Languages

All models ship in the APK; choose which to use in *Settings → Recognition languages*.

| Option | Model | Scripts |
|---|---|---|
| Latin, Chinese & Japanese (default) | PP-OCRv6 small | Latin script (English, German, French, Spanish, Italian, Portuguese, Polish, Turkish, …), Chinese, Japanese, Greek |
| Cyrillic | PP-OCRv5 Cyrillic | Russian, Ukrainian, Belarusian, Bulgarian, Serbian, … |
| Korean | PP-OCRv5 Korean | Hangul |

When several are enabled, every text line is read by the main model first; lines it cannot
read well are re-read by the other models and the best result wins. Extra languages only
cost time for those lines.

*Scan mode* trades accuracy for speed:

* **Accurate**: PP-OCRv6 small detector at 960 px, every text line read by the main model.
* **Fast**: PP-OCRv6 tiny detector at 736 px,
  and a *cascade* for reading: the PP-OCRv6 tiny recognizer reads every line first, and only
  lines it is unsure about (average confidence below 0.95, any character below 0.5, or
  tell-tale misreadings such as odd capitals inside a word) are read again by the main model.
  On a Pixel 8 that makes recognition about 1.4× faster; on test screenshots about 2 % of words
  came out differently, mostly in very small text. Cyrillic, Korean and Japanese lines are
  always handed on to the right model.

## Smart search

Smart search uses [CLIP](https://openai.com/research/clip)-style models, which map images and
text into the same space so that a description can be compared with every image. Two
[TinyCLIP](https://github.com/wkcn/TinyCLIP) models (MIT) are built in; choose one in
*Settings → Smart search*:

| Mode | Model | Size | ImageNet zero-shot |
|---|---|---|---|
| Accurate (default) | TinyCLIP ViT-40M/32, text 19M (LAION-400M) | 93 MB | 59.8 % |
| Fast | TinyCLIP ViT-8M/16, text 3M (YFCC-15M) | 25 MB | 41.1 % |

Each image is scaled to 224 px and turned into a 512-number *embedding*; in Accurate mode tall
or wide images are additionally looked at as a whole (padded to a square) and both views are
averaged. A query is encoded by the text model of the same mode and compared with all stored
embeddings in memory. Images analysed with the other mode stay searchable until they are
analysed again. Queries work in English only.

## Requirements and installation

* Android 7.0 (API 24) or newer, phones and tablets.
* APK download of about 165 MB (per-CPU APK) or 190 MB (universal); the OCR models are
  ~57 MB, the smart search models ~118 MB.

Release builds produce one APK per CPU type plus a universal one:

| APK | Use for |
|---|---|
| `app-arm64-v8a-release.apk` | almost all phones and tablets from the last ~8 years |
| `app-armeabi-v7a-release.apk` | older 32-bit ARM devices |
| `app-x86_64-release.apk` / `app-x86-release.apk` | Intel/AMD devices, Chromebooks, emulators |
| `app-universal-release.apk` | works everywhere, larger download |

## How it works

1. **Indexing** (`scan/Indexer.kt`): the app reads Android's MediaStore, adds new images of
   the chosen folders to a local Room/SQLite database, re-queues changed ones and drops
   deleted ones.
2. **OCR** (`ocr/OcrEngine.kt`, run by `scan/OcrWorker.kt` through WorkManager as a
   foreground job):
   * the image is scaled so that its short side is 960 px (736 px in Fast mode); very long
     images are decoded in overlapping tiles with `BitmapRegionDecoder`;
   * the **DB text detector** finds text lines (probability map → connected regions →
     boxes, `ocr/DbPostProcess.kt`);
   * every line is cropped to 48 px height and read by the **CTC recognizer**;
   * lines are sorted into reading order; text and box positions are stored.
   Models run on **ONNX Runtime** (CPU, up to 4 threads).
   After OCR, the same job computes **smart search embeddings** (`smart/ClipEngine.kt`) for
   images in smart search folders.
3. **Search** (`data/SearchText.kt`): text is normalized (lower case, accents removed for
   European scripts, compatibility forms unified, whitespace removed) and searched with
   SQLite `instr()`. A search over 10 000 indexed screenshots takes about 15 ms on a desktop
   CPU, so roughly a tenth of a second on a phone. Smart search (`smart/SmartIndex.kt`)
   tokenizes the query (`smart/ClipTokenizer.kt`), runs the text model and ranks all embeddings
   by cosine similarity.

Measured on a Pixel 8 (Tensor G3, CPU only, Fast mode, phone already warm): about **1.6 s per
screenshot** on average (roughly 0.3 s detection, 1.1 s recognition, the rest decoding). Text-heavy
screenshots take longer, and the phone throttles under long loads. A first scan of 1 000
screenshots takes roughly 30 minutes in the background; later scans only process new images.
Accurate mode is slower: it uses the larger detector and the main model for every line.

## Building

Requirements: JDK 17 or newer and the Android SDK with platform 35 and build-tools 35.0.0
(Android Studio installs both).

```bash
./gradlew assembleDebug      # debug APKs in app/build/outputs/apk/debug/
./gradlew assembleRelease    # release APKs in app/build/outputs/apk/release/
```

Release APKs are only signed when a local signing configuration is present; otherwise
they are unsigned and you can sign them with your own key.

### Updating the OCR models

The prepared models are committed in `app/src/main/assets/models/`, so building needs no
model download. To update them, edit the pinned revisions/checksums in
`tools/prepare_models.py` and run:

```bash
python3 -m venv tools/.venv
tools/.venv/bin/pip install onnx onnxruntime numpy pyyaml
tools/.venv/bin/python tools/prepare_models.py
```

The script downloads the official PaddleOCR ONNX exports from Hugging Face, verifies their
SHA-256, extracts the character dictionaries and adds arg-max outputs to the recognizers
(their full output can be ~240 MB per batch; the patched output is tiny and gives the same
result). Sources and checksums are listed in [docs/MODELS.md](docs/MODELS.md).

### Updating the smart search models

`tools/prepare_clip_models.py` downloads the TinyCLIP checkpoints at pinned revisions, exports
image and text graphs to ONNX, quantizes them to int8 (keeping single layers in float where
int8 would lose accuracy) and checks them against the original PyTorch model. It also writes
the tokenizer files and the token fixture for the unit test. It needs PyTorch:

```bash
python3 -m venv tools/.venv
tools/.venv/bin/pip install --index-url https://download.pytorch.org/whl/cpu torch
tools/.venv/bin/pip install transformers onnx onnxruntime onnxscript pillow numpy
tools/.venv/bin/python tools/prepare_clip_models.py
```

Results are listed in [docs/SMART_SEARCH_MODELS.md](docs/SMART_SEARCH_MODELS.md). Run the unit
tests with `./gradlew testDebugUnitTest`.

## Project structure

```
app/src/main/java/io/github/minuus69/galleryocr/
  ocr/      OcrEngine (pipeline), DbPostProcess (text detection), OcrConfig (models, modes)
  smart/    CLIP image/text encoders, tokenizer, in-memory smart search index
  image/    decoding: tiles for OCR, strips for the viewer, EXIF rotation
  data/     Room database, MediaStore access, settings, search normalization
  scan/     Indexer (MediaStore sync), OcrWorker, ScanScheduler, notification
  ui/       Compose screens: home/search, viewer, folders, collections, settings
  util/     permissions, thumbnails, share/open intents, delete flow
tools/prepare_models.py        reproducible OCR model preparation
tools/prepare_clip_models.py   reproducible smart search model preparation
```

## Limitations

* Vertical text (e.g. vertical Japanese) and handwriting are not handled well.
* Match highlighting marks the position inside a recognized line by character position,
  so it can be slightly off for proportional fonts.
* Images of folders you turn off keep their results but are hidden; images on an SD card
  that is removed keep their results until the card is back.
* With *limited photo access* (Android 14+) only the photos you picked are indexed.
* Smart search understands English queries only, and the small models are much weaker than
  large server-side models: expect good results for common objects and scenes, fewer for
  fine details.

## License

Optindex is free software: you can redistribute it and/or modify it under the terms of the
[GNU Affero General Public License v3.0](LICENSE).

Third-party components:

* PaddleOCR PP-OCRv6 / PP-OCRv5 models — Apache License 2.0, © PaddlePaddle Authors
* TinyCLIP models — MIT License, © Microsoft Corporation
* ONNX Runtime — MIT License, © Microsoft Corporation
* Android Jetpack (Compose, Room, WorkManager, Core, Activity, Lifecycle) — Apache License 2.0
* Kotlin, kotlinx.coroutines — Apache License 2.0, © JetBrains
