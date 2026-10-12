#!/usr/bin/env python3
"""Unpack and repack Android v0 boot images and their gzip'd newc cpio ramdisks.

Pure Python so ownership and modes of ramdisk entries are preserved exactly
(no host cpio, no root needed).
"""
import gzip
import hashlib
import struct
import sys

HDR = struct.Struct("<8s10I16s512s32s1024s")
FIELDS = ("magic", "kernel_size", "kernel_addr", "ramdisk_size", "ramdisk_addr", "second_size",
          "second_addr", "tags_addr", "page_size", "unused0", "unused1", "name", "cmdline", "id",
          "extra_cmdline")


def pad(n, page):
    return (n + page - 1) // page * page


def unpack_boot(data):
    h = dict(zip(FIELDS, HDR.unpack_from(data)))
    assert h["magic"] == b"ANDROID!", "not an Android boot image"
    page = h["page_size"]
    off = page
    kernel = data[off:off + h["kernel_size"]]
    off += pad(h["kernel_size"], page)
    ramdisk = data[off:off + h["ramdisk_size"]]
    off += pad(h["ramdisk_size"], page)
    second = data[off:off + h["second_size"]]
    return h, kernel, ramdisk, second


def pack_boot(h, kernel, ramdisk, second):
    h = dict(h, kernel_size=len(kernel), ramdisk_size=len(ramdisk), second_size=len(second))
    sha = hashlib.sha1()
    for blob in (kernel, ramdisk, second):
        sha.update(blob)
        sha.update(struct.pack("<I", len(blob)))
    h["id"] = sha.digest().ljust(32, b"\0")
    page = h["page_size"]
    out = bytearray(HDR.pack(*(h[f] for f in FIELDS)).ljust(page, b"\0"))
    for blob in (kernel, ramdisk, second):
        out += blob.ljust(pad(len(blob), page), b"\0")
    return bytes(out)


def read_cpio(data):
    """Returns a list of [name, fields(list of 13 ints), body] in archive order."""
    entries, off = [], 0
    while True:
        assert data[off:off + 6] == b"070701", "bad cpio magic at %d" % off
        fields = [int(data[off + 6 + i * 8: off + 14 + i * 8], 16) for i in range(13)]
        namesize, filesize = fields[11], fields[6]
        name = data[off + 110: off + 110 + namesize - 1].decode()
        off = (off + 110 + namesize + 3) & ~3
        body = data[off:off + filesize]
        off = (off + filesize + 3) & ~3
        if name == "TRAILER!!!":
            return entries
        entries.append([name, fields, body])


def write_cpio(entries):
    out = bytearray()
    for ino, (name, fields, body) in enumerate(entries + [["TRAILER!!!", [0] * 13, b""]], 300000):
        fields = list(fields)
        fields[0] = ino if name != "TRAILER!!!" else 0
        fields[6] = len(body)
        fields[11] = len(name) + 1
        if name == "TRAILER!!!":
            fields[4] = 1  # nlink
        out += b"070701" + b"".join(b"%08X" % f for f in fields) + name.encode() + b"\0"
        out += b"\0" * (-len(out) % 4)
        out += body
        out += b"\0" * (-len(out) % 4)
    out += b"\0" * (-len(out) % 512)
    return bytes(out)


def ramdisk_entries(boot):
    return read_cpio(gzip.decompress(unpack_boot(boot)[2]))


if __name__ == "__main__":
    boot = open(sys.argv[1], "rb").read()
    h, kernel, ramdisk, second = unpack_boot(boot)
    for f in FIELDS:
        v = h[f]
        print(f, hex(v) if isinstance(v, int) else v.rstrip(b"\0")[:80])
    for name, fields, body in read_cpio(gzip.decompress(ramdisk)):
        print("%06o %4d %4d %8d %s" % (fields[1], fields[2], fields[3], len(body), name))
