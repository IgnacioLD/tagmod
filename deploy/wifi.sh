#!/bin/sh
# Add or replace a WPA network on a TagMod e-reader (adb is root) and make it the preferred one.
#   deploy/wifi.sh <ssid> <password>
# Credentials only go to the device; nothing is written to the repository.
set -eu
[ $# -eq 2 ] || { sed -n '2,4p' "$0"; exit 2; }
ADB="adb -s ${SERIAL:-0123456789ABCDEF}"
CONF=/data/misc/wifi/wpa_supplicant.conf
case "$1$2" in *'"'*) echo "ssid/password must not contain double quotes" >&2; exit 1 ;; esac

$ADB shell id | grep -q "uid=0(" || { echo "adb is not root" >&2; exit 1; }
BLOCK=$(mktemp)
trap 'rm -f "$BLOCK"' EXIT
printf '\nnetwork={\n\tssid="%s"\n\tpsk="%s"\n\tkey_mgmt=WPA-PSK\n\tpriority=10\n}\n' "$1" "$2" > "$BLOCK"
$ADB push "$BLOCK" /data/local/tmp/wifi-block >/dev/null

# Stop wifi so wpa_supplicant does not rewrite the file while it is edited.
$ADB shell "svc wifi disable; sleep 2
	busybox awk -v ssid='\"$1\"' '
		/^network=\\{/ { block = \$0 \"\\n\"; inblock = 1; next }
		inblock { block = block \$0 \"\\n\"; if (\$0 ~ /^\\t*ssid=/) { s = \$0; sub(/^\\t*ssid=/, \"\", s); if (s == ssid) drop = 1 }
		          if (\$0 ~ /^}/) { if (!drop) printf \"%s\", block; inblock = 0; drop = 0 } next }
		{ print }' $CONF > $CONF.new &&
	busybox cat /data/local/tmp/wifi-block >> $CONF.new &&
	busybox mv $CONF.new $CONF && chown system:wifi $CONF && chmod 660 $CONF
	rm /data/local/tmp/wifi-block
	svc wifi enable"
echo "Network \"$1\" configured."
