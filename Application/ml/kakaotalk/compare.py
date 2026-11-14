"""모델·전처리 선정 근거 실험: 같은 데이터 분할에서 후보 조합을 학습해 성능·크기·속도를 비교한다.

    python compare.py            # train.py 이후 실행(최고 하이퍼파라미터를 그대로 사용)

결과: artifacts/compare_results.csv, compare_history.csv → plots.py가 그래프로 만든다.
"""
from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path

import numpy as np
import torch

from train import GRID, metrics, predict, prepare_data, train_one, write_csv

ROOT = Path(__file__).resolve().parent

CANDIDATES = [
    # (표시 이름, 모델, 전처리) — 첫 줄이 앱에 쓰는 조합
    ("MobileNetV3-Small + UI 타일", "mobilenet_v3_small", "ui_tiles_v1"),
    ("MobileNetV3-Small + 전체화면 축소", "mobilenet_v3_small", "letterbox"),
    ("MobileNetV3-Large + UI 타일", "mobilenet_v3_large", "ui_tiles_v1"),
    ("ResNet-18 + UI 타일", "resnet18", "ui_tiles_v1"),
]


@torch.no_grad()
def cpu_latency_ms(model: torch.nn.Module, runs: int = 20) -> float:
    model.eval()
    sample = torch.zeros(1, 3, 320, 320)
    for _ in range(3):
        model(sample)
    timings = []
    for _ in range(runs):
        started = time.perf_counter()
        model(sample)
        timings.append((time.perf_counter() - started) * 1000)
    return float(np.median(timings))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    parser.add_argument("--out", type=Path, default=ROOT / "artifacts")
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--patience", type=int, default=6)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--no-pretrained", action="store_true")
    args = parser.parse_args()
    torch.set_num_threads(os.cpu_count() or 4)

    report_path = args.out / "report.json"
    config = json.loads(report_path.read_text(encoding="utf-8"))["best_config"] if report_path.exists() else GRID[1]
    print(f"하이퍼파라미터: {config['name']}")

    data_cache, rows, histories = {}, [], []
    for title, arch, preprocess in CANDIDATES:
        if preprocess not in data_cache:
            data_cache[preprocess] = prepare_data(args.data, preprocess, 0.15, 0.15, 32, args.seed)
        _, _, _, loaders, class_weights = data_cache[preprocess]
        print(f"후보 {title} 학습")
        model, best, history = train_one({**config, "name": title}, loaders, class_weights, args.epochs,
                                         args.patience, not args.no_pretrained, args.seed, arch)
        histories += history
        test = metrics(*predict(model, loaders["test"]))
        params = sum(p.numel() for p in model.parameters())
        rows.append({"candidate": title, "model": arch, "preprocess": preprocess,
                     "val_accuracy": round(best["accuracy"], 4), "val_macro_f1": round(best["macro_f1"], 4),
                     "test_accuracy": round(test["accuracy"], 4), "test_macro_f1": round(test["macro_f1"], 4),
                     "params_m": round(params / 1e6, 2), "size_mb": round(params * 4 / 1e6, 1),
                     "pc_cpu_latency_ms": round(cpu_latency_ms(model), 1)})
        print(f"  → {rows[-1]}")

    args.out.mkdir(parents=True, exist_ok=True)
    write_csv(args.out / "compare_results.csv", rows)
    write_csv(args.out / "compare_history.csv", histories)
    print("다음 단계: python plots.py")


if __name__ == "__main__":
    main()
