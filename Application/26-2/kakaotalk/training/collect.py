"""USB 디버깅으로 연결한 안드로이드 폰에서 카카오톡 화면을 클래스별 폴더로 수집한다.

    python collect.py --label SHORTFORM_NORMAL --count 60 --interval 2   # 2초마다 자동 캡처
    python collect.py --label SHORTFORM_AD --interval 0                  # Enter를 누를 때마다 캡처(드문 광고용)

캡처는 data/<LABEL>/<세션>__<번호>.png 로 저장되고, 기기 정보는 세션마다 data/manifests/<세션>.csv에 기록된다.
여러 사람이 수집한 data 폴더는 그대로 합쳐도(복사) 파일 이름이 겹치지 않는다.
카카오톡이 앞 화면이 아닐 때 찍힌 캡처는 저장하지 않는다(--allow-any-app으로 끌 수 있음).
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import os
import re
import shutil
import subprocess
import time
from datetime import datetime
from pathlib import Path

from PIL import Image

from common import LABELS

ROOT = Path(__file__).resolve().parent
KAKAOTALK_PACKAGE = "com.kakao.talk"
MANIFEST_FIELDS = ["file", "label", "collector", "device_model", "manufacturer", "android_release",
                   "kakaotalk_version", "night_mode", "screen_width", "screen_height", "captured_at"]


def find_adb() -> str:
    adb = shutil.which("adb")
    if adb:
        return adb
    sdk = Path(os.environ.get("ANDROID_HOME") or Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")
    candidate = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if candidate.exists():
        return str(candidate)
    raise SystemExit("adb를 찾을 수 없습니다. Android SDK platform-tools를 PATH에 추가하세요.")


def adb_output(adb: str, *args: str) -> bytes:
    return subprocess.run([adb, *args], check=True, capture_output=True).stdout


def shell(adb: str, command: str) -> str:
    return adb_output(adb, "shell", command).decode(errors="replace").strip()


def device_info(adb: str) -> dict[str, str]:
    version = re.search(r"versionName=(\S+)", shell(adb, f"dumpsys package {KAKAOTALK_PACKAGE}"))
    return {
        "device_model": shell(adb, "getprop ro.product.model"),
        "manufacturer": shell(adb, "getprop ro.product.manufacturer"),
        "android_release": shell(adb, "getprop ro.build.version.release"),
        "kakaotalk_version": version.group(1) if version else "",
    }


def night_mode(adb: str) -> str:
    """'yes' / 'no' / 'auto' (다크 모드 여부는 캡처마다 기록한다)."""
    match = re.search(r"Night mode:\s*(\w+)", shell(adb, "cmd uimode night"))
    return match.group(1) if match else ""


def kakaotalk_in_front(adb: str) -> bool:
    window = shell(adb, "dumpsys window")
    focus = [line for line in window.splitlines() if "mCurrentFocus" in line or "mFocusedApp" in line]
    return any(KAKAOTALK_PACKAGE in line for line in focus)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True, choices=LABELS)
    parser.add_argument("--count", type=int, default=50, help="저장할 최대 장수")
    parser.add_argument("--interval", type=float, default=2.0, help="자동 캡처 간격(초). 0이면 Enter마다 캡처")
    parser.add_argument("--collector", default=os.environ.get("USERNAME", ""), help="수집자 이름(제출용 기록)")
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    parser.add_argument("--allow-any-app", action="store_true", help="카카오톡이 앞 화면인지 확인하지 않음")
    args = parser.parse_args()

    adb = find_adb()
    devices = [line for line in adb_output(adb, "devices").decode().splitlines()[1:] if line.strip().endswith("device")]
    if len(devices) != 1:
        raise SystemExit(f"연결된 기기가 정확히 1대여야 합니다(현재 {len(devices)}대). USB 디버깅을 확인하세요.")

    info = device_info(adb)
    if not info["kakaotalk_version"] and not args.allow_any_app:
        raise SystemExit("이 기기에 카카오톡이 설치되어 있지 않습니다.")
    session = datetime.now().strftime("%Y%m%d-%H%M%S") + "_" + re.sub(r"[^A-Za-z0-9-]", "", info["device_model"])
    folder = args.data / args.label
    folder.mkdir(parents=True, exist_ok=True)
    manifest = args.data / "manifests" / f"{session}.csv"
    manifest.parent.mkdir(parents=True, exist_ok=True)

    print(f"[{info['manufacturer']} {info['device_model']} / Android {info['android_release']} / "
          f"카카오톡 {info['kakaotalk_version'] or '?'}] {args.label} 수집 시작 → {folder}")
    if args.interval > 0:
        print("3초 뒤 시작합니다. 카카오톡 화면을 넘기면서 기다리세요. Ctrl+C로 중단.")
        time.sleep(3)

    saved, last_hash = 0, None
    with manifest.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=MANIFEST_FIELDS)
        writer.writeheader()
        try:
            while saved < args.count:
                if args.interval <= 0:
                    input(f"[{saved + 1}/{args.count}] Enter를 누르면 캡처합니다 ")
                if not args.allow_any_app and not kakaotalk_in_front(adb):
                    print("  카카오톡이 앞 화면이 아니라 건너뜀")
                else:
                    png = adb_output(adb, "exec-out", "screencap", "-p")
                    digest = hashlib.sha1(png).hexdigest()
                    image = Image.open(io.BytesIO(png))
                    if digest == last_hash:
                        print("  같은 화면이라 건너뜀")
                    elif image.width >= image.height:
                        print("  가로 화면은 앱에서 분류하지 않으므로 건너뜀")
                    else:
                        last_hash = digest
                        path = folder / f"{session}__{saved:04d}.png"
                        path.write_bytes(png)
                        writer.writerow({
                            "file": path.relative_to(args.data).as_posix(), "label": args.label,
                            "collector": args.collector, **info, "night_mode": night_mode(adb),
                            "screen_width": image.width, "screen_height": image.height,
                            "captured_at": datetime.now().isoformat(timespec="seconds"),
                        })
                        handle.flush()
                        saved += 1
                        print(f"  저장 {saved}/{args.count}: {path.name}")
                if args.interval > 0:
                    time.sleep(args.interval)
        except KeyboardInterrupt:
            print()
    if saved == 0:
        manifest.unlink()
    print(f"완료: {saved}장 저장. 저장 후 review.py로 잘못 찍힌 화면을 꼭 검수하세요.")


if __name__ == "__main__":
    main()
