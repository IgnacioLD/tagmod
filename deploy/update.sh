#!/bin/sh
# Rebuild and write the TagMod boot and resource images on a Tagus that already runs TagMod (adb is root).
#   deploy/update.sh <name>   (<name> as used with deploy/flash.sh; its stock images are the base)
# The new images apply on the next boot.
set -eu
cd "$(dirname "$0")/.."
[ $# -eq 1 ] || { sed -n '2,4p' "$0"; exit 2; }
DIR="backups/$1"
ADB="adb -s ${SERIAL:-0123456789ABCDEF}"

$ADB shell id | grep -q "uid=0(" || { echo "adb is not root; use deploy/flash.sh" >&2; exit 1; }
./build.sh
./build-apk.sh
art/build-art.sh >/dev/null
bootimg/build-boot.py "$DIR/boot.img" out/boot-tagmod.img
bootimg/build-resource.py "$DIR/resource.img" out/resource-tagmod.img

write_part() { # <image> <block device>
	size=$(wc -c < "$1" | tr -d ' ')
	want=$(shasum "$1" | cut -d' ' -f1)
	$ADB push "$1" /data/local/tmp/part.img
	$ADB shell "busybox dd if=/data/local/tmp/part.img of=$2 bs=512 conv=fsync 2>/dev/null"
	got=$($ADB shell "busybox head -c $size $2 | busybox sha1sum" | cut -d' ' -f1)
	$ADB shell rm /data/local/tmp/part.img
	[ "$got" = "$want" ] || { echo "Verification of $2 FAILED ($got != $want)" >&2; exit 1; }
	echo "Verified $1 -> $2"
}
write_part out/resource-tagmod.img /dev/block/mmcblk0p3
write_part out/boot-tagmod.img /dev/block/mmcblk0p5
echo "Restart the device to apply."
