#!/bin/sh
# Flash TagMod onto a Tagus in Rockusb (Loader) mode: `adb reboot bootloader` gets it there.
#
#   deploy/flash.sh backup  <name>   save this device's stock boot and resource partitions to backups/<name>/
#   deploy/flash.sh install <name>   build and flash the TagMod images (built from that backup)
#   deploy/flash.sh restore <name>   flash the stock partitions back
#
# <name> is any label for the physical device (all Tagus units share the adb serial).
# Once TagMod is installed adb runs as root, and deploy/update.sh updates it without Loader mode.
# Partition offsets come from the Rockchip parameter block (mtdparts in the kernel cmdline).
set -eu
cd "$(dirname "$0")/.."
BOOT_LBA=0x16000
BOOT_SECTORS=0x10000
RESOURCE_LBA=0x6000
RESOURCE_SECTORS=0x8000
RK=out/rkdeveloptool
[ -x "$RK" ] || tools/build-rkdeveloptool.sh

[ $# -eq 2 ] || { sed -n '2,11p' "$0"; exit 2; }
"$RK" ld | grep -q Loader || { echo "No Rockusb device in Loader mode" >&2; exit 1; }
DIR="backups/$2"

write_part() { # <lba> <file>
	# Writes on this loader fail intermittently with a stale CSW tag; a test-unit-ready
	# resyncs the command/status pipe, so retry with one in between.
	for try in 1 2 3; do
		"$RK" td
		if "$RK" wl $(($1)) "$2" | grep -q "100%"; then
			break
		fi
		[ $try = 3 ] && { echo "Writing $2 failed 3 times; device NOT reset, partition may be partial" >&2; exit 1; }
		echo "Write failed, retrying"
	done
	"$RK" rl $(($1)) $(( ($(wc -c < "$2") + 511) / 512 )) out/readback.img
	cmp -n "$(wc -c < "$2")" "$2" out/readback.img
	echo "Verified $2"
}

case "$1" in
backup)
	mkdir -p "$DIR"
	for f in boot resource; do
		[ -f "$DIR/$f.img" ] && { echo "$DIR/$f.img already exists" >&2; exit 1; }
	done
	"$RK" rl $((BOOT_LBA)) $((BOOT_SECTORS)) "$DIR/boot.img"
	"$RK" rl $((RESOURCE_LBA)) $((RESOURCE_SECTORS)) "$DIR/resource.img"
	if python3 bootimg/bootimg.py "$DIR/boot.img" | grep -q tagmod; then
		echo "boot already has TagMod; this is not a stock backup" >&2
		rm "$DIR/boot.img" "$DIR/resource.img"
		exit 1
	fi
	ls -l "$DIR"
	;;
install)
	[ -f "$DIR/boot.img" ] || { echo "Run '$0 backup $2' first" >&2; exit 1; }
	./build.sh
	./build-apk.sh
	art/build-art.sh >/dev/null
	bootimg/build-boot.py "$DIR/boot.img" out/boot-tagmod.img
	bootimg/build-resource.py "$DIR/resource.img" out/resource-tagmod.img
	write_part $RESOURCE_LBA out/resource-tagmod.img
	write_part $BOOT_LBA out/boot-tagmod.img
	"$RK" rd
	;;
restore)
	write_part $RESOURCE_LBA "$DIR/resource.img"
	write_part $BOOT_LBA "$DIR/boot.img"
	"$RK" rd
	;;
*)
	sed -n '2,11p' "$0"
	exit 2
	;;
esac
