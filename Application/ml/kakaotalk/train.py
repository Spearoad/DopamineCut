"""MobileNetV3-Small로 카카오톡 화면 4클래스 분류기를 학습하고 하이퍼파라미터를 비교한다.

    python train.py              # 기본 4개 설정 비교 → 검증 Macro F1 최고 모델 저장
    python train.py --quick      # 설정 1개만 빠르게

연속 프레임(같은 영상)이 학습/검증에 동시에 들어가 점수가 부풀지 않도록 그룹 단위로 나눈다.
결과: artifacts/best.pt, report.json, hparam_results.csv, history.csv(에폭별 곡선), confusion_matrix.csv
그래프는 plots.py로 만든다.
"""
from __future__ import annotations

import argparse
import copy
import csv
import json
import os
import random
import time
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from torch import nn
from torchvision import models
from torchvision.transforms import v2

from common import IMAGENET_MEAN, IMAGENET_STD, LABELS, PREPROCESSORS, list_dataset

ROOT = Path(__file__).resolve().parent

GRID = [
    {"name": "lr1e-3_ls0.0", "lr": 1e-3, "label_smoothing": 0.0, "freeze_epochs": 0},
    {"name": "lr1e-3_ls0.1", "lr": 1e-3, "label_smoothing": 0.1, "freeze_epochs": 0},
    {"name": "lr3e-4_ls0.1", "lr": 3e-4, "label_smoothing": 0.1, "freeze_epochs": 0},
    {"name": "lr1e-3_ls0.1_freeze3", "lr": 1e-3, "label_smoothing": 0.1, "freeze_epochs": 3},
]

ARCHITECTURES = {
    "mobilenet_v3_small": (models.mobilenet_v3_small, models.MobileNet_V3_Small_Weights.IMAGENET1K_V1),
    "mobilenet_v3_large": (models.mobilenet_v3_large, models.MobileNet_V3_Large_Weights.IMAGENET1K_V1),
    "resnet18": (models.resnet18, models.ResNet18_Weights.IMAGENET1K_V1),
}


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


def build_model(pretrained: bool, arch: str = "mobilenet_v3_small") -> nn.Module:
    factory, weights = ARCHITECTURES[arch]
    model = factory(weights=weights if pretrained else None)
    if arch == "resnet18":
        model.fc = nn.Linear(model.fc.in_features, len(LABELS))
    else:
        model.classifier[3] = nn.Linear(model.classifier[3].in_features, len(LABELS))
    return model


def backbone(model: nn.Module) -> list[nn.Parameter]:
    if hasattr(model, "features"):
        return list(model.features.parameters())
    return [p for name, p in model.named_parameters() if not name.startswith("fc.")]


def group_split(items, val_frac: float, test_frac: float, seed: int):
    """클래스마다 그룹을 섞어 test → val → train 순으로 채운다."""
    rng = random.Random(seed)
    by_class = defaultdict(lambda: defaultdict(list))
    for index, (_, label, group) in enumerate(items):
        by_class[label][group].append(index)
    splits = {"train": [], "val": [], "test": []}
    for label, groups in sorted(by_class.items()):
        names = sorted(groups)
        rng.shuffle(names)
        total = sum(len(groups[g]) for g in names)
        if len(names) < 3:
            raise SystemExit(f"{LABELS[label]}: 분할 그룹이 {len(names)}개뿐입니다. 서로 다른 화면을 더 수집하세요.")
        targets = {"test": total * test_frac, "val": total * val_frac}
        filled = {"test": 0, "val": 0}
        for name in names:
            part = next((p for p in ("test", "val") if filled[p] < targets[p]), "train")
            if part != "train":
                filled[part] += len(groups[name])
            splits[part].extend(groups[name])
        if not any(items[i][1] == label for i in splits["train"]):
            raise SystemExit(f"{LABELS[label]}: 학습에 쓸 그룹이 남지 않았습니다.")
    return splits


class TileDataset(torch.utils.data.Dataset):
    def __init__(self, tiles: np.ndarray, labels: np.ndarray, train: bool):
        self.tiles, self.labels = tiles, labels
        steps = [v2.ToDtype(torch.float32, scale=True)]
        if train:
            # 레이아웃(타일 위치)은 앱과 똑같이 고정이므로 기하 변형은 쓰지 않는다.
            steps += [v2.ColorJitter(brightness=0.2, contrast=0.2, saturation=0.15),
                      v2.RandomErasing(p=0.25, scale=(0.01, 0.05), value=0)]
        steps.append(v2.Normalize(IMAGENET_MEAN, IMAGENET_STD))
        self.transform = v2.Compose(steps)

    def __len__(self):
        return len(self.labels)

    def __getitem__(self, index):
        image = torch.from_numpy(self.tiles[index]).permute(2, 0, 1)
        return self.transform(image), int(self.labels[index])


def prepare_data(data_dir: Path, preprocess: str, val_frac: float, test_frac: float,
                 batch_size: int, seed: int):
    """이미지 전처리 → 그룹 분할 → DataLoader. compare.py도 같은 분할을 쓴다."""
    items = list_dataset(data_dir)
    if not items:
        raise SystemExit(f"{data_dir} 에 학습 이미지가 없습니다. collect.py로 먼저 수집하세요.")
    print(f"{len(items)}장 전처리({preprocess}) 중...")
    transform = PREPROCESSORS[preprocess]
    tiles = np.stack([np.asarray(transform(Image.open(path))) for path, _, _ in items])
    labels = np.array([label for _, label, _ in items])
    splits = group_split(items, val_frac, test_frac, seed)
    generator = torch.Generator().manual_seed(seed)
    loaders = {
        name: torch.utils.data.DataLoader(
            TileDataset(tiles[indices], labels[indices], train=name == "train"),
            batch_size=batch_size, shuffle=name == "train", generator=generator)
        for name, indices in splits.items()
    }
    train_counts = np.bincount(labels[splits["train"]], minlength=len(LABELS)).clip(min=1)
    class_weights = torch.tensor(len(splits["train"]) / (len(LABELS) * train_counts), dtype=torch.float32)
    return items, labels, splits, loaders, class_weights


@torch.no_grad()
def predict(model: nn.Module, loader) -> tuple[np.ndarray, np.ndarray]:
    model.eval()
    probs, labels = [], []
    for images, targets in loader:
        probs.append(torch.softmax(model(images), dim=1).numpy())
        labels.append(targets.numpy())
    return np.concatenate(probs), np.concatenate(labels)


def metrics(probs: np.ndarray, labels: np.ndarray) -> dict:
    preds = probs.argmax(1)
    return {"accuracy": float(accuracy_score(labels, preds)),
            "macro_f1": float(f1_score(labels, preds, average="macro", labels=range(len(LABELS)), zero_division=0)),
            "loss": float(-np.log(np.clip(probs[np.arange(len(labels)), labels], 1e-7, 1)).mean()),
            "correct": int((preds == labels).sum()), "total": int(len(labels))}


def train_one(config: dict, loaders: dict, class_weights: torch.Tensor, epochs: int, patience: int,
              pretrained: bool, seed: int, arch: str = "mobilenet_v3_small"):
    """반환: (검증 Macro F1 최고 시점 모델, 그 시점 점수, 에폭별 기록)."""
    seed_everything(seed)
    model = build_model(pretrained, arch)
    criterion = nn.CrossEntropyLoss(weight=class_weights, label_smoothing=config["label_smoothing"])
    optimizer = torch.optim.AdamW(model.parameters(), lr=config["lr"], weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    best, best_state, stale, history = {"macro_f1": -1.0}, None, 0, []
    for epoch in range(1, epochs + 1):
        frozen = epoch <= config["freeze_epochs"]
        for parameter in backbone(model):
            parameter.requires_grad = not frozen
        model.train()
        started, running, correct = time.time(), 0.0, 0
        for images, targets in loaders["train"]:
            optimizer.zero_grad()
            logits = model(images)
            loss = criterion(logits, targets)
            loss.backward()
            optimizer.step()
            running += loss.item() * len(targets)
            correct += int((logits.argmax(1) == targets).sum())
        scheduler.step()
        size = len(loaders["train"].dataset)
        score = metrics(*predict(model, loaders["val"]))
        history.append({"config": config["name"], "epoch": epoch,
                        "train_loss": round(running / size, 5), "train_acc": round(correct / size, 5),
                        "val_loss": round(score["loss"], 5), "val_acc": round(score["accuracy"], 5),
                        "val_macro_f1": round(score["macro_f1"], 5)})
        print(f"  [{config['name']}] epoch {epoch:02d} loss={running / size:.4f} "
              f"val_acc={score['accuracy']:.4f} val_f1={score['macro_f1']:.4f} ({time.time() - started:.0f}s)")
        if score["macro_f1"] > best["macro_f1"]:
            best, best_state, stale = {**score, "epoch": epoch}, copy.deepcopy(model.state_dict()), 0
        else:
            stale += 1
            if stale >= patience:
                break
    model.load_state_dict(best_state)
    return model, best, history


def decision_thresholds(probs: np.ndarray, labels: np.ndarray, target_precision: float) -> dict:
    """검증셋에서 클래스별로 정밀도 목표를 만족하는 가장 낮은 신뢰도 임계값(0.55~0.90)을 고른다."""
    preds, confidence = probs.argmax(1), probs.max(1)
    thresholds = {}
    for index, label in enumerate(LABELS):
        chosen = 0.70
        for threshold in np.arange(0.55, 0.901, 0.05):
            selected = (preds == index) & (confidence >= threshold)
            if selected.sum() >= 3 and (labels[selected] == index).mean() >= target_precision:
                chosen = float(round(threshold, 2))
                break
        thresholds[label] = chosen
    return thresholds


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    parser.add_argument("--out", type=Path, default=ROOT / "artifacts")
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--patience", type=int, default=6)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--val-frac", type=float, default=0.15)
    parser.add_argument("--test-frac", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--quick", action="store_true", help="첫 번째 설정만 학습")
    parser.add_argument("--no-pretrained", action="store_true", help="ImageNet 가중치 없이(오프라인 점검용)")
    args = parser.parse_args()
    torch.set_num_threads(os.cpu_count() or 4)

    items, labels, splits, loaders, class_weights = prepare_data(
        args.data, "ui_tiles_v1", args.val_frac, args.test_frac, args.batch_size, args.seed)
    for name, indices in splits.items():
        counts = np.bincount(labels[indices], minlength=len(LABELS))
        print(f"  {name:<5} {len(indices):>4}장 " + " ".join(f"{l}={c}" for l, c in zip(LABELS, counts)))

    args.out.mkdir(parents=True, exist_ok=True)
    results, histories, best_model, best_config, best_score = [], [], None, None, None
    for config in GRID[:1] if args.quick else GRID:
        print(f"설정 {config['name']} 학습")
        model, score, history = train_one(config, loaders, class_weights, args.epochs, args.patience,
                                          not args.no_pretrained, args.seed)
        histories += history
        results.append({**config, "best_epoch": score["epoch"], "val_accuracy": round(score["accuracy"], 4),
                        "val_macro_f1": round(score["macro_f1"], 4)})
        if best_score is None or score["macro_f1"] > best_score["macro_f1"]:
            best_model, best_config, best_score = model, config, score
    write_csv(args.out / "hparam_results.csv", results)
    write_csv(args.out / "history.csv", histories)

    val_probs, val_labels = predict(best_model, loaders["val"])
    test_probs, test_labels = predict(best_model, loaders["test"])
    val_score, test_score = metrics(val_probs, val_labels), metrics(test_probs, test_labels)
    thresholds = decision_thresholds(val_probs, val_labels, target_precision=0.97)
    matrix = confusion_matrix(test_labels, test_probs.argmax(1), labels=range(len(LABELS)))
    with (args.out / "confusion_matrix.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["actual \\ predicted", *LABELS])
        for label, row in zip(LABELS, matrix):
            writer.writerow([label, *row])
    report_text = classification_report(test_labels, test_probs.argmax(1), labels=range(len(LABELS)),
                                        target_names=LABELS, digits=4, zero_division=0)
    (args.out / "classification_report.txt").write_text(report_text, encoding="utf-8")
    per_class = classification_report(test_labels, test_probs.argmax(1), labels=range(len(LABELS)),
                                      target_names=LABELS, zero_division=0, output_dict=True)

    split_counts = {name: {l: int(c) for l, c in zip(LABELS, np.bincount(labels[idx], minlength=len(LABELS)))}
                    for name, idx in splits.items()}
    report = {"best_config": best_config, "validation": val_score, "test": test_score,
              "per_class_test": {label: per_class[label] for label in LABELS},
              "decision_thresholds": thresholds, "split_counts": split_counts, "hparam_results": results}
    (args.out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    torch.save({"state_dict": best_model.state_dict(), "labels": LABELS, "report": report,
                "test_files": [str(items[i][0]) for i in splits["test"]]}, args.out / "best.pt")

    print(f"\n최고 설정: {best_config['name']}")
    print(f"검증 정확도 {val_score['correct']}/{val_score['total']} = {val_score['accuracy']:.4f}, "
          f"Macro F1 {val_score['macro_f1']:.4f}")
    print(f"테스트 정확도 {test_score['correct']}/{test_score['total']} = {test_score['accuracy']:.4f}, "
          f"Macro F1 {test_score['macro_f1']:.4f}")
    print(report_text)
    print(f"임계값: {thresholds}\n다음 단계: python plots.py, python export.py --install")


if __name__ == "__main__":
    main()
