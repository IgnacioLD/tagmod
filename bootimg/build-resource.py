#!/usr/bin/env python3
"""Build the TagMod resource partition image from a stock Tagus resource.img.

  bootimg/build-resource.py <stock resource.img> <output resource.img>

Replaces uboot_logo.bmp (the first screen the bootloader draws) with out/art/uboot_logo.bmp.
The replacement must be the same size, so it is written in place and no other entry moves.
Only the used part of the partition is written out.
"""
import struct
import sys

ENTRY = "uboot_logo.bmp"
BLOCK = 512


def entries(data):
    assert data[:4] == b"RSCE", "not a Rockchip resource image"
    off = BLOCK
    while data[off:off + 4] == b"ENTR":
        name = data[off + 4:off + 260].split(b"\0")[0].decode()
        blk, size = struct.unpack_from("<II", data, off + 260)
        yield name, blk * BLOCK, size
        off += BLOCK


def main(src, dst):
    data = bytearray(open(src, "rb").read())
    table = list(entries(data))
    used = max(off + size for _, off, size in table)
    for name, off, size in table:
        if name == ENTRY:
            new = open("out/art/uboot_logo.bmp", "rb").read()
            assert len(new) == size, "%s is %d bytes, stock is %d" % (ENTRY, len(new), size)
            data[off:off + size] = new
            break
    else:
        sys.exit("%s not found in %s" % (ENTRY, src))
    used = (used + BLOCK - 1) // BLOCK * BLOCK
    with open(dst, "wb") as f:
        f.write(data[:used])
    print("%s: %d bytes" % (dst, used))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
