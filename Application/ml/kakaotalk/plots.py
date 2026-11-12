"""train.py / compare.py 결과를 보고서용 그래프와 지표 요약으로 만든다.

    python plots.py

artifacts/figures/
  01_learning_curves.png      최고 설정의 손실 곡선 + 정확도·Macro F1 곡선
  02_confusion_matrix.png     테스트셋 혼동행렬(개수 + 클래스별 재현율)
  03_per_class_metrics.png    클래스별 정밀도·재현율·F1
  04_hparam_comparison.png    하이퍼파라미터 설정별 검증 Macro F1 비교
  05_model_preprocess.png     모델·전처리 후보 비교(compare.py 실행 시)
artifacts/metrics_summary.md   정확도·Macro F1 계산 과정 표
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

from common import LABELS, read_manifests  # noqa: E402

ROOT = Path(__file__).resolve().parent
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]  # 범주 색은 고정 순서로만 사용
INK, INK_2, GRID_INK, MUTED = "#0b0b0b", "#52514e", "#e5e4e0", "#c3c2b7"
SHORT = {"SHORTFORM_NORMAL": "숏폼 일반", "SHORTFORM_AD": "숏폼 광고", "NOW_OPENCHAT": "지금탭 기타",
         "CHAT_FRIENDS": "친구·채팅"}

_installed = {font.name for font in matplotlib.font_manager.fontManager.ttflist}
plt.rcParams.update({
    "font.family": next((f for f in ("Malgun Gothic", "AppleGothic", "NanumGothic") if f in _installed), "DejaVu Sans"),
    "axes.unicode_minus": False, "figure.dpi": 150, "savefig.bbox": "tight",
    "axes.edgecolor": MUTED, "axes.labelcolor": INK_2, "xtick.color": INK_2, "ytick.color": INK_2,
    "axes.titlecolor": INK, "axes.titlesize": 12, "axes.titleweight": "bold", "axes.spines.top": False,
    "axes.spines.right": False, "axes.grid": True, "grid.color": GRID_INK, "grid.linewidth": 0.8,
    "legend.frameon": False, "lines.linewidth": 2,
})


def read_csv(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def by_config(rows: list[dict]) -> dict[str, list[dict]]:
    grouped = defaultdict(list)
    for row in rows:
        grouped[row["config"]].append(row)
    return grouped


def learning_curves(history: list[dict], best: str, test: dict, out: Path) -> None:
    epochs = [int(r["epoch"]) for r in history]
    col = lambda key, scale=1.0: [float(r[key]) * scale for r in history]  # noqa: E731
    fig, (left, right) = plt.subplots(1, 2, figsize=(12, 4.2))
    left.plot(epochs, col("train_loss"), color=SERIES[0], marker="o", markersize=4, label="Train Loss")
    left.plot(epochs, col("val_loss"), color=SERIES[1], marker="o", markersize=4, label="Val Loss")
    left.set(title="손실 곡선 (Loss)", xlabel="Epoch", ylabel="Loss")
    right.plot(epochs, col("train_acc", 100), color=SERIES[0], marker="o", markersize=4, label="Train Acc (%)")
    right.plot(epochs, col("val_acc", 100), color=SERIES[1], marker="o", markersize=4, label="Val Acc (%)")
    right.plot(epochs, col("val_macro_f1", 100), color=SERIES[2], linestyle="--", label="Val Macro F1 (%)")
    best_row = max(history, key=lambda r: float(r["val_macro_f1"]))
    right.scatter([int(best_row["epoch"])], [float(best_row["val_macro_f1"]) * 100], s=70, zorder=5,
                  color=SERIES[2], edgecolor="white", linewidth=2)
    right.annotate(f"선택 epoch {best_row['epoch']}\nVal F1 {float(best_row['val_macro_f1']) * 100:.2f}%",
                   (int(best_row["epoch"]), float(best_row["val_macro_f1"]) * 100), textcoords="offset points",
                   xytext=(0, 14), ha="center", fontsize=9, color=INK_2,
                   bbox={"boxstyle": "round,pad=0.25", "facecolor": "white", "edgecolor": "none", "alpha": 0.9})
    right.set(title=f"정확도 곡선 (Val Acc 최고 {max(col('val_acc', 100)):.2f}%)", xlabel="Epoch", ylabel="%",
              ylim=(None, 112))
    right.legend(loc="upper center", bbox_to_anchor=(0.5, -0.16), ncol=3)
    left.legend(loc="upper center", bbox_to_anchor=(0.5, -0.16), ncol=2)
    fig.suptitle(f"카카오톡 화면 분류 학습 — 설정 {best} · 테스트 정확도 {test['accuracy'] * 100:.2f}% · "
                 f"Macro F1 {test['macro_f1'] * 100:.2f}%", color=INK, fontsize=12, y=1.03)
    fig.savefig(out)
    plt.close(fig)


def confusion(matrix: np.ndarray, out: Path) -> None:
    fig, ax = plt.subplots(figsize=(6.6, 5.6))
    rows = matrix.sum(1, keepdims=True).clip(min=1)
    ax.imshow(matrix / rows, cmap="Blues", vmin=0, vmax=1)
    ax.grid(False)
    names = [SHORT[l] for l in LABELS]
    ax.set_xticks(range(len(LABELS)), names, rotation=30, ha="right")
    ax.set_yticks(range(len(LABELS)), names)
    ax.set(xlabel="예측 (Predicted)", ylabel="정답 (True)",
           title=f"혼동행렬 — 테스트 {int(np.trace(matrix))}/{int(matrix.sum())}장 정답")
    for i in range(len(LABELS)):
        for j in range(len(LABELS)):
            share = matrix[i, j] / rows[i, 0]
            ax.text(j, i, f"{matrix[i, j]}\n{share * 100:.0f}%" if matrix[i, j] else "0", ha="center",
                    va="center", fontsize=9, color="white" if share > 0.55 else INK)
    fig.savefig(out)
    plt.close(fig)


def per_class(report: dict, out: Path) -> None:
    keys = [("precision", "정밀도"), ("recall", "재현율"), ("f1-score", "F1")]
    x = np.arange(len(LABELS))
    width = 0.26
    fig, ax = plt.subplots(figsize=(9, 4.2))
    for k, (key, name) in enumerate(keys):
        values = [report[label][key] * 100 for label in LABELS]
        ax.bar(x + (k - 1) * width, values, width - 0.02, color=SERIES[k], label=name)
    for i, label in enumerate(LABELS):
        ax.text(i + width, report[label]["f1-score"] * 100 + 1, f"{report[label]['f1-score'] * 100:.1f}",
                ha="center", fontsize=8, color=INK_2)
    ax.set_xticks(x, [f"{SHORT[l]}\n(n={int(report[l]['support'])})" for l in LABELS])
    ax.set(ylim=(0, 108), ylabel="%", title="클래스별 성능 (테스트셋)")
    ax.legend(ncol=3, loc="upper center", bbox_to_anchor=(0.5, -0.2))
    fig.savefig(out)
    plt.close(fig)


def hparams(results: list[dict], history: list[dict], best: str, out: Path) -> None:
    fig, (left, right) = plt.subplots(1, 2, figsize=(12, 4.2))
    names = [r["name"] for r in results]
    values = [float(r["val_macro_f1"]) * 100 for r in results]
    colors = [SERIES[k % len(SERIES)] for k in range(len(names))]  # 오른쪽 곡선과 같은 설정 = 같은 색
    bars = left.barh(names, values, color=colors, height=0.6)
    for bar, value, name in zip(bars, values, names):
        left.text(bar.get_width() + 0.5, bar.get_y() + bar.get_height() / 2,
                  f"{value:.2f}%" + ("  ★ 선택" if name == best else ""),
                  ha="left", va="center", fontsize=9, color=INK if name == best else INK_2,
                  fontweight="bold" if name == best else "normal")
    left.set(xlim=(max(min(values) - 10, 0), 108), xlabel="검증 Macro F1 (%)",
             title="하이퍼파라미터 설정별 성능")
    left.invert_yaxis()
    left.grid(axis="y", visible=False)
    for k, (name, rows) in enumerate(by_config(history).items()):
        right.plot([int(r["epoch"]) for r in rows], [float(r["val_macro_f1"]) * 100 for r in rows],
                   color=SERIES[k % len(SERIES)], label=name, linewidth=2.5 if name == best else 1.5)
    right.set(xlabel="Epoch", ylabel="검증 Macro F1 (%)", title="설정별 검증 Macro F1 곡선")
    right.legend(fontsize=8, loc="upper center", bbox_to_anchor=(0.5, -0.16), ncol=2)
    fig.savefig(out)
    plt.close(fig)


def candidates(rows: list[dict], out: Path) -> None:
    measures = [("test_macro_f1", "테스트 Macro F1 (%)", 100, "높을수록 좋음"),
                ("size_mb", "모델 크기 (MB)", 1, "작을수록 좋음"),
                ("pc_cpu_latency_ms", "PC CPU 추론 시간 (ms)", 1, "작을수록 좋음")]
    names = [r["candidate"] for r in rows]
    colors = [SERIES[0]] + [MUTED] * (len(rows) - 1)
    fig, axes = plt.subplots(1, 3, figsize=(15, 3.6), sharey=True)
    for ax, (key, title, scale, hint) in zip(axes, measures):
        values = [float(r[key]) * scale for r in rows]
        bars = ax.barh(names, values, color=colors, height=0.6)
        for bar, value in zip(bars, values):
            ax.text(bar.get_width(), bar.get_y() + bar.get_height() / 2, f" {value:.1f}", va="center",
                    fontsize=9, color=INK_2)
        ax.set(title=f"{title}\n{hint}", xlim=(0, max(values) * 1.18))
        ax.grid(axis="y", visible=False)
    axes[0].invert_yaxis()
    fig.suptitle("모델·전처리 선정 근거 (파랑 = 앱에 적용한 조합)", color=INK, fontsize=12, y=1.06)
    fig.savefig(out)
    plt.close(fig)


def summary(report: dict, matrix: np.ndarray, results: list[dict], compare: list[dict] | None,
            devices: list[dict], out: Path) -> None:
    val, test = report["validation"], report["test"]
    lines = ["# 카카오톡 화면 분류 모델 지표 요약", "",
             f"- 모델: MobileNetV3-Small (ImageNet 사전학습) / 전처리: ui_tiles_v1 (320x320, 3분할 + 검은 패딩)",
             f"- 선택한 하이퍼파라미터: `{report['best_config']['name']}` "
             f"(lr={report['best_config']['lr']}, label_smoothing={report['best_config']['label_smoothing']}, "
             f"freeze_epochs={report['best_config']['freeze_epochs']})", "",
             "## 데이터 분할", "", "| 분할 | " + " | ".join(SHORT[l] for l in LABELS) + " | 합계 |",
             "|---|" + "---:|" * (len(LABELS) + 1)]
    for name, counts in report["split_counts"].items():
        lines.append(f"| {name} | " + " | ".join(str(counts[l]) for l in LABELS) + f" | {sum(counts.values())} |")
    if devices:
        grouped = defaultdict(int)
        for r in devices:
            grouped[(r["collector"], f"{r['manufacturer']} {r['device_model']}", r["android_release"],
                     r["kakaotalk_version"], f"{r['screen_width']}x{r['screen_height']}")] += 1
        lines += ["", "## 수집 기기", "", "| 수집자 | 기기 | Android | 카카오톡 | 해상도 | 장수 |",
                  "|---|---|---|---|---|---:|"]
        lines += [f"| {c or '-'} | {d} | {a} | {k or '-'} | {s} | {n} |" for (c, d, a, k, s), n in grouped.items()]
    lines += ["", "## 정확도", "",
              f"- 검증: 정답 {val['correct']} / {val['total']} = **{val['accuracy'] * 100:.2f}%**",
              f"- 테스트(선택에 쓰지 않은 데이터): 혼동행렬 대각선 합 {int(np.trace(matrix))} / {int(matrix.sum())} "
              f"= **{test['accuracy'] * 100:.2f}%**", "",
              "## Macro F1 (테스트)", "", f"각 클래스 F1을 구한 뒤 클래스 수({len(LABELS)})로 나눈 값.", "",
              "| 클래스 | 정밀도 | 재현율 | F1 | 장수 |", "|---|---:|---:|---:|---:|"]
    for label in LABELS:
        row = report["per_class_test"][label]
        lines.append(f"| {SHORT[label]} | {row['precision'] * 100:.2f}% | {row['recall'] * 100:.2f}% | "
                     f"{row['f1-score'] * 100:.2f}% | {int(row['support'])} |")
    f1s = [report["per_class_test"][l]["f1-score"] * 100 for l in LABELS]
    lines += ["", f"Macro F1 = ({' + '.join(f'{v:.2f}' for v in f1s)}) / {len(LABELS)} = **{np.mean(f1s):.2f}%**",
              "", "## 하이퍼파라미터 비교 (검증)", "", "| 설정 | lr | label smoothing | 백본 고정 epoch | 최고 epoch | "
              "검증 정확도 | 검증 Macro F1 |", "|---|---:|---:|---:|---:|---:|---:|"]
    for r in results:
        lines.append(f"| {r['name']} | {r['lr']} | {r['label_smoothing']} | {r['freeze_epochs']} | {r['best_epoch']} | "
                     f"{float(r['val_accuracy']) * 100:.2f}% | {float(r['val_macro_f1']) * 100:.2f}% |")
    if compare:
        lines += ["", "## 모델·전처리 비교", "", "| 후보 | 테스트 정확도 | 테스트 Macro F1 | 파라미터(M) | 크기(MB) | "
                  "PC CPU 추론(ms) |", "|---|---:|---:|---:|---:|---:|"]
        for r in compare:
            lines.append(f"| {r['candidate']} | {float(r['test_accuracy']) * 100:.2f}% | "
                         f"{float(r['test_macro_f1']) * 100:.2f}% | {r['params_m']} | {r['size_mb']} | "
                         f"{r['pc_cpu_latency_ms']} |")
    lines += ["", f"클래스별 확정 임계값: {report['decision_thresholds']}", ""]
    out.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path, default=ROOT / "artifacts")
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    args = parser.parse_args()
    a = args.artifacts
    figures = a / "figures"
    figures.mkdir(parents=True, exist_ok=True)

    report = json.loads((a / "report.json").read_text(encoding="utf-8"))
    best = report["best_config"]["name"]
    history = read_csv(a / "history.csv")
    results = read_csv(a / "hparam_results.csv")
    matrix = np.array([[int(v) for v in row[1:]] for row in list(csv.reader((a / "confusion_matrix.csv").open(
        encoding="utf-8")))[1:]])

    learning_curves(by_config(history)[best], best, report["test"], figures / "01_learning_curves.png")
    confusion(matrix, figures / "02_confusion_matrix.png")
    per_class(report["per_class_test"], figures / "03_per_class_metrics.png")
    hparams(results, history, best, figures / "04_hparam_comparison.png")
    compare = read_csv(a / "compare_results.csv") if (a / "compare_results.csv").exists() else None
    if compare:
        candidates(compare, figures / "05_model_preprocess.png")
    devices = read_manifests(args.data) if args.data.is_dir() else []
    summary(report, matrix, results, compare, devices, a / "metrics_summary.md")
    print(f"그래프: {figures}\n지표 요약: {a / 'metrics_summary.md'}")


if __name__ == "__main__":
    main()
