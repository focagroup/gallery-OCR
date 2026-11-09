#!/usr/bin/env python3
"""Download, verify and prepare the OCR models that are bundled into the APK.

This script is only needed by maintainers when updating the models. The prepared
files are committed to app/src/main/assets/models/, so building the app does not
need any network access.

What it does:
  1. Downloads the official PaddleOCR ONNX exports from Hugging Face at pinned
     revisions and verifies their SHA-256 checksums.
  2. Extracts the character dictionaries of the recognition models from their
     inference.yml into plain text files (one character per line).
  3. Appends ArgMax/ReduceMax nodes to the recognition models so that they
     return [batch, time] best-class indices and probabilities instead of the
     full [batch, time, classes] probability tensor (up to 18 710 classes).
     This keeps memory usage on phones small. The results are identical.

Usage:
  python3 -m venv .venv && .venv/bin/pip install onnx onnxruntime numpy pyyaml
  .venv/bin/python tools/prepare_models.py
"""
import hashlib
import http.client
import pathlib
import shutil
import sys
import urllib.request

import numpy as np
import onnx
import onnxruntime as ort
import yaml
from onnx import TensorProto, helper

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "assets" / "models"
CACHE = ROOT / "tools" / ".cache"

HF = "https://huggingface.co/PaddlePaddle/{repo}/resolve/{rev}/{file}"

# name in assets -> (repo, revision, sha256 of inference.onnx, is_recognizer)
MODELS = {
    "det": ("PP-OCRv6_small_det_onnx", "28fe5895c24fd108c19eb3e8479f4ab385fbfc62",
            "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e", False),
    "det_fast": ("PP-OCRv6_tiny_det_onnx", "2ba1506c0380b8f0b03dd142459aac66d4421f6c",
                 "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8", False),
    "rec_main": ("PP-OCRv6_small_rec_onnx", "b8f84f0b80c529de40b4fbb3544b84fa7233a513",
                 "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634", True),
    "rec_fast": ("PP-OCRv6_tiny_rec_onnx", "2612ab37152ae0a677521bae4e1e3d4fb4cf7c30",
                 "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6", True),
    "rec_cyrillic": ("cyrillic_PP-OCRv5_mobile_rec_onnx", "2cef88145434beb8afa9dd82d77d799eb1ad7b29",
                     "5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5", True),
    "rec_korean": ("korean_PP-OCRv5_mobile_rec_onnx", "5c6f574b8e2230adf4287b33e736d71b9fabd28e",
                   "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08", True),
}


def fetch(repo, rev, file, digest=None):
    """Download a file (cached). If a digest is given, retry until it matches."""
    dest = CACHE / repo / rev / file
    if dest.exists() and (digest is None or sha256(dest) == digest):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    url = HF.format(repo=repo, rev=rev, file=file)
    part = dest.with_suffix(".part")
    for attempt in range(1, 6):
        print(f"downloading {url} (attempt {attempt})")
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(part, "wb") as f:
                expected = int(r.headers.get("Content-Length") or -1)
                shutil.copyfileobj(r, f, 1 << 20)
            size = part.stat().st_size
            if expected >= 0 and size != expected:
                print(f"  incomplete: {size} of {expected} bytes")
                continue
            if digest is not None and sha256(part) != digest:
                print("  checksum mismatch")
                continue
            part.rename(dest)
            return dest
        except (OSError, http.client.HTTPException) as e:
            print("  failed:", e)
    sys.exit(f"could not download {url}")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def patch_recognizer(src, dst):
    model = onnx.load(src)
    probs = model.graph.output[0].name
    opset = next(o.version for o in model.opset_import if o.domain in ("", "ai.onnx"))
    nodes = [helper.make_node("ArgMax", [probs], ["rec_index"], axis=2, keepdims=0)]
    if opset >= 18:
        axes = helper.make_tensor("rec_axes", TensorProto.INT64, [1], [2])
        model.graph.initializer.append(axes)
        nodes.append(helper.make_node("ReduceMax", [probs, "rec_axes"], ["rec_prob"], keepdims=0))
    else:
        nodes.append(helper.make_node("ReduceMax", [probs], ["rec_prob"], axes=[2], keepdims=0))
    model.graph.node.extend(nodes)
    del model.graph.output[:]
    model.graph.output.extend([
        helper.make_tensor_value_info("rec_index", TensorProto.INT64, ["batch", "time"]),
        helper.make_tensor_value_info("rec_prob", TensorProto.FLOAT, ["batch", "time"]),
    ])
    onnx.checker.check_model(model)
    onnx.save(model, dst)

    # verify equivalence on random input
    so = ort.SessionOptions()
    so.log_severity_level = 3
    a = ort.InferenceSession(str(src), so, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(str(dst), so, providers=["CPUExecutionProvider"])
    x = np.random.default_rng(0).random((2, 3, 48, 320), dtype=np.float32) * 2 - 1
    full = a.run(None, {"x": x})[0]
    idx, prob = b.run(None, {"x": x})
    assert (full.argmax(2) == idx).all(), "argmax mismatch"
    assert np.allclose(full.max(2), prob, atol=1e-6), "max mismatch"
    return full.shape[2]


def extract_dict(yml_path, dst):
    cfg = yaml.safe_load(open(yml_path, encoding="utf-8"))
    chars = [str(c) for c in cfg["PostProcess"]["character_dict"]]
    bad = [c for c in chars if len(c) != 1]
    assert not bad, f"unexpected multi-character dictionary entries: {bad[:5]}"
    dst.write_text("\n".join(chars) + "\n", encoding="utf-8")
    return len(chars)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (ROOT / "docs").mkdir(exist_ok=True)
    lines = []
    for name, (repo, rev, digest, is_rec) in MODELS.items():
        src = fetch(repo, rev, "inference.onnx", digest)
        dst = OUT / f"{name}.onnx"
        if is_rec:
            n_dict = extract_dict(fetch(repo, rev, "inference.yml"), OUT / f"{name}.txt")
            n_classes = patch_recognizer(src, dst)
            assert n_classes == n_dict + 2, f"{name}: {n_classes} classes vs {n_dict} dictionary entries"
            print(f"{name}: {n_dict} characters, patched")
        else:
            dst.write_bytes(src.read_bytes())
            print(f"{name}: copied")
        lines.append(f"| `{name}.onnx` | [{repo}](https://huggingface.co/PaddlePaddle/{repo}/tree/{rev}) | `{digest}` |")
    (ROOT / "docs" / "MODELS.md").write_text(
        "# Bundled OCR models\n\n"
        "Official PaddleOCR ONNX exports, licensed Apache-2.0. Recognition models are patched by\n"
        "`tools/prepare_models.py` to output arg-max indices and probabilities only.\n\n"
        "| Asset | Source (pinned revision) | SHA-256 of original |\n|---|---|---|\n" + "\n".join(lines) + "\n",
        encoding="utf-8")


if __name__ == "__main__":
    main()
