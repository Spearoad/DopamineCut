"""카카오톡 숏폼 화면 분류 파이프라인 공통 정의.

- 라벨 순서는 앱의 KakaotalkUiClass(MODEL_CLASSES)와 1:1로 같아야 한다.
- ui_tiles_v1()은 앱의 WholeUiTiles.kt + AntialiasedBilinearResize.kt와 픽셀 단위로 같아야 한다.
  (Kotlin roundToInt는 0.5를 올림하므로 Python round() 대신 floor(x + 0.5)를 쓴다.)
"""
from __future__ import annotations

import csv
import math
import re
from pathlib import Path

from PIL import Image

LABELS = [
    "SHORTFORM_NORMAL",  # 지금 탭 숏폼 일반 영상 → 시청 집계 대상
    "SHORTFORM_AD",      # 숏폼 피드 사이 광고 → 집계 제외
    "NOW_OPENCHAT",      # 지금 탭의 숏폼 외 화면(오픈채팅 목록·추천 등) → 집계 제외
    "CHAT_FRIENDS",      # 친구 탭·채팅 목록·채팅방 → 집계 제외
]

INPUT_SIZE = 320
ROI_SOURCES = [(0.0, 0.0, 1.0, 1.0), (0.0, 0.60, 1.0, 0.85), (0.0, 0.82, 1.0, 0.97)]
ROI_DESTINATIONS = [(0, 0, 160, 320), (160, 0, 320, 160), (160, 160, 320, 320)]
IMAGENET_MEAN = (0.485, 0.456, 0.406)
IMAGENET_STD = (0.229, 0.224, 0.225)
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp"}

# collect.py가 만드는 파일명: <세션>__<번호>.png. 연속 프레임은 거의 같은 화면이라 묶어서 분할한다.
_CAPTURE_NAME = re.compile(r"^(?P<session>.+)__(?P<index>\d+)$")
FRAMES_PER_GROUP = 5


def kotlin_round(value: float) -> int:
    return math.floor(value + 0.5)


def ui_tiles_v1(image: Image.Image) -> Image.Image:
    """세로 스크린샷을 320x320 입력(왼쪽 전체 화면, 오른쪽 위 60~85%, 오른쪽 아래 82~97%)으로 만든다."""
    image = image.convert("RGB")
    width, height = image.size
    canvas = Image.new("RGB", (INPUT_SIZE, INPUT_SIZE), (0, 0, 0))
    for source, destination in zip(ROI_SOURCES, ROI_DESTINATIONS):
        left, top = kotlin_round(width * source[0]), kotlin_round(height * source[1])
        right, bottom = kotlin_round(width * source[2]), kotlin_round(height * source[3])
        crop = image.crop((left, top, right, bottom))
        crop_width, crop_height = right - left, bottom - top
        target_width = destination[2] - destination[0]
        target_height = destination[3] - destination[1]
        scale = min(target_width / crop_width, target_height / crop_height)
        fitted_width = min(max(kotlin_round(crop_width * scale), 1), target_width)
        fitted_height = min(max(kotlin_round(crop_height * scale), 1), target_height)
        resized = crop.resize((fitted_width, fitted_height), Image.Resampling.BILINEAR)
        x = destination[0] + (target_width - fitted_width) // 2
        y = destination[1] + (target_height - fitted_height) // 2
        canvas.paste(resized, (x, y))
    return canvas


def letterbox(image: Image.Image) -> Image.Image:
    """비교 실험용 단순 전처리: 전체 화면만 비율 유지로 320x320에 넣고 검은 여백(앱에서는 사용하지 않음)."""
    image = image.convert("RGB")
    scale = min(INPUT_SIZE / image.width, INPUT_SIZE / image.height)
    size = (max(kotlin_round(image.width * scale), 1), max(kotlin_round(image.height * scale), 1))
    canvas = Image.new("RGB", (INPUT_SIZE, INPUT_SIZE), (0, 0, 0))
    canvas.paste(image.resize(size, Image.Resampling.BILINEAR),
                 ((INPUT_SIZE - size[0]) // 2, (INPUT_SIZE - size[1]) // 2))
    return canvas


PREPROCESSORS = {"ui_tiles_v1": ui_tiles_v1, "letterbox": letterbox}


def read_manifests(data_dir: Path) -> list[dict]:
    """수집 세션별 기기 정보(data/manifests/*.csv)를 읽는다. 지금 남아 있는 사진만 돌려준다."""
    rows = []
    for path in sorted((data_dir / "manifests").glob("*.csv")):
        with path.open(encoding="utf-8") as handle:
            rows += [row for row in csv.DictReader(handle) if (data_dir / row["file"]).exists()]
    return rows


def list_dataset(data_dir: Path) -> list[tuple[Path, int, str]]:
    """data/<LABEL>/*.png → (경로, 라벨 번호, 분할 그룹)."""
    if not data_dir.is_dir():
        raise SystemExit(f"{data_dir} 폴더가 없습니다. collect.py로 먼저 수집하세요.")
    items = []
    for label_index, label in enumerate(LABELS):
        folder = data_dir / label
        if not folder.is_dir():
            continue
        for path in sorted(folder.iterdir()):
            if path.suffix.lower() not in IMAGE_SUFFIXES:
                continue
            match = _CAPTURE_NAME.match(path.stem)
            if match:
                group = f"{label}/{match['session']}/{int(match['index']) // FRAMES_PER_GROUP}"
            else:
                group = f"{label}/{path.stem}"
            items.append((path, label_index, group))
    unknown = [p.name for p in data_dir.iterdir()
               if p.is_dir() and p.name not in LABELS and p.name not in ("_unsorted", "manifests")]
    if unknown:
        raise SystemExit(f"알 수 없는 클래스 폴더: {unknown}. 사용 가능: {LABELS}")
    return items
