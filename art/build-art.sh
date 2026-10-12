#!/bin/sh
# Render the e-ink art into out/art/ from art/source/:
#   images/standby-{1..6}.png, images/shutdown.png, images/reboot.png  -> /data/local/assets/images/
#   bootanimation.zip                                                  -> /data/local/bootanimation.zip
#   uboot_logo.bmp                                                     -> resource partition
# The panel is 758x1024 portrait, but surfaceflinger expects its images as 1024x758,
# rotated 90 degrees counter-clockwise.
set -eu
cd "$(dirname "$0")"
W=758 H=1024
OUT=../out/art
TMP=$OUT/tmp
rm -rf "$OUT" && mkdir -p "$OUT/images" "$TMP/part0"

FONT=fonts/Poppins-SemiBold.ttf
word() {
	magick -background white -font "$FONT" -pointsize "$1" \
		\( -fill '#8a8a8a' label:tag \) \( -fill black label:mod \) +append -trim +repage "$2"
}
word 150 "$TMP/word.png"

# Portrait logo screen: wordmark only, centered.
magick -size ${W}x${H} xc:white \
	"$TMP/word.png" -gravity center -composite \
	-colorspace Gray "$TMP/logo-screen.png"

# Bootloader logo (resource partition uboot_logo.bmp): 275x75 portrait, stored rotated like the rest.
word 80 "$TMP/word-small.png"
magick -size 275x75 xc:white \( "$TMP/word-small.png" -resize 255x60 \) -gravity center -composite \
	-rotate -90 -colorspace Gray -depth 8 "gray:$TMP/uboot_logo.gray"
python3 make-uboot-logo.py "$TMP/uboot_logo.gray" "$OUT/uboot_logo.bmp"

# Sleep screen: the artwork scaled to the panel.
magick source/standby.png -resize ${W}x${H}^ -gravity center -extent ${W}x${H} \
	-colorspace Gray "$TMP/standby.png"

landscape() {
	magick "$1" -rotate -90 -type TrueColor -define png:color-type=2 "$2"
}
for i in 1 2 3 4 5 6; do
	landscape "$TMP/standby.png" "$OUT/images/standby-$i.png"
done
landscape "$TMP/logo-screen.png" "$OUT/images/shutdown.png"
landscape "$TMP/logo-screen.png" "$OUT/images/reboot.png"

# Boot animation: the logo with a row of 12 dots filling in, one per frame, looped.
DOTS=12 R=7 GAP=30
X0=$(( (W - (DOTS - 1) * GAP) / 2 )) Y=$(( H / 2 + 150 ))
for f in $(seq 1 $DOTS); do
	draw=""
	for d in $(seq 0 $((DOTS - 1))); do
		x=$(( X0 + d * GAP ))
		if [ "$d" -lt "$f" ]; then fill=black; else fill=white; fi
		draw="$draw fill $fill circle $x,$Y $((x + R)),$Y"
	done
	magick "$TMP/logo-screen.png" -stroke black -strokewidth 2 -draw "$draw" \
		-colorspace Gray "$TMP/part0/$(printf 'a%02d' "$f").png"
done
printf '%s %s 1\np 0 0 part0\n' "$W" "$H" > "$TMP/desc.txt"
# bootanimation requires stored (uncompressed) entries.
(cd "$TMP" && zip -q -0 -r ../bootanimation.zip desc.txt part0)

rm -rf "$TMP"
find "$OUT" -type f | sort
