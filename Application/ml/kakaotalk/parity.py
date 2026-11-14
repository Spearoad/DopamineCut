"""Python ui_tiles_v1과 앱 WholeUiTiles.kt가 같은 픽셀을 만드는지 확인할 기준값(CRC32)을 출력한다.

    python parity.py

출력값은 app/src/test/.../KakaotalkPreprocessingParityTest.kt 의 expected 값과 같아야 한다.
"""
from __future__ import annotations

import zlib

from PIL import Image

from common import ui_tiles_v1

# 서로 다른 세로 종횡비 3개(일반 폰, 홀수 크기, 넓은 폴더블 비율)
SIZES = [(108, 240), (73, 161), (98, 109)]


def synthetic(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height))
    image.putdata([((x * 37 + y * 13) % 256, (x * 11 + y * 53) % 256, (x * 71 + y * 7) % 256)
                   for y in range(height) for x in range(width)])
    return image


if __name__ == "__main__":
    for width, height in SIZES:
        crc = zlib.crc32(ui_tiles_v1(synthetic(width, height)).tobytes())
        print(f"{width}x{height}: {crc}")
