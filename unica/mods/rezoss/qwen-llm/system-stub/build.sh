#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh <target> first}"
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
OUT="${1:?Output APK required}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS="$SDK/build-tools/37.0.0"
WORK=$(mktemp -d /tmp/rezoss-qwen-stub.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex"
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" \
    -d "$WORK/classes" "$ROOT/java/io/mesalabs/rezoss/qwen/EngineService.java"
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$TOOLS/d8" --min-api 35 --lib "$SDK/platforms/android-37.0/android.jar" \
    --output "$WORK/dex" "$WORK/classes.jar"
"$TOOLS/aapt2" link --manifest "$ROOT/AndroidManifest.xml" \
    -I "$SDK/platforms/android-37.0/android.jar" -o "$WORK/base.apk"
cp "$WORK/dex/classes.dex" "$WORK/"
(
    cd "$WORK"
    zip -q -0 base.apk classes.dex
)
"$TOOLS/zipalign" -P 16 -f 4 "$WORK/base.apk" "$WORK/aligned.apk"
CERT=aosp
if ${ROM_IS_OFFICIAL:-false}; then CERT=unica; fi
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/${CERT}_platform.pk8" \
    --cert "$SRC_DIR/security/${CERT}_platform.x509.pem" --out "$WORK/signed.apk" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$WORK/signed.apk"
"$TOOLS/zipalign" -c -P 16 4 "$WORK/signed.apk"
mkdir -p "$(dirname -- "$OUT")"
mv "$WORK/signed.apk" "$OUT"
sha256sum "$OUT"
