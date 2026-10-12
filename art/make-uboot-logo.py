#!/usr/bin/env python3
"""Write a 75x275 8-bit grayscale raw image as the bootloader's uboot_logo.bmp.

  make-uboot-logo.py <in.gray> <out.bmp>

Reproduces the stock file's exact layout (BITMAPINFOHEADER, the same 17-entry palette, bottom-up
rows padded to 4 bytes), so the result has the stock size and can be written in place inside the
resource partition.
"""
import struct
import sys

W, H = 75, 275
# Stock palette as stored (B, G, R, 0).
PALETTE = bytes.fromhex(
    "201f2300" "2e2d3100" "3c3b3f00" "4a494c00" "58575a00" "66656800" "74737600" "82818400" "8f8f9100"
    "9d9d9e00" "ababac00" "b9b9ba00" "c7c7c800" "d5d5d600" "e3e3e300" "f1f1f100" "ffffff00")
COLORS = len(PALETTE) // 4
LEVELS = [PALETTE[i * 4 + 1] for i in range(COLORS)]  # green channel as the grey level
OFFSET = 14 + 40 + len(PALETTE)
STRIDE = (W + 3) & ~3


def main(src, dst):
    gray = open(src, "rb").read()
    assert len(gray) == W * H, "expected %dx%d 8-bit gray" % (W, H)
    nearest = [min(range(COLORS), key=lambda i: abs(LEVELS[i] - v)) for v in range(256)]
    rows = [bytes(nearest[v] for v in gray[y * W:(y + 1) * W]).ljust(STRIDE, b"\0") for y in range(H - 1, -1, -1)]
    pixels = b"".join(rows)
    header = struct.pack("<2sIHHI", b"BM", OFFSET + len(pixels), 0, 0, OFFSET)
    info = struct.pack("<IiiHHIIiiII", 40, W, H, 1, 8, 0, len(pixels), 2835, 2835, COLORS, COLORS)
    with open(dst, "wb") as f:
        f.write(header + info + PALETTE + pixels)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
