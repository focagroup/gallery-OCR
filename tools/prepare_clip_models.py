#!/usr/bin/env python3
"""Download, convert, quantize and verify the CLIP models used for smart search.

Like tools/prepare_models.py, this is only needed by maintainers when updating the
models. The prepared files are committed to app/src/main/assets/models/, so building
the app does not need any network access.

What it does:
  1. Downloads TinyCLIP checkpoints (MIT) from Hugging Face at pinned revisions and
     verifies their SHA-256 checksums.
  2. Exports the image and text towers to two ONNX graphs per model. Both return
     L2-normalized embeddings, so a dot product is the cosine similarity.
  3. Quantizes the graphs to int8 (dynamic quantization) and checks that the int8
     embeddings stay close to the PyTorch ones on a few test photos.
  4. Writes the CLIP BPE tokenizer (vocabulary and merges, shared by all models) and
     a token fixture for the Kotlin tokenizer unit test.

Usage:
  python3 -m venv tools/.venv
  tools/.venv/bin/pip install --index-url https://download.pytorch.org/whl/cpu torch
  tools/.venv/bin/pip install transformers onnx onnxruntime onnxscript pillow numpy
  tools/.venv/bin/python tools/prepare_clip_models.py
"""
import base64
import hashlib
import io
import json
import pathlib
import shutil
import sys
import urllib.request

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "assets" / "models"
FIXTURE = ROOT / "app" / "src" / "test" / "resources" / "clip_tokens.txt"
CACHE = ROOT / "tools" / ".cache"
WORK = CACHE / "clip-work"

HF = "https://huggingface.co/{repo}/resolve/{rev}/{file}"

# asset id -> (repo, revision, sha256 of model.safetensors)
MODELS = {
    "fast": ("wkcn/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M", "a2a8c6eaa2549ad66eb7c31b85022bf58273a26c",
             "9339ee3d736344d0ddcaa6c03edc9f89688f08caaea5401220885233da726fcc"),
    "accurate": ("wkcn/TinyCLIP-ViT-40M-32-Text-19M-LAION400M", "95ec8197b3f2fe7f747865c61ca556cf0768b2f7",
                 "1b08131ac0ac54429aab216c397c550527d31f5682afb5d21d35091ad39538ae"),
}
SMALL_FILES = ["config.json", "tokenizer.json", "preprocessor_config.json"]

# COCO val2017 photos (CC BY), used only to verify the conversion; not bundled.
TEST_IMAGES = {
    "000000039769": ["two cats sleeping on a couch", "a dog", "a city street", "a plate of food"],
    "000000000285": ["a bear", "a cat", "a car", "a beach"],
    "000000000724": ["a stop sign", "a bear", "a plate of food", "a bedroom"],
    "000000000632": ["a bedroom", "a bear", "a stop sign", "a tennis player"],
}
COCO = "http://images.cocodataset.org/val2017/{id}.jpg"

# Extra prompts for checking the text graphs.
EXTRA_PROMPTS = [
    "screenshot of a chat", "receipt from a restaurant", "map of a city", "a selfie at the beach at sunset",
    "a cooking recipe", "a bank transfer confirmation", "a concert ticket", "a cute puppy", "a mountain lake",
    "a whiteboard with notes", "a boarding pass", "a birthday cake with candles",
]

TOKEN_QUERIES = [
    "a photo of a cat",
    "Two cats sleeping on a COUCH!",
    "screenshot of a chat conversation",
    "receipt from a restaurant, 23.50 €",
    "  multiple   spaces\tand\nnewlines  ",
    "don't they're we'll I'd you've it's",
    "Müller's Straße in Zürich",
    "emoji 😀🎉 and symbols #hashtag @user",
    "日本語のテキスト",
    "Привет мир",
    "numbers 1234567 and 3.14159",
    "url https://example.org/path?q=1",
    "",
    " ".join(["long"] * 100),
]

MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
CONTEXT = 77
CONTEXT_SIZE = 224
# Quantization must keep the embeddings this close to the original model.
TARGET_COSINE = 0.99
MIN_COSINE = 0.98
# ONNX Runtime 1.22 (used by the app) reads IR versions up to 11.
IR_VERSION = 9


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest, digest=None):
    if dest.exists() and (digest is None or sha256(dest) == digest):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    for attempt in range(1, 6):
        print(f"downloading {url} (attempt {attempt})")
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(part, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
            if digest is not None and sha256(part) != digest:
                print("  checksum mismatch")
                continue
            part.rename(dest)
            return dest
        except OSError as e:
            print("  failed:", e)
    sys.exit(f"could not download {url}")


def fetch_model(repo, rev, digest):
    folder = CACHE / repo.replace("/", "__") / rev
    download(HF.format(repo=repo, rev=rev, file="model.safetensors"), folder / "model.safetensors", digest)
    for name in SMALL_FILES:
        download(HF.format(repo=repo, rev=rev, file=name), folder / name)
    return folder


def preprocess(img):
    """CLIP preprocessing: bicubic resize of the short side to 224, center crop, normalize."""
    img = img.convert("RGB")
    w, h = img.size
    s = CONTEXT_SIZE / min(w, h)
    img = img.resize((max(CONTEXT_SIZE, round(w * s)), max(CONTEXT_SIZE, round(h * s))), Image.BICUBIC)
    w, h = img.size
    left, top = (w - CONTEXT_SIZE) // 2, (h - CONTEXT_SIZE) // 2
    img = img.crop((left, top, left + CONTEXT_SIZE, top + CONTEXT_SIZE))
    x = (np.asarray(img, dtype=np.float32) / 255.0 - MEAN) / STD
    return x.transpose(2, 0, 1)



class Visual(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, pixel_values):
        f = self.model.get_image_features(pixel_values=pixel_values)
        f = getattr(f, "pooler_output", f)
        return torch.nn.functional.normalize(f, dim=-1)


class Text(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids):
        f = self.model.get_text_features(input_ids=input_ids)
        f = getattr(f, "pooler_output", f)
        return torch.nn.functional.normalize(f, dim=-1)


def export(module, example, input_name, path):
    torch.onnx.export(
        module, (example,), str(path),
        input_names=[input_name], output_names=["embedding"],
        dynamic_axes={input_name: {0: "batch"}, "embedding": {0: "batch"}},
        opset_version=17, dynamo=False,
    )


def fix_ir(path):
    model = onnx.load(str(path))
    model.ir_version = IR_VERSION
    onnx.checker.check_model(model)
    onnx.save(model, str(path))


def session(path):
    so = ort.SessionOptions()
    so.log_severity_level = 3
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def tokenize(tokenizer, texts):
    """CLIP tokens padded with zeros, like the original CLIP and the Kotlin tokenizer."""
    out = np.zeros((len(texts), CONTEXT), dtype=np.int64)
    for i, t in enumerate(texts):
        ids = tokenizer(t, truncation=True, max_length=CONTEXT)["input_ids"]
        out[i, :len(ids)] = ids
    return out


def quantize(fp32, int8, input_name, inputs, ref):
    """Dynamic int8 quantization. Some MatMuls see activation outliers that per-tensor
    activation quantization cannot handle; greedily keep the worst ones in fp32."""
    excluded = []

    def attempt(exclude):
        # Conv stays in fp32: the ONNX Runtime 1.22 used by the app has no ConvInteger kernel.
        quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QInt8, per_channel=True,
                         op_types_to_quantize=["MatMul", "Gather"], nodes_to_exclude=exclude)
        fix_ir(int8)
        return cosine_rows(session(int8).run(None, {input_name: inputs})[0], ref).min()

    cos = attempt(excluded)
    graph = onnx.load(str(fp32)).graph
    weights = {i.name for i in graph.initializer}
    candidates = [n.name for n in graph.node if n.op_type == "MatMul" and n.input[1] in weights]
    while cos < TARGET_COSINE and len(excluded) < 4:
        best = max(((attempt(excluded + [n]), n) for n in candidates if n not in excluded))
        if best[0] <= cos:
            break
        cos, node = best
        excluded.append(node)
    return attempt(excluded), excluded


def cosine_rows(a, b):
    return (a * b).sum(1) / (np.linalg.norm(a, axis=1) * np.linalg.norm(b, axis=1))


def prepare(name, folder, images, tokenizer):
    from transformers import CLIPModel

    model = CLIPModel.from_pretrained(folder, attn_implementation="eager").eval()
    visual, text = Visual(model).eval(), Text(model).eval()
    pixels = np.stack([preprocess(img) for img in images.values()])
    prompts = [p for ps in TEST_IMAGES.values() for p in ps] + EXTRA_PROMPTS
    ids = tokenize(tokenizer, prompts)
    with torch.no_grad():
        ref_img = visual(torch.from_numpy(pixels)).numpy()
        ref_txt = text(torch.from_numpy(ids)).numpy()
    # HF pads with the end token instead of zeros; both must give the same embedding.
    hf_ids = tokenizer(prompts, padding="max_length", max_length=CONTEXT, return_tensors="pt")["input_ids"]
    with torch.no_grad():
        hf_txt = text(hf_ids).numpy()
    assert cosine_rows(ref_txt, hf_txt).min() > 0.9999, "zero padding changes the text embedding"

    WORK.mkdir(parents=True, exist_ok=True)
    report = {}
    for kind, module, example, input_name, inputs, ref in [
        ("visual", visual, torch.from_numpy(pixels[:1]), "pixel_values", pixels, ref_img),
        ("text", text, torch.from_numpy(ids[:1]), "input_ids", ids, ref_txt),
    ]:
        fp32 = WORK / f"clip_{name}_{kind}.fp32.onnx"
        int8 = WORK / f"clip_{name}_{kind}.int8.onnx"
        export(module, example, input_name, fp32)
        fix_ir(fp32)
        out32 = session(fp32).run(None, {input_name: inputs})[0]
        c32 = cosine_rows(out32, ref).min()
        assert c32 > 0.9999, f"{name} {kind}: ONNX export differs from PyTorch ({c32})"
        c8, excluded = quantize(fp32, int8, input_name, inputs, ref)
        chosen = int8 if c8 >= MIN_COSINE else fp32
        if chosen is fp32:
            print(f"WARNING {name} {kind}: int8 cosine {c8:.4f} < {MIN_COSINE}, keeping fp32")
        elif excluded:
            print(f"{name} {kind}: kept in fp32 for accuracy: {excluded}")
        dst = OUT / f"clip_{name}_{kind}.onnx"
        shutil.copyfile(chosen, dst)
        report[kind] = (c8, dst.stat().st_size, "int8" if chosen is int8 else "fp32")
        print(f"{name} {kind}: fp32 cos {c32:.5f}, int8 cos {c8:.4f}, "
              f"{fp32.stat().st_size / 1e6:.1f} MB -> {dst.stat().st_size / 1e6:.1f} MB")

    # Zero-shot sanity check with the bundled graphs: the first prompt must win.
    img_emb = session(OUT / f"clip_{name}_visual.onnx").run(None, {"pixel_values": pixels})[0]
    txt_emb = session(OUT / f"clip_{name}_text.onnx").run(None, {"input_ids": ids})[0]
    k = 0
    for i, (img_id, ps) in enumerate(TEST_IMAGES.items()):
        scores = txt_emb[k:k + len(ps)] @ img_emb[i]
        k += len(ps)
        print(f"  {img_id}: " + ", ".join(f"{p}={s:.3f}" for p, s in zip(ps, scores)))
        assert scores.argmax() == 0, f"{name}: zero-shot check failed for {img_id}"
    return report


def write_tokenizer(folder):
    data = json.loads((folder / "tokenizer.json").read_text(encoding="utf-8"))
    vocab = data["model"]["vocab"]
    tokens = [None] * len(vocab)
    for tok, i in vocab.items():
        tokens[i] = tok
    assert all(t is not None and "\n" not in t for t in tokens)
    merges = [m if isinstance(m, str) else " ".join(m) for m in data["model"]["merges"]]
    (OUT / "clip_vocab.txt").write_text("\n".join(tokens) + "\n", encoding="utf-8")
    (OUT / "clip_merges.txt").write_text("\n".join(merges) + "\n", encoding="utf-8")
    print(f"tokenizer: {len(tokens)} tokens, {len(merges)} merges")


def write_fixture(tokenizer):
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# base64(UTF-8 query) <TAB> token ids (generated by tools/prepare_clip_models.py)"]
    for q in TOKEN_QUERIES:
        ids = tokenizer(q, truncation=True, max_length=CONTEXT)["input_ids"]
        lines.append(base64.b64encode(q.encode("utf-8")).decode("ascii") + "\t" + ",".join(map(str, ids)))
    FIXTURE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    from transformers import CLIPTokenizerFast

    OUT.mkdir(parents=True, exist_ok=True)
    images = {}
    for img_id in TEST_IMAGES:
        path = download(COCO.format(id=img_id), CACHE / "coco" / f"{img_id}.jpg")
        images[img_id] = Image.open(io.BytesIO(path.read_bytes()))

    folders = {name: fetch_model(*spec) for name, spec in MODELS.items()}
    tok_hashes = {sha256(f / "tokenizer.json") for f in folders.values()}
    assert len(tok_hashes) == 1, "models use different tokenizers"
    first = next(iter(folders.values()))
    tokenizer = CLIPTokenizerFast.from_pretrained(first)
    write_tokenizer(first)
    write_fixture(tokenizer)

    rows = []
    for name, (repo, rev, digest) in MODELS.items():
        report = prepare(name, folders[name], images, tokenizer)
        for kind, (cos, size, precision) in report.items():
            asset = f"clip_{name}_{kind}.onnx"
            rows.append(f"| `{asset}` | [{repo}](https://huggingface.co/{repo}/tree/{rev}) | "
                        f"{precision}, {size / 1e6:.1f} MB, cosine {cos:.4f} | `{sha256(OUT / asset)}` |")

    (ROOT / "docs" / "SMART_SEARCH_MODELS.md").write_text(
        "# Bundled smart search (CLIP) models\n\n"
        "[TinyCLIP](https://github.com/wkcn/TinyCLIP) by Microsoft, licensed MIT. Converted by\n"
        "`tools/prepare_clip_models.py`: each model is split into an image graph and a text graph that\n"
        "return L2-normalized embeddings, then quantized to int8 with ONNX Runtime dynamic quantization.\n"
        "\"Cosine\" is the worst agreement between the int8 graph and the original PyTorch model on the\n"
        "test inputs (1.0 = identical).\n\n"
        "| Asset | Source (pinned revision) | Precision, size, cosine | SHA-256 |\n|---|---|---|---|\n"
        + "\n".join(rows) + "\n\n"
        f"Original `model.safetensors` SHA-256: "
        + ", ".join(f"{n} `{d}`" for n, (_, _, d) in MODELS.items()) + ".\n\n"
        "`clip_vocab.txt` and `clip_merges.txt` are the CLIP BPE tokenizer (vocabulary in id order, merges in\n"
        "rank order), extracted from the models' `tokenizer.json`.\n",
        encoding="utf-8")


if __name__ == "__main__":
    main()
