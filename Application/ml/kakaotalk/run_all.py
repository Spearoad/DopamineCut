"""수집·검수가 끝난 data/로 학습 → 모델 비교 → 그래프 → ONNX 변환·앱 설치까지 한 번에 실행한다.

    python run_all.py                 # 전체(모델 비교 포함, 오래 걸림)
    python run_all.py --skip-compare  # 모델·전처리 비교 실험 생략
    python run_all.py --no-install    # 앱 assets에 복사하지 않음
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def run(*args: str) -> None:
    print(f"\n=== python {' '.join(args)} ===", flush=True)
    if subprocess.run([sys.executable, *args], cwd=ROOT).returncode != 0:
        raise SystemExit(f"{args[0]} 단계에서 실패했습니다. 위 메시지를 확인하세요.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-compare", action="store_true")
    parser.add_argument("--no-install", action="store_true")
    args = parser.parse_args()

    run("review.py")
    run("train.py")
    if not args.skip_compare:
        run("compare.py")
    run("plots.py")
    run("export.py", *([] if args.no_install else ["--install"]))
    print("\n완료: artifacts/figures/, artifacts/metrics_summary.md 를 확인하세요.")


if __name__ == "__main__":
    main()
