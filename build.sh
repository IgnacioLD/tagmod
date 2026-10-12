#!/bin/sh
# Build a static ARMv7 powerkeyd binary into out/ with Debian's ARM cross compiler.
set -eu
cd "$(dirname "$0")"
mkdir -p out
docker run --rm --platform linux/arm64 -v "$PWD:/src" -w /src debian:bookworm-slim sh -c '
	export DEBIAN_FRONTEND=noninteractive &&
	apt-get update -qq >/dev/null &&
	apt-get install -y -qq --no-install-recommends gcc-arm-linux-gnueabihf libc6-dev-armhf-cross file >/dev/null 2>&1 &&
	arm-linux-gnueabihf-gcc -mcpu=cortex-a7 -Os -Wall -Wextra -Werror -static -s \
		-o out/powerkeyd src/powerkeyd.c &&
	file out/powerkeyd'
