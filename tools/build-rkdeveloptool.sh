#!/bin/sh
# Build Rockchip's rkdeveloptool (Rockusb client) into out/rkdeveloptool. Needs Homebrew libusb.
set -eu
cd "$(dirname "$0")/.."
SRC=out/rkdeveloptool-src
[ -d "$SRC" ] || git clone -q --depth 1 https://github.com/rockchip-linux/rkdeveloptool.git "$SRC"
echo '#define PACKAGE_VERSION "1.32"' > "$SRC/config.h"
PREFIX="$(brew --prefix)"
# -w: upstream code is warning-heavy; it is a third-party tool, not ours.
clang++ -std=c++11 -O2 -w -I"$PREFIX/include/libusb-1.0" \
	"$SRC"/main.cpp "$SRC"/crc.cpp "$SRC"/RKBoot.cpp "$SRC"/RKComm.cpp "$SRC"/RKDevice.cpp \
	"$SRC"/RKImage.cpp "$SRC"/RKLog.cpp "$SRC"/RKScan.cpp \
	-L"$PREFIX/lib" -lusb-1.0 -o out/rkdeveloptool
echo out/rkdeveloptool
