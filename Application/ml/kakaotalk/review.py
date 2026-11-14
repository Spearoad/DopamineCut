"""라벨 검수용 요약과 클래스별 썸네일 시트를 만든다.

    python review.py

artifacts/review/<LABEL>.jpg 의 썸네일 번호를 보고 잘못 들어간 파일은 맞는 폴더로 옮기거나
data/_unsorted/ 로 빼면 학습에서 제외된다.
"""
from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw

from common import LABELS, list_dataset, read_manifests

ROOT = Path(__file__).resolve().parent
THUMB_W, THUMB_H, COLUMNS = 120, 260, 10


def contact_sheet(paths: list[Path], output: Path) -> None:
    rows = (len(paths) + COLUMNS - 1) // COLUMNS
    sheet = Image.new("RGB", (COLUMNS * THUMB_W, rows * (THUMB_H + 16)), (32, 32, 32))
    draw = ImageDraw.Draw(sheet)
    for index, path in enumerate(paths):
        with Image.open(path) as image:
            thumb = image.convert("RGB")
            thumb.thumbnail((THUMB_W - 4, THUMB_H - 4))
        x, y = (index % COLUMNS) * THUMB_W, (index // COLUMNS) * (THUMB_H + 16)
        sheet.paste(thumb, (x + 2, y + 2))
        draw.text((x + 4, y + THUMB_H), f"{index:03d} {path.stem[-12:]}", fill=(255, 255, 0))
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output, quality=85)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    parser.add_argument("--out", type=Path, default=ROOT / "artifacts" / "review")
    args = parser.parse_args()

    items = list_dataset(args.data)
    counts = Counter(LABELS[label] for _, label, _ in items)
    groups = Counter(LABELS[label] for label, _ in {(label, group) for _, label, group in items})
    print(f"총 {len(items)}장")
    for label in LABELS:
        print(f"  {label:<18} {counts[label]:>4}장  (분할 그룹 {groups[label]}개)")
        if counts[label]:
            contact_sheet([p for p, l, _ in items if LABELS[l] == label], args.out / f"{label}.jpg")

    rows = read_manifests(args.data)
    if rows:
        devices = Counter(f"{r['collector'] or '?'} — {r['manufacturer']} {r['device_model']} "
                          f"(Android {r['android_release']}, 카카오톡 {r['kakaotalk_version'] or '?'}, "
                          f"{r['screen_width']}x{r['screen_height']})" for r in rows)
        print("수집 기기:")
        for device, count in devices.items():
            print(f"  {device}: {count}장")
        print("다크 모드: " + ", ".join(f"{k or '?'}={v}장" for k, v in Counter(r["night_mode"] for r in rows).items()))
    unrecorded = len(items) - len(rows)
    if unrecorded > 0:
        print(f"기기 정보가 없는 사진 {unrecorded}장(직접 넣은 파일). 제출용 수집 기기 정보에서 빠집니다.")
    print(f"썸네일 시트: {args.out}")


if __name__ == "__main__":
    main()
