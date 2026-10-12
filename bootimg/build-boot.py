#!/usr/bin/env python3
"""Build the TagMod boot image from a stock Tagus boot.img.

  bootimg/build-boot.py <stock boot.img> <output boot.img>

Adds /tagmod (powerkeyd, launcher APK, art, setup script) and init.tagmod.rc to the ramdisk,
imports it from init.rc and enables adb root. Expects out/powerkeyd, out/tagmod-launcher.apk
and out/art/ to be built already.
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bootimg  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR, FILE = 0o040000, 0o100000


def entry(name, mode, body=b""):
    # newc fields: ino mode uid gid nlink mtime filesize devmaj devmin rdevmaj rdevmin namesize check
    return [name, [0, mode, 0, 0, 2 if mode & DIR else 1, 0, 0, 0, 0, 0, 0, 0, 0], body]


def read(*path):
    with open(os.path.join(ROOT, *path), "rb") as f:
        return f.read()


def set_props(text, props):
    lines = text.decode().splitlines()
    for key, value in props.items():
        lines = [l for l in lines if not l.startswith(key + "=")] + ["%s=%s" % (key, value)]
    return ("\n".join(lines) + "\n").encode()


def main(src, dst):
    header, kernel, ramdisk, second = bootimg.unpack_boot(open(src, "rb").read())
    entries = bootimg.read_cpio(gzip.decompress(ramdisk))
    if any(e[0] == "tagmod" for e in entries):
        sys.exit("%s already contains TagMod; start from the stock boot.img" % src)

    for e in entries:
        if e[0] == "init.rc":
            e[2] = e[2].replace(b"import /init.trace.rc\n",
                                b"import /init.trace.rc\nimport /init.tagmod.rc\n", 1)
            assert b"import /init.tagmod.rc" in e[2], "init.rc import anchor not found"
        elif e[0] == "default.prop":
            e[2] = set_props(e[2], {"ro.secure": "0", "ro.adb.secure": "0", "ro.debuggable": "1"})

    entries.append(entry("init.tagmod.rc", FILE | 0o750, read("ramdisk", "init.tagmod.rc")))
    entries.append(entry("tagmod", DIR | 0o750))
    entries.append(entry("tagmod/tagmod.sh", FILE | 0o750, read("ramdisk", "tagmod.sh")))
    entries.append(entry("tagmod/powerkeyd", FILE | 0o750, read("out", "powerkeyd")))
    entries.append(entry("tagmod/launcher.apk", FILE | 0o644, read("out", "tagmod-launcher.apk")))
    entries.append(entry("tagmod/art", DIR | 0o755))
    entries.append(entry("tagmod/art/bootanimation.zip", FILE | 0o644, read("out", "art", "bootanimation.zip")))
    entries.append(entry("tagmod/art/images", DIR | 0o755))
    for name in sorted(os.listdir(os.path.join(ROOT, "out", "art", "images"))):
        entries.append(entry("tagmod/art/images/" + name, FILE | 0o644, read("out", "art", "images", name)))

    new_ramdisk = gzip.compress(bootimg.write_cpio(entries), 9, mtime=0)
    # The kernel cmdline reserves 8 MiB for the initrd.
    assert len(new_ramdisk) < 0x800000, "ramdisk too large: %d" % len(new_ramdisk)
    image = bootimg.pack_boot(header, kernel, new_ramdisk, second)
    # The boot partition is 0x10000 sectors.
    assert len(image) <= 0x10000 * 512, "boot image too large"
    with open(dst, "wb") as f:
        f.write(image)
    print("%s: %d bytes, ramdisk %d -> %d bytes" % (dst, len(image), len(ramdisk), len(new_ramdisk)))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
