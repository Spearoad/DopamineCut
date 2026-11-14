"""학습된 best.pt를 ONNX로 변환하고, 앱이 검증하는 metadata JSON을 만든다.

    python export.py             # artifacts/ 에만 생성
    python export.py --install   # app/src/main/assets/models/ 에 복사

PyTorch와 ONNX Runtime 출력이 일치하는지, 테스트셋 정확도가 그대로인지 확인한 뒤에만 설치한다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import time
from collections import Counter
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from PIL import Image

from common import (IMAGENET_MEAN, IMAGENET_STD, INPUT_SIZE, LABELS, ROI_DESTINATIONS, ROI_SOURCES,
                    read_manifests, ui_tiles_v1)
from train import build_model

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT.parent.parent / "app" / "src" / "main" / "assets" / "models"
MODEL_NAME = "kakaotalk_shortform_state"


def to_input(path: Path) -> np.ndarray:
    tile = np.asarray(ui_tiles_v1(Image.open(path)), dtype=np.float32) / 255.0
    tile = (tile - np.array(IMAGENET_MEAN, dtype=np.float32)) / np.array(IMAGENET_STD, dtype=np.float32)
    return tile.transpose(2, 0, 1)[None]


def device_summary(data_dir: Path) -> list[dict]:
    counts = Counter((r["collector"], r["manufacturer"], r["device_model"], r["android_release"],
                      r["kakaotalk_version"], f"{r['screen_width']}x{r['screen_height']}")
                     for r in read_manifests(data_dir))
    return [{"collector": c, "manufacturer": m, "model": d, "android": a, "kakaotalk": k, "resolution": s,
             "images": n} for (c, m, d, a, k, s), n in counts.items()]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path, default=ROOT / "artifacts")
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()

    checkpoint = torch.load(args.artifacts / "best.pt", map_location="cpu", weights_only=False)
    if checkpoint["labels"] != LABELS:
        raise SystemExit("체크포인트 라벨 순서가 common.LABELS와 다릅니다.")
    report = checkpoint["report"]
    model = build_model(pretrained=False)
    model.load_state_dict(checkpoint["state_dict"])
    model.eval()

    onnx_path = args.artifacts / f"{MODEL_NAME}.onnx"
    dummy = torch.zeros(1, 3, INPUT_SIZE, INPUT_SIZE)
    torch.onnx.export(model, dummy, onnx_path, input_names=["screen"], output_names=["logits"],
                      opset_version=17, do_constant_folding=True, dynamo=False)

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    test_files = [Path(p) for p in checkpoint["test_files"]]
    labels = {path: LABELS.index(path.parent.name) for path in test_files}
    max_diff, correct, latencies = 0.0, 0, []
    with torch.no_grad():
        for path in test_files:
            screen = to_input(path)
            started = time.perf_counter()
            onnx_logits = session.run(["logits"], {"screen": screen})[0]
            latencies.append((time.perf_counter() - started) * 1000)
            torch_logits = model(torch.from_numpy(screen)).numpy()
            max_diff = max(max_diff, float(np.abs(onnx_logits - torch_logits).max()))
            correct += int(onnx_logits.argmax() == labels[path])
    onnx_accuracy = correct / max(len(test_files), 1)
    print(f"PyTorch↔ONNX 최대 logit 차이 {max_diff:.2e}, ONNX 테스트 정확도 {correct}/{len(test_files)} "
          f"= {onnx_accuracy:.4f}, PC CPU 평균 추론 {np.mean(latencies):.1f}ms")
    if max_diff > 1e-3 or abs(onnx_accuracy - report["test"]["accuracy"]) > 1e-6:
        raise SystemExit("ONNX 변환 결과가 PyTorch와 다릅니다. 설치하지 않습니다.")

    model_bytes = onnx_path.read_bytes()
    metadata = {
        "model_name": f"{MODEL_NAME}_mobilenetv3_small",
        "version": 1,
        "sha256": hashlib.sha256(model_bytes).hexdigest(),
        "labels": LABELS,
        "input_name": "screen",
        "input_shape": [1, 3, INPUT_SIZE, INPUT_SIZE],
        "output_name": "logits",
        "output_shape": [1, len(LABELS)],
        "validation_accuracy": round(report["validation"]["accuracy"], 4),
        "validation_macro_f1": round(report["validation"]["macro_f1"], 4),
        "test_accuracy": round(report["test"]["accuracy"], 4),
        "test_macro_f1": round(report["test"]["macro_f1"], 4),
        "training": {"config": report["best_config"], "split_counts": report["split_counts"],
                     "devices": device_summary(args.data)},
        "preprocessing": {
            "ui_view_mode": "ui_tiles_v1",
            "experiment": "whole_ui_v1",
            "layout": "NCHW",
            "color": "RGB",
            "tile_resize": "fit_bilinear",
            "tile_padding_rgb": [0, 0, 0],
            "imagenet_mean": list(IMAGENET_MEAN),
            "imagenet_std": list(IMAGENET_STD),
            "roi_tiles": [{"source_ratio": list(s), "destination_px": list(d)}
                          for s, d in zip(ROI_SOURCES, ROI_DESTINATIONS)],
        },
        "decision_thresholds": report["decision_thresholds"],
    }
    json_path = args.artifacts / f"{MODEL_NAME}.json"
    json_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"생성: {onnx_path} ({len(model_bytes) / 1e6:.1f}MB), {json_path}")

    if args.install:
        ASSETS.mkdir(parents=True, exist_ok=True)
        shutil.copy2(onnx_path, ASSETS / onnx_path.name)
        shutil.copy2(json_path, ASSETS / json_path.name)
        print(f"앱 assets에 설치: {ASSETS}")


if __name__ == "__main__":
    main()
