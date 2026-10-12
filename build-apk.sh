#!/bin/sh
# Build and sign out/tagmod-launcher.apk with the plain SDK tools (no Gradle).
set -eu
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/android-34/android.jar"
KEYSTORE=signing/tagmod.keystore
STORE_PASS="${TAGMOD_KEYSTORE_PASS:-tagmod}"
KEY_PASS="${TAGMOD_KEY_PASS:-$STORE_PASS}"
B=out/apk-build
rm -rf "$B" && mkdir -p "$B/classes" "$B/dex"

if [ ! -f "$KEYSTORE" ]; then
	mkdir -p signing
	keytool -genkeypair -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
		-alias tagmod -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=TagMod" >/dev/null
fi

"$BT/aapt2" compile --dir app/res -o "$B/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest app/AndroidManifest.xml --java "$B/gen" \
	-o "$B/unsigned.apk" "$B/res.zip"
# API 19 is the only target, so APIs deprecated in later releases are the right ones to use.
javac -Xlint:all -Xlint:-options -Xlint:-deprecation -XDsuppressNotes -Werror --release 8 -classpath "$JAR" -d "$B/classes" \
	$(find app/src "$B/gen" -name '*.java')
"$BT/d8" --release --min-api 19 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
(cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" --v1-signing-enabled true \
	--v2-signing-enabled true --out out/tagmod-launcher.apk "$B/aligned.apk"
echo "out/tagmod-launcher.apk"
